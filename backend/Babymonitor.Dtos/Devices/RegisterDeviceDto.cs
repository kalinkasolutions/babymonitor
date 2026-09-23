using System.ComponentModel.DataAnnotations;

namespace Dtos.Devices;

public sealed class RegisterDeviceDto
{
    [Required]
    [MaxLength(128)]
    public string Name { get; set; }

    /// <summary>Base64 identity public key, generated on the device and never rotated casually.</summary>
    [Required]
    [MaxLength(1024)]
    public string PublicKey { get; set; }
}
