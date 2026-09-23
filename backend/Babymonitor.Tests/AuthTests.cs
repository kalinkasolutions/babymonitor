using System.Net;
using System.Net.Http.Json;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Identity;
using Microsoft.AspNetCore.TestHost;
using Microsoft.Extensions.DependencyInjection;

namespace Babymonitor.Tests;

/// <summary>
/// Signing in. Two properties matter beyond "the right password works": a wrong password and an
/// unknown address must be indistinguishable, or the endpoint becomes a way to find out which
/// addresses have accounts; and failures must be counted, or a short password is a few million
/// unthrottled guesses away from a live microphone in a child's bedroom.
/// </summary>
public sealed class AuthTests : IClassFixture<BabymonitorApp>
{
    private readonly BabymonitorApp m_app;

    public AuthTests(BabymonitorApp app) => m_app = app;

    [Fact]
    public async Task AWrongPasswordAndAnUnknownAddressLookTheSame()
    {
        var phone = await Accounts.RegisterAsync(m_app, "real");
        using var client = m_app.CreateSignedOutClient();

        var wrongPassword = await client.PostAsJsonAsync(
            "api/auth/login", new { email = phone.Email, password = "definitely-not-it" });
        var unknownAddress = await client.PostAsJsonAsync(
            "api/auth/login", new { email = "nobody@example.test", password = "definitely-not-it" });

        Assert.Equal(unknownAddress.StatusCode, wrongPassword.StatusCode);
        Assert.Equal(
            await unknownAddress.Content.ReadAsStringAsync(),
            await wrongPassword.Content.ReadAsStringAsync());
    }

    /// <summary>
    /// Identity's default is five failures, then a lockout. What is asserted is only that the
    /// answer changes once they have been spent — the exact number is Identity's to choose.
    /// </summary>
    [Fact]
    public async Task RepeatedWrongPasswordsEventuallyLockTheAccount()
    {
        var phone = await Accounts.RegisterAsync(m_app, "victim");
        using var client = m_app.CreateSignedOutClient();

        string? lastBody = null;
        for (var attempt = 0; attempt < 6; attempt++)
        {
            var response = await client.PostAsJsonAsync(
                "api/auth/login", new { email = phone.Email, password = "wrong" });
            lastBody = await response.Content.ReadAsStringAsync();
        }

        Assert.Contains("Too many attempts", lastBody!, StringComparison.Ordinal);
    }

    /// <summary>
    /// And the lockout has to be real: the correct password must stop working while it lasts,
    /// otherwise counting the failures achieved nothing.
    /// </summary>
    [Fact]
    public async Task ALockedAccountRefusesTheRightPasswordToo()
    {
        var phone = await Accounts.RegisterAsync(m_app, "locked");
        using var client = m_app.CreateSignedOutClient();

        for (var attempt = 0; attempt < 6; attempt++)
        {
            await client.PostAsJsonAsync("api/auth/login", new { email = phone.Email, password = "wrong" });
        }

        var correct = await client.PostAsJsonAsync(
            "api/auth/login", new { email = phone.Email, password = "a-long-enough-password" });

        Assert.Equal(HttpStatusCode.BadRequest, correct.StatusCode);
    }

    [Fact]
    public async Task ShortPasswordsAreRefused()
    {
        using var client = m_app.CreateSignedOutClient();

        var response = await client.PostAsJsonAsync("api/auth/register", new
        {
            email = $"short-{Guid.NewGuid():N}@example.test",
            username = "short",
            password = "abcd"
        });

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
    }

    /// <summary>
    /// Asking to reset a password must answer the same way whether or not the address exists,
    /// or it becomes the address oracle that the login endpoint is careful not to be.
    /// </summary>
    [Fact]
    public async Task ForgotPasswordSaysNothingAboutWhoExists()
    {
        var phone = await Accounts.RegisterAsync(m_app, "known");
        using var client = m_app.CreateSignedOutClient();

        var known = await client.PostAsJsonAsync("api/auth/forgot-password", new { email = phone.Email });
        var unknown = await client.PostAsJsonAsync(
            "api/auth/forgot-password", new { email = "nobody@example.test" });

        Assert.Equal(unknown.StatusCode, known.StatusCode);
    }

    /// <summary>
    /// Rotating the security stamp is what "sign out everywhere" does, and every cookie carrying
    /// the old one stops working — including this phone's, which is deliberate.
    ///
    /// The app checks the stamp once a minute rather than on every request, so in production a
    /// cookie issued moments ago keeps working for up to that long. Waiting it out would put a
    /// minute into the test run, so the interval is turned down here instead: what is under test
    /// is that the stamp moved and that the check acts on it, not the length of the window.
    /// </summary>
    [Fact]
    public async Task SigningOutEverywhereEndsThisSessionToo()
    {
        using var app = new ImmediateStampApp();
        var phone = await Accounts.RegisterAsync(app, "everywhere");

        var signedOut = await phone.Client.PostAsJsonAsync("api/account/sign-out-everywhere", new { });
        Assert.Equal(HttpStatusCode.OK, signedOut.StatusCode);

        var after = await phone.Client.GetAsync("api/account");

        Assert.Equal(HttpStatusCode.Unauthorized, after.StatusCode);
    }

    private sealed class ImmediateStampApp : BabymonitorApp
    {
        protected override void ConfigureWebHost(IWebHostBuilder builder)
        {
            base.ConfigureWebHost(builder);
            builder.ConfigureTestServices(services =>
                services.Configure<SecurityStampValidatorOptions>(
                    options => options.ValidationInterval = TimeSpan.Zero));
        }
    }
}
