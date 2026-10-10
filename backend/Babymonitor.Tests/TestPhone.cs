using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using Babymonitor.Auth;

namespace Babymonitor.Tests;

/// <summary>A phone's identity key, signing requests the way the Android app does.</summary>
public sealed class TestPhone : IDisposable
{
    private readonly ECDsa m_key;

    public TestPhone(ECCurve? curve = null)
    {
        m_key = ECDsa.Create(curve ?? ECCurve.NamedCurves.nistP256);
        var publicKey = m_key.ExportSubjectPublicKeyInfo();
        PublicKey = Convert.ToBase64String(publicKey);
        DeviceId = Auth.DeviceId.FromPublicKey(publicKey);
    }

    public string PublicKey { get; }

    public string DeviceId { get; }

    public string Sign(string time) =>
        Convert.ToBase64String(m_key.SignData(
            Encoding.UTF8.GetBytes("babymonitor-hub\n" + time),
            HashAlgorithmName.SHA256,
            DSASignatureFormat.Rfc3279DerSequence));

    public Dictionary<string, string> AuthHeaders(string serverKey, DateTimeOffset? signedAt = null)
    {
        var time = (signedAt ?? DateTimeOffset.UtcNow).ToUnixTimeMilliseconds().ToString(CultureInfo.InvariantCulture);
        return new Dictionary<string, string>(StringComparer.Ordinal)
        {
            [ServerKey.HeaderName] = serverKey,
            [DeviceKeyAuthenticationHandler.KeyHeader] = PublicKey,
            [DeviceKeyAuthenticationHandler.TimeHeader] = time,
            [DeviceKeyAuthenticationHandler.SignatureHeader] = Sign(time)
        };
    }

    public void Dispose() => m_key.Dispose();
}
