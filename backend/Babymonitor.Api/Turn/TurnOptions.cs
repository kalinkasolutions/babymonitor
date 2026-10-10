namespace Babymonitor.Turn;

/// <summary>
/// The relay, bound from the <c>Turn</c> section. The secret is coturn's
/// <c>static-auth-secret</c>; anything holding it can mint credentials.
/// </summary>
public sealed class TurnOptions
{
    public const string SectionName = "Turn";

    public string Secret { get; set; } = string.Empty;

    /// <summary>ICE server URLs, handed to the phones as they are.</summary>
    public List<string> Urls { get; set; } = [];

    /// <summary>Long enough to cover a night, short enough that a leaked one does not last.</summary>
    public TimeSpan Lifetime { get; set; } = TimeSpan.FromHours(12);

    public bool IsConfigured => !string.IsNullOrWhiteSpace(Secret) && Urls.Count > 0;
}
