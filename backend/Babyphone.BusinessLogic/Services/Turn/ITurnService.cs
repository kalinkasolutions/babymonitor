using Dtos.Turn;
using Shared;

namespace Babyphone.BusinessLogic.Services.Turn;

public interface ITurnService
{
    /// <summary>
    /// Mints a short-lived relay credential for this user. Nothing is stored: coturn recomputes
    /// the same signature from the shared secret, so there are no accounts to provision or revoke.
    /// </summary>
    OperationResult<IceServersDto> IssueAsync(Guid userId);
}
