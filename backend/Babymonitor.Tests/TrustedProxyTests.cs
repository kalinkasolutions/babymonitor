using System.Net;
using Babymonitor.Extensions;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.TestHost;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging.Abstractions;

namespace Babymonitor.Tests;

/// <summary>
/// Whether the app believes a proxy describing the original request.
///
/// It matters more than it looks. nginx terminates TLS and forwards plain HTTP, so the scheme is
/// something only the proxy knows; ignore it and the app treats an encrypted request as clear, and
/// attributes every log line to the proxy instead of the caller. The framework default trusts
/// loopback only — and a proxy is never loopback from inside a container, whatever the topology —
/// so out of the box the headers are silently thrown away. That is the failure these pin down.
///
/// The peer address is set explicitly rather than left to the harness, because the address a proxy
/// appears as is the whole question: a proxy dialling loopback on the same host arrives as the
/// Docker gateway, one dialling the host's own address arrives as that address, and one on another
/// machine arrives as itself.
/// </summary>
public sealed class TrustedProxyTests
{
    /// <summary>What a Docker bridge gateway looks like, which is the common case.</summary>
    private const string Gateway = "172.17.0.1";

    private const string ClaimedClient = "203.0.113.9";

    [Fact]
    public async Task WithNothingConfiguredTheHeadersAreIgnored()
    {
        var (scheme, remote) = await AskAsync(new ProxyOptions(), peer: Gateway);

        Assert.Equal("http", scheme);
        Assert.Equal(Gateway, remote);
    }

    [Fact]
    public async Task TrustAnyBelievesTheHeaders()
    {
        var (scheme, remote) = await AskAsync(new ProxyOptions { TrustAny = true }, peer: Gateway);

        Assert.Equal("https", scheme);
        Assert.Equal(ClaimedClient, remote);
    }

    [Fact]
    public async Task ANamedProxyIsBelieved()
    {
        var options = new ProxyOptions { Trusted = [Gateway] };

        var (scheme, remote) = await AskAsync(options, peer: Gateway);

        Assert.Equal("https", scheme);
        Assert.Equal(ClaimedClient, remote);
    }

    [Fact]
    public async Task ANamedRangeIsBelieved()
    {
        var options = new ProxyOptions { Trusted = ["172.16.0.0/12"] };

        var (scheme, _) = await AskAsync(options, peer: Gateway);

        Assert.Equal("https", scheme);
    }

    /// <summary>
    /// Every other tool that takes a CIDR range accepts one whose host bits are set and means the
    /// network around it. Refusing would quietly trust nobody, which is the silent failure again.
    /// </summary>
    [Fact]
    public async Task ARangeWithHostBitsSetIsStillBelieved()
    {
        var options = new ProxyOptions { Trusted = ["172.17.0.1/16"] };

        var (scheme, _) = await AskAsync(options, peer: Gateway);

        Assert.Equal("https", scheme);
    }

    /// <summary>
    /// The case that bites when nginx is on another machine and the container sees its real
    /// address: naming the Docker bridge does nothing, because the proxy never appears as one.
    /// </summary>
    [Fact]
    public async Task NamingTheBridgeDoesNotHelpAProxyElsewhere()
    {
        var options = new ProxyOptions { Trusted = ["172.16.0.0/12"] };

        var (scheme, remote) = await AskAsync(options, peer: "10.0.0.5");

        Assert.Equal("http", scheme);
        Assert.Equal("10.0.0.5", remote);
    }

    [Fact]
    public async Task AProxyOnAnotherMachineIsBelievedOnceNamed()
    {
        var options = new ProxyOptions { Trusted = ["10.0.0.5"] };

        var (scheme, remote) = await AskAsync(options, peer: "10.0.0.5");

        Assert.Equal("https", scheme);
        Assert.Equal(ClaimedClient, remote);
    }

    /// <summary>A typo in the configuration must not take the whole app down with it.</summary>
    [Fact]
    public async Task AnUnparseableEntryIsSkippedRatherThanFatal()
    {
        var options = new ProxyOptions { Trusted = ["not-an-address", Gateway] };

        var (scheme, _) = await AskAsync(options, peer: Gateway);

        // The good entry beside it still counts, so one bad line costs that line and nothing else.
        Assert.Equal("https", scheme);
    }

    [Fact]
    public async Task BlankEntriesAreIgnored()
    {
        var options = new ProxyOptions { Trusted = ["", "   ", Gateway] };

        var (scheme, _) = await AskAsync(options, peer: Gateway);

        Assert.Equal("https", scheme);
    }

    /// <summary>
    /// Runs one request through the real middleware and reports what the app ended up believing
    /// about it.
    /// </summary>
    private static async Task<(string Scheme, string? Remote)> AskAsync(ProxyOptions options, string peer)
    {
        using var host = await new HostBuilder()
            .ConfigureWebHost(web => web
                .UseTestServer()
                .Configure(app =>
                {
                    // The harness leaves the peer unset, and the peer is the entire question here.
                    app.Use(async (context, next) =>
                    {
                        context.Connection.RemoteIpAddress = IPAddress.Parse(peer);
                        await next();
                    });

                    app.UseConfiguredForwardedHeaders(options, NullLogger.Instance);
                    app.Run(context => context.Response.WriteAsync(
                        $"{context.Request.Scheme} {context.Connection.RemoteIpAddress}"));
                }))
            .StartAsync();

        using var client = host.GetTestClient();
        using var request = new HttpRequestMessage(HttpMethod.Get, "/");
        request.Headers.Add("X-Forwarded-For", ClaimedClient);
        request.Headers.Add("X-Forwarded-Proto", "https");

        using var response = await client.SendAsync(request);
        var parts = (await response.Content.ReadAsStringAsync()).Split(' ');

        // The middleware writes back an IPv4-mapped address; compare on the plain form.
        var remote = IPAddress.TryParse(parts[1], out var address)
            ? (address.IsIPv4MappedToIPv6 ? address.MapToIPv4() : address).ToString()
            : parts[1];

        return (parts[0], remote);
    }
}
