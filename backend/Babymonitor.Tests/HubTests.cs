using System.Text.Json;
using System.Threading.Channels;
using Microsoft.AspNetCore.SignalR;
using Microsoft.AspNetCore.SignalR.Client;

namespace Babymonitor.Tests;

/// <summary>The hub over a real SignalR connection: routing by device id, and presence.</summary>
public sealed class HubTests : IClassFixture<BabymonitorApp>, IAsyncLifetime
{
    private static readonly TimeSpan s_timeout = TimeSpan.FromSeconds(10);

    private readonly BabymonitorApp m_app;
    private readonly List<HubConnection> m_connections = [];
    private readonly List<TestPhone> m_phones = [];

    public HubTests(BabymonitorApp app) => m_app = app;

    public Task InitializeAsync() => Task.CompletedTask;

    public async Task DisposeAsync()
    {
        foreach (var connection in m_connections)
        {
            await connection.DisposeAsync();
        }

        foreach (var phone in m_phones)
        {
            phone.Dispose();
        }
    }

    [Fact]
    public async Task ASignalReachesTheAddressedPhoneUntouched()
    {
        var (_, sender) = await ConnectNewPhoneAsync();
        var (receiverPhone, receiver) = await ConnectNewPhoneAsync();
        var signals = Listen(receiver, "signal");

        const string sdp = "v=0\r\na=fingerprint:sha-256 AA:BB:CC\r\n";
        var delivered = await SignalAsync(sender, receiverPhone.DeviceId, "offer", body: sdp, sentAt: 1_700_000_000_000L, signature: "c2ln");

        Assert.True(delivered);
        var signal = await NextAsync(signals);
        Assert.Equal(receiverPhone.DeviceId, signal.GetProperty("toDeviceId").GetString());
        Assert.Equal("offer", signal.GetProperty("kind").GetString());
        Assert.Equal(sdp, signal.GetProperty("body").GetString());
        Assert.Equal(1_700_000_000_000L, signal.GetProperty("sentAt").GetInt64());
        Assert.Equal("c2ln", signal.GetProperty("signature").GetString());
    }

    [Fact]
    public async Task TheHubNamesTheSenderItself()
    {
        var (senderPhone, sender) = await ConnectNewPhoneAsync();
        var (receiverPhone, receiver) = await ConnectNewPhoneAsync();
        var signals = Listen(receiver, "signal");

        await sender.InvokeAsync<bool>("Signal", JsonSerializer.Serialize(new
        {
            toDeviceId = receiverPhone.DeviceId,
            fromDeviceId = "00000000000000000000000000000000",
            kind = "candidate",
            body = "candidate:1 1 udp",
            sentAt = 1L,
            signature = "x"
        }));

        var signal = await NextAsync(signals);
        Assert.Equal(senderPhone.DeviceId, signal.GetProperty("fromDeviceId").GetString());
    }

    [Fact]
    public async Task ASignalToAPhoneThatIsNotConnectedIsNotDelivered()
    {
        var (_, sender) = await ConnectNewPhoneAsync();
        using var absent = new TestPhone();

        Assert.False(await SignalAsync(sender, absent.DeviceId, "start"));
    }

    [Fact]
    public async Task APairSignalIsCarried()
    {
        var (_, sender) = await ConnectNewPhoneAsync();
        var (receiverPhone, receiver) = await ConnectNewPhoneAsync();
        var signals = Listen(receiver, "signal");

        Assert.True(await SignalAsync(sender, receiverPhone.DeviceId, "pair"));
        var signal = await NextAsync(signals);
        Assert.Equal("pair", signal.GetProperty("kind").GetString());
    }

    [Fact]
    public async Task AnAvailabilitySignalIsCarried()
    {
        var (_, sender) = await ConnectNewPhoneAsync();
        var (receiverPhone, receiver) = await ConnectNewPhoneAsync();
        var signals = Listen(receiver, "signal");

        Assert.True(await SignalAsync(sender, receiverPhone.DeviceId, "availability"));
        var signal = await NextAsync(signals);
        Assert.Equal("availability", signal.GetProperty("kind").GetString());
    }

    [Fact]
    public async Task ASignalOfAnUnknownKindIsRefused()
    {
        var (_, sender) = await ConnectNewPhoneAsync();
        using var target = new TestPhone();

        await Assert.ThrowsAsync<HubException>(() => SignalAsync(sender, target.DeviceId, "please-do-something-else"));
    }

    [Fact]
    public async Task AnOversizedSignalIsRefused()
    {
        var (_, sender) = await ConnectNewPhoneAsync();
        using var target = new TestPhone();

        await Assert.ThrowsAsync<HubException>(() =>
            SignalAsync(sender, target.DeviceId, "offer", body: new string('x', (16 * 1024) + 1)));
    }

    [Fact]
    public async Task ASignalToSomethingThatIsNotADeviceIdIsRefused()
    {
        var (_, sender) = await ConnectNewPhoneAsync();

        await Assert.ThrowsAsync<HubException>(() => SignalAsync(sender, "not-a-device", "start"));
    }

