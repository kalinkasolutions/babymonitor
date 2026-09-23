using System.Net;

namespace Babymonitor.Tests;

/// <summary>
/// The page an invitation link lands on. Mail clients and security scanners fetch links they find
/// in a message, so a GET that did the linking was accepted by whatever opened the mail rather
/// than by the person reading it. Asking first is the whole fix, and this is what stops it
/// quietly turning back into a one-click GET.
/// </summary>
public sealed class AccountLinkPageTests : IClassFixture<BabymonitorApp>
{
    private readonly BabymonitorApp m_app;

    public AccountLinkPageTests(BabymonitorApp app) => m_app = app;

    [Fact]
    public async Task FetchingTheLinkOnlyAsks()
    {
        var invitee = await Accounts.RegisterAsync(m_app, "invitee");
        var inviter = await Accounts.RegisterAsync(m_app, "inviter");
        using var client = m_app.CreateSignedOutClient();

        var page = await client.GetAsync(
            $"api/auth/accept-link?userId={invitee.UserId}&inviterId={inviter.UserId}&token=whatever");

        Assert.Equal(HttpStatusCode.OK, page.StatusCode);
        Assert.Contains("Yes, link our accounts", await page.Content.ReadAsStringAsync(), StringComparison.Ordinal);

        // Nothing happened: the accounts still cannot see each other's phones.
        var devices = await Accounts.ReadAsync(await invitee.Client.GetAsync("api/devices"));
        Assert.DoesNotContain(
            devices.EnumerateArray(), d => d.GetProperty("id").GetGuid() == inviter.DeviceId);
    }

    [Fact]
    public async Task ThePageNamesWhoIsAsking()
    {
        var invitee = await Accounts.RegisterAsync(m_app, "invitee");
        var inviter = await Accounts.RegisterAsync(m_app, "inviter");
        using var client = m_app.CreateSignedOutClient();

        var page = await client.GetAsync(
            $"api/auth/accept-link?userId={invitee.UserId}&inviterId={inviter.UserId}&token=whatever");

        Assert.Contains("inviter", await page.Content.ReadAsStringAsync(), StringComparison.Ordinal);
    }

    /// <summary>Accepting still needs a real token; the form is not the authorisation.</summary>
    [Fact]
    public async Task AcceptingWithAnInventedTokenIsRefused()
    {
        var invitee = await Accounts.RegisterAsync(m_app, "invitee");
        var inviter = await Accounts.RegisterAsync(m_app, "inviter");
        using var client = m_app.CreateSignedOutClient();

        using var form = new FormUrlEncodedContent(new Dictionary<string, string>(StringComparer.Ordinal)
        {
            ["userId"] = invitee.UserId.ToString(),
            ["inviterId"] = inviter.UserId.ToString(),
            ["token"] = "not a real token"
        });

        var response = await client.PostAsync("api/auth/accept-link", form);

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
    }
}
