package ai.icen.fw.agent.web.runtime

import ai.icen.fw.agent.api.AgentApprovalDecision
import ai.icen.fw.agent.api.AgentApprovalRequest
import ai.icen.fw.agent.api.AgentCancellation
import ai.icen.fw.agent.api.AgentEvaluationProviderSnapshot
import ai.icen.fw.agent.api.AgentEvaluationSuite
import ai.icen.fw.agent.api.AgentRunRequest
import ai.icen.fw.agent.api.AgentRunEvent
import ai.icen.fw.agent.api.AgentRunSnapshot
import ai.icen.fw.agent.api.ProviderId
import ai.icen.fw.agent.evaluation.AgentEvaluationEvaluatorReference
import ai.icen.fw.agent.evaluation.AgentEvaluationRegressionReport
import ai.icen.fw.agent.runtime.AgentEvaluationRunRequest
import ai.icen.fw.agent.runtime.AgentEvaluationRunState
import ai.icen.fw.agent.runtime.AgentRunCommandContext
import ai.icen.fw.agent.web.api.AgentWebDoctorCheckDto
import ai.icen.fw.agent.web.api.AgentWebDoctorReportDto
import ai.icen.fw.agent.web.api.AgentWebPageQuery
import ai.icen.fw.agent.web.api.AgentWebProviderCapabilityDto
import ai.icen.fw.agent.web.api.AgentWebTrustedContext
import ai.icen.fw.core.id.Identifier

fun interface AgentWebRuntimeClock {
    fun currentTimeMillis(): Long
}

fun interface AgentWebRuntimeIdGenerator {
    fun nextId(purpose: String): Identifier
}

class AgentWebAuthorizationAction(value: String) {
    val value: String = webRuntimeCode(value, "Agent Web authorization action")

    override fun equals(other: Any?): Boolean = other is AgentWebAuthorizationAction && value == other.value
    override fun hashCode(): Int = value.hashCode()
    override fun toString(): String = value

    companion object {
        @JvmField val CONVERSATION_CREATE = AgentWebAuthorizationAction("agent.conversation.create")
        @JvmField val CONVERSATION_READ = AgentWebAuthorizationAction("agent.conversation.read")
        @JvmField val RUN_CREATE = AgentWebAuthorizationAction("agent.run.create")
        @JvmField val RUN_READ = AgentWebAuthorizationAction("agent.run.read")
        @JvmField val RUN_CANCEL = AgentWebAuthorizationAction("agent.run.cancel")
        @JvmField val MESSAGE_READ = AgentWebAuthorizationAction("agent.message.read")
        @JvmField val EVENT_READ = AgentWebAuthorizationAction("agent.event.read")
        @JvmField val CITATION_READ = AgentWebAuthorizationAction("agent.citation.read")
        @JvmField val CONFIRMATION_READ = AgentWebAuthorizationAction("agent.confirmation.read")
        @JvmField val CONFIRMATION_APPROVE = AgentWebAuthorizationAction("agent.confirmation.approve")
        @JvmField val CONFIRMATION_REJECT = AgentWebAuthorizationAction("agent.confirmation.reject")
        @JvmField val PROVIDER_READ = AgentWebAuthorizationAction("agent.provider.read")
        @JvmField val CONFIG_READ = AgentWebAuthorizationAction("agent.config.read")
        @JvmField val CONFIG_WRITE = AgentWebAuthorizationAction("agent.config.write")
        @JvmField val DOCTOR_READ = AgentWebAuthorizationAction("agent.doctor.read")
        @JvmField val EVALUATION_READ = AgentWebAuthorizationAction("agent.evaluation.read")
        @JvmField val EVALUATION_TRIGGER = AgentWebAuthorizationAction("agent.evaluation.trigger")
    }
}

/** Exact resource and revision evaluated by the authoritative host authorization provider. */
class AgentWebAuthorizationTarget @JvmOverloads constructor(
    resourceType: String,
    resourceId: Identifier,
    resourceRevision: String = "current",
    purpose: String = "interactive-agent-web",
) {
    val resourceType: String = webRuntimeCode(resourceType, "Agent Web authorization resource type")
    val resourceId: Identifier = resourceId
    val resourceRevision: String = webRuntimeToken(resourceRevision, "Agent Web authorization resource revision")
    val purpose: String = webRuntimeToken(purpose, "Agent Web authorization purpose")
}

