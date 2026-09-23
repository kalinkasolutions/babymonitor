using System.Net;
using System.Net.Http.Json;
using Microsoft.AspNetCore.Hosting;

namespace Babymonitor.Tests;

/// <summary>
/// The limits turned down far enough to see them bite. The rest of the suite runs with them raised
/// out of the way, which is what makes a test here worth having: otherwise nothing would notice a
/// policy that stopped being applied to an endpoint.
/// </summary>
public sealed class RateLimitTests
{
    private sealed class App : BabymonitorApp
    {
        protected override IReadOnlyDictionary<string, string> Limits =>
            new Dictionary<string, string>(StringComparer.Ordinal)
            {
                [$"{RateLimitOptions.SectionName}:{nameof(RateLimitOptions.CredentialsPerFiveMinutes)}"] = "3",
                [$"{RateLimitOptions.SectionName}:{nameof(RateLimitOptions.PairingClaimsPerMinute)}"] = "3",
                [$"{RateLimitOptions.SectionName}:{nameof(RateLimitOptions.InvitesPerHour)}"] = "1000"
            };
    }

    [Fact]
    public async Task GuessingPasswordsIsThrottled()
    {
        using var app = new App();
        using var client = app.CreateSignedOutClient();

        var statuses = new List<HttpStatusCode>();
        for (var attempt = 0; attempt < 6; attempt++)
        {
            var response = await client.PostAsJsonAsync(
                "api/auth/login", new { email = "nobody@example.test", password = "guess" });
            statuses.Add(response.StatusCode);
        }

        Assert.Contains(HttpStatusCode.TooManyRequests, statuses);
    }

    /// <summary>
    /// Claiming is the one endpoint where every call is a guess at somebody's live code. Counted
    /// per account, so this uses one caller throughout.
    /// </summary>
    [Fact]
    public async Task GuessingPairingCodesIsThrottled()
    {
        using var app = new App();
        var phone = await Accounts.RegisterAsync(app, "guesser");

        var statuses = new List<HttpStatusCode>();
        for (var attempt = 0; attempt < 6; attempt++)
        {
            var response = await phone.Client.PostAsJsonAsync(
                "api/pairing/claim", new { code = "ZZZZ-ZZZZ", proof = "" });
            statuses.Add(response.StatusCode);
        }

        Assert.Contains(HttpStatusCode.TooManyRequests, statuses);
    }

    /// <summary>
    /// The limits are configuration rather than constants, because a household behind one address
    /// shares a budget with everyone else behind it and what is generous differs per deployment.
    /// </summary>
    [Fact]
    public async Task RaisingTheLimitLetsTheSameTrafficThrough()
    {
        using var app = new BabymonitorApp();
        using var client = app.CreateSignedOutClient();

        var statuses = new List<HttpStatusCode>();
        for (var attempt = 0; attempt < 6; attempt++)
        {
            var response = await client.PostAsJsonAsync(
                "api/auth/login", new { email = "nobody@example.test", password = "guess" });
            statuses.Add(response.StatusCode);
        }

        Assert.DoesNotContain(HttpStatusCode.TooManyRequests, statuses);
    }
}
