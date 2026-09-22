using Microsoft.AspNetCore.Identity;

namespace Entities.Account;

public sealed class BabyphoneUser : IdentityUser<Guid>, IBaseEntity
{
    public DateTime CreatedAt { get; set; }
    public DateTime LastUpdatedAt { get; set; }
}
