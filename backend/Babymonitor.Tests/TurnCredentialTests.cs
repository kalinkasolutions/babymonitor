using System.Globalization;
using System.Net;
using System.Net.Http.Json;
using System.Security.Cryptography;
using System.Text;
using Microsoft.AspNetCore.Hosting;

namespace Babymonitor.Tests;

/// <summary>
/// The relay credential. coturn recomputes the same HMAC from the same shared secret and compares,
/// so this is one of the few things in the app where the two sides must agree byte for byte and
/// nothing in the codebase would notice if they stopped: a wrong signature is not an error, it is
/// a relay that quietly refuses every call made from a mobile network.
/// </summary>
public sealed class TurnCredentialTests
{
    private const string Secret = "a-shared-secret-with-coturn";

    private sealed class App(bool withRelay) : BabymonitorApp
    {
        protected override void ConfigureWebHost(IWebHostBuilder builder)
        {
            base.ConfigureWebHost(builder);
            builder.UseSetting("Turn:Secret", withRelay ? Secret : string.Empty);
            builder.UseSetting("Turn:Urls:0", "stun:turn.example.com:3478");
            builder.UseSetting("Turn:Urls:1", "turn:turn.example.com:3478?transport=udp");
        }
    }

    [Fact]
    public async Task TheCredentialIsTheHmacCoturnWillRecompute()
    {
        using var app = new App(withRelay: true);
        var phone = await Accounts.RegisterAsync(app, "phone");

        var issued = await Accounts.ReadAsync(await phone.Client.GetAsync("api/turn/credentials"));
        var username = issued.GetProperty("username").GetString()!;
        var credential = issued.GetProperty("credential").GetString()!;

        // Exactly what coturn does with use-auth-secret: HMAC-SHA1 of the username, base64.
        using var hmac = new HMACSHA1(Encoding.UTF8.GetBytes(Secret));
        var expected = Convert.ToBase64String(hmac.ComputeHash(Encoding.UTF8.GetBytes(username)));

        Assert.Equal(expected, credential);
    }

    /// <summary>The TURN REST scheme: the username is the expiry and the user, so coturn can reject a stale one alone.</summary>
    [Fact]
    public async Task TheUsernameCarriesItsOwnExpiry()
    {
        using var app = new App(withRelay: true);
        var phone = await Accounts.RegisterAsync(app, "phone");

        var issued = await Accounts.ReadAsync(await phone.Client.GetAsync("api/turn/credentials"));
        var username = issued.GetProperty("username").GetString()!;

        var parts = username.Split(':');
        Assert.Equal(2, parts.Length);

        var expiry = DateTimeOffset.FromUnixTimeSeconds(
            long.Parse(parts[0], CultureInfo.InvariantCulture));
        Assert.True(expiry > DateTimeOffset.UtcNow, "the credential is already expired");
        Assert.Equal(phone.UserId, Guid.Parse(parts[1]));
    }

    /// <summary>
    /// A household on one WiFi never needs a relay, so running without one is a setup rather than
    /// a misconfiguration. STUN still goes out, because that needs no credential.
    /// </summary>
    [Fact]
    public async Task WithoutARelayStunIsStillIssuedAndCarriesNoCredential()
    {
        using var app = new App(withRelay: false);
        var phone = await Accounts.RegisterAsync(app, "phone");

        var issued = await Accounts.ReadAsync(await phone.Client.GetAsync("api/turn/credentials"));

        Assert.Equal(string.Empty, issued.GetProperty("credential").GetString());
        Assert.All(
            issued.GetProperty("urls").EnumerateArray(),
            url => Assert.StartsWith("stun:", url.GetString()!, StringComparison.Ordinal));
    }

    [Fact]
    public async Task ACredentialNeedsASignedInCaller()
    {
        using var app = new App(withRelay: true);
        using var anonymous = app.CreateSignedOutClient();

        var response = await anonymous.GetAsync("api/turn/credentials");

        Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
    }
}
