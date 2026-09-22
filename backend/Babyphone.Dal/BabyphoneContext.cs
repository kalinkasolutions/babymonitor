using Entities;
using Entities.Account;
using Entities.Devices;
using Microsoft.AspNetCore.Identity;
using Microsoft.AspNetCore.Identity.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore;

namespace Dal;

public class BabyphoneContext : IdentityDbContext<BabyphoneUser, IdentityRole<Guid>, Guid>
{
    public BabyphoneContext(DbContextOptions<BabyphoneContext> options) : base(options)
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

            entity.HasOne<BabyphoneUser>()
                .WithMany()
                .HasForeignKey(d => d.UserId)
                .OnDelete(DeleteBehavior.Cascade);
        });

        builder.Entity<AccountLink>(entity =>
        {
            // Ids are stored in order, so this index is what stops the same link being created
            // twice by the two sides linking to each other.
            entity.HasIndex(l => new { l.FirstUserId, l.SecondUserId }).IsUnique();

            entity.HasOne<BabyphoneUser>()
                .WithMany()
                .HasForeignKey(l => l.FirstUserId)
                .OnDelete(DeleteBehavior.Cascade);

            entity.HasOne<BabyphoneUser>()
                .WithMany()
                .HasForeignKey(l => l.SecondUserId)
                .OnDelete(DeleteBehavior.NoAction);
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
        builder.Entity<BabyphoneUser>()
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
