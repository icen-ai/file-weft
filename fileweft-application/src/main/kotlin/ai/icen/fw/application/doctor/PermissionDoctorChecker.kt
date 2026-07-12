package ai.icen.fw.application.doctor

import ai.icen.fw.core.context.DoctorCheckContext
import ai.icen.fw.core.result.DoctorCheckResult
import ai.icen.fw.core.result.DoctorStatus
import ai.icen.fw.spi.authorization.AuthorizationAction
import ai.icen.fw.spi.authorization.AuthorizationEnvironment
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.authorization.AuthorizationRequest
import ai.icen.fw.spi.authorization.AuthorizationResource
import ai.icen.fw.spi.authorization.AuthorizationSubject
import ai.icen.fw.spi.doctor.DoctorChecker
import ai.icen.fw.spi.identity.UserRealmProvider

/** Checks whether the current principal may inspect the requested document. */
class PermissionDoctorChecker(
    private val userRealmProvider: UserRealmProvider,
    private val authorizationProvider: AuthorizationProvider,
) : DoctorChecker {
    override fun name(): String = NAME

    override fun check(context: DoctorCheckContext): DoctorCheckResult {
        val documentId = context.documentId ?: return DoctorCheckResult(
            NAME,
            DoctorStatus.SKIPPED,
            "Permission diagnosis needs a document identifier.",
            repairSuggestion = "Run this checker as part of document diagnosis.",
        )
        val user = userRealmProvider.currentUser() ?: return DoctorCheckResult(
            NAME,
            DoctorStatus.ERROR,
            "No current user is available for permission diagnosis.",
            repairSuggestion = "Configure UserRealmProvider to resolve the authenticated user.",
        )
        val decision = authorizationProvider.authorize(
            AuthorizationRequest(
                subject = AuthorizationSubject(user.id, "USER", user.attributes),
                resource = AuthorizationResource(documentId, "DOCUMENT", context.tenantId),
                action = AuthorizationAction(DOCTOR_ACTION),
                environment = AuthorizationEnvironment(),
            ),
        )
        return if (decision.allowed) {
            DoctorCheckResult(
                NAME,
                DoctorStatus.HEALTHY,
                "The current user is authorized to inspect this document.",
                evidence = mapOf("userId" to user.id.value, "action" to DOCTOR_ACTION),
            )
        } else {
            DoctorCheckResult(
                NAME,
                DoctorStatus.ERROR,
                decision.reason ?: "The current user is not authorized to inspect this document.",
                evidence = mapOf("userId" to user.id.value, "action" to DOCTOR_ACTION),
                repairSuggestion = "Grant document:doctor permission to the current user or use an authorized account.",
            )
        }
    }

    companion object {
        const val NAME = "permission"
        const val DOCTOR_ACTION = "document:doctor"
    }
}