class AgentWebAuthorizationRequest(
    requestId: Identifier,
    val context: AgentWebTrustedContext,
    val action: AgentWebAuthorizationAction,
    val target: AgentWebAuthorizationTarget,
    val requestedAt: Long,
    val expiresAt: Long,
) {
    val requestId: Identifier = requestId
    val bindingDigest: String

    init {
        context.requireFresh(requestedAt)
        require(expiresAt > requestedAt && expiresAt <= context.authorizationExpiresAt) {
            "Agent Web authorization request lifetime is invalid."
        }
        bindingDigest = AgentWebRuntimeDigest("flowweft.agent.web.runtime.authorization-request.v1")
            .add(context.trustedContextDigest)
            .add(action.value)
            .add(target.resourceType)
            .add(target.resourceId.value)
            .add(target.resourceRevision)
            .add(target.purpose)
            .add(requestedAt)
            .add(expiresAt)
            .finish()
    }

    override fun toString(): String = "AgentWebAuthorizationRequest(action=$action, <redacted>)"
}

enum class AgentWebAuthorizationOutcome {
    ALLOW,
    DENY,
}

class AgentWebAuthorizationDecision private constructor(
    decisionId: Identifier,
    val providerId: ProviderId,
    request: AgentWebAuthorizationRequest,
    val outcome: AgentWebAuthorizationOutcome,
    authorizationRevision: String,
    evidenceDigest: String,
    val decidedAt: Long,
    val expiresAt: Long,
    reasonCode: String?,
) {
    val decisionId: Identifier = decisionId
    val requestId: Identifier = request.requestId
    val requestBindingDigest: String = request.bindingDigest
    val authorizationRevision: String = webRuntimeToken(
        authorizationRevision,
        "Agent Web authorization revision",
    )
    val evidenceDigest: String = webRuntimeDigest(evidenceDigest, "Agent Web authorization evidence digest")
    val reasonCode: String? = reasonCode?.let { webRuntimeCode(it, "Agent Web authorization reason") }

    init {
        require(decidedAt >= request.requestedAt && decidedAt < request.expiresAt &&
            expiresAt > decidedAt && expiresAt <= request.expiresAt
        ) { "Agent Web authorization decision lifetime is invalid." }
        require(outcome != AgentWebAuthorizationOutcome.DENY || this.reasonCode != null) {
            "Denied Agent Web authorization requires a reason code."
        }
    }

    fun requireAllowedFor(request: AgentWebAuthorizationRequest, atTime: Long) {
        require(requestId == request.requestId && requestBindingDigest == request.bindingDigest) {
            "Agent Web authorization decision binding changed."
        }
        require(authorizationRevision == request.context.authorizationRevision) {
            "Agent Web authorization revision is stale."
        }
        require(atTime >= decidedAt && atTime < expiresAt) {
            "Agent Web authorization decision is no longer current."
        }
        if (outcome != AgentWebAuthorizationOutcome.ALLOW) throw AgentWebHiddenException()
    }

    companion object {
        @JvmStatic
        fun allow(
            decisionId: Identifier,
            providerId: ProviderId,
            request: AgentWebAuthorizationRequest,
            authorizationRevision: String,
            evidenceDigest: String,
            decidedAt: Long,
            expiresAt: Long,
        ): AgentWebAuthorizationDecision = AgentWebAuthorizationDecision(
            decisionId, providerId, request, AgentWebAuthorizationOutcome.ALLOW,
            authorizationRevision, evidenceDigest, decidedAt, expiresAt, null,
        )

        @JvmStatic
        fun deny(
            decisionId: Identifier,
            providerId: ProviderId,
            request: AgentWebAuthorizationRequest,
            authorizationRevision: String,
            evidenceDigest: String,
            decidedAt: Long,
            expiresAt: Long,
            reasonCode: String,
        ): AgentWebAuthorizationDecision = AgentWebAuthorizationDecision(
            decisionId, providerId, request, AgentWebAuthorizationOutcome.DENY,
            authorizationRevision, evidenceDigest, decidedAt, expiresAt, reasonCode,
        )
    }
}

interface AgentWebAuthoritativeAuthorizationPort {
    fun providerId(): ProviderId
    fun authorize(request: AgentWebAuthorizationRequest): AgentWebAuthorizationDecision
}

