namespace Shared;

public static class ResultCodeExtensions
{
    // Anything that isn't an explicit success is an error, so a future ResultCode added
    // to the enum is treated as a failure by default rather than silently as a success.
    public static bool IsError(this ResultCode code) => code != ResultCode.Success;
}
