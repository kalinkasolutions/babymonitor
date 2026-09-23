using System.Net;
using System.Net.Http.Json;

namespace Babymonitor.Tests;

/// <summary>
/// What one account may do to another account's phones, which is nothing. The answers are
/// deliberately identical for "no such device" and "not yours", so a device id cannot be probed —
/// these tests are what stops that sameness being lost to a well-meaning better error message.
/// </summary>
public sealed class DeviceAccessTests : IClassFixture<BabymonitorApp>
{
    private readonly BabymonitorApp m_app;

    public DeviceAccessTests(BabymonitorApp app) => m_app = app;

    [Fact]
    public async Task AStrangerCannotRevokeYourPhone()
    {
        var mine = await Accounts.RegisterAsync(m_app, "mine");
        var stranger = await Accounts.RegisterAsync(m_app, "stranger");

        var response = await stranger.Client.DeleteAsync($"api/devices/{mine.DeviceId}");

        Assert.Equal(HttpStatusCode.NotFound, response.StatusCode);
    }

    /// <summary>
    /// A link is what makes a phone visible, and it is explicitly not what makes it yours. Seeing
    /// a babysitter's phone in the list must not come with the ability to delete it.
    /// </summary>
    [Fact]
    public async Task ALinkedAccountCannotRevokeYourPhoneEither()
    {
        var mine = await Accounts.RegisterAsync(m_app, "mine");
        var friend = await Accounts.RegisterAsync(m_app, "friend");
        await Accounts.LinkAsync(mine, friend);

        var response = await friend.Client.DeleteAsync($"api/devices/{mine.DeviceId}");

        Assert.Equal(HttpStatusCode.NotFound, response.StatusCode);
    }

    [Fact]
    public async Task AStrangerCannotRenameYourPhone()
    {
        var mine = await Accounts.RegisterAsync(m_app, "mine");
        var stranger = await Accounts.RegisterAsync(m_app, "stranger");

        var response = await stranger.Client.PutAsJsonAsync(
            $"api/devices/{mine.DeviceId}/name", new { name = "renamed by somebody else" });

        Assert.Equal(HttpStatusCode.NotFound, response.StatusCode);
    }

    [Fact]
    public async Task AStrangerCannotHeartbeatYourPhone()
    {
        var mine = await Accounts.RegisterAsync(m_app, "mine");
        var stranger = await Accounts.RegisterAsync(m_app, "stranger");

        var response = await stranger.Client.PostAsJsonAsync(
            $"api/devices/{mine.DeviceId}/heartbeat", new { batteryPercent = 1, isCharging = false });

        Assert.Equal(HttpStatusCode.NotFound, response.StatusCode);
    }

    /// <summary>
    /// The same status for a device that exists but is not the caller's and one that never
    /// existed. A difference here is how a device id becomes guessable.
    /// </summary>
    [Fact]
    public async Task SomebodyElsesPhoneAndNoPhoneLookTheSame()
    {
        var mine = await Accounts.RegisterAsync(m_app, "mine");
        var stranger = await Accounts.RegisterAsync(m_app, "stranger");

        var theirs = await stranger.Client.DeleteAsync($"api/devices/{mine.DeviceId}");
        var nothing = await stranger.Client.DeleteAsync($"api/devices/{Guid.NewGuid()}");

        Assert.Equal(theirs.StatusCode, nothing.StatusCode);
    }

    [Fact]
    public async Task EveryDeviceRouteNeedsASignedInCaller()
    {
        using var anonymous = m_app.CreateSignedOutClient();

        Assert.Equal(HttpStatusCode.Unauthorized, (await anonymous.GetAsync("api/devices")).StatusCode);
        Assert.Equal(HttpStatusCode.Unauthorized, (await anonymous.GetAsync("api/account")).StatusCode);
        Assert.Equal(
            HttpStatusCode.Unauthorized,
            (await anonymous.GetAsync("api/turn/credentials")).StatusCode);
        Assert.Equal(
            HttpStatusCode.Unauthorized,
            (await anonymous.PostAsJsonAsync("api/pairing/code", new { })).StatusCode);
    }

    /// <summary>
    /// Registering is idempotent on the identity key, so a phone that reinstalls the app or signs
    /// in again does not accumulate rows the other phone then has to choose between.
    /// </summary>
    [Fact]
    public async Task RegisteringTheSameKeyTwiceKeepsOneDevice()
    {
        var phone = await Accounts.RegisterAsync(m_app, "phone");
        var key = Convert.ToBase64String(Guid.NewGuid().ToByteArray());

        var first = await phone.Client.PostAsJsonAsync("api/devices", new { name = "same phone", publicKey = key });
        var again = await phone.Client.PostAsJsonAsync("api/devices", new { name = "renamed", publicKey = key });

        var firstId = (await Accounts.ReadAsync(first)).GetProperty("id").GetGuid();
        var againId = (await Accounts.ReadAsync(again)).GetProperty("id").GetGuid();

        Assert.Equal(firstId, againId);
    }
}
