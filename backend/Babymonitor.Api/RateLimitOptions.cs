namespace Babymonitor;

/// <summary>
/// How hard the endpoints an outsider can reach may be hammered, bound from the
/// <c>RateLimits</c> section.
///
/// These are counted per address, and a household is one address — two phones, a babysitter and a
/// spare tablet all share a budget. So the defaults are set where a family never notices them and
/// somebody working through a list of addresses does. Password guessing has a second defence that
/// does not depend on the address at all: Identity locks the account after a few failures.
/// </summary>
public sealed class RateLimitOptions
{
    public const string SectionName = "RateLimits";

    /// <summary>Sign-in, registration, and the endpoints that send somebody a mail.</summary>
    public int CredentialsPerFiveMinutes { get; set; } = 20;

    /// <summary>
    /// Guesses at a pairing code. Eight characters from a 32-letter alphabet are not guessable in
    /// the five minutes a code lives whatever this is set to; the limit is so nothing can sit
    /// there trying.
    /// </summary>
    public int PairingClaimsPerMinute { get; set; } = 10;

    /// <summary>
    /// Invitations, counted per account. Each one can create an account and send mail to an
    /// address the caller chose, which is worth bounding even for a signed-in caller.
    /// </summary>
    public int InvitesPerHour { get; set; } = 5;
}
