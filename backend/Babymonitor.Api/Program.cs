using Babymonitor;
using Babymonitor.BusinessLogic.Email;
using Babymonitor.BusinessLogic.Services.Account;
using Babymonitor.BusinessLogic.Services.Devices;
using Babymonitor.BusinessLogic.Services.Identity;
using Babymonitor.BusinessLogic.Services.Turn;
using Babymonitor.BusinessLogic.Validation;
using Babymonitor.Endpoints;
using Babymonitor.Hubs;
using Babymonitor.Realtime;
using Babymonitor.Extensions;
using Dal;
using Dal.Repositories.Account;
using Dal.Repositories.Devices;
using Dal.Repositories.Identity;
using Entities.Account;
using Microsoft.AspNetCore.DataProtection;
using Microsoft.AspNetCore.Diagnostics;
using Microsoft.AspNetCore.Identity;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection.Extensions;
using System.Threading.RateLimiting;
using Serilog;
using Serilog.Events;

var builder = WebApplication.CreateBuilder(args);

var connectionString = builder.Configuration.GetConnectionString("Babymonitor");

// Log to the console (so `docker compose logs -f` shows them) and to a "Logs" table in the same
// SQLite database. The SQLite sink writes via its own ADO.NET connection, so persisting a log
// entry does not itself go through EF and can't feed back into the logging pipeline.
var logDbPath = new SqliteConnectionStringBuilder(connectionString).DataSource;
var minimumLevel = ParseLogLevel(builder.Configuration["Logging:LogLevel:Default"]);
var logRetention = TimeSpan.FromDays(builder.Configuration.GetValue("Logging:RetentionDays", 30));

builder.Host.UseSerilog((context, configuration) =>
{
    configuration
        .MinimumLevel.Is(minimumLevel)
        .MinimumLevel.Override("Microsoft", LogEventLevel.Warning)
        .MinimumLevel.Override("Microsoft.EntityFrameworkCore", LogEventLevel.Warning)
        .MinimumLevel.Override("Microsoft.Hosting.Lifetime", LogEventLevel.Information)
        .Enrich.FromLogContext()
        .Enrich.WithProperty("ServiceName", "Babymonitor")
        .WriteTo.Console()
        .WriteTo.SQLite(
            logDbPath,
            tableName: "Logs",
            storeTimestampInUtc: true,
            retentionPeriod: logRetention);
});

// Read ahead of AddIdentity, which needs to know whether confirmation mails can actually be sent.
var emailOptions = builder.Configuration.GetSection(EmailOptions.SectionName).Get<EmailOptions>()
    ?? new EmailOptions();

builder.Services.AddDbContext<BabymonitorContext>(options => options.UseSqlite(connectionString));

builder.Services.AddSignalR();
builder.Services.AddHttpContextAccessor();

// Length is the only password rule worth having — character classes push people towards
// "Passw0rd!" and buy nothing. Enforced here rather than on the DTOs so it can be relaxed in
// Development, where the seed accounts have short, known passwords by design.
var minimumPasswordLength = builder.Environment.IsDevelopment() ? 4 : 10;

builder.Services.AddIdentity<BabymonitorUser, IdentityRole<Guid>>(options =>
    {
        options.Password.RequireDigit = false;
        options.Password.RequireNonAlphanumeric = false;
        options.Password.RequireUppercase = false;
        options.Password.RequiredLength = minimumPasswordLength;
        options.User.RequireUniqueEmail = true;
        // The username is a display name, not an identifier, so it has to allow what people call
        // themselves — spaces and accented letters included. An empty string turns Identity's
        // character filter off; it need not be unique either (see DisplayNameUserValidator).
        options.User.AllowedUserNameCharacters = string.Empty;
        // Tied to having a mail server rather than to the environment. Demanding a confirmation
        // that cannot be delivered is not a guarantee, it is an account nobody can ever sign in
        // to — which is exactly what a fresh deployment without SMTP used to be.
        options.SignIn.RequireConfirmedEmail = emailOptions.IsConfigured;
    })
    .AddEntityFrameworkStores<BabymonitorContext>()
    .AddDefaultTokenProviders();

