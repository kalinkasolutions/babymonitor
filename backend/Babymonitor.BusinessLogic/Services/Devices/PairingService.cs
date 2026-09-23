using System.Security.Cryptography;
using System.Text;
using Babymonitor.BusinessLogic.Validation;
using Dal.Repositories.Devices;
using Dtos.Devices;
using Microsoft.Extensions.Logging;
using Shared;
using Entities.Devices;

namespace Babymonitor.BusinessLogic.Services.Devices;

public sealed class PairingService : IPairingService
{
    /// <summary>
    /// Long enough to read a code out over the phone and type it, short enough that a code left
    /// on a screen — or photographed — is worthless by the time anyone gets to it.
    /// </summary>
    private static readonly TimeSpan CodeLifetime = TimeSpan.FromMinutes(5);

    private const int CodeLength = 8;

    /// <summary>
    /// Crockford's alphabet: no I, L, O or U, so nothing can be misread as something else or
    /// accidentally spell anything. 32^8 is far more than five minutes of guessing can cover.
    /// </summary>
    private const string CodeAlphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    private const string UnknownDevice = "This phone is not registered.";
    private const string BadCode = "That code is not valid any more. Show a fresh one and try again.";

    private readonly ILogger<PairingService> m_logger;
    private readonly IDeviceRepository m_deviceRepository;
    private readonly IPairingRepository m_pairingRepository;

    public PairingService(
        ILogger<PairingService> logger,
        IDeviceRepository deviceRepository,
        IPairingRepository pairingRepository
    )
    {
        m_logger = logger;
        m_deviceRepository = deviceRepository;
        m_pairingRepository = pairingRepository;
    }

    public async Task<OperationResult<PairingCodeDto>> CreateCodeAsync(Guid userId, Guid? thisDeviceId)
    {
        var device = await OwnedDeviceAsync(userId, thisDeviceId);
        if (device is null)
        {
            m_logger.LogInformation(
                "User {UserId} asked for a pairing code from unregistered device {DeviceId}", userId, thisDeviceId);
            return OperationResult<PairingCodeDto>.BadRequest(UnknownDevice);
        }

        var code = GenerateCode();
        var expiresAt = DateTime.UtcNow.Add(CodeLifetime);
        await m_pairingRepository.IssueCodeAsync(device.Id, code, expiresAt);

        // The code itself is never logged: it is a live credential for the five minutes it lasts.
        m_logger.LogInformation(
            "Device {DeviceId} is showing a pairing code until {ExpiresAt:u}", device.Id, expiresAt);

        return OperationResult<PairingCodeDto>.Success(new PairingCodeDto
        {
            DeviceId = device.Id,
            PublicKey = device.PublicKey,
            Code = code,
            ExpiresAt = expiresAt
        });
    }

    public async Task<OperationResult<PairingClaimDto>> ClaimAsync(
        Guid userId, Guid? thisDeviceId, ClaimPairingDto dto)
    {
        var validation = DtoValidator.Validate(dto);
        if (validation.HasError)
        {
            return validation.MapError<PairingClaimDto>();
        }

        var self = await OwnedDeviceAsync(userId, thisDeviceId);
        if (self is null)
        {
            m_logger.LogInformation(
                "User {UserId} tried to claim a code from unregistered device {DeviceId}", userId, thisDeviceId);
            return OperationResult<PairingClaimDto>.BadRequest(UnknownDevice);
        }

        // Redeeming is what looks the code up: a code is good for exactly one link, and that is
        // only true if the check and the spending are the same step.
        var token = await m_pairingRepository.RedeemCodeAsync(Normalise(dto.Code), DateTime.UtcNow);
        if (token is null)
        {
            // One message for missing, spent and expired: the difference is no use to the person
            // holding the phone, and telling them apart would help anyone guessing at codes. The
            // log is where they are worth telling apart — a run of these is somebody guessing.
            m_logger.LogInformation(
                "Device {DeviceId} offered a pairing code that was unknown, spent or expired", self.Id);
            return OperationResult<PairingClaimDto>.BadRequest(BadCode);
        }

        var other = await m_deviceRepository.FindAsync(token.DeviceId);
        if (other is null)
        {
            m_logger.LogWarning(
                "Pairing code redeemed for device {DeviceId}, which no longer exists", token.DeviceId);
            return OperationResult<PairingClaimDto>.BadRequest(BadCode);
        }

        // Phones on one account already see each other, so there is no link to make — but the
        // scan is still worth completing, because confirming the key is the whole point and an
        // account's own phones start out no more trusted than anyone else's.
        if (other.UserId != userId)
        {
            var linked = await m_pairingRepository.LinkAccountsAsync(userId, other.UserId);
            if (linked.HasError)
            {
                return linked.MapError<PairingClaimDto>();
            }
        }

        // Whether a proof rode along decides how far the two phones can trust each other, and
        // it is the one thing about a pairing that cannot be worked out later from the database.
        m_logger.LogInformation(
            "Account {UserId} linked with {OtherUserId} via device {DeviceId}, {Proof}",
            userId, other.UserId, other.Id,
            string.IsNullOrEmpty(dto.Proof) ? "typed code, no key confirmed" : "scanned, with proof");

        var ownerNames = await m_deviceRepository.OwnerNamesAsync([other.UserId, userId]);
        return OperationResult<PairingClaimDto>.Success(new PairingClaimDto
        {
            ShowingDevice = DeviceService.ToDto(other, thisDeviceId, userId, ownerNames),

            // Described for the account that showed the code, not for the scanner: "this phone"
            // and "mine" are claims about the reader, and the reader is the other end.
            ClaimingDevice = DeviceService.ToDto(self, other.Id, other.UserId, ownerNames),
            Proof = dto.Proof ?? string.Empty
        });
    }

    public async Task<OperationResult<Empty>> UnlinkAsync(Guid userId, Guid otherUserId)
    {
        var result = await m_pairingRepository.UnlinkAccountsAsync(userId, otherUserId);
        if (result.IsSuccess)
        {
            m_logger.LogInformation("Account {UserId} unlinked from {OtherUserId}", userId, otherUserId);
        }

        return result;
    }

    private async Task<Device?> OwnedDeviceAsync(Guid userId, Guid? deviceId)
    {
        if (deviceId is null)
        {
            return null;
        }

        var device = await m_deviceRepository.FindAsync(deviceId.Value);
        return device?.UserId == userId ? device : null;
    }

    private static string GenerateCode()
    {
        var code = new StringBuilder(CodeLength + 1);
        for (var i = 0; i < CodeLength; i++)
        {
            if (i == CodeLength / 2)
            {
                code.Append('-');
            }

            code.Append(CodeAlphabet[RandomNumberGenerator.GetInt32(CodeAlphabet.Length)]);
        }

        return code.ToString();
    }

    /// <summary>Accept whatever the user typed: any case, with or without the grouping dash.</summary>
    private static string Normalise(string code)
    {
        var bare = code.Trim().Replace("-", string.Empty, StringComparison.Ordinal)
            .Replace(" ", string.Empty, StringComparison.Ordinal)
            .ToUpperInvariant();

        return bare.Length == CodeLength
            ? $"{bare[..(CodeLength / 2)]}-{bare[(CodeLength / 2)..]}"
            : bare;
    }
}
