using Babymonitor.Extensions;

namespace Babymonitor.Endpoints;

/// <summary>
/// Where a phone sends its own log, so it ends up next to the server's. Lines arrive late when
/// the phone was offline or had just crashed, which is why each one carries the phone's own time.
/// </summary>
public static class PhoneLogEndpoint
{
    public const int MaxEntriesPerUpload = 500;

    // Long enough for a stack trace with its causes; a cap so one phone cannot flood the log.
    private const int MaxTextLength = 16_000;

    public sealed record PhoneLogEntry(DateTimeOffset Time, string? Level, string? Tag, string? Message, string? Error);

    public sealed record PhoneLogUpload(string? AppVersion, List<PhoneLogEntry>? Entries);

    public static void MapPhoneLogEndpoint(this IEndpointRouteBuilder builder)
    {
        builder.MapPost("api/logs", Write).RequireAuthorization();
    }

    /// <summary>Accepts Android's names as well as .NET's; anything unknown is kept at Information.</summary>
    public static LogLevel LevelOf(string? name) => name?.Trim().ToLowerInvariant() switch
    {
        "verbose" or "debug" => LogLevel.Debug,
        "warn" or "warning" => LogLevel.Warning,
        "error" => LogLevel.Error,
        "fatal" or "assert" or "critical" => LogLevel.Critical,
        _ => LogLevel.Information
    };

    private static IResult Write(PhoneLogUpload upload, HttpContext httpContext, ILoggerFactory loggerFactory)
    {
        var entries = upload.Entries ?? [];
        if (entries.Count > MaxEntriesPerUpload)
        {
            return Results.Problem(
                detail: $"At most {MaxEntriesPerUpload} lines per upload.",
                statusCode: StatusCodes.Status400BadRequest);
        }

        var logger = loggerFactory.CreateLogger("Phone");
        var deviceId = httpContext.User.GetDeviceId();
        var appVersion = Truncate(upload.AppVersion);

        foreach (var entry in entries)
        {
            using var scope = logger.BeginScope(new Dictionary<string, object?>
            {
                ["Source"] = "phone",
                ["PhoneTime"] = entry.Time,
                ["AppVersion"] = appVersion
            });

            var level = LevelOf(entry.Level);
            var tag = Truncate(entry.Tag);
            var message = Truncate(entry.Message);
            if (string.IsNullOrEmpty(entry.Error))
            {
                logger.Log(level, "Phone {DeviceId} {Tag}: {Message}", deviceId, tag, message);
            }
            else
            {
                logger.Log(level, "Phone {DeviceId} {Tag}: {Message}\n{PhoneError}", deviceId, tag, message, Truncate(entry.Error));
            }
        }

        return Results.NoContent();
    }

    private static string Truncate(string? text) =>
        text is null ? string.Empty
        : text.Length <= MaxTextLength ? text
        : text[..MaxTextLength];
}
