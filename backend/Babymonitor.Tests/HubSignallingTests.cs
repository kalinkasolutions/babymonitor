using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;
using Microsoft.AspNetCore.SignalR;
using Microsoft.AspNetCore.SignalR.Client;

namespace Babymonitor.Tests;

/// <summary>
/// The hub, driven over a real SignalR connection.
///
/// Two things here are load-bearing and are checked nowhere else. The hub must refuse to carry a
/// signal to a phone the caller has no link to, which is the only thing between two accounts on
/// one server; and it must pass the sender's signature through untouched, because that signature
/// is what lets the phone at the far end tell a genuine offer from one this server substituted.
/// Both are a line of code that would keep working in every other test if it were deleted.
/// </summary>
public sealed class HubSignallingTests : IClassFixture<BabymonitorApp>, IAsyncLifetime
{
    private const string Password = "a-long-enough-password";

    private readonly BabymonitorApp m_app;
    private readonly List<HubConnection> m_connections = [];

    public HubSignallingTests(BabymonitorApp app) => m_app = app;

    public Task InitializeAsync() => Task.CompletedTask;

    public async Task DisposeAsync()
    {
        foreach (var connection in m_connections)
        {
            await connection.DisposeAsync();
        }
    }

    [Fact]
    public async Task TheSignatureAndItsTimestampArriveUntouched()
    {
        var (sender, receiver) = await LinkedPairAsync();

        var arrived = new TaskCompletionSource<JsonElement>();
        receiver.Hub.On<string>("signal", payload =>
            arrived.TrySetResult(JsonSerializer.Deserialize<JsonElement>(payload)));

        const string signature = "c2lnbmF0dXJlLXRoZS1zZXJ2ZXItY2Fubm90LW1ha2U=";
        const long sentAt = 1_700_000_000_000L;
        const string sdp = "v=0\r\na=fingerprint:sha-256 AA:BB:CC\r\n";

        var delivered = await sender.Hub.InvokeAsync<bool>("Signal", JsonSerializer.Serialize(new
        {
            toDeviceId = receiver.DeviceId,
            kind = "offer",
            body = sdp,
            sentAt,
            signature
        }));

        Assert.True(delivered, "the receiving phone was connected and should have been handed it");

        var signal = await arrived.Task.WaitAsync(TimeSpan.FromSeconds(10));
        Assert.Equal(signature, signal.GetProperty("signature").GetString());
        Assert.Equal(sentAt, signal.GetProperty("sentAt").GetInt64());
        Assert.Equal(sdp, signal.GetProperty("body").GetString());
    }

    /// <summary>
    /// The sender does not get to say who it is. Whatever it claims, the hub replaces it with the
    /// connection it authenticated — so a phone cannot put somebody else's name on an offer.
    /// </summary>
    [Fact]
    public async Task TheHubNamesTheSenderItself()
    {
        var (sender, receiver) = await LinkedPairAsync();

        var arrived = new TaskCompletionSource<JsonElement>();
        receiver.Hub.On<string>("signal", payload =>
            arrived.TrySetResult(JsonSerializer.Deserialize<JsonElement>(payload)));

        await sender.Hub.InvokeAsync<bool>("Signal", JsonSerializer.Serialize(new
        {
            toDeviceId = receiver.DeviceId,
            fromDeviceId = Guid.NewGuid(),
            kind = "candidate",
            body = "candidate:1 1 udp",
            sentAt = 1L,
            signature = "x"
        }));

        var signal = await arrived.Task.WaitAsync(TimeSpan.FromSeconds(10));
        Assert.Equal(sender.DeviceId, signal.GetProperty("fromDeviceId").GetGuid());
    }

    [Fact]
    public async Task APhoneOnAnUnlinkedAccountCannotBeSignalled()
    {
        var sender = await ConnectAsync("sender");
        var stranger = await ConnectAsync("stranger");

        await Assert.ThrowsAsync<HubException>(() => sender.Hub.InvokeAsync<bool>(
            "Signal",
            JsonSerializer.Serialize(new
            {
                toDeviceId = stranger.DeviceId,
                kind = "start",
                body = "{}",
                sentAt = 1L,
                signature = "x"
            })));
    }

