namespace Dtos.Account;

/// <summary>
/// Freshly generated recovery codes, returned the once. They are not shown again — but note that
/// Identity's default store keeps them as plaintext in <c>AspNetUserTokens</c>, joined by
/// semicolons, and redeems one by string comparison. So "shown once" is a property of this API,
/// not of the database: anyone who can read the database can read the codes.
/// </summary>
public sealed class RecoveryCodesDto
{
    public List<string> Codes { get; set; } = [];
}
