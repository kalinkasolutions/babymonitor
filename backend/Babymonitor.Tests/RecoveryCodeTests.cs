using System.Net;
using System.Net.Http.Json;

namespace Babymonitor.Tests;

/// <summary>
/// Fresh recovery codes retire the old ones and are ten standing ways past the second factor, so
/// asking for them is as much of a change as turning two-factor off — and takes the password for
/// the same reason. Without that, a stolen cookie was as good as the password the second factor
/// existed to back up.
/// </summary>
public sealed class RecoveryCodeTests : IClassFixture<BabymonitorApp>
{
    private readonly BabymonitorApp m_app;

    public RecoveryCodeTests(BabymonitorApp app) => m_app = app;

    [Fact]
    public async Task RegeneratingNeedsThePassword()
    {
        var phone = await Accounts.RegisterAsync(m_app, "twofactor");

        var response = await phone.Client.PostAsJsonAsync(
            "api/account/2fa/recovery-codes", new { currentPassword = "not the password" });

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
    }

    [Fact]
    public async Task RegeneratingNeedsAPasswordAtAll()
    {
        var phone = await Accounts.RegisterAsync(m_app, "twofactor");

        var response = await phone.Client.PostAsJsonAsync(
            "api/account/2fa/recovery-codes", new { });

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
    }

    /// <summary>
    /// With the right password it still refuses, because there is no second factor to recover
    /// from yet. The ordering matters: the password is checked first, so a wrong one cannot be
    /// used to find out whether an account has two-factor turned on.
    /// </summary>
    [Fact]
    public async Task WithTheRightPasswordItAsksForTwoFactorFirst()
    {
        var phone = await Accounts.RegisterAsync(m_app, "twofactor");

        var response = await phone.Client.PostAsJsonAsync(
            "api/account/2fa/recovery-codes", new { currentPassword = "a-long-enough-password" });

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
        Assert.Contains(
            "Turn on two-factor authentication first",
            await response.Content.ReadAsStringAsync(),
            StringComparison.Ordinal);
    }
}
