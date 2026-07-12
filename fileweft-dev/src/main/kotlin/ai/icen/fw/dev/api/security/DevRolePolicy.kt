package ai.icen.fw.dev.api.security

import ai.icen.fw.dev.api.config.DevRole

/**
 * Development-only role policy shared by the authorization adapter and the
 * proof-lab UI. The UI receives only this finite capability surface; the
 * server still performs the authoritative authorization check for every call.
 */
object DevRolePolicy {
    private val editorActions = setOf(
        "document:read", "document:create", "document:edit", "document:rename", "document:version:add",
        "document:submit", "document:revise", "document:restore", "document:download", "file:upload", "document:doctor",
    )
    private val reviewerActions = setOf("document:read", "document:download", "document:audit", "document:doctor", "agent:suggestion:read")
    private val viewerActions = setOf("document:read", "document:download")
    private val proofLabActions = linkedSetOf(
        "document:read", "document:download", "document:create", "document:edit", "file:upload", "document:rename", "document:version:add", "document:submit",
        "document:revise", "document:restore", "document:audit", "document:doctor", "document:publish", "document:offline",
        "document:archive", "system:outbox:process", "system:task:process",
        "system:doctor:read",
        "document:delivery:retry", "agent:suggestion:read", "agent:suggestion:confirm", "system:upload:cleanup", "file:upload:maintenance",
    )

    fun allows(role: DevRole, action: String): Boolean = role == DevRole.ADMIN || action in actionsFor(role)

    fun proofLabPermissions(role: DevRole): List<String> = proofLabActions.filter { allows(role, it) }

    private fun actionsFor(role: DevRole): Set<String> = when (role) {
        DevRole.ADMIN -> proofLabActions
        DevRole.EDITOR -> editorActions
        DevRole.REVIEWER -> reviewerActions
        DevRole.VIEWER -> viewerActions
    }
}
