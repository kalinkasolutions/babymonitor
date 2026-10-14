package ch.kalinka.babymonitor.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** RFC 9457 problem details, which is what the backend returns for every failure. */
@Serializable
data class ProblemDetails(
    val title: String? = null,
    val detail: String? = null,
    val status: Int? = null,
    @SerialName("errors") val errors: Map<String, List<String>>? = null
) {
    fun message(): String =
        errors?.values?.firstOrNull()?.firstOrNull()
            ?: detail
            ?: title
            ?: "Something went wrong."
}

@Serializable
data class IceServersDto(
    val urls: List<String> = emptyList(),
    val username: String = "",
    val credential: String = "",
    val expiresAt: String = ""
)
