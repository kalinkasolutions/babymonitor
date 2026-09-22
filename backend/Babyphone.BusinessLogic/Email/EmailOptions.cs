using MailKit.Security;

namespace Babyphone.BusinessLogic.Email;

/// <summary>
/// SMTP and link-building settings bound from the <c>Email</c> section of configuration
/// (appsettings.json / user-secrets / environment).
/// </summary>
public sealed class EmailOptions
{
    public const string SectionName = "Email";

    /// <summary>Address the mails are sent from (and shown as the sender).</summary>
    public string FromAddress { get; set; } = string.Empty;

    /// <summary>Display name for the sender; falls back to <see cref="FromAddress"/> when empty.</summary>
    public string FromName { get; set; } = string.Empty;

    public string SmtpHost { get; set; } = string.Empty;
    public int Port { get; set; }
    public string Username { get; set; } = string.Empty;
    public string Password { get; set; } = string.Empty;

    /// <summary>Bound by name from config, e.g. "StartTls", "SslOnConnect", "None", "Auto".</summary>
    public SecureSocketOptions SecureSocketOptions { get; set; } = SecureSocketOptions.Auto;

    /// <summary>
    /// Public origin of the backend (no trailing slash). There is no web front end, so the links in
    /// these mails point at endpoints on the API itself, which act on the token and render a small
    /// page — e.g. "https://babyphone.example.com".
    /// </summary>
    public string BaseUrl { get; set; } = string.Empty;

    /// <summary>Whether an SMTP host is configured at all; without one nothing is sent.</summary>
    public bool IsConfigured => !string.IsNullOrWhiteSpace(SmtpHost);
}
