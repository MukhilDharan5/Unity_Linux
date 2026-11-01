using System.Buffers.Binary;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;

namespace Unity.Core;

public static class Wire
{
    public const int Version = 1, Port = 17161, DiscoveryPort = 17162;
    public const int MaxFrame = 524288, MaxText = 65536, Chunk = 49152;
    public const long MaxFile = 64L * 1024 * 1024;
    public static string Id() => Guid.NewGuid().ToString("N");
    public static string Secret() => Convert.ToHexStringLower(RandomNumberGenerator.GetBytes(32));
    public static bool IsHex(string s, int length) => s.Length == length && s.All(c => c is >= '0' and <= '9' or >= 'a' and <= 'f');
    public static string Proof(string secret, string id, string nonce)
    {
        Require(IsHex(secret, 64) && IsHex(id, 32) && IsHex(nonce, 64), "Invalid authentication values");
        return Convert.ToHexStringLower(HMACSHA256.HashData(Convert.FromHexString(secret), Encoding.UTF8.GetBytes($"unity-v1:{id}:{nonce}")));
    }
    public static JsonObject Object(params (string Key, object? Value)[] fields)
    {
        var o = new JsonObject();
        foreach (var (k, v) in fields) o[k] = v is JsonNode n ? n.DeepClone() : System.Text.Json.JsonSerializer.SerializeToNode(v);
        return o;
    }
    public static string Str(this JsonNode? n, string key, string fallback = "") => n?[key]?.GetValue<string>() ?? fallback;
    public static bool Bool(this JsonNode? n, string key, bool fallback = false) => n?[key]?.GetValue<bool>() ?? fallback;
    public static long Num(this JsonNode? n, string key, long fallback = 0) => n?[key]?.GetValue<long>() ?? fallback;
    public static JsonObject Data(this JsonNode n) => n["data"] as JsonObject ?? throw new InvalidDataException("Expected data object");
    public static void Require(bool ok, string error) { if (!ok) throw new InvalidDataException(error); }
    public static void Text(string s, int max) => Require(Encoding.UTF8.GetByteCount(s) <= max, "Text exceeds protocol limit");
    public static bool SafeName(string name)
    {
        // Windows also rejects device names, alternate data streams, and trailing dots/spaces.
        var stem = name.Split('.')[0].ToUpperInvariant();
        return !string.IsNullOrWhiteSpace(name) && Encoding.UTF8.GetByteCount(name) <= 255 &&
            name is not "." and not ".." && !name.EndsWith('.') && !name.EndsWith(' ') &&
            !name.Any(c => c < 32 || "<>:\"/\\|?*".Contains(c)) &&
            stem is not ("CON" or "PRN" or "AUX" or "NUL" or "CONIN$" or "CONOUT$") &&
            !(stem.Length == 4 && (stem.StartsWith("COM") || stem.StartsWith("LPT")) && char.IsDigit(stem[3]));
    }
    public static bool SafeUrl(string url) => url.Length <= 8192 && !url.Any(char.IsControl) &&
        Uri.TryCreate(url, UriKind.Absolute, out var u) && u.Scheme is "http" or "https" && u.Host.Length > 0;
    public static async Task<JsonObject> ReadAsync(Stream stream, CancellationToken ct = default)
    {
        byte[] header = new byte[4];
        await stream.ReadExactlyAsync(header, ct);
        var length = BinaryPrimitives.ReadUInt32BigEndian(header);
        Require(length is > 0 and <= MaxFrame, "Invalid frame length");
        var data = new byte[(int)length];
        await stream.ReadExactlyAsync(data, ct);
        var utf8 = new UTF8Encoding(false, true).GetString(data);
        return JsonNode.Parse(utf8, documentOptions: new() { MaxDepth = 32 }) as JsonObject ?? throw new InvalidDataException("Expected JSON object");
    }
    public static async Task WriteAsync(Stream stream, JsonObject value, CancellationToken ct = default)
    {
        var bytes = Encoding.UTF8.GetBytes(value.ToJsonString());
        Require(bytes.Length is > 0 and <= MaxFrame, "Invalid frame length");
        byte[] header = new byte[4];
        BinaryPrimitives.WriteInt32BigEndian(header, bytes.Length);
        await stream.WriteAsync(header, ct);
        await stream.WriteAsync(bytes, ct);
        await stream.FlushAsync(ct);
    }
    public static void Hello(JsonObject hello)
    {
        Require(hello.Num("version") == Version && IsHex(hello.Str("device_id"), 32), "Unsupported version or invalid identity");
        var name = hello.Str("name"); Text(name, 128);
        Require(name.Length > 0 && !name.Any(char.IsControl), "Invalid phone name");
        Capabilities(hello["capabilities"]);
    }
    public static JsonArray Capabilities(JsonNode? node)
    {
        Require(node is JsonArray, "Expected capabilities");
        var caps = (JsonArray)node!;
        Require(caps.Count <= 32, "Too many capabilities");
        var ids = new HashSet<string>();
        foreach (var c in caps)
        {
            Require(c is JsonObject, "Invalid capability");
            Text(c.Str("id"), 64); Text(c.Str("reason"), 512);
            Require(ids.Add(c.Str("id")) && c.Str("id").Length > 0 &&
                c.Str("status") is "available" or "permission_required" or "user_action_required" or "unsupported" or "disabled", "Invalid capability");
        }
        return (JsonArray)caps.DeepClone();
    }
    public static (int Width, int Height) Jpeg(ReadOnlySpan<byte> data, int edge, int pixels, int maxBytes)
    {
        Require(data.Length is >= 4 && data.Length <= maxBytes && data[0] == 255 && data[1] == 216, "Invalid JPEG");
        var i = 2;
        while (i + 3 < data.Length)
        {
            Require(data[i++] == 255, "Invalid JPEG marker");
            while (i < data.Length && data[i] == 255) i++;
            Require(i < data.Length, "Truncated JPEG");
            var marker = data[i++];
            if (marker is 0xD9 or 0xDA) break;
            if (marker is 0x01 or >= 0xD0 and <= 0xD7) continue;
            Require(i + 2 <= data.Length, "Truncated JPEG");
            var size = BinaryPrimitives.ReadUInt16BigEndian(data[i..]);
            Require(size >= 2 && i + size <= data.Length, "Truncated JPEG segment");
            if (marker is 0xC0 or 0xC1 or 0xC2)
            {
                Require(size >= 8, "Invalid JPEG header");
                int h = BinaryPrimitives.ReadUInt16BigEndian(data[(i + 3)..]), w = BinaryPrimitives.ReadUInt16BigEndian(data[(i + 5)..]);
                Require(w > 0 && h > 0 && Math.Max(w, h) <= edge && (long)w * h <= pixels, "JPEG dimensions exceed limit");
                return (w, h);
            }
            i += size;
        }
        throw new InvalidDataException("JPEG dimensions missing");
    }
    public static byte[] Preview(JsonObject preview)
    {
        Require(preview.Str("mime") == "image/jpeg", "Invalid preview MIME type");
        Text(preview.Str("data"), 43692);
        var bytes = Convert.FromBase64String(preview.Str("data"));
        var (w, h) = Jpeg(bytes, 320, 320 * 320, 32768);
        Require(preview.Num("width") == w && preview.Num("height") == h, "Preview dimensions mismatch");
        return bytes;
    }
}
