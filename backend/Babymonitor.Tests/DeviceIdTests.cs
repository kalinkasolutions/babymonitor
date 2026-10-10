using Babymonitor.Auth;

namespace Babymonitor.Tests;

/// <summary>The phones derive the same id from the same key, so the two must agree byte for byte.</summary>
public sealed class DeviceIdTests
{
    // A P-256 SubjectPublicKeyInfo; the id was computed with openssl, independently of this code.
    private const string FixedPublicKey =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEdmOgVpvF/cEdTUgGwq0NmnP6ogOJkJZ/OFKkQTVx5D1H1bNqEwGSlhcB6O9R/0juTjuKAdHlwOi9E95btJSUJw==";

    private const string FixedDeviceId = "227ffade69852ccfdc929494dc7316cc";

    [Fact]
    public void AFixedKeyGivesTheExpectedId() =>
        Assert.Equal(FixedDeviceId, DeviceId.FromPublicKey(Convert.FromBase64String(FixedPublicKey)));

    [Theory]
    [InlineData(FixedDeviceId, true)]
    [InlineData("227FFADE69852CCFDC929494DC7316CC", false)]
    [InlineData("227ffade69852ccfdc929494dc7316c", false)]
    [InlineData("227ffade69852ccfdc929494dc7316ccc", false)]
    [InlineData("227ffade69852ccfdc929494dc7316cg", false)]
    [InlineData("", false)]
    [InlineData(null, false)]
    public void OnlyThirtyTwoLowercaseHexCharactersAreAnId(string? value, bool expected) =>
        Assert.Equal(expected, DeviceId.IsValid(value));
}
