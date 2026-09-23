using System.Globalization;
using System.Net;
using Microsoft.AspNetCore.HttpOverrides;

// Two types of this name are in scope: the one KnownIPNetworks holds, and the obsolete
// HttpOverrides one that the deprecated KnownNetworks holds.
using IPNetwork = System.Net.IPNetwork;

namespace Babymonitor.Extensions;

public static class ForwardedHeadersExtension
{
    /// <summary>
    /// Applies <c>X-Forwarded-For</c> and <c>X-Forwarded-Proto</c> from the proxies named in
    /// <paramref name="options"/>. Anything unparseable is dropped with a line saying so — a
    /// typo in a trusted address would otherwise silently narrow what is trusted to nothing.
    /// </summary>
    public static IApplicationBuilder UseConfiguredForwardedHeaders(
        this IApplicationBuilder app, ProxyOptions options, ILogger logger)
    {
        var forwarded = new ForwardedHeadersOptions
        {
            ForwardedHeaders = ForwardedHeaders.XForwardedFor | ForwardedHeaders.XForwardedProto
        };

        // Both lists start with loopback in them, which is never what is wanted here: either the
        // proxy is named below, or it is not trusted at all.
        forwarded.KnownIPNetworks.Clear();
        forwarded.KnownProxies.Clear();

        if (options.TrustAny)
        {
            // Empty lists are what turns the peer check off altogether, so this is the whole of it.
            logger.LogWarning(
                "Trusting forwarded headers from any address. This is only safe while nothing but "
                + "the reverse proxy can reach this port.");
            app.UseForwardedHeaders(forwarded);
            return app;
        }

        foreach (var entry in options.Trusted.Select(value => value.Trim()).Where(value => value.Length > 0))
        {
            if (TryParseNetwork(entry, out var network))
            {
                forwarded.KnownIPNetworks.Add(network);
            }
            else if (IPAddress.TryParse(entry, out var address))
            {
                forwarded.KnownProxies.Add(address);
            }
            else
            {
                logger.LogError("Ignoring {Entry}: not an IP address or a CIDR range", entry);
            }
        }

        var trusted = forwarded.KnownProxies.Count + forwarded.KnownIPNetworks.Count;
        if (trusted == 0)
        {
            // Return without the middleware rather than with two empty lists. Empty is what turns
            // the peer check off, so registering it here would trust every caller — the opposite
            // of what "nothing configured" should mean, and of what this warning says.
            logger.LogWarning(
                "No trusted proxies configured. Set {TrustedSetting} or {TrustAnySetting}, or "
                + "forwarded headers are ignored and every request through a proxy looks like "
                + "plain HTTP from the proxy's own address.",
                $"{ProxyOptions.SectionName}:{nameof(ProxyOptions.Trusted)}",
                $"{ProxyOptions.SectionName}:{nameof(ProxyOptions.TrustAny)}");
            return app;
        }

        logger.LogInformation("Trusting forwarded headers from {Count} configured proxy entries", trusted);
        app.UseForwardedHeaders(forwarded);
        return app;
    }

    private static bool TryParseNetwork(string entry, out IPNetwork network)
    {
        network = default;

        var slash = entry.IndexOf('/', StringComparison.Ordinal);
        if (slash < 0)
        {
            return false;
        }

        if (!IPAddress.TryParse(entry.AsSpan(0, slash), out var prefix) ||
            !int.TryParse(entry.AsSpan(slash + 1), NumberStyles.Integer, CultureInfo.InvariantCulture, out var length))
        {
            return false;
        }

        // IPNetwork insists the host bits are already zero, while every other tool that takes a
        // CIDR range accepts "172.17.0.1/16" and means the /16 around it. Mask rather than refuse.
        // It also throws on a prefix length the address family cannot hold, and a bad line in
        // configuration should cost a log entry rather than the whole startup.
        try
        {
            network = new IPNetwork(Mask(prefix, length), length);
            return true;
        }
        catch (ArgumentException)
        {
            return false;
        }
    }

    private static IPAddress Mask(IPAddress address, int prefixLength)
    {
        var bytes = address.GetAddressBytes();
        for (var i = 0; i < bytes.Length; i++)
        {
            var bitsHere = Math.Clamp(prefixLength - (i * 8), 0, 8);
            bytes[i] &= (byte)(0xFF << (8 - bitsHere));
        }

        return new IPAddress(bytes);
    }
}
