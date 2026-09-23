using Microsoft.AspNetCore.Identity;

namespace Entities.Account;

public sealed class BabymonitorUser : IdentityUser<Guid>, IBaseEntity
{
    public DateTime CreatedAt { get; set; }
    public DateTime LastUpdatedAt { get; set; }
}
