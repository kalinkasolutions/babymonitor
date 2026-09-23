using System.Text.Encodings.Web;
using System.Text.Unicode;

namespace Babymonitor.BusinessLogic.Email;

/// <summary>
/// Filling the <c>@Placeholder</c> tokens in a mail template. A function rather than a step inside
/// the sender, so what it does to a value can be checked without an SMTP server.
/// </summary>
public static class EmailTemplate
{
    /// <summary>
    /// Escapes what is dangerous and leaves the rest alone. The default encoder also escapes every
    /// non-ASCII character, which would turn a display name like "Anna Müller" into "Anna
    /// M&amp;#252;ller" — correct, and unreadable to anyone looking at the mail's source. The mail
    /// is UTF-8, so the accents need no escaping to arrive intact.
    /// </summary>
    private static readonly HtmlEncoder s_encoder = HtmlEncoder.Create(UnicodeRanges.All);

    /// <summary>
    /// Every value is HTML-encoded on the way in. One of them is a display name its owner chose
    /// and the recipient is an address they also chose, so substituting raw let anyone with an
    /// account put their own markup — a link to anywhere — into a mail sent over this server's
    /// name. The URLs built by the sender survive encoding intact: <c>&amp;</c> between query
    /// parameters becomes <c>&amp;amp;</c>, which is what it should have been in HTML all along.
    /// </summary>
    public static string Render(string template, IReadOnlyDictionary<string, string> values)
    {
        ArgumentNullException.ThrowIfNull(template);
        ArgumentNullException.ThrowIfNull(values);

        var body = template;
        foreach (var (key, value) in values)
        {
            body = body.Replace($"@{key}", s_encoder.Encode(value), StringComparison.Ordinal);
        }

        return body;
    }
}
