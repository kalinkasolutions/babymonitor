namespace Babymonitor.Endpoints;

public static class StatusEndpoint
{
    private sealed record Status(bool Ok);

    public static void MapStatusEndpoint(this IEndpointRouteBuilder builder)
    {
        builder.MapGet("api/status", () => new Status(Ok: true));

        // Apps up to 0.0.6 check a server key here, from when the server had one.
        builder.MapGet("api/status/key", () => new Status(Ok: true));
    }
}
