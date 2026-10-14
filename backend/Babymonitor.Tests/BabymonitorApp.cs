using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http.Connections;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.AspNetCore.SignalR.Client;

namespace Babymonitor.Tests;

/// <summary>The real application, with no relay.</summary>
public class BabymonitorApp : WebApplicationFactory<Program>
{
    // Not Development, so the tests run the configuration a real deployment uses.
    protected override void ConfigureWebHost(IWebHostBuilder builder)
    {
        builder.UseEnvironment("Production");
        builder.UseSetting("Turn:Secret", string.Empty);
    }

    /// <summary>A client that sends the phone's signed headers on every request, and the server key if given.</summary>
    public HttpClient CreateClientFor(TestPhone phone, DateTimeOffset? signedAt = null, string? serverKey = null)
    {
        var client = CreateClient(new WebApplicationFactoryClientOptions { BaseAddress = new Uri("https://localhost") });
        foreach (var (name, value) in phone.AuthHeaders(signedAt))
        {
            client.DefaultRequestHeaders.Add(name, value);
        }

        if (serverKey is not null)
        {
            client.DefaultRequestHeaders.Add(Auth.ServerKey.HeaderName, serverKey);
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
                foreach (var (name, value) in phone.AuthHeaders())
                {
                    options.Headers.Add(name, value);
                }
            })
            .Build();

        await hub.StartAsync();
        return hub;
    }
}
