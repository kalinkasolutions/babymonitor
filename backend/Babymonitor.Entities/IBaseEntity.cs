namespace Entities;

public interface IBaseEntity
{
    Guid Id { get; set; }
    DateTime CreatedAt { get; set; }
    DateTime LastUpdatedAt { get; set; }
}
