namespace Babyphone.Extensions;

public static class DeviceHeaderExtension
{
    /// <summary>
    /// Header the app sends so the server knows which of the account's phones is calling. It is a
    /// label, not a credential — the auth cookie is what proves anything, and every device
    /// operation is checked against the caller's ownership regardless of what this says.
    /// </summary>
    public const string DeviceIdHeader = "X-Device-Id";

    public static Guid? DeviceId(this HttpContext context) =>
        Guid.TryParse(context.Request.Headers[DeviceIdHeader], out var id) ? id : null;
}
