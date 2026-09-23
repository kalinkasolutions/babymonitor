namespace Dtos.Turn;

/// <summary>
/// What a phone needs to configure its ICE agent. The same credential works for every URL,
/// which is how one relay can be offered over UDP, TCP and TLS at once.
/// </summary>
public sealed class IceServersDto
{
    public List<string> Urls { get; set; } = [];

    public string Username { get; set; }
    public string Credential { get; set; }

    /// <summary>When these stop working, so a phone can renew before a night rather than during one.</summary>
    public DateTime ExpiresAt { get; set; }
}
