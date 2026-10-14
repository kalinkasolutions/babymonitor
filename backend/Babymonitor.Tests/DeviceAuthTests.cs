using System.Globalization;
using System.Net;
using System.Security.Cryptography;
using Babymonitor.Auth;

namespace Babymonitor.Tests;

/// <summary>The DeviceKey scheme, checked through the TURN endpoint that requires it.</summary>
public sealed class DeviceAuthTests : IClassFixture<BabymonitorApp>
{
    private const string ProtectedPath = "api/turn/credentials";

    private readonly BabymonitorApp m_app;

    public DeviceAuthTests(BabymonitorApp app) => m_app = app;

    [Fact]
    public async Task AGoodSignatureIsAccepted()
    {
        using var phone = new TestPhone();
        using var client = m_app.CreateClientFor(phone);

        using var response = await client.GetAsync(ProtectedPath);

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
    }

    [Fact]
    public async Task NoHeadersAtAllIsRefused()
    {
        using var client = m_app.CreateClient();

        using var response = await client.GetAsync(ProtectedPath);

        Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
    }

    [Fact]
    public async Task ASignatureFromAnotherKeyIsRefused()
    {
        using var phone = new TestPhone();
        using var impostor = new TestPhone();
        var headers = phone.AuthHeaders();
        headers[DeviceKeyAuthenticationHandler.SignatureHeader] =
            impostor.Sign(headers[DeviceKeyAuthenticationHandler.TimeHeader]);

        using var response = await SendAsync(headers);

        Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
    }

    [Fact]
    public async Task ASignatureOverADifferentTimeIsRefused()
    {
        using var phone = new TestPhone();
        var headers = phone.AuthHeaders();
        headers[DeviceKeyAuthenticationHandler.TimeHeader] =
            (long.Parse(headers[DeviceKeyAuthenticationHandler.TimeHeader], CultureInfo.InvariantCulture) + 1)
            .ToString(CultureInfo.InvariantCulture);

        using var response = await SendAsync(headers);

        Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
    }

    [Theory]
    [InlineData(-6)]
    [InlineData(6)]
    public async Task ATimeMoreThanFiveMinutesOffIsRefused(int minutes)
    {
        using var phone = new TestPhone();
        using var client = m_app.CreateClientFor(phone, signedAt: DateTimeOffset.UtcNow.AddMinutes(minutes));

        using var response = await client.GetAsync(ProtectedPath);

        Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
    }

    [Fact]
    public async Task ATimeWithinFiveMinutesIsAccepted()
    {
        using var phone = new TestPhone();
        using var client = m_app.CreateClientFor(phone, signedAt: DateTimeOffset.UtcNow.AddMinutes(-4));

        using var response = await client.GetAsync(ProtectedPath);

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
    }

    [Fact]
    public async Task AKeyOnAnotherCurveIsRefused()
    {
        using var phone = new TestPhone(ECCurve.NamedCurves.nistP384);
        using var client = m_app.CreateClientFor(phone);

        using var response = await client.GetAsync(ProtectedPath);

        Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
    }

    [Fact]
    public async Task AnRsaKeyIsRefused()
    {
        using var phone = new TestPhone();
        using var rsa = RSA.Create(2048);
        var headers = phone.AuthHeaders();
        headers[DeviceKeyAuthenticationHandler.KeyHeader] = Convert.ToBase64String(rsa.ExportSubjectPublicKeyInfo());

        using var response = await SendAsync(headers);

        Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
    }

    [Fact]
    public async Task TheStatusNeedsNoHeaders()
    {
        using var client = m_app.CreateClient();

        using var response = await client.GetAsync("api/status");

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.Equal("""{"ok":true}""", await response.Content.ReadAsStringAsync());
    }

    /// <summary>Apps up to 0.0.6 check a server key here; the server no longer has one.</summary>
    [Fact]
    public async Task TheOldKeyCheckStillAnswers()
    {
        using var client = m_app.CreateClient();
        using var request = new HttpRequestMessage(HttpMethod.Get, "api/status/key");
        request.Headers.Add("X-Server-Key", "whatever-the-old-app-had");

        using var response = await client.SendAsync(request);

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
    }

    private async Task<HttpResponseMessage> SendAsync(Dictionary<string, string> headers)
    {
        using var client = m_app.CreateClient();
        using var request = new HttpRequestMessage(HttpMethod.Get, ProtectedPath);
        foreach (var (name, value) in headers)
        {
            request.Headers.Add(name, value);
        }

        return await client.SendAsync(request);
    }
}
