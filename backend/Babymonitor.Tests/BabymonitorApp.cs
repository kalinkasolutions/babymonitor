using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.Extensions.Configuration;

namespace Babymonitor.Tests;

/// <summary>
/// The real application on a disposable SQLite file, which is the point: these tests exist to
/// catch the things that only go wrong once EF, Identity and the endpoint pipeline are all
/// present — a foreign key that refuses a delete, a code that two callers both redeem. An
/// in-memory provider would enforce none of it.
/// </summary>
public class BabymonitorApp : WebApplicationFactory<Program>
{
    private readonly string m_root = Path.Combine(
        Path.GetTempPath(), "babymonitor-tests", Guid.NewGuid().ToString("N"));

    /// <summary>
    /// Not Development: that environment creates the seed accounts and relaxes the password rules,
    /// so tests running there would exercise a configuration no real deployment uses.
    /// </summary>
    protected override void ConfigureWebHost(IWebHostBuilder builder)
    {
        Directory.CreateDirectory(m_root);
        builder.UseEnvironment("Production");
        builder.UseSetting("ConnectionStrings:Babymonitor", $"Data Source={Path.Combine(m_root, "test.db")}");
        builder.UseSetting("DataProtection:KeyPath", Path.Combine(m_root, "keys"));

        // No SMTP host, so Identity does not demand a confirmation nobody can deliver and a freshly
        // registered account can sign in. Mails are logged and dropped, as they are without a server.
        builder.UseSetting("Email:SmtpHost", string.Empty);
        builder.UseSetting("Turn:Secret", string.Empty);

        // Every test signs in from the same address, so the per-address limits would start
        // refusing partway through a run. Raised rather than removed, so the pipeline under test
        // is still the real one; RateLimitTests turns them down on purpose to check they bite.
        foreach (var (key, value) in Limits)
        {
            builder.UseSetting(key, value);
        }
    }

    /// <summary>Overrides applied to every app this fixture builds, so a subclass can add to them.</summary>
    protected virtual IReadOnlyDictionary<string, string> Limits => new Dictionary<string, string>(StringComparer.Ordinal)
    {
        [$"{RateLimitOptions.SectionName}:{nameof(RateLimitOptions.CredentialsPerFiveMinutes)}"] = "1000",
        [$"{RateLimitOptions.SectionName}:{nameof(RateLimitOptions.PairingClaimsPerMinute)}"] = "1000",
        [$"{RateLimitOptions.SectionName}:{nameof(RateLimitOptions.InvitesPerHour)}"] = "1000"
    };

    /// <summary>
    /// A client that keeps its auth cookie, so a test can sign in and stay signed in.
    ///
    /// The address is https even though the test server terminates nothing: outside Development
    /// the auth cookie is marked <c>Secure</c>, and a client on a plain http address would drop it
    /// and see every later request as unauthenticated. Which is the production behaviour, so the
    /// tests meet it here rather than configuring it away.
    /// </summary>
    public HttpClient CreateSignedOutClient() =>
        CreateClient(new WebApplicationFactoryClientOptions
        {
            HandleCookies = true,
            BaseAddress = new Uri("https://localhost")
        });

    /// <summary>
    /// A client on a plain http address that does not follow redirects, for asking what the app
    /// made of the request scheme. <c>UseHttpsRedirection</c> answers 307 when it believes the
    /// request arrived in the clear and passes it through when it believes otherwise, which is the
    /// only way from outside to see whether a forwarded scheme was adopted.
    /// </summary>
    public HttpClient CreateInsecureClient() =>
        CreateClient(new WebApplicationFactoryClientOptions
        {
            AllowAutoRedirect = false,
            HandleCookies = true,
            BaseAddress = new Uri("http://localhost")
        });

    protected override void Dispose(bool disposing)
    {
        base.Dispose(disposing);
        if (disposing)
        {
            // The SQLite file, its write-ahead log and the key ring. Left behind they would
            // accumulate one directory per test class per run.
            try
            {
                Directory.Delete(m_root, recursive: true);
            }
            catch (IOException)
            {
                // A handle still open on Windows, or the directory already gone. Neither is worth
                // failing a test run that has otherwise passed.
            }
        }
    }
}
