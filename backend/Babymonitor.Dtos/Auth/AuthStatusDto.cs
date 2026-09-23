namespace Dtos.Auth;

public sealed class AuthStatusDto
{
    public bool Authenticated { get; set; }
    public string Username { get; set; }
    public bool IsAdmin { get; set; }
}
