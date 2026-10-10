using System.Security.Cryptography;
using System.Text;

namespace Babymonitor.Auth;

/// <summary>
/// The shared key every phone sends in <c>X-Server-Key</c>, so only people told the key can use
/// this relay at all. Bound from <c>Server:Key</c>.
/// </summary>
public sealed class ServerKey
{
    public const string SectionName = "Server";
    public const string HeaderName = "X-Server-Key";

    private readonly byte[] m_key;

    public ServerKey(string key)
    {
        if (string.IsNullOrWhiteSpace(key))
        {
            throw new InvalidOperationException(
                "Server:Key is not set. Generate one with `openssl rand -base64 24` and set SERVER_KEY in .env.");
        }

        m_key = Encoding.UTF8.GetBytes(key);
    }

    // Constant time, so the key cannot be guessed one character at a time from response times.
    public bool Matches(string? presented) =>
        presented is not null
        && CryptographicOperations.FixedTimeEquals(Encoding.UTF8.GetBytes(presented), m_key);
}
