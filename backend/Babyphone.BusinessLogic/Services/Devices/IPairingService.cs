using Dtos.Devices;
using Shared;

namespace Babyphone.BusinessLogic.Services.Devices;

/// <summary>
/// Linking two accounts so their phones can see each other. A link is about visibility only:
/// nothing here grants trust, because trust is a comparison people make on the phones themselves.
/// </summary>
public interface IPairingService
{
    /// <summary>The short-lived code this phone shows, as a QR and as eight typeable characters.</summary>
    Task<OperationResult<PairingCodeDto>> CreateCodeAsync(Guid userId, Guid? thisDeviceId);

    /// <summary>
    /// Redeems a code, linking the two accounts and describing both phones — the one that showed
    /// the code, so the scanner can compare the key it read off the screen against the key the
    /// backend holds, and the one that scanned, so the other phone can check its proof.
    /// </summary>
    Task<OperationResult<PairingClaimDto>> ClaimAsync(Guid userId, Guid? thisDeviceId, ClaimPairingDto dto);

    /// <summary>Drops the link to another account, and with it the visibility of their phones.</summary>
    Task<OperationResult<Empty>> UnlinkAsync(Guid userId, Guid otherUserId);
}
