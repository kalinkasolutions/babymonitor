namespace Babymonitor;

/// <summary>
/// Which reverse proxies this app believes when they describe the original request.
///
/// nginx terminates TLS and forwards plain HTTP, so without <c>X-Forwarded-Proto</c> the app
/// thinks every request arrived in the clear — which decides whether the auth cookie is marked
/// <c>Secure</c>, what address is logged, and where a redirect points. Those headers are trivially
/// forged, so they are only honoured from an address named here.
///
/// The default trusts nobody, which means loopback only, which in a container means nothing at
/// all: the proxy is never loopback from inside a network namespace. That is the setting to
/// change, and <c>Program.cs</c> says so at startup rather than letting it fail silently.
/// </summary>
public sealed class ProxyOptions
{
    public const string SectionName = "Proxy";

    /// <summary>
    /// Addresses or CIDR ranges of the proxies in front, e.g. <c>172.17.0.1</c> or
    /// <c>172.16.0.0/12</c>. Which address to put here is whatever the proxy appears as from
    /// inside the container, and that depends on how it reaches the published port — a proxy
    /// dialling loopback on the same host arrives as the Docker gateway, one dialling the host's
    /// own address arrives as that address, and one on another machine arrives as itself.
    /// </summary>
    public List<string> Trusted { get; set; } = [];

    /// <summary>
    /// Believe the headers whoever sends them. Correct only when nothing but the proxy can reach
    /// the app's port — otherwise anyone who can is free to claim any client address and that the
    /// request was secure when it was not. Bind the published port to loopback, or firewall it,
    /// before turning this on.
    /// </summary>
    public bool TrustAny { get; set; }
}
