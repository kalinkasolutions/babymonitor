using System.Security.Claims;

namespace Babymonitor.Extensions;

public static class ClaimsPrincipalExtension
{
    /// <summary>The authenticated device's id. Throws on a principal the DeviceKey scheme did not create.</summary>
    public static string GetDeviceId(this ClaimsPrincipal principal) =>
        principal.FindFirstValue(ClaimTypes.NameIdentifier)
        ?? throw new InvalidOperationException("No device id claim on the current principal.");
}
