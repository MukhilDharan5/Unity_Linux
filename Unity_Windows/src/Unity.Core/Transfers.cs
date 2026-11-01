using System.Security.Cryptography;
using System.Text.Json.Nodes;

namespace Unity.Core;

public sealed class TransferReceiver : IDisposable
{
    private sealed class Incoming(string path, string name, long size, string digest) : IDisposable
    {
        public string Path = path, Name = name, Digest = digest;
        public long Size = size, Count;
        public FileStream File = new(path, FileMode.CreateNew, FileAccess.Write, FileShare.None);
        public IncrementalHash Hash = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
        public void Dispose() { File.Dispose(); Hash.Dispose(); if (System.IO.File.Exists(Path)) System.IO.File.Delete(Path); }
    }
    private readonly Dictionary<string, Incoming> incoming = [];
    private readonly string root;
    public event Action<string>? Received;
    public TransferReceiver(string root) { this.root = Path.GetFullPath(root); Directory.CreateDirectory(this.root); }
    public void Receive(JsonObject data)
    {
        var id = data.Str("id"); Wire.Require(Wire.IsHex(id, 32), "Invalid transfer ID");
        try
        {
            switch (data.Str("phase"))
            {
                case "begin":
                    Wire.Require(incoming.Count < 4 && !incoming.ContainsKey(id), "Too many transfers or duplicate ID");
                    var name = data.Str("name"); var size = data.Num("size", -1); var hash = data.Str("sha256");
                    Wire.Require(Wire.SafeName(name) && size is >= 0 and <= Wire.MaxFile && Wire.IsHex(hash, 64), "Invalid file metadata or Windows file name");
                    incoming.Add(id, new Incoming(Path.Combine(root, "." + id + ".part"), name, size, hash));
                    break;
                case "chunk":
                    var chunk = Get(id); Wire.Text(data.Str("data"), 65536);
                    var bytes = Convert.FromBase64String(data.Str("data"));
                    Wire.Require(bytes.Length <= Wire.Chunk && chunk.Count + bytes.Length <= chunk.Size, "File chunk exceeds limit");
                    chunk.File.Write(bytes); chunk.Hash.AppendData(bytes); chunk.Count += bytes.Length;
                    break;
                case "end":
                    var t = Get(id); incoming.Remove(id);
                    using (t)
                    {
                        t.File.Flush(true); t.File.Dispose();
                        Wire.Require(t.Count == t.Size && Convert.ToHexStringLower(t.Hash.GetHashAndReset()) == t.Digest, "File integrity check failed");
                        // A unique prefix prevents replacing an existing received file.
                        var destination = Path.Combine(root, id[..8] + "-" + t.Name);
                        File.Move(t.Path, destination, false);
                        Received?.Invoke(destination);
                    }
                    break;
                case "abort": Abort(id); break;
                default: throw new InvalidDataException("Invalid transfer phase");
            }
        }
        catch { Abort(id); throw; }
    }
    private Incoming Get(string id) => incoming.GetValueOrDefault(id) ?? throw new InvalidDataException("Unknown transfer");
    private void Abort(string id) { if (incoming.Remove(id, out var t)) t.Dispose(); }
    public void Dispose() { foreach (var t in incoming.Values) t.Dispose(); incoming.Clear(); }

    public static async Task<JsonObject> SendAsync(DeviceSession session, string path, CancellationToken ct = default)
    {
        // One open, deny writes: the bytes hashed are the exact bytes transmitted.
        await using var file = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read, Wire.Chunk, true);
        Wire.Require(file.Length <= Wire.MaxFile && Wire.SafeName(Path.GetFileName(path)), "File exceeds 64 MiB or has an invalid name");
        var digest = Convert.ToHexStringLower(await SHA256.HashDataAsync(file, ct)); file.Position = 0;
        var id = Wire.Id();
        return await session.FileCommandAsync(id, async send =>
        {
            try
            {
                await send("begin", Wire.Object(("id", id), ("name", Path.GetFileName(path)), ("size", file.Length), ("sha256", digest)));
                var buffer = new byte[Wire.Chunk]; int count;
                while ((count = await file.ReadAsync(buffer, ct)) > 0)
                    await send("chunk", Wire.Object(("id", id), ("data", Convert.ToBase64String(buffer, 0, count))));
                await send("end", Wire.Object(("id", id)));
            }
            catch { try { await send("abort", Wire.Object(("id", id))); } catch { } throw; }
        }, ct);
    }
}
