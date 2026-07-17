package ai.icen.fw.spi.connector

import ai.icen.fw.core.id.Identifier

/**
 * A read-only reconciliation request for an external identity previously
 * returned by [FileConnector.sync]. The opaque [externalId] remains the
 * authority supplied by the FlowWeft delivery target; providers must bind it
 * to the exact tenant and business resource again before any remote call.
 */
class ConnectorStatusRequest(
    val tenantId: Identifier,
    val businessId: Identifier,
    externalId: String,
    val invocation: ConnectorInvocation,
) {
    val externalId: String = validateExternalId(externalId)
}

/**
 * Provider-neutral state visible at the connector boundary.
 *
 * [API_ABSENT] means only that the provider API no longer exposes the
 * resource. It never proves physical erasure of indexes, replicas, backups,
 * caches, or provider-side asynchronous work.
 */
enum class ConnectorExternalState {
    PROCESSING,
    AVAILABLE,
    FAILED,
    REMOVAL_ACCEPTED,
    API_ABSENT,
    UNKNOWN,
}

/** Whether the status query itself produced authoritative connector evidence. */
enum class ConnectorStatusQueryStatus {
    SUCCESS,
    RETRYABLE_FAILURE,
    PERMANENT_FAILURE,
}

/**
 * A bounded, credential-free status result. Provider response bodies and raw
 * exceptions must never be copied into [message]. Query failures always use
 * [ConnectorExternalState.UNKNOWN] so callers cannot mistake stale state for
 * current evidence.
 */
class ConnectorStatusResult @JvmOverloads constructor(
    val queryStatus: ConnectorStatusQueryStatus,
    val state: ConnectorExternalState,
    message: String? = null,
) {
    val message: String? = message?.let(::validateDiagnostic)

    init {
        require(queryStatus == ConnectorStatusQueryStatus.SUCCESS || state == ConnectorExternalState.UNKNOWN) {
            "A failed connector status query must report UNKNOWN external state."
        }
    }

    companion object {
        const val MAX_DIAGNOSTIC_UTF16_LENGTH: Int = 512
    }
}

/**
 * Optional additive capability for connectors that can reconcile a persisted
 * external identity. It is deliberately separate from [FileConnector] so the
 * released connector ABI remains unchanged.
 */
interface FileConnectorStatusProvider {
    fun status(request: ConnectorStatusRequest): ConnectorStatusResult
}

private fun validateExternalId(value: String): String {
    require(value.isNotBlank()) { "External identifier must not be blank." }
    require(value.length <= ConnectorSyncResult.MAX_EXTERNAL_ID_UTF16_LENGTH) {
        "External identifier must not exceed ${ConnectorSyncResult.MAX_EXTERNAL_ID_UTF16_LENGTH} UTF-16 code units."
    }
    require(value.none { character ->
        Character.isISOControl(character) || Character.getType(character) == Character.FORMAT.toInt()
    }) {
        "External identifier must not contain unsafe characters."
    }
    require(isWellFormedUtf16(value)) { "External identifier must contain well-formed Unicode." }
    return value
}

private fun validateDiagnostic(value: String): String {
    require(value.isNotBlank()) { "Connector status diagnostic must not be blank when provided." }
    require(value.length <= ConnectorStatusResult.MAX_DIAGNOSTIC_UTF16_LENGTH) {
        "Connector status diagnostic must not exceed ${ConnectorStatusResult.MAX_DIAGNOSTIC_UTF16_LENGTH} UTF-16 code units."
    }
    require(value.none { character ->
        Character.isISOControl(character) || Character.getType(character) == Character.FORMAT.toInt()
    }) {
        "Connector status diagnostic must not contain unsafe characters."
    }
    require(isWellFormedUtf16(value)) { "Connector status diagnostic must contain well-formed Unicode." }
    return value
}

private fun isWellFormedUtf16(value: String): Boolean {
    var index = 0
    while (index < value.length) {
        val character = value[index]
        when {
            Character.isHighSurrogate(character) -> {
                if (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1])) return false
                index += 2
            }
            Character.isLowSurrogate(character) -> return false
            else -> index++
        }
    }
    return true
}
