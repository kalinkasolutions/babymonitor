using MailKit.Net.Smtp;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;
using MimeKit;
using Shared;

namespace Babymonitor.BusinessLogic.Email;

/// <summary>
/// Sends transactional mail via SMTP (MailKit). Bodies are HTML templates loaded from the
/// <c>EmailTemplates</c> folder next to the running app, with <c>@Placeholder</c> tokens replaced.
/// </summary>
public sealed class EmailService : IEmailService
{
    private readonly EmailOptions m_options;
    private readonly ILogger<EmailService> m_logger;

    public EmailService(IOptions<EmailOptions> options, ILogger<EmailService> logger)
    {
        m_options = options.Value;
        m_logger = logger;
    }

    public Task<OperationResult<Empty>> SendConfirmationMailAsync(string recipient, Guid userId, string token)
    {
        var confirmUrl = $"{TrimmedBaseUrl()}/api/auth/confirm-email" +
                         $"?userId={Uri.EscapeDataString(userId.ToString())}" +
                         $"&token={Uri.EscapeDataString(token)}";

        return SendTemplatedAsync(
            recipient,
            "Confirm your Babymonitor account",
            "ConfirmEmailTemplate.html",
            new Dictionary<string, string>(StringComparer.Ordinal)
            {
                ["Title"] = "Confirm your email",
                ["ConfirmUrl"] = confirmUrl
            });
    }

    public Task<OperationResult<Empty>> SendResetPasswordMailAsync(string recipient, string token)
    {
        var resetUrl = $"{TrimmedBaseUrl()}/api/auth/reset-password-form" +
                       $"?email={Uri.EscapeDataString(recipient)}" +
                       $"&token={Uri.EscapeDataString(token)}";

        return SendTemplatedAsync(
            recipient,
            "Reset your Babymonitor password",
            "ResetPasswordEmailTemplate.html",
            new Dictionary<string, string>(StringComparer.Ordinal)
            {
                ["Title"] = "Reset your password",
                ["ResetUrl"] = resetUrl
            });
    }

    public Task<OperationResult<Empty>> SendInviteMailAsync(string recipient, string invitedBy, string token)
    {
        var inviteUrl = $"{TrimmedBaseUrl()}/api/auth/reset-password-form" +
                        $"?email={Uri.EscapeDataString(recipient)}" +
                        $"&token={Uri.EscapeDataString(token)}";

        return SendTemplatedAsync(
            recipient,
            $"{invitedBy} invited you to their Babymonitor",
            "InviteEmailTemplate.html",
            new Dictionary<string, string>(StringComparer.Ordinal)
            {
                ["Title"] = "Set your password",
                ["InvitedBy"] = invitedBy,
                ["InviteUrl"] = inviteUrl
            });
    }

    public Task<OperationResult<Empty>> SendAccountLinkMailAsync(
        string recipient, string invitedBy, Guid userId, Guid inviterId, string token)
    {
        var acceptUrl = $"{TrimmedBaseUrl()}/api/auth/accept-link" +
                        $"?userId={Uri.EscapeDataString(userId.ToString())}" +
                        $"&inviterId={Uri.EscapeDataString(inviterId.ToString())}" +
                        $"&token={Uri.EscapeDataString(token)}";

        return SendTemplatedAsync(
            recipient,
            $"{invitedBy} would like to share their Babymonitor with you",
            "AccountLinkEmailTemplate.html",
            new Dictionary<string, string>(StringComparer.Ordinal)
            {
                ["Title"] = "Share a baby monitor",
                ["InvitedBy"] = invitedBy,
                ["AcceptUrl"] = acceptUrl
            });
    }

    public Task<OperationResult<Empty>> SendChangeEmailMailAsync(string newEmail, Guid userId, string token)
    {
        var confirmUrl = $"{TrimmedBaseUrl()}/api/auth/confirm-email-change" +
                         $"?userId={Uri.EscapeDataString(userId.ToString())}" +
                         $"&email={Uri.EscapeDataString(newEmail)}" +
                         $"&token={Uri.EscapeDataString(token)}";

        return SendTemplatedAsync(
            newEmail,
            "Confirm your new Babymonitor email address",
            "ChangeEmailTemplate.html",
            new Dictionary<string, string>(StringComparer.Ordinal)
            {
                ["Title"] = "Confirm your new email address",
                ["ConfirmUrl"] = confirmUrl
            });
    }

    private async Task<OperationResult<Empty>> SendTemplatedAsync(
        string recipient,
        string subject,
        string templateName,
        Dictionary<string, string> templateData
    )
    {
        // A personal installation may have no mail server at all. Say so once, at Warning, rather
        // than failing the operation that triggered the mail.
        if (!m_options.IsConfigured)
        {
            m_logger.LogWarning(
                "No SMTP host configured; dropping the email {Subject} to {Recipient}", subject, recipient);
            return OperationResult<Empty>.BadGateway("No mail server is configured.");
        }

        var body = await LoadTemplateAsync(templateName, templateData);
        if (string.IsNullOrEmpty(body))
        {
            return OperationResult<Empty>.Error("Email template could not be loaded.");
        }

        using var email = new MimeMessage();
        email.From.Add(new MailboxAddress(
            string.IsNullOrWhiteSpace(m_options.FromName) ? m_options.FromAddress : m_options.FromName,
            m_options.FromAddress));
        email.To.Add(MailboxAddress.Parse(recipient));
        email.Subject = subject;
        email.Body = new TextPart("html") { Text = body };

        try
        {
            m_logger.LogInformation(
                "Sending email {Subject} to {Recipient} via {SmtpHost}:{Port}",
                subject, recipient, m_options.SmtpHost, m_options.Port);

            using var smtp = new SmtpClient();
            await smtp.ConnectAsync(m_options.SmtpHost, m_options.Port, m_options.SecureSocketOptions);
            if (!string.IsNullOrEmpty(m_options.Username))
            {
                await smtp.AuthenticateAsync(m_options.Username, m_options.Password);
            }

            await smtp.SendAsync(email);
            await smtp.DisconnectAsync(true);
            m_logger.LogInformation("Sent email {Subject} to {Recipient}", subject, recipient);
            return OperationResult<Empty>.Success();
        }
        catch (Exception e)
        {
            m_logger.LogError(e, "Failed to send email to {Recipient}", recipient);
            return OperationResult<Empty>.BadGateway("The email could not be sent.");
        }
    }

    private async Task<string> LoadTemplateAsync(string templateName, Dictionary<string, string> templateData)
    {
        var templatePath = Path.Combine(AppContext.BaseDirectory, "EmailTemplates", templateName);
        if (!File.Exists(templatePath))
        {
            m_logger.LogError("Email template not found: {TemplatePath}", templatePath);
            return string.Empty;
        }

        return EmailTemplate.Render(await File.ReadAllTextAsync(templatePath), templateData);
    }

    private string TrimmedBaseUrl() => m_options.BaseUrl.TrimEnd('/');
}
