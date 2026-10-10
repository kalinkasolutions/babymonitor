using System.Globalization;
using System.Security.Claims;
using System.Security.Cryptography;
using System.Text;
using System.Text.Encodings.Web;
using Microsoft.AspNetCore.Authentication;
using Microsoft.Extensions.Options;

namespace Babymonitor.Auth;

/// <summary>
/// Authenticates a phone by its identity key: the request carries the public key and a fresh
/// signature over the current time, made with the private key that never leaves the phone.
/// </summary>
public sealed class DeviceKeyAuthenticationHandler : AuthenticationHandler<AuthenticationSchemeOptions>
{
    public const string SchemeName = "DeviceKey";
    public const string KeyHeader = "X-Device-Key";
    public const string TimeHeader = "X-Device-Time";
    public const string SignatureHeader = "X-Device-Signature";

    private const string SignedPrefix = "babymonitor-hub\n";
    private static readonly TimeSpan s_maxClockSkew = TimeSpan.FromMinutes(5);

    private readonly ServerKey m_serverKey;

    public DeviceKeyAuthenticationHandler(
        IOptionsMonitor<AuthenticationSchemeOptions> options,
        ILoggerFactory logger,
        UrlEncoder encoder,
        ServerKey serverKey)
        : base(options, logger, encoder)
    {
        m_serverKey = serverKey;
    }

    protected override Task<AuthenticateResult> HandleAuthenticateAsync() => Task.FromResult(VerifyRequest());

    private AuthenticateResult VerifyRequest()
    {
        var headers = Request.Headers;
        string? key = headers[KeyHeader];
        string? time = headers[TimeHeader];
        string? signature = headers[SignatureHeader];

        // Anonymous requests like the status check carry none of these and are not a failure.
        if (key is null && time is null && signature is null)
        {
            return AuthenticateResult.NoResult();
        }

        if (!TryDecodeBase64(key, out var publicKey))
        {
            return Refuse("device key is not base64", deviceId: null);
        }

        var deviceId = DeviceId.FromPublicKey(publicKey);

        if (!m_serverKey.Matches(headers[ServerKey.HeaderName]))
        {
            return Refuse("wrong server key", deviceId);
        }

        if (!long.TryParse(time, NumberStyles.None, CultureInfo.InvariantCulture, out var milliseconds))
        {
            return Refuse("device time is not a number", deviceId);
        }

        var nowMilliseconds = TimeProvider.GetUtcNow().ToUnixTimeMilliseconds();
        if (Math.Abs(nowMilliseconds - milliseconds) > s_maxClockSkew.TotalMilliseconds)
        {
            return Refuse("device time is more than five minutes off", deviceId);
        }

        if (!TryDecodeBase64(signature, out var signatureBytes))
        {
            return Refuse("signature is not base64", deviceId);
        }

        using var ecdsa = ECDsa.Create();
        if (!TryImportP256(ecdsa, publicKey))
        {
            return Refuse("device key is not an EC P-256 public key", deviceId);
        }

        var signed = Encoding.UTF8.GetBytes(SignedPrefix + time);
        if (!ecdsa.VerifyData(signed, signatureBytes, HashAlgorithmName.SHA256, DSASignatureFormat.Rfc3279DerSequence))
        {
            return Refuse("signature does not verify", deviceId);
        }

        var identity = new ClaimsIdentity([new Claim(ClaimTypes.NameIdentifier, deviceId)], SchemeName);
        return AuthenticateResult.Success(new AuthenticationTicket(new ClaimsPrincipal(identity), SchemeName));
    }

    private AuthenticateResult Refuse(string reason, string? deviceId)
    {
        Logger.LogWarning("Refused device authentication for {DeviceId}: {Reason}", deviceId ?? "unknown device", reason);
        return AuthenticateResult.Fail(reason);
    }

    private static bool TryDecodeBase64(string? value, out byte[] bytes)
    {
        bytes = [];
        if (string.IsNullOrEmpty(value))
        {
            return false;
        }

        try
        {
            bytes = Convert.FromBase64String(value);
            return true;
        }
        catch (FormatException)
        {
            return false;
        }
    }

    private static bool TryImportP256(ECDsa ecdsa, byte[] subjectPublicKeyInfo)
    {
        try
        {
            ecdsa.ImportSubjectPublicKeyInfo(subjectPublicKeyInfo, out _);
        }
        catch (CryptographicException)
        {
            return false;
        }

        return ecdsa.KeySize == 256
            && ecdsa.ExportParameters(includePrivateParameters: false).Curve.Oid.Value == ECCurve.NamedCurves.nistP256.Oid.Value;
    }
}