/** Persistence scope passed only after fresh authoritative authorization succeeds. */
class AgentWebAuthorizedPersistenceScope private constructor(
    val tenantId: Identifier,
    val principalId: Identifier,
    val principalType: String,
    authorizationRevision: String,
    val authorizationDecisionId: Identifier,
    authorizationEvidenceDigest: String,
    val action: AgentWebAuthorizationAction,
    val target: AgentWebAuthorizationTarget,
    requestBindingDigest: String,
    val authorizedAt: Long,
    val authorizationExpiresAt: Long,
) {
    val authorizationRevision: String = webRuntimeToken(
        authorizationRevision,
        "Agent Web persistence authorization revision",
    )
    val authorizationEvidenceDigest: String = webRuntimeDigest(
        authorizationEvidenceDigest,
        "Agent Web persistence authorization evidence",
    )
    val requestBindingDigest: String = webRuntimeDigest(
        requestBindingDigest,
        "Agent Web persistence authorization request binding",
    )

    init {
        require(authorizedAt >= 0L && authorizationExpiresAt > authorizedAt) {
            "Agent Web persistence authorization lifetime is invalid."
        }
    }

    fun requireCurrent(atTime: Long) {
        require(atTime >= authorizedAt && atTime < authorizationExpiresAt) {
            "Agent Web persistence authorization is no longer current."
        }
    }

    fun requireExact(
        expectedAction: AgentWebAuthorizationAction,
        expectedResourceType: String,
        expectedResourceId: Identifier,
        expectedResourceRevision: String,
        expectedPurpose: String,
    ) {
        require(action == expectedAction && target.resourceType == expectedResourceType &&
            target.resourceId == expectedResourceId &&
            target.resourceRevision == expectedResourceRevision && target.purpose == expectedPurpose
        ) { "Agent Web persistence scope does not match the authorized action and target." }
    }

    fun requireExact(expectedAction: AgentWebAuthorizationAction, expectedTarget: AgentWebAuthorizationTarget) {
        requireExact(
            expectedAction,
            expectedTarget.resourceType,
            expectedTarget.resourceId,
            expectedTarget.resourceRevision,
            expectedTarget.purpose,
        )
    }

    companion object {
        internal fun authorized(
            context: AgentWebTrustedContext,
            request: AgentWebAuthorizationRequest,
            decision: AgentWebAuthorizationDecision,
            atTime: Long,
        ): AgentWebAuthorizedPersistenceScope {
            require(context.trustedContextDigest == request.context.trustedContextDigest &&
                context.tenantId == request.context.tenantId &&
                context.principalId == request.context.principalId &&
                context.principalType == request.context.principalType &&
                context.authorizationRevision == request.context.authorizationRevision
            ) { "Agent Web persistence context does not match its authorization request." }
            context.requireFresh(atTime)
            decision.requireAllowedFor(request, atTime)
            return AgentWebAuthorizedPersistenceScope(
                context.tenantId,
                context.principalId,
                context.principalType,
                decision.authorizationRevision,
                decision.decisionId,
                decision.evidenceDigest,
                request.action,
                request.target,
                request.bindingDigest,
                atTime,
                decision.expiresAt,
            )
        }
    }
}

fun interface AgentWebTransactionWork<T> {
    fun execute(): T
}

/** All enlisted repositories and the outbox must share this transaction boundary. */
interface AgentWebTransactionBoundary {
    fun <T> inTransaction(work: AgentWebTransactionWork<T>): T
}

enum class AgentWebUseCaseOutcome {
    SUCCEEDED,
    REJECTED,
    NOT_FOUND,
    CONFLICT,
    OUTCOME_UNKNOWN,
}

/** Typed, provider-payload-free result for calls made strictly outside a database transaction. */
class AgentWebUseCaseResult<T> private constructor(
    val outcome: AgentWebUseCaseOutcome,
    val value: T?,
    diagnosticCode: String?,
) {
    val diagnosticCode: String? = diagnosticCode?.let { webRuntimeCode(it, "Agent Web use-case diagnostic") }

    init {
        require((outcome == AgentWebUseCaseOutcome.SUCCEEDED) == (value != null)) {
            "Agent Web use-case result value does not match its outcome."
        }
        require((outcome == AgentWebUseCaseOutcome.SUCCEEDED) == (this.diagnosticCode == null)) {
            "Agent Web use-case diagnostics do not match its outcome."
        }
    }

    companion object {
        @JvmStatic fun <T> success(value: T): AgentWebUseCaseResult<T> =
            AgentWebUseCaseResult(AgentWebUseCaseOutcome.SUCCEEDED, value, null)
        @JvmStatic fun <T> rejected(code: String): AgentWebUseCaseResult<T> =
            AgentWebUseCaseResult(AgentWebUseCaseOutcome.REJECTED, null, code)
        @JvmStatic fun <T> missing(code: String): AgentWebUseCaseResult<T> =
            AgentWebUseCaseResult(AgentWebUseCaseOutcome.NOT_FOUND, null, code)
        @JvmStatic fun <T> conflict(code: String): AgentWebUseCaseResult<T> =
            AgentWebUseCaseResult(AgentWebUseCaseOutcome.CONFLICT, null, code)
        @JvmStatic fun <T> outcomeUnknown(code: String): AgentWebUseCaseResult<T> =
            AgentWebUseCaseResult(AgentWebUseCaseOutcome.OUTCOME_UNKNOWN, null, code)
    }
}

