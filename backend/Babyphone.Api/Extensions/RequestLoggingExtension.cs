using Serilog;
using Serilog.Events;

namespace Babyphone.Extensions;

public static class RequestLoggingExtension
{
    /// <summary>
    /// Serilog's request summary, with the traffic that is not worth a row dropped to Verbose (and
    /// so below the configured minimum). The observing phone polls status and holds a websocket
    /// open; left in, those lines would bury everything an operator actually reads.
    /// </summary>
    public static IApplicationBuilder UseBabyphoneRequestLogging(this IApplicationBuilder app) =>
        app.UseSerilogRequestLogging(options => options.GetLevel = (context, _, exception) =>
            LevelFor(context.Request.Path, context.Response.StatusCode, exception is not null));

    /// <summary>
    /// The whole rule, as a function so it can be tested without a request. Failures are always
    /// kept, whatever the path — the point of dropping a line is that nothing went wrong on it.
    /// </summary>
    public static LogEventLevel LevelFor(PathString path, int statusCode, bool failed)
    {
        if (failed || statusCode >= StatusCodes.Status500InternalServerError)
        {
            return LogEventLevel.Error;
        }

        if (statusCode >= StatusCodes.Status400BadRequest)
        {
            return LogEventLevel.Information;
        }

        // A websocket logs one summary when it finally closes, saying only how long a phone was
        // connected. Matched on the status rather than on /hubs/, so a refused handshake — a 401 on
        // negotiate, which is exactly what you go to the log for — is still written.
        if (statusCode == StatusCodes.Status101SwitchingProtocols)
        {
            return LogEventLevel.Verbose;
        }

        var value = path.Value;
        return !string.IsNullOrEmpty(value) && Path.HasExtension(value)
            ? LogEventLevel.Verbose
            : LogEventLevel.Information;
    }
}
