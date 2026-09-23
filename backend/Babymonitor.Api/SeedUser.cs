namespace Babymonitor;

/// <summary>One account to create at startup if it is not there yet.</summary>
public sealed class SeedUser
{
    public string Email { get; set; } = string.Empty;
    public string Username { get; set; } = string.Empty;
    public string Password { get; set; } = string.Empty;
    public bool IsAdmin { get; set; }
}
