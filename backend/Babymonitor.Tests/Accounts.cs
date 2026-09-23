using System.Net.Http.Json;
using System.Text.Json;

namespace Babymonitor.Tests;

/// <summary>
/// Signing up and pairing, as a test writes them. Every test here needs two accounts with phones
/// registered and linked, and doing that by hand in each one buries what is being asserted.
/// </summary>
internal static class Accounts
{
    /// <summary>Comfortably over the production minimum, so the tests run against the real rules.</summary>
    private const string Password = "a-long-enough-password";

    private static readonly JsonSerializerOptions s_json = new(JsonSerializerDefaults.Web);

    internal sealed record Phone(HttpClient Client, Guid UserId, Guid DeviceId, string Email);

    /// <summary>Registers an account, signs in, and registers one phone on it.</summary>
    internal static async Task<Phone> RegisterAsync(BabymonitorApp app, string name)
    {
        var email = $"{name}-{Guid.NewGuid():N}@example.test";
        var client = app.CreateSignedOutClient();

        var registered = await client.PostAsJsonAsync("api/auth/register", new
        {
            email,
            username = name,
            password = Password
        });
        await ThrowIfFailedAsync(registered, "register");

        var signedIn = await client.PostAsJsonAsync("api/auth/login", new { email, password = Password });
        await ThrowIfFailedAsync(signedIn, "login");

        // A key per phone, and unique, because the device table has a unique index on it and the
        // app works out which row is itself by matching it.
        var publicKey = Convert.ToBase64String(Guid.NewGuid().ToByteArray());
        var device = await client.PostAsJsonAsync("api/devices", new { name = $"{name}'s phone", publicKey });
        await ThrowIfFailedAsync(device, "register device");

        var deviceId = (await ReadAsync(device)).GetProperty("id").GetGuid();

        // Every later request says which phone it is, exactly as the app does.
        client.DefaultRequestHeaders.Add("X-Device-Id", deviceId.ToString());

        var account = await client.GetAsync("api/account");
        await ThrowIfFailedAsync(account, "read account");
        var userId = (await ReadAsync(account)).GetProperty("userId").GetGuid();

        return new Phone(client, userId, deviceId, email);
    }

    /// <summary>Links two accounts the way the app does: one shows a code, the other claims it.</summary>
    internal static async Task LinkAsync(Phone showing, Phone claiming)
    {
        var issued = await showing.Client.PostAsJsonAsync("api/pairing/code", new { });
        await ThrowIfFailedAsync(issued, "issue pairing code");
        var code = (await ReadAsync(issued)).GetProperty("code").GetString();

        var claimed = await claiming.Client.PostAsJsonAsync("api/pairing/claim", new { code, proof = "" });
        await ThrowIfFailedAsync(claimed, "claim pairing code");
    }

    internal static async Task<string> PairingCodeAsync(Phone phone)
    {
        var issued = await phone.Client.PostAsJsonAsync("api/pairing/code", new { });
        await ThrowIfFailedAsync(issued, "issue pairing code");
        return (await ReadAsync(issued)).GetProperty("code").GetString()!;
    }

    internal static async Task<JsonElement> ReadAsync(HttpResponseMessage response) =>
        JsonSerializer.Deserialize<JsonElement>(await response.Content.ReadAsStringAsync(), s_json);

    internal static Task<HttpResponseMessage> DeleteAccountAsync(Phone phone) =>
        DeleteAccountAsync(phone, Password);

    /// <summary>A DELETE with a body, which needs building by hand.</summary>
    internal static async Task<HttpResponseMessage> DeleteAccountAsync(Phone phone, string password)
    {
        using var request = new HttpRequestMessage(HttpMethod.Delete, "api/account")
        {
            Content = JsonContent.Create(new { currentPassword = password })
        };

        return await phone.Client.SendAsync(request);
    }

    private static async Task ThrowIfFailedAsync(HttpResponseMessage response, string what)
    {
        if (!response.IsSuccessStatusCode)
        {
            throw new InvalidOperationException(
                $"Could not {what}: {(int)response.StatusCode} {await response.Content.ReadAsStringAsync()}");
        }
    }
}
