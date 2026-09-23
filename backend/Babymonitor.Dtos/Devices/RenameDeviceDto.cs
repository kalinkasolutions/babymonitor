using System.ComponentModel.DataAnnotations;

namespace Dtos.Devices;

public sealed class RenameDeviceDto
{
    [Required]
    [MaxLength(128)]
    public string Name { get; set; }
}