    [Fact]
    public async Task MalformedJsonIsRefused()
    {
        var (_, sender) = await ConnectNewPhoneAsync();

        await Assert.ThrowsAsync<HubException>(() => sender.InvokeAsync<bool>("Signal", "{not json"));
    }

    [Fact]
    public async Task WatchReturnsTheWatchedPhonesThatAreOnline()
    {
        var (_, watcher) = await ConnectNewPhoneAsync();
        var (onlinePhone, _) = await ConnectNewPhoneAsync();
        using var offlinePhone = new TestPhone();

        var online = await watcher.InvokeAsync<string[]>(
            "Watch", new[] { onlinePhone.DeviceId, offlinePhone.DeviceId, "not-a-device" });

        Assert.Equal([onlinePhone.DeviceId], online);
    }

    [Fact]
    public async Task AWatchedPhoneIsAnnouncedWhenItComesAndGoes()
    {
        var (_, watcher) = await ConnectNewPhoneAsync();
        var presence = Listen(watcher, "devicePresence");
        var watchedPhone = NewPhone();
        await watcher.InvokeAsync<string[]>("Watch", new[] { watchedPhone.DeviceId });

        var watched = await m_app.ConnectAsync(watchedPhone);
        m_connections.Add(watched);
        var cameOnline = await NextAsync(presence);
        await watched.DisposeAsync();
        var wentOffline = await NextAsync(presence);

        Assert.Equal(watchedPhone.DeviceId, cameOnline.GetProperty("deviceId").GetString());
        Assert.True(cameOnline.GetProperty("isOnline").GetBoolean());
        Assert.Equal(watchedPhone.DeviceId, wentOffline.GetProperty("deviceId").GetString());
        Assert.False(wentOffline.GetProperty("isOnline").GetBoolean());
    }

    // Presence events reach a connection in order, so the marker phone shows nothing came before it.
    [Fact]
    public async Task WatchReplacesThePreviousSet()
    {
        var (_, watcher) = await ConnectNewPhoneAsync();
        var presence = Listen(watcher, "devicePresence");
        var dropped = NewPhone();
        var marker = NewPhone();
        await watcher.InvokeAsync<string[]>("Watch", new[] { dropped.DeviceId });
        await watcher.InvokeAsync<string[]>("Watch", new[] { marker.DeviceId });

        m_connections.Add(await m_app.ConnectAsync(dropped));
        m_connections.Add(await m_app.ConnectAsync(marker));

        var first = await NextAsync(presence);
        Assert.Equal(marker.DeviceId, first.GetProperty("deviceId").GetString());
    }

    [Fact]
    public async Task APhoneIsOfflineOnlyWhenItsLastConnectionCloses()
    {
        var (_, watcher) = await ConnectNewPhoneAsync();
        var presence = Listen(watcher, "devicePresence");
        var phone = NewPhone();
        var marker = NewPhone();
        await watcher.InvokeAsync<string[]>("Watch", new[] { phone.DeviceId, marker.DeviceId });

        var first = await m_app.ConnectAsync(phone);
        var second = await m_app.ConnectAsync(phone);
        m_connections.AddRange([first, second]);
        Assert.True((await NextAsync(presence)).GetProperty("isOnline").GetBoolean());

        await first.DisposeAsync();
        m_connections.Add(await m_app.ConnectAsync(marker));
        Assert.Equal(marker.DeviceId, (await NextAsync(presence)).GetProperty("deviceId").GetString());

        await second.DisposeAsync();
        var offline = await NextAsync(presence);
        Assert.Equal(phone.DeviceId, offline.GetProperty("deviceId").GetString());
        Assert.False(offline.GetProperty("isOnline").GetBoolean());
    }

    private TestPhone NewPhone()
    {
        var phone = new TestPhone();
        m_phones.Add(phone);
        return phone;
    }

    private async Task<(TestPhone Phone, HubConnection Hub)> ConnectNewPhoneAsync()
    {
        var phone = NewPhone();
        var hub = await m_app.ConnectAsync(phone);
        m_connections.Add(hub);
        return (phone, hub);
    }

    private static ChannelReader<JsonElement> Listen(HubConnection hub, string eventName)
    {
        var events = Channel.CreateUnbounded<JsonElement>();
        hub.On<string>(eventName, payload => events.Writer.TryWrite(JsonSerializer.Deserialize<JsonElement>(payload)));
        return events.Reader;
    }

    private static Task<JsonElement> NextAsync(ChannelReader<JsonElement> events) =>
        events.ReadAsync().AsTask().WaitAsync(s_timeout);

    private static Task<bool> SignalAsync(
        HubConnection sender, string toDeviceId, string kind, string body = "{}", long sentAt = 1L, string signature = "x") =>
        sender.InvokeAsync<bool>("Signal", JsonSerializer.Serialize(new { toDeviceId, kind, body, sentAt, signature }));
}
