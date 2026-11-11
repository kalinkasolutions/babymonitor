using System.Text.RegularExpressions;
using Microsoft.AspNetCore.StaticFiles;

namespace Babymonitor;

/// <summary>
/// The page and other unversioned files are checked on every load, so a new release reaches an
/// open browser on its next reload. Files the build named after their content are kept for good.
/// </summary>
public static partial class WebAppCaching
{
    public static void Apply(StaticFileResponseContext context)
    {
        var isHashed = HashedName().IsMatch(context.File.Name);
        context.Context.Response.Headers.CacheControl = isHashed ? "public, max-age=31536000, immutable" : "no-cache";
    }

    // Angular's output hashing: main-HR5LDMJJ.js, styles-BWISIKKR.css.
    [GeneratedRegex(@"-[A-Z0-9]{8}\.(js|css)$", RegexOptions.ExplicitCapture, matchTimeoutMilliseconds: 100)]
    private static partial Regex HashedName();
}
