namespace Babyphone;

/// <summary>
/// Accounts created at startup, bound from the <c>SeedUsers</c> configuration section. Empty in
/// production on purpose: these carry known passwords, so they belong in a development config or
/// an explicitly set environment variable, never in a shipped default.
/// </summary>
public sealed class SeedUserOptions
{
    public const string SectionName = "SeedUsers";

    public List<SeedUser> Users { get; set; } = [];
}
