using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using Babymonitor.Endpoints;
using Microsoft.AspNetCore.Hosting;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.Logging;

namespace Babymonitor.Tests;

/// <summary>
/// The phone's log upload. Only a signed-in phone may write to the log, and one upload is capped
/// so a phone that was offline for a week cannot send it all in one request.
/// </summary>
public sealed class PhoneLogTests
{
    private static object Upload(int lines) => new
    {
        appVersion = "0.0.5-alpha",
        entries = Enumerable.Range(0, lines).Select(i => new
        {
            time = DateTimeOffset.UtcNow,
            level = "Info",
            tag = "Test",
            message = $"line {i}"
        })
    };

    [Fact]
    public async Task APhoneCanUploadItsLog()
    {
        using var app = new BabymonitorApp();
        using var phone = new TestPhone();
        using var client = app.CreateClientFor(phone);

        using var response = await client.PostAsJsonAsync("api/logs", Upload(3));

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
    }

    [Fact]
    public async Task AnUploadNeedsAnAuthenticatedDevice()
    {
        using var app = new BabymonitorApp();
        using var anonymous = app.CreateClient();

        using var response = await anonymous.PostAsJsonAsync("api/logs", Upload(1));

        Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
    }

    [Fact]
    public async Task AnOversizedUploadIsRefused()
    {
        using var app = new BabymonitorApp();
        using var phone = new TestPhone();
        using var client = app.CreateClientFor(phone);

        using var response = await client.PostAsJsonAsync("api/logs", Upload(PhoneLogEndpoint.MaxEntriesPerUpload + 1));

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
    }

    private sealed class AppAt(string serverLevel, string? phoneLevel) : BabymonitorApp
    {
        protected override void ConfigureWebHost(IWebHostBuilder builder)
        {
            base.ConfigureWebHost(builder);
            builder.UseSetting("Logging:LogLevel:Default", serverLevel);
            builder.UseSetting("PhoneLogging:Level", phoneLevel ?? string.Empty);
        }
    }

    [Theory]
    [InlineData("Information", null, "Info")]
    [InlineData("Trace", null, "Verbose")]
    [InlineData("Information", "Debug", "Debug")]
    [InlineData("Debug", "Warning", "Warn")]
    public async Task TheReplySaysHowMuchThePhoneShouldSend(string serverLevel, string? phoneLevel, string expected)
    {
        using var app = new AppAt(serverLevel, phoneLevel);
        using var phone = new TestPhone();
        using var client = app.CreateClientFor(phone);

        using var response = await client.PostAsJsonAsync("api/logs", Upload(1));
        var reply = JsonSerializer.Deserialize<JsonElement>(await response.Content.ReadAsStringAsync());

        Assert.Equal(expected, reply.GetProperty("level").GetString());
    }

    [Theory]
    [InlineData("Info", LogLevel.Information)]
    [InlineData("Warn", LogLevel.Warning)]
    [InlineData("Error", LogLevel.Error)]
    [InlineData("Fatal", LogLevel.Critical)]
    [InlineData("Debug", LogLevel.Debug)]
    [InlineData("Verbose", LogLevel.Trace)]
    [InlineData("nonsense", LogLevel.Information)]
    [InlineData(null, LogLevel.Information)]
    public void AndroidLevelsMapToDotnetLevels(string? name, LogLevel expected) =>
        Assert.Equal(expected, PhoneLogEndpoint.LevelOf(name));
}
