using System.ComponentModel.DataAnnotations;

namespace Dtos.Devices;

/// <summary>
/// Sent on a slow timer and whenever the charger state changes. Both ends report: a recording
/// phone that dies stops the monitor, and an observing phone that dies sleeps through the alarm.
/// </summary>
public sealed class HeartbeatDto
{
    [Range(0, 100)]
    public int? BatteryPercent { get; set; }

    public bool? IsCharging { get; set; }
}
