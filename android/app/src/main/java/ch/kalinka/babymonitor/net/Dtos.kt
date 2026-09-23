package ch.kalinka.babymonitor.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest(val email: String, val password: String)

@Serializable
data class RegisterRequest(val email: String, val username: String, val password: String)

@Serializable
data class LoginResponse(
    val requiresTwoFactor: Boolean = false,
    val username: String = "",
    val isAdmin: Boolean = false
)

@Serializable
data class TwoFactorLoginRequest(val code: String, val rememberMachine: Boolean = true)

@Serializable
data class AuthStatus(
    val authenticated: Boolean = false,
    val username: String = "",
    val isAdmin: Boolean = false
)

/**
 * RFC 9457 problem details, which is what the backend returns for every failure.
 * [errors] is present only on a 400 from DataAnnotations validation.
 */
@Serializable
data class ProblemDetails(
    val title: String? = null,
    val detail: String? = null,
    val status: Int? = null,
    @SerialName("errors") val errors: Map<String, List<String>>? = null
) {
    /** The most specific message the server offered, for showing in the UI. */
    fun message(): String =
        errors?.values?.firstOrNull()?.firstOrNull()
            ?: detail
            ?: title
            ?: "Something went wrong."
}

@Serializable
data class AccountDto(
    val userId: String = "",
    val username: String = "",
    val email: String = "",
    val emailConfirmed: Boolean = false,
    val twoFactorEnabled: Boolean = false,
    val recoveryCodesLeft: Int = 0,
    val memberSince: String = ""
)

@Serializable
data class UpdateUsernameRequest(val username: String)

@Serializable
data class ChangePasswordRequest(val currentPassword: String, val newPassword: String)

@Serializable
data class ChangeEmailRequest(val email: String, val currentPassword: String)

@Serializable
data class PasswordRequest(val currentPassword: String)

@Serializable
data class TwoFactorSetupDto(val sharedKey: String = "", val authenticatorUri: String = "")

@Serializable
data class CodeRequest(val code: String)

@Serializable
data class RecoveryCodesDto(val codes: List<String> = emptyList())

@Serializable
data class InviteRequest(val email: String, val username: String)

@Serializable
data class DeviceDto(
    val id: String = "",
    val name: String = "",
    /** The server's opinion of which phone is asking. Nothing that decides trust reads it. */
    val isThisDevice: Boolean = false,

    /** Whether that phone is connected right now, as of the last thing the server said. */
    val isOnline: Boolean = false,
    val isMine: Boolean = false,
    val ownerId: String = "",
    val ownerName: String = "",
    val keyFingerprint: String = "",
    /** As the backend holds it. Compared against the key scanned off the device's own screen. */
    val publicKey: String = "",
    val lastSeenAt: String? = null,
    val batteryPercent: Int? = null,
    val isCharging: Boolean? = null,
    val createdAt: String = ""
)

@Serializable
data class RegisterDeviceRequest(val name: String, val publicKey: String)

@Serializable
data class HeartbeatRequest(val batteryPercent: Int? = null, val isCharging: Boolean? = null)

@Serializable
data class PairingCodeDto(
    val deviceId: String = "",
    val publicKey: String = "",
    val code: String = "",
    val expiresAt: String = ""
)

@Serializable
data class ClaimPairingRequest(val code: String, val proof: String = "")

@Serializable
data class PairingCompletedDto(
    val showingDeviceId: String = "",
    val claimingDevice: DeviceDto = DeviceDto(),
    val proof: String = ""
)

@Serializable
data class IceServersDto(
    val urls: List<String> = emptyList(),
    val username: String = "",
    val credential: String = "",
    val expiresAt: String = ""
)
