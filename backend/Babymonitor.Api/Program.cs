using Babymonitor;
using Babymonitor.Auth;
using Babymonitor.Endpoints;
using Babymonitor.Extensions;
using Babymonitor.Hubs;
using Babymonitor.Realtime;
using Babymonitor.Turn;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Diagnostics;
using Serilog;
using Serilog.Events;
using Serilog.Sinks.Grafana.Loki;

var builder = WebApplication.CreateBuilder(args);

var minimumLevel = ParseLogLevel(builder.Configuration["Logging:LogLevel:Default"]);
var lokiUri = builder.Configuration["Loki:Uri"];
var lokiEnabled = builder.Configuration.GetValue("Loki:Enabled", false);

builder.Host.UseSerilog((_, configuration) =>
{
    configuration
        .MinimumLevel.Is(minimumLevel)
        .MinimumLevel.Override("Microsoft", LogEventLevel.Warning)
        .MinimumLevel.Override("Microsoft.Hosting.Lifetime", LogEventLevel.Information)
        .Enrich.FromLogContext()
        .Enrich.WithProperty("ServiceName", "Babymonitor")
        .WriteTo.Console();

    if (lokiEnabled && !string.IsNullOrWhiteSpace(lokiUri))
    {
        configuration.WriteTo.GrafanaLoki(
            uri: lokiUri,
            labels:
            [
                new LokiLabel { Key = "app", Value = "babymonitor" },
                new LokiLabel { Key = "env", Value = builder.Environment.EnvironmentName }
            ],
            restrictedToMinimumLevel: LogEventLevel.Information);
    }
});

builder.Services.AddSingleton(new ServerKey(builder.Configuration[$"{ServerKey.SectionName}:Key"]));

builder.Services.AddSignalR();
builder.Services.AddAuthentication(DeviceKeyAuthenticationHandler.SchemeName)
    .AddScheme<AuthenticationSchemeOptions, DeviceKeyAuthenticationHandler>(
        DeviceKeyAuthenticationHandler.SchemeName, configureOptions: null);
builder.Services.AddAuthorization();

builder.Services.Configure<TurnOptions>(builder.Configuration.GetSection(TurnOptions.SectionName));
builder.Services.Configure<TurnOptions>(TurnOptions.HomeRelay, builder.Configuration.GetSection(TurnOptions.HomeRelay));
builder.Services.AddSingleton<TurnService>();
builder.Services.AddSingleton<DeviceConnections>();

var app = builder.Build();

// nginx terminates TLS, so the scheme and client address come from its forwarded headers.
// First in the pipeline, so everything below sees the corrected values.
var proxyOptions = builder.Configuration.GetSection(ProxyOptions.SectionName).Get<ProxyOptions>()
    ?? new ProxyOptions();
app.UseConfiguredForwardedHeaders(proxyOptions, app.Logger);

// Escaped exceptions become ProblemDetails; only Development is told the cause.
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

app.UseHttpsRedirection();

app.MapStatusEndpoint();
app.MapTurnEndpoint();
app.MapPhoneLogEndpoint();

app.MapHub<DeviceHub>("/hubs/devices");

await app.RunAsync();

// Maps a Microsoft-style level name from Logging:LogLevel to Serilog's; Information otherwise.
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
