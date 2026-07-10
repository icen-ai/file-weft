package com.fileweft.dev.api.security

import com.fileweft.dev.api.config.DevRole

/**
 * Development-only role policy shared by the authorization adapter and the
 * proof-lab UI. The UI receives only this finite capability surface; the
 * server still performs the authoritative authorization check for every call.
 */
object DevRolePolicy {
    private val editorActions = setOf(
        "document:read", "document:create", "document:edit", "document:rename", "document:version:add",
        "document:submit", "document:revise", "file:upload", "document:doctor",
    )
    private val reviewerActions = setOf("document:read", "document:audit", "document:doctor")
    private val viewerActions = setOf("document:read")
    private val proofLabActions = linkedSetOf(
        "document:read", "document:create", "document:rename", "document:version:add", "document:submit",
        "document:revise", "document:audit", "document:doctor", "document:publish", "document:offline",
        "document:archive", "system:outbox:process",
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
