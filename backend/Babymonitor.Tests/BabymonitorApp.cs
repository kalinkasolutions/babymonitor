using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http.Connections;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.AspNetCore.SignalR.Client;

namespace Babymonitor.Tests;

/// <summary>The real application, with a known server key and no relay.</summary>
public class BabymonitorApp : WebApplicationFactory<Program>
{
    public const string ServerKey = "test-server-key";

    // Not Development, so the tests run the configuration a real deployment uses.
    protected override void ConfigureWebHost(IWebHostBuilder builder)
    {
        builder.UseEnvironment("Production");
        builder.UseSetting("Server:Key", ServerKey);
        builder.UseSetting("Turn:Secret", string.Empty);
    }

    /// <summary>A client that sends the phone's signed headers on every request.</summary>
    public HttpClient CreateClientFor(TestPhone phone, string serverKey = ServerKey, DateTimeOffset? signedAt = null)
    {
        var client = CreateClient(new WebApplicationFactoryClientOptions { BaseAddress = new Uri("https://localhost") });
        foreach (var (name, value) in phone.AuthHeaders(serverKey, signedAt))
        {
            client.DefaultRequestHeaders.Add(name, value);
        }

        return client;
    }

    public async Task<HubConnection> ConnectAsync(TestPhone phone)
    {
        var hub = new HubConnectionBuilder()
            .WithUrl(new Uri("https://localhost/hubs/devices"), options =>
            {
                // Long polling, because the test server's handler only speaks plain HTTP.
                options.Transports = HttpTransportType.LongPolling;
                options.HttpMessageHandlerFactory = _ => Server.CreateHandler();
                foreach (var (name, value) in phone.AuthHeaders(ServerKey))
                {
                    options.Headers.Add(name, value);
                }
            })
            .Build();

        await hub.StartAsync();
        return hub;
    }
}
