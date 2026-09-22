using Babyphone;
using Babyphone.BusinessLogic.Email;
using Babyphone.BusinessLogic.Services.Account;
using Babyphone.BusinessLogic.Services.Devices;
using Babyphone.BusinessLogic.Services.Identity;
using Babyphone.BusinessLogic.Services.Turn;
using Babyphone.BusinessLogic.Validation;
using Babyphone.Endpoints;
using Babyphone.Hubs;
using Babyphone.Realtime;
using Babyphone.Extensions;
using Dal;
using Dal.Repositories.Account;
using Dal.Repositories.Devices;
using Dal.Repositories.Identity;
using Entities.Account;
using Microsoft.AspNetCore.DataProtection;
using Microsoft.AspNetCore.HttpOverrides;
using Microsoft.AspNetCore.Identity;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection.Extensions;
using System.Threading.RateLimiting;
using Serilog;
using Serilog.Events;

var builder = WebApplication.CreateBuilder(args);

var connectionString = builder.Configuration.GetConnectionString("Babyphone");

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
        .Enrich.WithProperty("ServiceName", "Babyphone")
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

builder.Services.AddDbContext<BabyphoneContext>(options => options.UseSqlite(connectionString));

builder.Services.AddSignalR();
builder.Services.AddHttpContextAccessor();

builder.Services.AddIdentity<BabyphoneUser, IdentityRole<Guid>>(options =>
    {
        options.Password.RequireDigit = false;
        options.Password.RequireNonAlphanumeric = false;
        options.Password.RequireUppercase = false;
        options.Password.RequiredLength = 4;
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
    .AddEntityFrameworkStores<BabyphoneContext>()
    .AddDefaultTokenProviders();

// Replaced rather than added: UserManager runs every registered validator, so AddUserValidator
// would leave Identity's duplicate-name rule in place beside this one.
builder.Services.Replace(
    ServiceDescriptor.Scoped<IUserValidator<BabyphoneUser>, DisplayNameUserValidator>());

// Cookie authentication and security-stamp validation, kept together in one place because the
// stamp interval is only meaningful against the cookie lifetime above it.
builder.Services.ConfigureCookieAuth();

// Persist the Data Protection key ring so auth cookies survive container recreation. By default
// keys live under $HOME/.aspnet/DataProtection-Keys, which is wiped whenever the container is
// recreated — invalidating every issued cookie and signing every phone out.
var keyRingPath = builder.Configuration["DataProtection:KeyPath"] ?? "/var/srv/keys";
Directory.CreateDirectory(keyRingPath);
builder.Services.AddDataProtection()
    .PersistKeysToFileSystem(new DirectoryInfo(keyRingPath))
    .SetApplicationName("Babyphone");
builder.Services.AddAuthorization();

// Claiming is the one endpoint an outsider can hammer: every call is a guess at somebody's
// pairing code. Per account where there is one, per address otherwise, since a caller without a
// cookie cannot reach it anyway.
builder.Services.AddRateLimiter(options =>
{
    options.RejectionStatusCode = StatusCodes.Status429TooManyRequests;
    options.AddPolicy(PairingEndpoint.ClaimPolicy, context => RateLimitPartition.GetFixedWindowLimiter(
        context.User.TryGetUserId(out var callerId)
            ? callerId.ToString()
            : context.Connection.RemoteIpAddress?.ToString() ?? "unknown",
        _ => new FixedWindowRateLimiterOptions
        {
            PermitLimit = 10,
            Window = TimeSpan.FromMinutes(1),
            QueueLimit = 0
        }));
});

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

// nginx terminates TLS and proxies from loopback, so without this every request looks like plain
// HTTP from 127.0.0.1 — which breaks secure-cookie decisions, redirect URLs and the logged client
// address. First in the pipeline, so everything below sees the corrected values.
app.UseForwardedHeaders(new ForwardedHeadersOptions
{
    ForwardedHeaders = ForwardedHeaders.XForwardedFor | ForwardedHeaders.XForwardedProto
});

app.UseBabyphoneRequestLogging();

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
