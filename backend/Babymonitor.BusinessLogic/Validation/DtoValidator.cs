using System.ComponentModel.DataAnnotations;
using Shared;

namespace Babymonitor.BusinessLogic.Validation;

/// <summary>
/// Validates a DTO's DataAnnotations and surfaces failures through <see cref="OperationResult{T}"/>,
/// so shape validation flows through the same result pipeline as business errors. Call it at the
/// top of a service method; the endpoint turns a <see cref="ResultCode.Validation"/> result into a
/// 400 <c>ValidationProblemDetails</c> via <c>ToHttpResult()</c>.
/// </summary>
public static class DtoValidator
{
    public static OperationResult<Empty> Validate<T>(T dto)
    {
        ArgumentNullException.ThrowIfNull(dto);

        var results = new List<ValidationResult>();
        var context = new ValidationContext(dto);
        if (Validator.TryValidateObject(dto, context, results, validateAllProperties: true))
        {
            return OperationResult<Empty>.Success();
        }

        var errors = results
            .SelectMany(r => r.MemberNames.DefaultIfEmpty(string.Empty),
                (r, member) => (Member: member, Message: r.ErrorMessage ?? "Invalid value"))
            .GroupBy(x => x.Member)
            .ToDictionary(g => g.Key, g => g.Select(x => x.Message).ToArray());

        return OperationResult<Empty>.Validation(errors);
    }
}