/** Minimal bridge to the existing durable Agent runtime/application service; no repository is exposed. */
class AgentWebRunUseCaseEvent(
    val event: AgentRunEvent,
    val stateVersion: Long,
) {
    init {
        require(stateVersion >= 0L) { "Agent Web run use-case event version is invalid." }
    }
}

class AgentWebRunUseCaseReceipt(
    val snapshot: AgentRunSnapshot,
    events: Collection<AgentWebRunUseCaseEvent>,
) {
    val events: List<AgentWebRunUseCaseEvent> = immutableRuntimeList(
        events,
        MAX_WEB_RUNTIME_OUTBOX_EVENTS,
        "Agent Web run use-case events",
    )

    init {
        require(this.events.isNotEmpty() && this.events.all { item ->
            item.event.runId == snapshot.runId && item.event.tenantId == snapshot.tenantId &&
                item.stateVersion <= snapshot.stateVersion
        }) { "Agent Web run use-case receipt does not match its canonical snapshot." }
    }
}

interface AgentWebRunUseCasePort {
    fun start(request: AgentRunRequest): AgentWebUseCaseResult<AgentWebRunUseCaseReceipt>

    fun cancel(
        context: AgentRunCommandContext,
        runId: Identifier,
        expectedStateVersion: Long,
        cancellation: AgentCancellation,
    ): AgentWebUseCaseResult<AgentWebRunUseCaseReceipt>
}

/** Minimal bridge to DurableAgentRunCoordinator.confirmApproval or an equivalent host use case. */
fun interface AgentWebApprovalUseCasePort {
    fun decide(
        context: AgentRunCommandContext,
        expectedRunStateVersion: Long,
        decision: AgentApprovalDecision,
    ): AgentWebUseCaseResult<AgentWebRunUseCaseReceipt>
}

/** Minimal bridge to the existing durable evaluation coordinator. */
interface AgentWebEvaluationUseCasePort {
    fun trigger(request: AgentEvaluationRunRequest): AgentWebUseCaseResult<AgentEvaluationRunState>
}

interface AgentWebProviderCapabilityInventoryPort {
    fun list(
        scope: AgentWebAuthorizedPersistenceScope,
        query: AgentWebPageQuery,
    ): AgentWebStoredPage<AgentWebProviderCapabilityDto>

    fun find(
        scope: AgentWebAuthorizedPersistenceScope,
        providerId: ProviderId,
    ): AgentWebProviderCapabilityDto?
}

class AgentWebProviderReferenceBinding(
    referenceId: Identifier,
    val tenantId: Identifier,
    val providerId: ProviderId,
    val enabled: Boolean,
    revision: String,
) {
    val referenceId: Identifier = referenceId
    val revision: String = webRuntimeToken(revision, "Agent Web provider reference revision")
}

interface AgentWebProviderReferenceInventoryPort {
    fun connection(referenceId: Identifier): AgentWebProviderReferenceBinding?
    fun credential(referenceId: Identifier): AgentWebProviderReferenceBinding?
}

interface AgentWebEvaluationCatalogPort {
    fun list(
        scope: AgentWebAuthorizedPersistenceScope,
        query: AgentWebPageQuery,
    ): AgentWebStoredPage<AgentEvaluationSuite>

    fun dataset(reference: ai.icen.fw.agent.evaluation.AgentEvaluationDatasetReference): AgentEvaluationSuite?
    fun provider(providerId: ProviderId): AgentEvaluationProviderSnapshot?
    fun evaluator(reference: AgentEvaluationEvaluatorReference): AgentEvaluationEvaluatorReference?
}

interface AgentWebDoctorPort {
    /** Local/value-free only: implementations return codes and aggregate status, never values or exceptions. */
    fun checks(atTime: Long): List<AgentWebDoctorCheckDto>
    fun report(checks: List<AgentWebDoctorCheckDto>, atTime: Long): AgentWebDoctorReportDto
}

/** Optional result projection source populated by the existing evaluation coordinator/worker. */
fun interface AgentWebEvaluationResultProjectionPort {
    fun result(tenantId: Identifier, evaluationId: Identifier): AgentEvaluationRegressionReport?
}
