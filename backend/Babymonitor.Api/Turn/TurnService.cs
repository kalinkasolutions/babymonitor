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
    private readonly TurnOptions m_open;
    private readonly TurnOptions m_home;
    private readonly ILogger<TurnService> m_logger;

    public TurnService(IOptionsMonitor<TurnOptions> options, ILogger<TurnService> logger)
    {
        m_open = options.Get(Options.DefaultName);
        m_home = options.Get(TurnOptions.HomeRelay);
        m_logger = logger;
    }

    public bool HasHomeRelay => m_home.IsConfigured;

    /// <summary>The open relay for every phone, and the home relay too for one that sent the key.</summary>
    public IceServers Issue(string deviceId, bool withHomeRelay)
    {
        var open = Mint(m_open, deviceId, "open");
        return withHomeRelay && m_home.IsConfigured
            ? open with { HomeRelay = Mint(m_home, deviceId, "home") }
            : open;
    }

    private IceServers Mint(TurnOptions relay, string deviceId, string relayName)
    {
        if (!relay.IsConfigured)
        {
            // Phones on one WiFi never need a relay, so STUN alone is a valid setup.
            var stun = relay.Urls
                .Where(url => url.StartsWith("stun:", StringComparison.OrdinalIgnoreCase))
                .ToList();
            return new IceServers(stun, string.Empty, string.Empty, DateTime.UtcNow);
        }

        var expiresAt = DateTime.UtcNow.Add(relay.Lifetime);
        var username = string.Create(
            CultureInfo.InvariantCulture,
            $"{new DateTimeOffset(expiresAt, TimeSpan.Zero).ToUnixTimeSeconds()}:{deviceId}");

        m_logger.LogInformation(
            "Issued a credential for the {Relay} relay to device {DeviceId}, good until {ExpiresAt:u}", relayName, deviceId, expiresAt);

        return new IceServers(relay.Urls, username, Sign(username, relay.Secret), expiresAt);
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
