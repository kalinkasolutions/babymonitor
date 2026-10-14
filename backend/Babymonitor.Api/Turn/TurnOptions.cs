namespace Babymonitor.Turn;

/// <summary>
/// A relay, bound from the <c>Turn</c> section, or from <c>HomeRelay</c> as the named options
/// <see cref="HomeRelay"/>. The secret is coturn's <c>static-auth-secret</c>; anything holding it
/// can mint credentials. The two relays need different secrets, or a credential for the open relay
/// would also work on the home one.
/// </summary>
public sealed class TurnOptions
{
    public const string SectionName = "Turn";
    public const string HomeRelay = "HomeRelay";

    public string Secret { get; set; } = string.Empty;

    /// <summary>ICE server URLs, handed to the phones as they are.</summary>
    public List<string> Urls { get; set; } = [];

    /// <summary>Long enough to cover a night, short enough that a leaked one does not last.</summary>
    public TimeSpan Lifetime { get; set; } = TimeSpan.FromHours(12);

    public bool IsConfigured => !string.IsNullOrWhiteSpace(Secret) && Urls.Count > 0;
}