// Replaced rather than added: UserManager runs every registered validator, so AddUserValidator
// would leave Identity's duplicate-name rule in place beside this one.
builder.Services.Replace(
    ServiceDescriptor.Scoped<IUserValidator<BabymonitorUser>, DisplayNameUserValidator>());

// Cookie authentication and security-stamp validation, kept together in one place because the
// stamp interval is only meaningful against the cookie lifetime above it.
builder.Services.ConfigureCookieAuth(allowInsecureCookies: builder.Environment.IsDevelopment());

// Persist the Data Protection key ring so auth cookies survive container recreation. By default
// keys live under $HOME/.aspnet/DataProtection-Keys, which is wiped whenever the container is
// recreated — invalidating every issued cookie and signing every phone out.
var keyRingPath = builder.Configuration["DataProtection:KeyPath"] ?? "/var/srv/keys";
Directory.CreateDirectory(keyRingPath);
builder.Services.AddDataProtection()
    .PersistKeysToFileSystem(new DirectoryInfo(keyRingPath))
    .SetApplicationName("Babymonitor");
builder.Services.AddAuthorization();

// Claiming is the one endpoint an outsider can hammer: every call is a guess at somebody's
// pairing code. Per account where there is one, per address otherwise, since a caller without a
// cookie cannot reach it anyway.
var rateLimits = builder.Configuration.GetSection(RateLimitOptions.SectionName).Get<RateLimitOptions>()
    ?? new RateLimitOptions();

builder.Services.AddRateLimiter(options =>
{
    options.RejectionStatusCode = StatusCodes.Status429TooManyRequests;
    options.AddPolicy(PairingEndpoint.ClaimPolicy, context => RateLimitPartition.GetFixedWindowLimiter(
        context.User.TryGetUserId(out var callerId)
            ? callerId.ToString()
            : context.Connection.RemoteIpAddress?.ToString() ?? "unknown",
        _ => new FixedWindowRateLimiterOptions
        {
            PermitLimit = rateLimits.PairingClaimsPerMinute,
            Window = TimeSpan.FromMinutes(1),
            QueueLimit = 0
        }));

    // Sign-in, registration and the two mail-triggering endpoints, all of which an outsider can
    // reach without a cookie. Account lockout already stops a run at one password; this stops a
    // run at many accounts from one place, and stops the mail endpoints being a way to send
    // somebody else a hundred messages. Per address, because there is no caller to count against.
    options.AddPolicy(AuthEndpoint.CredentialPolicy, context => RateLimitPartition.GetFixedWindowLimiter(
        context.Connection.RemoteIpAddress?.ToString() ?? "unknown",
        _ => new FixedWindowRateLimiterOptions
        {
            PermitLimit = rateLimits.CredentialsPerFiveMinutes,
            Window = TimeSpan.FromMinutes(5),
            QueueLimit = 0
        }));

    // An invitation creates an account and sends mail to an address the caller chose. A household
    // invites a babysitter now and then, so this is generous and still not a way to send in bulk.
    options.AddPolicy(AuthEndpoint.InvitePolicy, context => RateLimitPartition.GetFixedWindowLimiter(
        context.User.TryGetUserId(out var inviterId)
            ? inviterId.ToString()
            : context.Connection.RemoteIpAddress?.ToString() ?? "unknown",
        _ => new FixedWindowRateLimiterOptions
        {
            PermitLimit = rateLimits.InvitesPerHour,
            Window = TimeSpan.FromHours(1),
            QueueLimit = 0
        }));
});

builder.Services.Configure<ProxyOptions>(builder.Configuration.GetSection(ProxyOptions.SectionName));
builder.Services.Configure<RateLimitOptions>(builder.Configuration.GetSection(RateLimitOptions.SectionName));
builder.Services.Configure<EmailOptions>(builder.Configuration.GetSection(EmailOptions.SectionName));
builder.Services.Configure<TurnOptions>(builder.Configuration.GetSection(TurnOptions.SectionName));
builder.Services.Configure<SeedUserOptions>(builder.Configuration.GetSection(SeedUserOptions.SectionName));

