using Entities.Devices;
using Shared;

namespace Dal.Repositories.Devices;

public interface IPairingRepository
{
    /// <summary>
    /// Issues a code for this device and retires any it still had outstanding. Asking for a new
    /// code is what somebody does when the old one should stop working — so it stops working.
    /// </summary>
    Task<PairingToken> IssueCodeAsync(Guid deviceId, string code, DateTime expiresAt);

    /// <summary>
    /// Spends a code and hands it back, or null if it was unknown, expired or already used. The
    /// spending is the lookup, so two phones racing for one code cannot both be given it.
    /// </summary>
    Task<PairingToken?> RedeemCodeAsync(string code, DateTime now);

    Task<OperationResult<Empty>> LinkAccountsAsync(Guid userA, Guid userB);
    Task<OperationResult<Empty>> UnlinkAccountsAsync(Guid userA, Guid userB);

    /// <summary>Accounts linked to this one.</summary>
    Task<List<Guid>> LinkedAccountsAsync(Guid userId);
}
