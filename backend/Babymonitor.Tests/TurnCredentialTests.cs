using System.Globalization;
using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Microsoft.AspNetCore.Hosting;

namespace Babymonitor.Tests;

/// <summary>
/// The relay credential. coturn recomputes the same HMAC from the same secret, and a mismatch is
/// not an error anywhere: it is a relay that quietly refuses every call from mobile data.
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
        using var phone = new TestPhone();

        var issued = await IssueAsync(app, phone);
        var username = issued.GetProperty("username").GetString()!;

        // What coturn does with use-auth-secret: HMAC-SHA1 of the username, base64.
        using var hmac = new HMACSHA1(Encoding.UTF8.GetBytes(Secret));
        var expected = Convert.ToBase64String(hmac.ComputeHash(Encoding.UTF8.GetBytes(username)));

        Assert.Equal(expected, issued.GetProperty("credential").GetString());
    }

    [Fact]
    public async Task TheUsernameIsTheExpiryAndTheDevice()
    {
        using var app = new App(withRelay: true);
        using var phone = new TestPhone();

        var issued = await IssueAsync(app, phone);
        var parts = issued.GetProperty("username").GetString()!.Split(':');

        Assert.Equal(2, parts.Length);
        var expiry = DateTimeOffset.FromUnixTimeSeconds(long.Parse(parts[0], CultureInfo.InvariantCulture));
        Assert.True(expiry > DateTimeOffset.UtcNow, "the credential is already expired");
        Assert.Equal(phone.DeviceId, parts[1]);
        Assert.True(issued.TryGetProperty("expiresAt", out _));
    }

    [Fact]
    public async Task WithoutARelayStunIsStillIssuedAndCarriesNoCredential()
    {
        using var app = new App(withRelay: false);
        using var phone = new TestPhone();

        var issued = await IssueAsync(app, phone);

        Assert.Equal(string.Empty, issued.GetProperty("credential").GetString());
        Assert.All(
            issued.GetProperty("urls").EnumerateArray(),
            url => Assert.StartsWith("stun:", url.GetString()!, StringComparison.Ordinal));
    }

    [Fact]
    public async Task ACredentialNeedsAnAuthenticatedDevice()
    {
        using var app = new App(withRelay: true);
        using var anonymous = app.CreateClient();

        using var response = await anonymous.GetAsync("api/turn/credentials");

        Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
    }

    private static async Task<JsonElement> IssueAsync(BabymonitorApp app, TestPhone phone)
    {
        using var client = app.CreateClientFor(phone);
        using var response = await client.GetAsync("api/turn/credentials");
        response.EnsureSuccessStatusCode();
        return JsonSerializer.Deserialize<JsonElement>(await response.Content.ReadAsStringAsync());
    }
}