builder.Services.AddScoped<IIdentityService, IdentityService>();
builder.Services.AddScoped<IAccountService, AccountService>();
builder.Services.AddScoped<IEmailService, EmailService>();
builder.Services.AddScoped<IDeviceService, DeviceService>();
builder.Services.AddScoped<IPairingService, PairingService>();
builder.Services.AddScoped<IDeviceNotifier, DeviceNotifier>();

// Singleton: it is the set of phones currently holding a connection, which outlives any request.
builder.Services.AddSingleton<DeviceConnections>();
builder.Services.AddScoped<ITurnService, TurnService>();

builder.Services.AddScoped<IIdentityRepository, IdentityRepository>();
builder.Services.AddScoped<IAccountRepository, AccountRepository>();
builder.Services.AddScoped<IDeviceRepository, DeviceRepository>();
builder.Services.AddScoped<IPairingRepository, PairingRepository>();

var app = builder.Build();

// nginx terminates TLS and forwards plain HTTP, so without this every request looks like it
// arrived in the clear — which decides whether the auth cookie is marked Secure, what address is
// logged, and where a redirect points. First in the pipeline, so everything below sees the
// corrected values. Which proxies are believed is configuration: the proxy is never loopback from
// inside a container, so the framework default trusts nothing that will ever actually call.
var proxyOptions = builder.Configuration.GetSection(ProxyOptions.SectionName).Get<ProxyOptions>()
    ?? new ProxyOptions();
app.UseConfiguredForwardedHeaders(proxyOptions, app.Logger);

// Anything that escapes an endpoint becomes a ProblemDetails body like every other failure,
// rather than a bare 500 the app can only report as "Request failed (500)". Serilog has already
// written the exception by this point; this is about what the phone is told. Outside the
// development machine it is told nothing about the cause, which is the point of the separation.
app.UseExceptionHandler(handler => handler.Run(async context =>
{
    context.Response.StatusCode = StatusCodes.Status500InternalServerError;
    await Results.Problem(
        detail: app.Environment.IsDevelopment()
            ? context.Features.Get<IExceptionHandlerFeature>()?.Error.ToString()
            : "Something went wrong on the server.",
        statusCode: StatusCodes.Status500InternalServerError
    ).ExecuteAsync(context);
}));

app.UseBabymonitorRequestLogging();

app.UseRouting();

app.UseAuthentication();
app.UseAuthorization();

// After authentication, so a limit can be counted against the account rather than the address.
app.UseRateLimiter();

// After authentication, or there is no principal to read the caller off yet.
app.UseUserLogContext();

app.UseHttpsRedirection();

app.MapAuthEndpoint();
app.MapAccountEndpoint();
app.MapDeviceEndpoint();
app.MapPairingEndpoint();
app.MapTurnEndpoint();

app.MapHub<DeviceHub>("/hubs/devices");

await Seed.InitializeAsync(app);
await app.RunAsync();

// Maps a Microsoft-style log level name (as used in the Logging:LogLevel config section) to the
// Serilog level the pipeline is configured with. Falls back to Information for missing/unknown values.
static LogEventLevel ParseLogLevel(string? value) => value?.Trim().ToLowerInvariant() switch
{
    "trace" => LogEventLevel.Verbose,
    "debug" => LogEventLevel.Debug,
    "information" or "info" => LogEventLevel.Information,
    "warning" or "warn" => LogEventLevel.Warning,
    "error" => LogEventLevel.Error,
    "critical" or "fatal" => LogEventLevel.Fatal,
    _ => LogEventLevel.Information
};

// Names the implicit entry-point class so WebApplicationFactory can boot this app in tests.
public partial class Program
{
    protected Program() { }
}
