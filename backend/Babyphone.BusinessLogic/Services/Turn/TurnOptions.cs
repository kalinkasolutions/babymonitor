namespace Babyphone.BusinessLogic.Services.Turn;

/// <summary>
/// Settings for the relay, bound from the <c>Turn</c> section of configuration. The secret is
/// shared with coturn's <c>static-auth-secret</c>; anything holding it can mint credentials.
/// </summary>
public sealed class TurnOptions
{
    public const string SectionName = "Turn";

    public string Secret { get; set; } = string.Empty;

    /// <summary>
    /// ICE server URLs, handed to the phones as they are. Usually one <c>stun:</c> and several
    /// <c>turn:</c> / <c>turns:</c> entries for the same host over different transports.
    /// </summary>
    public List<string> Urls { get; set; } = [];

    /// <summary>
    /// How long an issued credential stays valid. Long enough to cover a night without reissuing,
    /// short enough that one leaking is not permanent.
    /// </summary>
    public TimeSpan Lifetime { get; set; } = TimeSpan.FromHours(12);

    /// <summary>Without a secret or a URL there is no relay to point anyone at.</summary>
    public bool IsConfigured => !string.IsNullOrWhiteSpace(Secret) && Urls.Count > 0;
}
