namespace Dtos.Auth;

/// <summary>
/// Outcome of a successful password check. When <see cref="RequiresTwoFactor"/> is true the caller
/// is not signed in yet and must post the authenticator code to <c>api/auth/login-2fa</c> to finish.
/// </summary>
public sealed class LoginResultDto
{
    public bool RequiresTwoFactor { get; set; }

    public string Username { get; set; }

    /// <summary>Saves the client a second call to <c>api/auth/status</c> to find out.</summary>
    public bool IsAdmin { get; set; }
}
