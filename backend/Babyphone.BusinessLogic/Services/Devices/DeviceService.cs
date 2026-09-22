using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using Babyphone.BusinessLogic.Validation;
using Dal.Repositories.Devices;
using Dtos.Devices;
using Microsoft.Extensions.Logging;
using Shared;
using Entities.Devices;

namespace Babyphone.BusinessLogic.Services.Devices;

public sealed class DeviceService : IDeviceService
{
    private const string NotFound = "That device does not exist.";

    private readonly ILogger<DeviceService> m_logger;
    private readonly IDeviceRepository m_deviceRepository;
    private readonly IPairingRepository m_pairingRepository;

    public DeviceService(
        ILogger<DeviceService> logger,
        IDeviceRepository deviceRepository,
        IPairingRepository pairingRepository
    )
    {
        m_logger = logger;
        m_deviceRepository = deviceRepository;
        m_pairingRepository = pairingRepository;
    }

    /// <summary>
    /// The caller's own phones and those of every account linked to theirs. A link decides what
    /// is visible; whether a phone's key can be trusted is decided on the phones themselves, by
    /// people comparing it, and is deliberately not something this list can assert.
    /// </summary>
    public async Task<OperationResult<List<DeviceDto>>> ListAsync(Guid userId, Guid? thisDeviceId)
    {
        var linked = await m_pairingRepository.LinkedAccountsAsync(userId);
        var owners = linked.Append(userId).Distinct().ToList();

        var devices = await m_deviceRepository.ListByOwnersAsync(owners);
        var ownerNames = await m_deviceRepository.OwnerNamesAsync(owners);

        return OperationResult<List<DeviceDto>>.Success(
            devices.ConvertAll(device => ToDto(device, thisDeviceId, userId, ownerNames)));
    }

    public async Task<OperationResult<DeviceDto>> FindAsync(Guid userId, Guid deviceId, Guid? thisDeviceId)
    {
        var device = await OwnedDeviceAsync(userId, deviceId);
        return device is null
            ? OperationResult<DeviceDto>.NotFound(NotFound)
            : OperationResult<DeviceDto>.Success(await ToDtoAsync(device, thisDeviceId, userId));
    }

    public async Task<OperationResult<DeviceDto>> RegisterAsync(Guid userId, RegisterDeviceDto dto)
    {
        var validation = DtoValidator.Validate(dto);
        if (validation.HasError)
        {
            return validation.MapError<DeviceDto>();
        }

        var publicKey = dto.PublicKey.Trim();
        var name = dto.Name.Trim();

        var existing = await m_deviceRepository.FindByPublicKeyAsync(userId, publicKey);
        if (existing is not null)
        {
            // Same phone, same key. Take the name it reports now — the model may have been renamed
            // on the device — and treat the call as a sign of life.
            existing.Name = name;
            existing.LastSeenAt = DateTime.UtcNow;
            var save = await m_deviceRepository.SaveAsync();
            return save.HasError
                ? save.MapError<DeviceDto>()
                : OperationResult<DeviceDto>.Success(await ToDtoAsync(existing, existing.Id, userId));
        }

        var added = await m_deviceRepository.AddAsync(new Device
        {
            UserId = userId,
            Name = name,
            PublicKey = publicKey,
            LastSeenAt = DateTime.UtcNow
        });

        if (added.HasError)
        {
            return added.MapError<DeviceDto>();
        }

        m_logger.LogInformation(
            "User {UserId} registered device {DeviceId} ({Name})", userId, added.Value.Id, name);
        return OperationResult<DeviceDto>.Success(await ToDtoAsync(added.Value, added.Value.Id, userId));
    }

