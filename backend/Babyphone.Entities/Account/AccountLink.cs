namespace Entities.Account;

/// <summary>
/// Two accounts that can see each other's phones — a parent and a babysitter, say. Created by
/// scanning a code, typing one, or accepting an emailed invitation.
///
/// A link decides visibility and nothing else. Whether a particular phone's key can be trusted is
/// decided on the phones themselves, by people comparing it, and is never recorded here.
/// </summary>
public sealed class AccountLink : IBaseEntity
{
    public Guid Id { get; set; }
    public DateTime CreatedAt { get; set; }
    public DateTime LastUpdatedAt { get; set; }

    /// <summary>
    /// The lower of the two user ids. Links are unordered, so storing them sorted is what lets a
    /// unique index reject the same link arriving the other way round.
    /// </summary>
    public Guid FirstUserId { get; set; }

    public Guid SecondUserId { get; set; }

    public static (Guid First, Guid Second) Order(Guid a, Guid b) =>
        a.CompareTo(b) <= 0 ? (a, b) : (b, a);
}
