using Babymonitor.Extensions;
using Microsoft.AspNetCore.Http;
using Serilog.Events;

namespace Babymonitor.Tests;

/// <summary>
/// Which request summaries are worth a line. The monitor holds a websocket open and polls status,
/// and left at Information those two bury everything an operator actually reads — but dropping
/// them must never drop a failure, because a refused handshake is exactly what somebody goes to
/// the log for.
/// </summary>
public sealed class RequestLogLevelTests
{
    [Fact]
    public void AnOrdinaryRequestIsWorthALine() =>
        Assert.Equal(
            LogEventLevel.Information,
            RequestLoggingExtension.LevelFor(new PathString("/api/devices"), 200, failed: false));

    [Fact]
    public void AWebsocketHoldingOpenIsNot() =>
        Assert.Equal(
            LogEventLevel.Verbose,
            RequestLoggingExtension.LevelFor(new PathString("/hubs/devices"), 101, failed: false));

    /// <summary>The reason the rule is on the status and not the path: this is the line you came for.</summary>
    [Fact]
    public void ARefusedHandshakeIsStillWritten() =>
        Assert.Equal(
            LogEventLevel.Information,
            RequestLoggingExtension.LevelFor(new PathString("/hubs/devices"), 401, failed: false));

    [Fact]
    public void AServerErrorIsAnError() =>
        Assert.Equal(
            LogEventLevel.Error,
            RequestLoggingExtension.LevelFor(new PathString("/api/account"), 500, failed: false));

    [Fact]
    public void AThrownRequestIsAnErrorWhateverTheStatus() =>
        Assert.Equal(
            LogEventLevel.Error,
            RequestLoggingExtension.LevelFor(new PathString("/favicon.ico"), 200, failed: true));

    [Fact]
    public void AStaticFileIsNotWorthALine() =>
        Assert.Equal(
            LogEventLevel.Verbose,
            RequestLoggingExtension.LevelFor(new PathString("/favicon.ico"), 200, failed: false));
}