    public async Task<OperationResult<DeviceDto>> RenameAsync(
        Guid userId, Guid deviceId, Guid? thisDeviceId, RenameDeviceDto dto)
    {
        var validation = DtoValidator.Validate(dto);
        if (validation.HasError)
        {
            return validation.MapError<DeviceDto>();
        }

        var device = await OwnedDeviceAsync(userId, deviceId);
        if (device is null)
        {
            return OperationResult<DeviceDto>.NotFound(NotFound);
        }

        device.Name = dto.Name.Trim();
        var save = await m_deviceRepository.SaveAsync();
        return save.HasError
            ? save.MapError<DeviceDto>()
            : OperationResult<DeviceDto>.Success(await ToDtoAsync(device, thisDeviceId, userId));
    }

    public async Task<OperationResult<Empty>> HeartbeatAsync(Guid userId, Guid deviceId, HeartbeatDto dto)
    {
        var validation = DtoValidator.Validate(dto);
        if (validation.HasError)
        {
            return validation;
        }

        var device = await OwnedDeviceAsync(userId, deviceId);
        if (device is null)
        {
            return OperationResult<Empty>.NotFound(NotFound);
        }

        device.LastSeenAt = DateTime.UtcNow;

        // Null means "not reported this time", not "unknown" — keep the last known reading rather
        // than blanking the other phone's battery display on a heartbeat that omitted it.
        if (dto.BatteryPercent is not null)
        {
            device.BatteryPercent = dto.BatteryPercent;
        }

        if (dto.IsCharging is not null)
        {
            device.IsCharging = dto.IsCharging;
        }

        return await m_deviceRepository.SaveAsync();
    }

    public async Task<OperationResult<Empty>> RevokeAsync(Guid userId, Guid deviceId)
    {
        var device = await OwnedDeviceAsync(userId, deviceId);
        if (device is null)
        {
            return OperationResult<Empty>.NotFound(NotFound);
        }

        m_logger.LogInformation(
            "User {UserId} revoked device {DeviceId} ({Name})", userId, deviceId, device.Name);
        return await m_deviceRepository.RemoveAsync(device);
    }

    /// <summary>
    /// A device the caller owns, or null. Deliberately does not distinguish "not yours" from
    /// "does not exist", so device ids cannot be probed across accounts.
    /// </summary>
    private async Task<Device?> OwnedDeviceAsync(Guid userId, Guid deviceId)
    {
        var device = await m_deviceRepository.FindAsync(deviceId);
        return device?.UserId == userId ? device : null;
    }

    private async Task<DeviceDto> ToDtoAsync(Device device, Guid? thisDeviceId, Guid callerId)
    {
        var names = await m_deviceRepository.OwnerNamesAsync([device.UserId]);
        return ToDto(device, thisDeviceId, callerId, names);
    }

    internal static DeviceDto ToDto(
        Device device,
        Guid? thisDeviceId,
        Guid callerId,
        IReadOnlyDictionary<Guid, string> ownerNames) => new()
    {
        Id = device.Id,
        Name = device.Name,
        IsThisDevice = thisDeviceId == device.Id,
        IsMine = device.UserId == callerId,
        OwnerName = ownerNames.TryGetValue(device.UserId, out var owner) ? owner : string.Empty,
        OwnerId = device.UserId,
        KeyFingerprint = Fingerprint(device.PublicKey),
        PublicKey = device.PublicKey,
        LastSeenAt = device.LastSeenAt,
        BatteryPercent = device.BatteryPercent,
        IsCharging = device.IsCharging,
        CreatedAt = device.CreatedAt
    };

    /// <summary>
    /// A short digest of the key, in groups of four, for a person to read aloud and compare with
    /// the other phone. Truncated to 96 bits: enough that nobody can grind out a second key with
    /// the same fingerprint, still short enough to read across a room.
    /// </summary>
    private static string Fingerprint(string publicKey)
    {
        const int fingerprintBytes = 12;
        var hash = SHA256.HashData(Encoding.UTF8.GetBytes(publicKey));
        var hex = Convert.ToHexString(hash.AsSpan(0, fingerprintBytes)).ToLower(CultureInfo.InvariantCulture);
        return string.Join(' ', Enumerable.Range(0, hex.Length / 4).Select(i => hex.Substring(i * 4, 4)));
    }
}
