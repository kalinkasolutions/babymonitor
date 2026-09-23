using System.Net;
using Entities.Account;

namespace Babymonitor.Tests;

/// <summary>
/// Deleting an account that is linked to another one.
///
/// <see cref="AccountLink"/> stores the pair sorted, so which of the two columns an account lands
/// in is decided by comparing two Guids — a coin flip. While one column cascaded and the other did
/// not, deleting the account that happened to sort second raised a foreign-key error, which came
/// back as an unexplained 500 and left an account nobody could delete. Both orderings are
/// exercised here because testing only one would have passed on the broken schema half the time.
/// </summary>
public sealed class AccountDeletionTests : IClassFixture<BabymonitorApp>
{
    private readonly BabymonitorApp m_app;

    public AccountDeletionTests(BabymonitorApp app) => m_app = app;

    [Fact]
    public async Task DeletesTheAccountThatSortsFirstInTheLink()
    {
        var (first, _) = await LinkedPairAsync();

        var response = await Accounts.DeleteAccountAsync(first);

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
    }

    [Fact]
    public async Task DeletesTheAccountThatSortsSecondInTheLink()
    {
        var (_, second) = await LinkedPairAsync();

        var response = await Accounts.DeleteAccountAsync(second);

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
    }

    [Fact]
    public async Task TakesTheLinkAndTheDevicesWithIt()
    {
        var (first, second) = await LinkedPairAsync();

        Assert.Equal(HttpStatusCode.OK, (await Accounts.DeleteAccountAsync(second)).StatusCode);

        // The survivor is still signed in, and the deleted account's phone is gone from its list
        // rather than lingering as a row pointing at an account that no longer exists.
        var devices = await first.Client.GetAsync("api/devices");
        Assert.Equal(HttpStatusCode.OK, devices.StatusCode);

        var remaining = await Accounts.ReadAsync(devices);
        Assert.DoesNotContain(
            remaining.EnumerateArray(),
            device => device.GetProperty("id").GetGuid() == second.DeviceId);
    }

    [Fact]
    public async Task RefusesWithoutTheRightPassword()
    {
        var phone = await Accounts.RegisterAsync(m_app, "solo");

        var response = await Accounts.DeleteAccountAsync(phone, "not the password");

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
    }

    /// <summary>
    /// Two linked accounts, returned in the order the link stores them, so a test can say which
    /// side it is deleting rather than hoping.
    /// </summary>
    private async Task<(Accounts.Phone First, Accounts.Phone Second)> LinkedPairAsync()
    {
        var one = await Accounts.RegisterAsync(m_app, "one");
        var other = await Accounts.RegisterAsync(m_app, "other");
        await Accounts.LinkAsync(one, other);

        var (firstId, _) = AccountLink.Order(one.UserId, other.UserId);
        return firstId == one.UserId ? (one, other) : (other, one);
    }
}
