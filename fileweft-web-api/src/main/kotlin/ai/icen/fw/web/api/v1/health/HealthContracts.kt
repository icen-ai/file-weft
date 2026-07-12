package ai.icen.fw.web.api.v1.health

import ai.icen.fw.web.api.requiredText

/**
 * Minimal liveness projection. UP means only that the HTTP process handled this request;
 * database, storage, connector, and plugin readiness remain the authorized Doctor's job.
 */
class HealthDto(status: String) {
    val status: String = requiredText(status, "Health status", 16)
}
