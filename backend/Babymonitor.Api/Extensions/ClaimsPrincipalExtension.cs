using System.Security.Claims;

namespace Babymonitor.Extensions;

public static class ClaimsPrincipalExtension
{
    /// <summary>
    /// The authenticated user's id. Throws when the <see cref="ClaimTypes.NameIdentifier"/> claim is
    /// missing or malformed — a broken invariant on an authorized endpoint, not something to swallow.
    /// For paths that may run unauthenticated, use <see cref="TryGetUserId"/>.
    /// </summary>
    public static Guid GetUserId(this ClaimsPrincipal principal) =>
        principal.TryGetUserId(out var id)
            ? id
            : throw new InvalidOperationException("No valid user id claim on the current principal.");

    /// <summary>Attempts to read the user id; returns false when the principal is unauthenticated or the claim is malformed.</summary>
    public static bool TryGetUserId(this ClaimsPrincipal principal, out Guid userId) =>
        Guid.TryParse(principal.FindFirstValue(ClaimTypes.NameIdentifier), out userId);
}
