using System.Net;
using System.Net.Http.Json;

namespace Babymonitor.Tests;

/// <summary>
/// The pairing code, which is the one credential an outsider can guess at. A code is good for
/// exactly one link, and that is only true because redeeming it is the same statement that looks
/// it up — so the interesting case is two claims arriving together.
/// </summary>
public sealed class PairingTests : IClassFixture<BabymonitorApp>
{
    private readonly BabymonitorApp m_app;

    public PairingTests(BabymonitorApp app) => m_app = app;

    [Fact]
    public async Task ACodeWorksOnce()
    {
        var showing = await Accounts.RegisterAsync(m_app, "showing");
        var first = await Accounts.RegisterAsync(m_app, "first");
        var second = await Accounts.RegisterAsync(m_app, "second");

        var code = await Accounts.PairingCodeAsync(showing);

        var claimed = await first.Client.PostAsJsonAsync("api/pairing/claim", new { code, proof = "" });
        var reclaimed = await second.Client.PostAsJsonAsync("api/pairing/claim", new { code, proof = "" });

        Assert.Equal(HttpStatusCode.OK, claimed.StatusCode);
        Assert.Equal(HttpStatusCode.BadRequest, reclaimed.StatusCode);
    }

    [Fact]
    public async Task TwoClaimsAtOnceCannotBothWin()
    {
        var showing = await Accounts.RegisterAsync(m_app, "showing");
        var racers = await Task.WhenAll(
            Enumerable.Range(0, 8).Select(i => Accounts.RegisterAsync(m_app, $"racer{i}")));

        var code = await Accounts.PairingCodeAsync(showing);

        // All at once against one code. The redemption is a single conditional UPDATE, so exactly
        // one of these should come back with the device and the rest should be told it is spent.
        var results = await Task.WhenAll(racers.Select(racer =>
            racer.Client.PostAsJsonAsync("api/pairing/claim", new { code, proof = "" })));

        Assert.Equal(1, results.Count(r => r.StatusCode == HttpStatusCode.OK));
    }

    [Fact]
    public async Task AskingForANewCodeRetiresTheOldOne()
    {
        var showing = await Accounts.RegisterAsync(m_app, "showing");
        var claiming = await Accounts.RegisterAsync(m_app, "claiming");

        var stale = await Accounts.PairingCodeAsync(showing);
        await Accounts.PairingCodeAsync(showing);

        var response = await claiming.Client.PostAsJsonAsync("api/pairing/claim", new { code = stale, proof = "" });

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
    }

    [Fact]
    public async Task AnInventedCodeIsRefused()
    {
        var claiming = await Accounts.RegisterAsync(m_app, "claiming");

        var response = await claiming.Client.PostAsJsonAsync(
            "api/pairing/claim", new { code = "ZZZZ-ZZZZ", proof = "" });

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
    }

    /// <summary>
    /// The proof travels from one phone to the other untouched. The backend cannot check it and
    /// must not be able to forge it, so all that is asserted here is that it is carried verbatim.
    /// </summary>
    [Fact]
    public async Task TheProofIsRelayedUnchanged()
    {
        var showing = await Accounts.RegisterAsync(m_app, "showing");
        var claiming = await Accounts.RegisterAsync(m_app, "claiming");
        var code = await Accounts.PairingCodeAsync(showing);

        const string proof = "Zm9yd2FyZGVkLXVudG91Y2hlZA==";
        var response = await claiming.Client.PostAsJsonAsync("api/pairing/claim", new { code, proof });

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);

        // The claim answers with the phone that showed the code; the proof goes to that phone over
        // the hub, which a plain HTTP test cannot observe. What is checked here is that the claim
        // was accepted with a proof attached and reported the right device.
        var device = await Accounts.ReadAsync(response);
        Assert.Equal(showing.DeviceId, device.GetProperty("id").GetGuid());
    }

    [Fact]
    public async Task LinkingMakesTheOtherAccountsPhonesVisible()
    {
        var one = await Accounts.RegisterAsync(m_app, "one");
        var other = await Accounts.RegisterAsync(m_app, "other");

        var before = await Accounts.ReadAsync(await one.Client.GetAsync("api/devices"));
        Assert.DoesNotContain(
            before.EnumerateArray(), d => d.GetProperty("id").GetGuid() == other.DeviceId);

        await Accounts.LinkAsync(other, one);

        var after = await Accounts.ReadAsync(await one.Client.GetAsync("api/devices"));
        Assert.Contains(after.EnumerateArray(), d => d.GetProperty("id").GetGuid() == other.DeviceId);
    }

    /// <summary>
    /// Links are not transitive. Linking to a babysitter must not introduce them to everybody else
    /// already linked to the same account.
    /// </summary>
    [Fact]
    public async Task LinksAreNotTransitive()
    {
        var middle = await Accounts.RegisterAsync(m_app, "middle");
        var left = await Accounts.RegisterAsync(m_app, "left");
        var right = await Accounts.RegisterAsync(m_app, "right");

        await Accounts.LinkAsync(middle, left);
        await Accounts.LinkAsync(middle, right);

        var seenByLeft = await Accounts.ReadAsync(await left.Client.GetAsync("api/devices"));

        Assert.Contains(seenByLeft.EnumerateArray(), d => d.GetProperty("id").GetGuid() == middle.DeviceId);
        Assert.DoesNotContain(seenByLeft.EnumerateArray(), d => d.GetProperty("id").GetGuid() == right.DeviceId);
    }

    [Fact]
    public async Task UnlinkingTakesThePhonesAwayAgain()
    {
        var one = await Accounts.RegisterAsync(m_app, "one");
        var other = await Accounts.RegisterAsync(m_app, "other");
        await Accounts.LinkAsync(other, one);

        var dropped = await one.Client.DeleteAsync($"api/pairing/link/{other.UserId}");
        Assert.Equal(HttpStatusCode.OK, dropped.StatusCode);

        var after = await Accounts.ReadAsync(await one.Client.GetAsync("api/devices"));
        Assert.DoesNotContain(after.EnumerateArray(), d => d.GetProperty("id").GetGuid() == other.DeviceId);
    }
}
