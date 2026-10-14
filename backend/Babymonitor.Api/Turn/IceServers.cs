namespace Babymonitor.Turn;

/// <summary>
/// What a phone needs for its ICE agent. One credential works for every URL; the home relay has
/// its own secret, so it comes with its own credential, and only for a phone that sent the key.
/// </summary>
public sealed record IceServers(
    IReadOnlyList<string> Urls,
    string Username,
    string Credential,
    DateTime ExpiresAt,
    IceServers? HomeRelay = null);
