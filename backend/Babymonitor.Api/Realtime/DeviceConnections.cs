using System.Collections.Concurrent;

namespace Babymonitor.Realtime;

/// <summary>
/// Which phones currently hold a hub connection, so an offer can be told apart from an offer
/// nobody is listening for. A phone in the device list is not the same thing as a phone that is
/// awake and connected, and only the second one can answer.
///
/// In memory and therefore true of this instance only. The deployment is a single container; a
/// second one would need this in Redis alongside a SignalR backplane.
/// </summary>
public sealed class DeviceConnections
{
    private readonly ConcurrentDictionary<Guid, HashSet<string>> m_connections = new();

    public void Add(Guid deviceId, string connectionId)
    {
        var ids = m_connections.GetOrAdd(deviceId, _ => []);
        lock (ids)
        {
            ids.Add(connectionId);
        }
    }

    public void Remove(Guid deviceId, string connectionId)
    {
        if (!m_connections.TryGetValue(deviceId, out var ids))
        {
            return;
        }

        lock (ids)
        {
            ids.Remove(connectionId);
            if (ids.Count == 0)
            {
                m_connections.TryRemove(deviceId, out _);
            }
        }
    }

    public bool IsConnected(Guid deviceId) =>
        m_connections.TryGetValue(deviceId, out var ids) && ids.Count > 0;
}
