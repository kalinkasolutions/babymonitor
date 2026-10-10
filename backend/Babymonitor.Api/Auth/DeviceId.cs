using System.Security.Cryptography;

namespace Babymonitor.Auth;

/// <summary>
/// A phone's address on this server: the first 16 bytes of SHA-256 over its public key, as
/// lowercase hex. The phones compute the same value from the key they scanned.
/// </summary>
public static class DeviceId
{
    private const int ByteLength = 16;
    private const int Length = ByteLength * 2;

    public static string FromPublicKey(byte[] subjectPublicKeyInfo) =>
        Convert.ToHexStringLower(SHA256.HashData(subjectPublicKeyInfo).AsSpan(0, ByteLength));

    public static bool IsValid(string? value) =>
        value is { Length: Length } && value.All(char.IsAsciiHexDigitLower);
}
