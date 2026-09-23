using Entities;
using Entities.Account;
using Entities.Devices;
using Microsoft.AspNetCore.Identity;
using Microsoft.AspNetCore.Identity.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore;

namespace Dal;

public class BabymonitorContext : IdentityDbContext<BabymonitorUser, IdentityRole<Guid>, Guid>
{
    public BabymonitorContext(DbContextOptions<BabymonitorContext> options) : base(options)
    {
    }

    public DbSet<Device> Devices { get; set; }
    public DbSet<AccountLink> AccountLinks { get; set; }
    public DbSet<PairingToken> PairingTokens { get; set; }

    protected override void OnModelCreating(ModelBuilder builder)
    {
        base.OnModelCreating(builder);

        builder.Entity<Device>(entity =>
        {
            entity.Property(d => d.Name).IsRequired().HasMaxLength(128);
            entity.Property(d => d.PublicKey).IsRequired().HasMaxLength(1024);

            // Registration is idempotent on the key, and that is enforced here rather than only in
            // the service: a phone reinstalling must not be able to accumulate duplicate rows.
            entity.HasIndex(d => new { d.UserId, d.PublicKey }).IsUnique();

            entity.HasOne<BabymonitorUser>()
                .WithMany()
                .HasForeignKey(d => d.UserId)
                .OnDelete(DeleteBehavior.Cascade);
        });

        builder.Entity<AccountLink>(entity =>
        {
            // Ids are stored in order, so this index is what stops the same link being created
            // twice by the two sides linking to each other.
            entity.HasIndex(l => new { l.FirstUserId, l.SecondUserId }).IsUnique();

            // Both sides cascade. A link is a fact about a pair, so it cannot outlive either of
            // them — and with one side left as NoAction, deleting the account that happened to
            // sort second raised a foreign-key error instead, which surfaced as a 500 and an
            // account its owner could not delete.
            entity.HasOne<BabymonitorUser>()
                .WithMany()
                .HasForeignKey(l => l.FirstUserId)
                .OnDelete(DeleteBehavior.Cascade);

            entity.HasOne<BabymonitorUser>()
                .WithMany()
                .HasForeignKey(l => l.SecondUserId)
                .OnDelete(DeleteBehavior.Cascade);
        });

        builder.Entity<PairingToken>(entity =>
        {
            entity.Property(t => t.Code).IsRequired().HasMaxLength(32);
            entity.HasIndex(t => t.Code).IsUnique();

            entity.HasOne<Device>()
                .WithMany()
                .HasForeignKey(t => t.DeviceId)
                .OnDelete(DeleteBehavior.Cascade);
        });

        // A display name is not an identifier — nothing is ever looked up by it — so it need not be
        // unique. Identity indexes it uniquely by default; this re-declares the same index without.
        builder.Entity<BabymonitorUser>()
            .HasIndex(u => u.NormalizedUserName)
            .HasDatabaseName("UserNameIndex")
            .IsUnique(false);
    }

    public override int SaveChanges()
    {
        UpdateAuditFields();
        return base.SaveChanges();
    }

    public override Task<int> SaveChangesAsync(CancellationToken cancellationToken = default)
    {
        UpdateAuditFields();
        return base.SaveChangesAsync(cancellationToken);
    }

    private void UpdateAuditFields()
    {
        var now = DateTime.UtcNow;
        foreach (var entry in ChangeTracker.Entries())
        {
            if (entry.Entity is not IBaseEntity baseEntity)
            {
                continue;
            }

            switch (entry.State)
            {
                case EntityState.Added:
                    baseEntity.CreatedAt = now;
                    baseEntity.LastUpdatedAt = now;
                    break;
                case EntityState.Modified:
                    baseEntity.LastUpdatedAt = now;
                    break;
                default:
                    break;
            }
        }
    }
}
