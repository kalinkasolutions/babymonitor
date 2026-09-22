using System.Collections.ObjectModel;

namespace Shared;

public sealed class OperationResult<T>
{
    private readonly T? m_value;

    public string? ContentType { get; }
    public string ErrorMessage { get; }
    public ResultCode ResultCode { get; }

    /// <summary>Field-keyed validation messages; empty unless <see cref="ResultCode"/> is <see cref="ResultCode.Validation"/>.</summary>
    public IReadOnlyDictionary<string, string[]> ValidationErrors { get; }

    // Guard on ResultCode rather than a null check: for a value-type T (e.g. OperationResult<Guid>)
    // m_value is never null, so `m_value ?? throw` would silently hand back default(T) on failure.
    public T Value => HasError
        ? throw new InvalidOperationException($"Cannot access Value when operation failed with {ResultCode}: {ErrorMessage}")
        : m_value!;
    public bool HasError => ResultCode.IsError();
    public bool IsSuccess => !HasError;

    private OperationResult(T? value, string? contentType, string errorMessage, ResultCode resultCode,
        IReadOnlyDictionary<string, string[]>? validationErrors = null)
    {
        m_value = value;
        ContentType = contentType;
        ErrorMessage = errorMessage;
        ResultCode = resultCode;
        ValidationErrors = validationErrors ?? ReadOnlyDictionary<string, string[]>.Empty;
    }

    public OperationResult<TNew> MapError<TNew>()
    {
        if (IsSuccess)
        {
            throw new InvalidOperationException("Cannot map error from a successful result");
        }

        return ResultCode switch
        {
            ResultCode.NotFound => OperationResult<TNew>.NotFound(ErrorMessage),
            ResultCode.Forbidden => OperationResult<TNew>.Forbidden(ErrorMessage),
            ResultCode.BadGateway => OperationResult<TNew>.BadGateway(ErrorMessage),
            ResultCode.BadRequest => OperationResult<TNew>.BadRequest(ErrorMessage),
            ResultCode.Validation => OperationResult<TNew>.Validation(ValidationErrors),
            _ => OperationResult<TNew>.Error(ErrorMessage)
        };
    }

    public static OperationResult<Empty> Success()
    {
        return new OperationResult<Empty>(new Empty(), null, string.Empty, ResultCode.Success);
    }

    public static OperationResult<T> Success(T value)
    {
        ArgumentNullException.ThrowIfNull(value);
        return new OperationResult<T>(value, null, string.Empty, ResultCode.Success);
    }

    public static OperationResult<T> NotFound(string message = "Resource not found")
    {
        return new OperationResult<T>(default, null, message, ResultCode.NotFound);
    }

    public static OperationResult<T> Forbidden(string message = "Forbidden")
    {
        return new OperationResult<T>(default, null, message, ResultCode.Forbidden);
    }

    public static OperationResult<T> BadGateway(string message = "Bad gateway")
    {
        return new OperationResult<T>(default, null, message, ResultCode.BadGateway);
    }

    public static OperationResult<T> Error(string message)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(message);
        return new OperationResult<T>(default, null, message, ResultCode.Error);
    }

    public static OperationResult<T> BadRequest(string message)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(message);
        return new OperationResult<T>(default, null, message, ResultCode.BadRequest);
    }

    public static OperationResult<T> Validation(IReadOnlyDictionary<string, string[]> errors)
    {
        ArgumentNullException.ThrowIfNull(errors);
        return new OperationResult<T>(default, null, "One or more validation errors occurred.",
            ResultCode.Validation, errors);
    }
}
