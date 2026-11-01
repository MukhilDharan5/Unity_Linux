using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text.Json;

namespace Unity.Core;

public sealed record TrustedPhone(string Id, string Name, string Secret, HashSet<string> Disabled);
public sealed class IdentityStore
{
    private sealed record Saved(string Certificate, List<TrustedPhone> Phones);
    private readonly object gate = new();
    private readonly Dictionary<string, TrustedPhone> phones = [];
    public string Root { get; }
    public X509Certificate2 Certificate { get; }
    public string Fingerprint => Convert.ToHexStringLower(SHA256.HashData(Certificate.RawData));
    public TrustedPhone[] Phones { get { lock (gate) return phones.Values.ToArray(); } }
    public IdentityStore(string root)
    {
        Root = Path.GetFullPath(root); Directory.CreateDirectory(Root);
        var path = Path.Combine(Root, "identity.dat");
        if (File.Exists(path))
        {
            // Never silently replace an unreadable identity and break the phone's pin.
            var saved = JsonSerializer.Deserialize<Saved>(Protect(File.ReadAllBytes(path), false)) ?? throw new InvalidDataException("Invalid identity store");
            Certificate = X509CertificateLoader.LoadPkcs12(Convert.FromBase64String(saved.Certificate), null, X509KeyStorageFlags.UserKeySet | X509KeyStorageFlags.Exportable);
            foreach (var p in saved.Phones) { Wire.Require(Wire.IsHex(p.Id, 32) && Wire.IsHex(p.Secret, 64), "Invalid saved phone"); phones.Add(p.Id, p); }
        }
        else
        {
            using var key = RSA.Create(2048);
            var req = new CertificateRequest("CN=Unity Windows", key, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
            req.CertificateExtensions.Add(new X509BasicConstraintsExtension(false, false, 0, true));
            req.CertificateExtensions.Add(new X509KeyUsageExtension(X509KeyUsageFlags.DigitalSignature, true));
            req.CertificateExtensions.Add(new X509EnhancedKeyUsageExtension(new System.Security.Cryptography.OidCollection { new("1.3.6.1.5.5.7.3.1") }, false));
            using var cert = req.CreateSelfSigned(DateTimeOffset.UtcNow.AddDays(-1), DateTimeOffset.UtcNow.AddYears(10));
            Certificate = X509CertificateLoader.LoadPkcs12(cert.Export(X509ContentType.Pfx), null, X509KeyStorageFlags.UserKeySet | X509KeyStorageFlags.Exportable);
            Save();
        }
    }
    public TrustedPhone? Find(string id) { lock (gate) return phones.GetValueOrDefault(id); }
    public void Trust(TrustedPhone phone) { lock (gate) { phones[phone.Id] = phone; Save(); } }
    public void Revoke(string id) { lock (gate) { phones.Remove(id); Save(); } }
    public void SetEnabled(string id, string feature, bool enabled)
    {
        lock (gate) { if (!phones.TryGetValue(id, out var phone)) return; if (enabled) phone.Disabled.Remove(feature); else phone.Disabled.Add(feature); Save(); }
    }
    private void Save()
    {
        var saved = new Saved(Convert.ToBase64String(Certificate.Export(X509ContentType.Pfx)), phones.Values.ToList());
        var path = Path.Combine(Root, "identity.dat");
        var bytes = Protect(JsonSerializer.SerializeToUtf8Bytes(saved), true);
        File.WriteAllBytes(path + ".tmp", bytes); File.Move(path + ".tmp", path, true);
    }
    // DPAPI CurrentUser protects certificate private key and pairing credentials together.
    [StructLayout(LayoutKind.Sequential)] private struct Blob { public int Size; public IntPtr Data; }
    [DllImport("crypt32.dll", SetLastError = true, CharSet = CharSet.Unicode)] private static extern bool CryptProtectData(ref Blob input, string? description, IntPtr entropy, IntPtr reserved, IntPtr prompt, int flags, out Blob output);
    [DllImport("crypt32.dll", SetLastError = true)] private static extern bool CryptUnprotectData(ref Blob input, IntPtr description, IntPtr entropy, IntPtr reserved, IntPtr prompt, int flags, out Blob output);
    [DllImport("kernel32.dll")] private static extern IntPtr LocalFree(IntPtr memory);
    private static byte[] Protect(byte[] data, bool encrypt)
    {
        if (!OperatingSystem.IsWindows()) throw new PlatformNotSupportedException("The identity store requires Windows DPAPI");
        var input = new Blob { Size = data.Length, Data = Marshal.AllocHGlobal(data.Length) };
        try
        {
            Marshal.Copy(data, 0, input.Data, data.Length);
            Blob output;
            var ok = encrypt ? CryptProtectData(ref input, "Unity Windows", IntPtr.Zero, IntPtr.Zero, IntPtr.Zero, 1, out output)
                : CryptUnprotectData(ref input, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero, 1, out output);
            if (!ok) throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error());
            try { var result = new byte[output.Size]; Marshal.Copy(output.Data, result, 0, result.Length); return result; }
            finally { LocalFree(output.Data); }
        }
        finally { Marshal.FreeHGlobal(input.Data); }
    }
}
