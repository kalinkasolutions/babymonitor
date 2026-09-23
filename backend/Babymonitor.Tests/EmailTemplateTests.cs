using Babymonitor.BusinessLogic.Email;

namespace Babymonitor.Tests;

/// <summary>
/// The invitation mail names whoever sent it, and that name is whatever they typed — Identity's
/// character filter is deliberately off, so a display name can hold anything. The recipient is an
/// address the same person chose. Unencoded, that made the server a way to send somebody a
/// well-formed mail, over the server's own name, with a link to anywhere in it.
/// </summary>
public sealed class EmailTemplateTests
{
    private const string Template = "<p>@InvitedBy invited you.</p><a href=\"@InviteUrl\">Set my password</a>";

    [Fact]
    public void AMarkupDisplayNameCannotAddALink()
    {
        var rendered = EmailTemplate.Render(Template, new Dictionary<string, string>(StringComparer.Ordinal)
        {
            ["InvitedBy"] = "<a href=\"https://evil.example/\">Click here</a>",
            ["InviteUrl"] = "https://baby.example.com/api/auth/reset-password-form?email=a%40b&token=xyz"
        });

        Assert.DoesNotContain("evil.example/\">", rendered, StringComparison.Ordinal);
        Assert.Contains("&lt;a href=", rendered, StringComparison.Ordinal);
    }

    [Fact]
    public void AnAttributeCannotBeBrokenOutOf()
    {
        var rendered = EmailTemplate.Render(Template, new Dictionary<string, string>(StringComparer.Ordinal)
        {
            ["InvitedBy"] = "nobody",
            ["InviteUrl"] = "\" onmouseover=\"steal()"
        });

        Assert.DoesNotContain("onmouseover=\"", rendered, StringComparison.Ordinal);
    }

    /// <summary>
    /// The links still have to work. Encoding turns the query separator into <c>&amp;amp;</c>,
    /// which is what an ampersand in HTML should be and which every mail client reads back as one.
    /// </summary>
    [Fact]
    public void AUrlSurvivesEncodingAsValidHtml()
    {
        var rendered = EmailTemplate.Render(Template, new Dictionary<string, string>(StringComparer.Ordinal)
        {
            ["InvitedBy"] = "Sam",
            ["InviteUrl"] = "https://baby.example.com/api/auth/confirm-email?userId=1&token=abc"
        });

        Assert.Contains(
            "href=\"https://baby.example.com/api/auth/confirm-email?userId=1&amp;token=abc\"",
            rendered,
            StringComparison.Ordinal);
    }

    [Fact]
    public void AnOrdinaryNameIsLeftReadable()
    {
        var rendered = EmailTemplate.Render(Template, new Dictionary<string, string>(StringComparer.Ordinal)
        {
            ["InvitedBy"] = "Anna Müller",
            ["InviteUrl"] = "https://baby.example.com/"
        });

        Assert.Contains("Anna Müller invited you.", rendered, StringComparison.Ordinal);
    }
}
