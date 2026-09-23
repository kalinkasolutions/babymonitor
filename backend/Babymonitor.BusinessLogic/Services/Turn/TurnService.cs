using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using Dtos.Turn;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;
using Shared;

namespace Babymonitor.BusinessLogic.Services.Turn;

public sealed class TurnService : ITurnService
{
    private readonly TurnOptions m_options;
    private readonly ILogger<TurnService> m_logger;

    public TurnService(IOptions<TurnOptions> options, ILogger<TurnService> logger)
    {
        m_options = options.Value;
        m_logger = logger;
    }

    public OperationResult<IceServersDto> IssueAsync(Guid userId)
    {
        // STUN is handed out whether or not a relay is configured, and needs no credential: it
        // only tells a phone its own public address, which is what lets two of them reach each
        // other directly. The relay is the fallback for when that does not work, never the first
        // choice — it costs bandwidth and puts this server in the middle of the media.
        var stun = m_options.Urls
            .Where(url => url.StartsWith("stun:", StringComparison.OrdinalIgnoreCase))
            .ToList();

        if (!m_options.IsConfigured)
        {
            // A household on one WiFi never needs a relay, so running without one is a legitimate
            // setup rather than a misconfiguration to fail on.
            m_logger.LogInformation("No relay configured; issuing STUN only");
            return OperationResult<IceServersDto>.Success(new IceServersDto
            {
                Urls = stun,
                Username = string.Empty,
                Credential = string.Empty,
                ExpiresAt = DateTime.UtcNow
            });
        }

        var expiresAt = DateTime.UtcNow.Add(m_options.Lifetime);
        var username = BuildUsername(expiresAt, userId);

        // The credential is not logged — it is a working password for the relay until it expires.
        // Who was issued one and until when is the part worth having when a bill looks wrong.
        m_logger.LogInformation(
            "Issued a relay credential to user {UserId}, good until {ExpiresAt:u}", userId, expiresAt);

        return OperationResult<IceServersDto>.Success(new IceServersDto
        {
            Urls = m_options.Urls,
            Username = username,
            Credential = Sign(username, m_options.Secret),
            ExpiresAt = expiresAt
        });
    }

    /// <summary>
    /// The TURN REST scheme: the username carries its own expiry, so coturn can reject a stale
    /// credential without having been told about it.
    /// </summary>
    private static string BuildUsername(DateTime expiresAt, Guid userId) =>
        string.Create(
            CultureInfo.InvariantCulture,
            $"{new DateTimeOffset(expiresAt, TimeSpan.Zero).ToUnixTimeSeconds()}:{userId}");

    /// <summary>
    /// HMAC-SHA1 is what the scheme specifies and what coturn recomputes. It is a message
    /// authentication code over a value we generated, not a password hash, so SHA-1's weakness
    /// as a digest does not come into it.
    /// </summary>
    private static string Sign(string username, string secret)
    {
        // S4790 flags SHA-1. It is not our choice: coturn recomputes this exact HMAC, so a
        // stronger digest would simply fail to authenticate.
#pragma warning disable S4790
        using var hmac = new HMACSHA1(Encoding.UTF8.GetBytes(secret));
#pragma warning restore S4790
        return Convert.ToBase64String(hmac.ComputeHash(Encoding.UTF8.GetBytes(username)));
    }
}