    [Fact]
    public async Task ASignalOfAnUnknownKindIsRefused()
    {
        var (sender, receiver) = await LinkedPairAsync();

        await Assert.ThrowsAsync<HubException>(() => sender.Hub.InvokeAsync<bool>(
            "Signal",
            JsonSerializer.Serialize(new
            {
                toDeviceId = receiver.DeviceId,
                kind = "please-do-something-else",
                body = "{}",
                sentAt = 1L,
                signature = "x"
            })));
    }

    [Fact]
    public async Task AnOversizedSignalIsRefused()
    {
        var (sender, receiver) = await LinkedPairAsync();

        await Assert.ThrowsAsync<HubException>(() => sender.Hub.InvokeAsync<bool>(
            "Signal",
            JsonSerializer.Serialize(new
            {
                toDeviceId = receiver.DeviceId,
                kind = "offer",
                body = new string('x', 17 * 1024),
                sentAt = 1L,
                signature = "x"
            })));
    }

    private sealed record Phone(HubConnection Hub, Guid DeviceId, Guid UserId, string Cookie);

    private async Task<(Phone Sender, Phone Receiver)> LinkedPairAsync()
    {
        var sender = await ConnectAsync("sender");
        var receiver = await ConnectAsync("receiver");
        await LinkAsync(sender, receiver);
        return (sender, receiver);
    }

    /// <summary>
    /// Registers an account and a phone, then opens the hub connection that phone would hold.
    /// The cookie is captured by hand because the SignalR client builds its own HTTP stack and
    /// cannot be handed the one the REST calls used — which is exactly what the app does too.
    /// </summary>
    private async Task<Phone> ConnectAsync(string name)
    {
        using var client = m_app.CreateClient(
            new Microsoft.AspNetCore.Mvc.Testing.WebApplicationFactoryClientOptions
            {
                HandleCookies = false,
                BaseAddress = new Uri("https://localhost")
            });

        var email = $"{name}-{Guid.NewGuid():N}@example.test";
        await client.PostAsJsonAsync("api/auth/register", new { email, username = name, password = Password });

        using var signedIn = await client.PostAsJsonAsync("api/auth/login", new { email, password = Password });
        signedIn.EnsureSuccessStatusCode();

        var cookie = signedIn.Headers.GetValues("Set-Cookie")
            .Select(header => header.Split(';')[0])
            .Aggregate((one, other) => $"{one}; {other}");

        client.DefaultRequestHeaders.Add("Cookie", cookie);

        var publicKey = Convert.ToBase64String(Guid.NewGuid().ToByteArray());
        using var registered = await client.PostAsJsonAsync(
            "api/devices", new { name = $"{name}'s phone", publicKey });
        registered.EnsureSuccessStatusCode();
        var deviceId = (await Accounts.ReadAsync(registered)).GetProperty("id").GetGuid();

        using var account = await client.GetAsync("api/account");
        var userId = (await Accounts.ReadAsync(account)).GetProperty("userId").GetGuid();

        var hub = new HubConnectionBuilder()
            .WithUrl(new Uri("https://localhost/hubs/devices"), options =>
            {
                options.HttpMessageHandlerFactory = _ => m_app.Server.CreateHandler();
                options.Headers.Add("Cookie", cookie);
                options.Headers.Add("X-Device-Id", deviceId.ToString());
            })
            .Build();

        await hub.StartAsync();
        m_connections.Add(hub);

        return new Phone(hub, deviceId, userId, cookie);
    }

    private async Task LinkAsync(Phone showing, Phone claiming)
    {
        using var client = ClientFor(showing);
        using var issued = await client.PostAsJsonAsync("api/pairing/code", new { });
        issued.EnsureSuccessStatusCode();
        var code = (await Accounts.ReadAsync(issued)).GetProperty("code").GetString();

        using var other = ClientFor(claiming);
        using var claimed = await other.PostAsJsonAsync("api/pairing/claim", new { code, proof = "" });
        claimed.EnsureSuccessStatusCode();
    }

    private HttpClient ClientFor(Phone phone)
    {
        var client = m_app.CreateClient(
            new Microsoft.AspNetCore.Mvc.Testing.WebApplicationFactoryClientOptions
            {
                HandleCookies = false,
                BaseAddress = new Uri("https://localhost")
            });

        client.DefaultRequestHeaders.Add("Cookie", phone.Cookie);
        client.DefaultRequestHeaders.Add("X-Device-Id", phone.DeviceId.ToString());
        client.DefaultRequestHeaders.Accept.Add(new MediaTypeWithQualityHeaderValue("application/json"));
        return client;
    }
}
