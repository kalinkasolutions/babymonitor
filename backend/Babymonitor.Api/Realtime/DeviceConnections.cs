namespace Babymonitor.Realtime;

/// <summary>
/// Which phones hold a hub connection right now. In memory, so true of this instance only;
/// a second instance would need a SignalR backplane.
/// </summary>
public sealed class DeviceConnections
{
    private readonly Dictionary<string, HashSet<string>> m_connections = new(StringComparer.Ordinal);
    private readonly Lock m_lock = new();

    /// <summary>Returns true when this is the device's first connection.</summary>
    public bool Add(string deviceId, string connectionId)
    {
        lock (m_lock)
        {
            if (!m_connections.TryGetValue(deviceId, out var connectionIds))
            {
                connectionIds = new HashSet<string>(StringComparer.Ordinal);
                m_connections[deviceId] = connectionIds;
            }

            connectionIds.Add(connectionId);
            return connectionIds.Count == 1;
        }
    }

    /// <summary>Returns true when that was the device's last connection.</summary>
    public bool Remove(string deviceId, string connectionId)
    {
        lock (m_lock)
        {
            if (!m_connections.TryGetValue(deviceId, out var connectionIds) || !connectionIds.Remove(connectionId))
            {
                return false;
            }

            if (connectionIds.Count > 0)
            {
                return false;
            }

            m_connections.Remove(deviceId);
            return true;
        }
    }

    public bool IsOnline(string deviceId)
    {
        lock (m_lock)
        {
            return m_connections.ContainsKey(deviceId);
        }
    }
}
