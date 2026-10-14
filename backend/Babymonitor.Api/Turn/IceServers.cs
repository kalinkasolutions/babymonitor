namespace Babymonitor.Turn;

/// <summary>What a phone needs for its ICE agent. One credential works for every URL.</summary>
public sealed record IceServers(IReadOnlyList<string> Urls, string Username, string Credential, DateTime ExpiresAt);
