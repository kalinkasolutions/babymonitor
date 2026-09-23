using System.Net;

namespace Babymonitor;

/// <summary>
/// The few pages the backend renders itself. There is no web front end — the product is two phone
/// apps — but a link in an email has to land somewhere, so confirmation and password-reset are
/// served as small self-contained pages rather than routes in a SPA that does not exist.
/// </summary>
public static class HtmlPage
{
    // Kept out of the interpolated template below so the CSS braces need no escaping.
    private const string Style = """
        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
               background: #181a1b; color: #e8e6e3; margin: 0; padding: 24px; }
        .container { max-width: 420px; margin: 10vh auto; background: #202324;
                     padding: 28px; border-radius: 8px; }
        h1 { font-size: 20px; margin: 0 0 16px; }
        p, label { font-size: 14px; line-height: 1.6; color: #cfccc7; }
        label { display: block; margin-bottom: 6px; }
        input { width: 100%; box-sizing: border-box; padding: 10px; margin-bottom: 16px;
                background: #181a1b; border: 1px solid #3a3d3e; border-radius: 6px; color: #e8e6e3; }
        button { width: 100%; padding: 12px; background: #3fb950; color: #181a1b;
                 font-weight: 600; border: 0; border-radius: 6px; font-size: 14px; }
        """;

    public static IResult Message(string heading, string body, int statusCode = StatusCodes.Status200OK) =>
        Results.Content(
            Wrap(heading, $"<p>{WebUtility.HtmlEncode(body)}</p>"),
            "text/html",
            statusCode: statusCode);

    /// <summary>
    /// <paramref name="minimumLength"/> comes from the configured Identity options rather than a
    /// literal, so the browser rejects what the server would have rejected anyway — a form that
    /// accepts a password the server then refuses is a round trip and a confusing error.
    /// </summary>
    public static IResult ResetPasswordForm(string email, string token, int minimumLength) =>
        Results.Content(
            Wrap("Choose a new password", $"""
                 <form method="post" action="/api/auth/reset-password-form">
                   <input type="hidden" name="email" value="{WebUtility.HtmlEncode(email)}">
                   <input type="hidden" name="token" value="{WebUtility.HtmlEncode(token)}">
                   <label for="newPassword">New password — at least {minimumLength} characters</label>
                   <input id="newPassword" name="newPassword" type="password" minlength="{minimumLength}" required autofocus>
                   <button type="submit">Set password</button>
                 </form>
                 """),
            "text/html");

    /// <summary>
    /// Asks before doing it. The link lands in a mailbox, and mail clients and security scanners
    /// fetch what they find there — a GET that linked two accounts on sight was accepted by
    /// whatever opened the message rather than by the person reading it. This page changes
    /// nothing; the token rides in the form and the POST is what acts on it.
    /// </summary>
    public static IResult ConfirmAccountLink(string invitedBy, Guid userId, Guid inviterId, string token) =>
        Results.Content(
            Wrap("Share a baby monitor", $"""
                 <p>{WebUtility.HtmlEncode(invitedBy)} would like your phones and theirs to be able
                 to see each other, so you can help watch over the baby monitor.</p>
                 <form method="post" action="/api/auth/accept-link">
                   <input type="hidden" name="userId" value="{userId}">
                   <input type="hidden" name="inviterId" value="{inviterId}">
                   <input type="hidden" name="token" value="{WebUtility.HtmlEncode(token)}">
                   <button type="submit">Yes, link our accounts</button>
                 </form>
                 """),
            "text/html");

    private static string Wrap(string heading, string content)
    {
        var title = WebUtility.HtmlEncode(heading);
        return $"""
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="UTF-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>{title}</title>
              <style>
            {Style}
              </style>
            </head>
            <body><div class="container"><h1>{title}</h1>{content}</div></body>
            </html>
            """;
    }
}
