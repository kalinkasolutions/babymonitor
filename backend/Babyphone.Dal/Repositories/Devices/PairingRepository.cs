using Entities.Account;
using Entities.Devices;
using Microsoft.EntityFrameworkCore;
using Shared;

namespace Dal.Repositories.Devices;

public sealed class PairingRepository : IPairingRepository
{
    /// <summary>How long a lapsed code is kept before the next issue sweeps it away.</summary>
    private static readonly TimeSpan SpentCodeRetention = TimeSpan.FromDays(1);

    private readonly BabyphoneContext m_context;

    public PairingRepository(BabyphoneContext context)
    {
        m_context = context;
    }

    public async Task<PairingToken> IssueCodeAsync(Guid deviceId, string code, DateTime expiresAt)
    {
        var now = DateTime.UtcNow;

        // ExecuteUpdate goes straight to SQL and never sees the change tracker, so the audit field
        // that SaveChanges would have maintained is set here by hand.
        await m_context.PairingTokens
            .Where(t => t.DeviceId == deviceId && t.UsedAt == null && t.ExpiresAt > now)
            .ExecuteUpdateAsync(t => t
                .SetProperty(p => p.ExpiresAt, now)
                .SetProperty(p => p.LastUpdatedAt, now));

        // Nothing ever reads a code once it has lapsed, and the table would otherwise grow for the
        // life of the installation.
        var staleBefore = now - SpentCodeRetention;
        await m_context.PairingTokens.Where(t => t.ExpiresAt < staleBefore).ExecuteDeleteAsync();

        var row = new PairingToken { DeviceId = deviceId, Code = code, ExpiresAt = expiresAt };
        m_context.PairingTokens.Add(row);
        await m_context.SaveChangesAsync();
        return row;
    }

    public async Task<PairingToken?> RedeemCodeAsync(string code, DateTime now)
    {
        // A single conditional update rather than read, check, then write: the check and the
        // spending have to be one statement or two claims arriving together both pass it.
        var spent = await m_context.PairingTokens
            .Where(t => t.Code == code && t.UsedAt == null && t.ExpiresAt > now)
            .ExecuteUpdateAsync(t => t
                .SetProperty(p => p.UsedAt, now)
                .SetProperty(p => p.LastUpdatedAt, now));

        return spent == 0
            ? null
            : await m_context.PairingTokens.AsNoTracking().FirstOrDefaultAsync(t => t.Code == code);
    }

    public async Task<OperationResult<Empty>> LinkAccountsAsync(Guid userA, Guid userB)
    {
        var (first, second) = AccountLink.Order(userA, userB);

        var exists = await m_context.AccountLinks
            .AnyAsync(l => l.FirstUserId == first && l.SecondUserId == second);
        if (exists)
        {
            return OperationResult<Empty>.Success();
        }

        m_context.AccountLinks.Add(new AccountLink { FirstUserId = first, SecondUserId = second });

        try
        {
            await m_context.SaveChangesAsync();
        }
        catch (DbUpdateException)
        {
            // The unique index got there first, which means the link this was asked to make now
            // exists. That is the outcome the caller wanted.
            m_context.ChangeTracker.Clear();
            return OperationResult<Empty>.Success();
        }

        return OperationResult<Empty>.Success();
    }

    public async Task<OperationResult<Empty>> UnlinkAccountsAsync(Guid userA, Guid userB)
    {
        var (first, second) = AccountLink.Order(userA, userB);
        var link = await m_context.AccountLinks
            .FirstOrDefaultAsync(l => l.FirstUserId == first && l.SecondUserId == second);

        if (link is null)
        {
            return OperationResult<Empty>.NotFound("Those accounts are not linked.");
        }

        m_context.AccountLinks.Remove(link);
        await m_context.SaveChangesAsync();
        return OperationResult<Empty>.Success();
    }

    public async Task<List<Guid>> LinkedAccountsAsync(Guid userId)
    {
        var links = await m_context.AccountLinks
            .Where(l => l.FirstUserId == userId || l.SecondUserId == userId)
            .ToListAsync();

        return links
            .Select(l => l.FirstUserId == userId ? l.SecondUserId : l.FirstUserId)
            .Distinct()
            .ToList();
    }
}
