using Microsoft.EntityFrameworkCore;
using Shared;
using Entities.Devices;

namespace Dal.Repositories.Devices;

public sealed class DeviceRepository : IDeviceRepository
{
    private readonly BabyphoneContext m_context;

    public DeviceRepository(BabyphoneContext context)
    {
        m_context = context;
    }

    public Task<List<Device>> ListAsync(Guid userId)
    {
        return m_context.Devices
            .Where(d => d.UserId == userId)
            .OrderBy(d => d.CreatedAt)
            .ToListAsync();
    }

    public Task<Device?> FindAsync(Guid deviceId)
    {
        return m_context.Devices.FirstOrDefaultAsync(d => d.Id == deviceId);
    }

    public Task<List<Device>> ListByOwnersAsync(IReadOnlyCollection<Guid> userIds)
    {
        return m_context.Devices
            .Where(d => userIds.Contains(d.UserId))
            .OrderBy(d => d.CreatedAt)
            .ToListAsync();
    }

    public async Task<Dictionary<Guid, string>> OwnerNamesAsync(IReadOnlyCollection<Guid> userIds)
    {
        var rows = await m_context.Users
            .Where(u => userIds.Contains(u.Id))
            .Select(u => new { u.Id, u.UserName })
            .ToListAsync();

        return rows.ToDictionary(r => r.Id, r => r.UserName ?? string.Empty);
    }

    public Task<Device?> FindByPublicKeyAsync(Guid userId, string publicKey)
    {
        return m_context.Devices
            .FirstOrDefaultAsync(d => d.UserId == userId && d.PublicKey == publicKey);
    }

    public async Task<OperationResult<Device>> AddAsync(Device device)
    {
        m_context.Devices.Add(device);
        await m_context.SaveChangesAsync();
        return OperationResult<Device>.Success(device);
    }

    public async Task<OperationResult<Empty>> SaveAsync()
    {
        await m_context.SaveChangesAsync();
        return OperationResult<Empty>.Success();
    }

    public async Task<OperationResult<Empty>> RemoveAsync(Device device)
    {
        m_context.Devices.Remove(device);
        await m_context.SaveChangesAsync();
        return OperationResult<Empty>.Success();
    }
}
