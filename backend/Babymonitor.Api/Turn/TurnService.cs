using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using Microsoft.Extensions.Options;

namespace Babymonitor.Turn;

/// <summary>
/// Mints short-lived relay credentials with coturn's REST scheme. Nothing is stored: coturn
/// recomputes the same HMAC from the shared secret.
/// </summary>
public sealed class TurnService
{
    private readonly TurnOptions m_options;
    private readonly ILogger<TurnService> m_logger;

    public TurnService(IOptions<TurnOptions> options, ILogger<TurnService> logger)
    {
        m_options = options.Value;
        m_logger = logger;
    }

    public IceServers Issue(string deviceId)
    {
        if (!m_options.IsConfigured)
        {
            // Phones on one WiFi never need a relay, so STUN alone is a valid setup.
            var stun = m_options.Urls
                .Where(url => url.StartsWith("stun:", StringComparison.OrdinalIgnoreCase))
                .ToList();
            return new IceServers(stun, string.Empty, string.Empty, DateTime.UtcNow);
        }

        var expiresAt = DateTime.UtcNow.Add(m_options.Lifetime);
        var username = string.Create(
            CultureInfo.InvariantCulture,
            $"{new DateTimeOffset(expiresAt, TimeSpan.Zero).ToUnixTimeSeconds()}:{deviceId}");

        m_logger.LogInformation(
            "Issued a relay credential to device {DeviceId}, good until {ExpiresAt:u}", deviceId, expiresAt);

        return new IceServers(m_options.Urls, username, Sign(username, m_options.Secret), expiresAt);
    }

    private static string Sign(string username, string secret)
    {
        // SHA-1 is not our choice: coturn recomputes exactly this HMAC.
#pragma warning disable S4790
        using var hmac = new HMACSHA1(Encoding.UTF8.GetBytes(secret));
#pragma warning restore S4790
        return Convert.ToBase64String(hmac.ComputeHash(Encoding.UTF8.GetBytes(username)));
    }
}
