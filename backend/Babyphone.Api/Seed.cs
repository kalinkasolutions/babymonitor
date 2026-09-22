using Dal;
using Entities.Account;
using Microsoft.AspNetCore.Identity;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;
using Shared;

namespace Babyphone;

public static class Seed
{
    /// <summary>
    /// Brings the SQLite file up to date and makes sure the roles exist. In Development it also
    /// creates the configured seed accounts. There is no deployment step separate from starting
    /// the app, so the app owns its own schema.
    /// </summary>
    public static async Task InitializeAsync(WebApplication app)
    {
        using var scope = app.Services.CreateScope();
        var services = scope.ServiceProvider;

        var logger = services.GetRequiredService<ILoggerFactory>().CreateLogger(nameof(Seed));

        var db = services.GetRequiredService<BabyphoneContext>();
        var pending = (await db.Database.GetPendingMigrationsAsync()).ToList();
        if (pending.Count > 0)
        {
            logger.LogInformation(
                "Applying {Count} pending database migration(s): {Migrations}", pending.Count, string.Join(", ", pending));
        }

        await db.Database.MigrateAsync();

        await EnsureRolesAsync(services.GetRequiredService<RoleManager<IdentityRole<Guid>>>(), logger);

        // Seed accounts carry known passwords, so they are a development convenience and nothing
        // else. Guarded on the environment as well as living only in the development config: a
        // stray SeedUsers section in production must not be able to create a login.
        if (app.Environment.IsDevelopment())
        {
            await EnsureSeedUsersAsync(
                services.GetRequiredService<UserManager<BabyphoneUser>>(),
                services.GetRequiredService<IOptions<SeedUserOptions>>().Value,
                logger);
        }
    }

    private static async Task EnsureRolesAsync(RoleManager<IdentityRole<Guid>> roleManager, ILogger logger)
    {
        foreach (var role in new[] { Roles.User, Roles.Admin })
        {
            if (await roleManager.RoleExistsAsync(role))
            {
                continue;
            }

            var result = await roleManager.CreateAsync(new IdentityRole<Guid>(role));
            if (result.Succeeded)
            {
                logger.LogInformation("Created the {Role} role", role);
            }
            else
            {
                logger.LogError("Could not create the {Role} role: {Errors}",
                    role, string.Join("; ", result.Errors.Select(e => e.Description)));
            }
        }
    }

    private static async Task EnsureSeedUsersAsync(
        UserManager<BabyphoneUser> userManager, SeedUserOptions options, ILogger logger)
    {
        foreach (var seed in options.Users)
        {
            if (string.IsNullOrWhiteSpace(seed.Email) || string.IsNullOrWhiteSpace(seed.Password))
            {
                logger.LogWarning("Skipping a seed user with no email or no password");
                continue;
            }

            if (await userManager.FindByEmailAsync(seed.Email) is not null)
            {
                continue;
            }

            var user = new BabyphoneUser
            {
                UserName = string.IsNullOrWhiteSpace(seed.Username) ? seed.Email : seed.Username,
                Email = seed.Email,

                // Confirmed on creation, or the account could not sign in wherever
                // SignIn.RequireConfirmedEmail is on — and no mail is sent for a seeded account.
                EmailConfirmed = true
            };

            var created = await userManager.CreateAsync(user, seed.Password);
            if (!created.Succeeded)
            {
                logger.LogError("Could not create seed user {Email}: {Errors}",
                    seed.Email, string.Join("; ", created.Errors.Select(e => e.Description)));
                continue;
            }

            var roles = seed.IsAdmin ? new[] { Roles.User, Roles.Admin } : [Roles.User];
            await userManager.AddToRolesAsync(user, roles);

            logger.LogInformation(
                "Created seed user {Email} ({Username}){Admin}",
                seed.Email, user.UserName, seed.IsAdmin ? " as an administrator" : string.Empty);
        }
    }
}
