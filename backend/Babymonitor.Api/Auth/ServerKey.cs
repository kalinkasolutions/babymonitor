using System.Security.Cryptography;
using System.Text;

namespace Babymonitor.Auth;

/// <summary>
/// The optional key a phone sends in <c>X-Server-Key</c>. It unlocks the home relay, the one relay
/// allowed to reach this server's own network; everything else works without it. Bound from
/// <c>Server:Key</c>.
/// </summary>
public sealed class ServerKey
{
    public const string SectionName = "Server";
    public const string HeaderName = "X-Server-Key";

    private readonly byte[]? m_key;

    public ServerKey(string? key)
    {
        m_key = string.IsNullOrWhiteSpace(key) ? null : Encoding.UTF8.GetBytes(key);
    }

    public bool IsSet => m_key is not null;

    // Constant time, so the key cannot be guessed one character at a time from response times.
    public bool Matches(string? presented) =>
        m_key is not null
        && presented is not null
        && CryptographicOperations.FixedTimeEquals(Encoding.UTF8.GetBytes(presented), m_key);
}
