using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Microsoft.AspNetCore.Hosting;

namespace Babymonitor.Tests;

/// <summary>
/// The home relay may reach the server's own network, so only a phone with the server key gets a
/// credential for it. Its secret differs from the open relay's, or any credential would do.
/// </summary>
public sealed class HomeRelayTests
{
    private const string Key = "the-server-key";
    private const string OpenSecret = "open-relay-secret";
    private const string HomeSecret = "home-relay-secret";

    private sealed class App(string serverKey) : BabymonitorApp
    {
        protected override void ConfigureWebHost(IWebHostBuilder builder)
        {
            base.ConfigureWebHost(builder);
            builder.UseSetting("Server:Key", serverKey);
            builder.UseSetting("Turn:Secret", OpenSecret);
            builder.UseSetting("Turn:Urls:0", "turn:turn.example.com:3478?transport=udp");
            builder.UseSetting("HomeRelay:Secret", HomeSecret);
            builder.UseSetting("HomeRelay:Urls:0", "turn:turn.example.com:3480?transport=udp");
        }
    }

    [Fact]
    public async Task TheRightKeyAddsTheHomeRelay()
    {
        using var app = new App(Key);
        using var phone = new TestPhone();

        var issued = await IssueAsync(app, phone, Key);
        var home = issued.GetProperty("homeRelay");

        Assert.Equal("turn:turn.example.com:3480?transport=udp", home.GetProperty("urls")[0].GetString());
        var username = home.GetProperty("username").GetString()!;
        Assert.Equal(Hmac(HomeSecret, username), home.GetProperty("credential").GetString());
    }

    [Fact]
    public async Task TheOpenRelayKeepsItsOwnSecret()
    {
        using var app = new App(Key);
        using var phone = new TestPhone();

        var issued = await IssueAsync(app, phone, Key);
        var username = issued.GetProperty("username").GetString()!;

        Assert.Equal(Hmac(OpenSecret, username), issued.GetProperty("credential").GetString());
    }

    [Theory]
    [InlineData(null)]
    [InlineData("not-the-key")]
    public async Task WithoutTheKeyThereIsNoHomeRelay(string? sent)
    {
        using var app = new App(Key);
        using var phone = new TestPhone();

        var issued = await IssueAsync(app, phone, sent);

        Assert.Equal(JsonValueKind.Null, issued.GetProperty("homeRelay").ValueKind);
        Assert.NotEqual(string.Empty, issued.GetProperty("credential").GetString());
    }

    [Fact]
    public async Task AServerWithoutAKeyNeverHandsOutTheHomeRelay()
    {
        using var app = new App(serverKey: string.Empty);
        using var phone = new TestPhone();

        var issued = await IssueAsync(app, phone, sent: string.Empty);

        Assert.Equal(JsonValueKind.Null, issued.GetProperty("homeRelay").ValueKind);
    }

    [Theory]
    [InlineData(Key, HttpStatusCode.OK)]
    [InlineData("not-the-key", HttpStatusCode.Unauthorized)]
    [InlineData(null, HttpStatusCode.Unauthorized)]
    public async Task TheKeyCheckTellsARightKeyFromAWrongOne(string? sent, HttpStatusCode expected)
    {
        using var app = new App(Key);

        using var response = await CheckKeyAsync(app, sent);

        Assert.Equal(expected, response.StatusCode);
    }

    [Fact]
    public async Task TheKeyCheckSaysTheHomeRelayIsThere()
    {
        using var app = new App(Key);

        using var response = await CheckKeyAsync(app, Key);
        var status = JsonSerializer.Deserialize<JsonElement>(await response.Content.ReadAsStringAsync());

        Assert.True(status.GetProperty("homeRelay").GetBoolean());
    }

    /// <summary>Apps up to 0.0.5 always check a key, so a server without one must still say yes.</summary>
    [Fact]
    public async Task AServerWithoutAKeyPassesAnyKeyCheck()
    {
        using var app = new App(serverKey: string.Empty);

        using var response = await CheckKeyAsync(app, "whatever-the-old-app-had");
        var status = JsonSerializer.Deserialize<JsonElement>(await response.Content.ReadAsStringAsync());

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.False(status.GetProperty("homeRelay").GetBoolean());
    }

    private static async Task<JsonElement> IssueAsync(BabymonitorApp app, TestPhone phone, string? sent)
    {
        using var client = app.CreateClientFor(phone, serverKey: sent);
        using var response = await client.GetAsync("api/turn/credentials");
        response.EnsureSuccessStatusCode();
        return JsonSerializer.Deserialize<JsonElement>(await response.Content.ReadAsStringAsync());
    }

    private static async Task<HttpResponseMessage> CheckKeyAsync(BabymonitorApp app, string? sent)
    {
        using var client = app.CreateClient();
        using var request = new HttpRequestMessage(HttpMethod.Get, "api/status/key");
        if (sent is not null)
        {
            request.Headers.Add(Auth.ServerKey.HeaderName, sent);
        }

        return await client.SendAsync(request);
    }

    // What coturn does with use-auth-secret: HMAC-SHA1 of the username, base64.
    private static string Hmac(string secret, string username)
    {
        using var hmac = new HMACSHA1(Encoding.UTF8.GetBytes(secret));
        return Convert.ToBase64String(hmac.ComputeHash(Encoding.UTF8.GetBytes(username)));
    }
}
