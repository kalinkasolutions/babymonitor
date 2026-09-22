using Shared;

namespace Babyphone.Extensions;

public static class OperationResultExtension
{
    public static IResult ToHttpResult<T>(this OperationResult<T> operationResult)
    {
        return operationResult.ResultCode switch
        {
            ResultCode.Success => operationResult.Value is not null
                ? Results.Ok(operationResult.Value)
                : Results.Ok(),

            ResultCode.NotFound => Results.Problem(
                detail: operationResult.ErrorMessage,
                statusCode: StatusCodes.Status404NotFound
            ),

            ResultCode.Forbidden => Results.Problem(
                detail: operationResult.ErrorMessage,
                statusCode: StatusCodes.Status403Forbidden
            ),

            ResultCode.Validation => Results.ValidationProblem(
                operationResult.ValidationErrors.ToDictionary(e => e.Key, e => e.Value)
            ),

            ResultCode.BadGateway => Results.Problem(
                detail: operationResult.ErrorMessage,
                statusCode: StatusCodes.Status502BadGateway
            ),

            ResultCode.BadRequest => Results.Problem(
                detail: operationResult.ErrorMessage,
                statusCode: StatusCodes.Status400BadRequest
            ),

            ResultCode.Error => Results.Problem(
                detail: operationResult.ErrorMessage,
                statusCode: StatusCodes.Status500InternalServerError
            ),

            _ => throw new ArgumentOutOfRangeException(
                nameof(operationResult),
                operationResult.ResultCode,
                "Unsupported result code"
            ),
        };
    }
}
