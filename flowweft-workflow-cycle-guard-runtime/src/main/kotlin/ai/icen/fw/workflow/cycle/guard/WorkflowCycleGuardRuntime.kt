package ai.icen.fw.workflow.cycle.guard

import ai.icen.fw.workflow.runtime.WorkflowRuntimeAction
import ai.icen.fw.workflow.runtime.WorkflowRuntimeAuthorizationDecision
import ai.icen.fw.workflow.runtime.WorkflowRuntimeAuthorizationPort
import ai.icen.fw.workflow.runtime.WorkflowRuntimeAuthorizationRequest
import ai.icen.fw.workflow.runtime.WorkflowRuntimeAuthorizationStatus
import ai.icen.fw.workflow.runtime.WorkflowTrustedCallContext

/**
 * Durable budget guard for loop-like human workflow operations.
 *
 * This component issues no workflow mutation itself. A caller first obtains an ALLOWED durable
 * receipt and then passes its exact record digest/idempotency binding into the existing workflow
 * command. The workflow command still performs its own authorization, version CAS and idempotency.
 */
class WorkflowCycleGuardRuntime(
    private val policyPort: WorkflowCycleBudgetPolicyPort,
    private val authorizationPort: WorkflowRuntimeAuthorizationPort,
    private val persistencePort: WorkflowCycleGuardPersistencePort,
) {
    fun consume(command: WorkflowCycleGuardCommand): WorkflowCycleGuardResult {
        if (!valid(command)) return failure(WorkflowCycleGuardResultCode.UNSUPPORTED, "operation-unsupported")

        val prepare = authorizeCommand(command, WorkflowCycleGuardPhase.PREPARE) ?: return denied()
        val preparePolicyRequest = WorkflowCycleBudgetPolicyRequest.of(
            command,
            WorkflowCycleGuardPhase.PREPARE,
            prepare.evidenceDigest,
        )
        val preparePolicy = resolvePolicy(preparePolicyRequest)
            ?: return failure(WorkflowCycleGuardResultCode.UNSUPPORTED, "policy-unsupported")

        val commit = authorizeCommand(command, WorkflowCycleGuardPhase.COMMIT) ?: return denied()
        if (!sameAuthority(prepare.decision, commit.decision)) {
            return failure(WorkflowCycleGuardResultCode.AUTHORIZATION_DENIED, "authorization-authority-drift")
        }
        val commitPolicyRequest = WorkflowCycleBudgetPolicyRequest.of(
            command,
            WorkflowCycleGuardPhase.COMMIT,
            commit.evidenceDigest,
        )
        val commitPolicy = resolvePolicy(commitPolicyRequest)
            ?: return failure(WorkflowCycleGuardResultCode.UNSUPPORTED, "policy-unsupported")
        if (commitPolicy.contentDigest != preparePolicy.contentDigest) {
            return failure(WorkflowCycleGuardResultCode.UNSUPPORTED, "policy-revision-drift")
        }

        val consumeRequest = WorkflowCycleGuardConsumeRequest.of(
            command,
            commitPolicy,
            commit.evidenceDigest,
        )
        val stored = try {
            persistencePort.consume(consumeRequest)
        } catch (_: RuntimeException) {
            return failure(WorkflowCycleGuardResultCode.OUTCOME_UNKNOWN, "store-outcome-unknown")
        }
        return when (stored.code) {
            WorkflowCycleGuardStoreCode.APPLIED -> validateApplied(command, consumeRequest, stored.record)
            WorkflowCycleGuardStoreCode.REPLAYED -> validateReplay(command, commitPolicy, stored.record)
            WorkflowCycleGuardStoreCode.LIMIT_REACHED ->
                failure(WorkflowCycleGuardResultCode.LIMIT_REACHED, "cycle-budget-exhausted")
            WorkflowCycleGuardStoreCode.POLICY_CONFLICT ->
                failure(WorkflowCycleGuardResultCode.POLICY_CONFLICT, "cycle-policy-conflict")
            WorkflowCycleGuardStoreCode.VERSION_CONFLICT ->
                failure(WorkflowCycleGuardResultCode.VERSION_CONFLICT, "guard-revision-conflict")
            WorkflowCycleGuardStoreCode.IDEMPOTENCY_CONFLICT ->
                failure(WorkflowCycleGuardResultCode.IDEMPOTENCY_CONFLICT, "idempotency-conflict")
            WorkflowCycleGuardStoreCode.OUTCOME_UNKNOWN ->
                failure(WorkflowCycleGuardResultCode.OUTCOME_UNKNOWN, "store-outcome-unknown")
            else -> failure(WorkflowCycleGuardResultCode.RECEIPT_DRIFT, "store-code-unsupported")
        }
    }

    /** Read-only recovery after a prior OUTCOME_UNKNOWN. It never consumes another budget unit. */
    fun reconcile(request: WorkflowCycleGuardReconciliationRequest): WorkflowCycleGuardResult {
        if (!valid(request.command)) {
            return failure(WorkflowCycleGuardResultCode.UNSUPPORTED, "operation-unsupported")
        }
        val prepare = authorizeObserved(
            request.callContext,
            request.command,
            request.requestDigest,
            request.observedAtEpochMilli,
            RECONCILE_ACTION,
            WorkflowCycleGuardPhase.PREPARE,
        ) ?: return denied()
        val commit = authorizeObserved(
            request.callContext,
            request.command,
            request.requestDigest,
            request.observedAtEpochMilli,
            RECONCILE_ACTION,
            WorkflowCycleGuardPhase.COMMIT,
        ) ?: return denied()
        if (!sameAuthority(prepare.decision, commit.decision)) {
            return failure(WorkflowCycleGuardResultCode.AUTHORIZATION_DENIED, "authorization-authority-drift")
        }
        val found = try {
            persistencePort.findReceipt(WorkflowCycleGuardReceiptLookup.of(request.command))
        } catch (_: RuntimeException) {
            return failure(WorkflowCycleGuardResultCode.OUTCOME_UNKNOWN, "receipt-lookup-unavailable")
        }
        return when (found.code) {
            WorkflowCycleGuardLookupCode.FOUND -> {
                val record = found.record
                if (record == null || !record.matchesCommand(request.command)) {
                    failure(WorkflowCycleGuardResultCode.RECEIPT_DRIFT, "receipt-binding-drift")
                } else {
                    WorkflowCycleGuardResult.success(WorkflowCycleGuardResultCode.REPLAYED, record)
                }
            }
            WorkflowCycleGuardLookupCode.NOT_FOUND ->
                failure(WorkflowCycleGuardResultCode.NOT_COMMITTED, "receipt-not-found")
            WorkflowCycleGuardLookupCode.CONFLICT ->
                failure(WorkflowCycleGuardResultCode.IDEMPOTENCY_CONFLICT, "receipt-idempotency-conflict")
            else -> failure(WorkflowCycleGuardResultCode.OUTCOME_UNKNOWN, "receipt-lookup-outcome-unknown")
        }
    }

    /** Authorization-filtered, redacted diagnostics for one exact durable scope. */
    fun diagnose(query: WorkflowCycleGuardDiagnosticQuery): WorkflowCycleGuardDiagnostic {
        if (!WorkflowCycleGuardOperation.supported(query.scope.operation)) {
            return WorkflowCycleGuardDiagnostic.unavailable(WorkflowCycleGuardDiagnosticCode.RECEIPT_DRIFT)
        }
        val prepare = authorizeScope(
            query.callContext,
            query.scope,
            query.requestDigest,
            query.observedAtEpochMilli,
            DIAGNOSE_ACTION,
            WorkflowCycleGuardPhase.PREPARE,
        ) ?: return WorkflowCycleGuardDiagnostic.unavailable(
            WorkflowCycleGuardDiagnosticCode.AUTHORIZATION_DENIED,
        )
        val commit = authorizeScope(
            query.callContext,
            query.scope,
            query.requestDigest,
            query.observedAtEpochMilli,
            DIAGNOSE_ACTION,
            WorkflowCycleGuardPhase.COMMIT,
        ) ?: return WorkflowCycleGuardDiagnostic.unavailable(
            WorkflowCycleGuardDiagnosticCode.AUTHORIZATION_DENIED,
        )
        if (!sameAuthority(prepare.decision, commit.decision)) {
            return WorkflowCycleGuardDiagnostic.unavailable(
                WorkflowCycleGuardDiagnosticCode.AUTHORIZATION_DENIED,
            )
        }
        val loaded = try {
            persistencePort.load(query.scope)
        } catch (_: RuntimeException) {
            return WorkflowCycleGuardDiagnostic.unavailable(WorkflowCycleGuardDiagnosticCode.STORE_UNAVAILABLE)
        }
        if (loaded.code == WorkflowCycleGuardLookupCode.NOT_FOUND) {
            return WorkflowCycleGuardDiagnostic.unavailable(WorkflowCycleGuardDiagnosticCode.NOT_FOUND)
        }
        val loadedRecord = loaded.record
        if (loaded.code != WorkflowCycleGuardLookupCode.FOUND || loadedRecord?.scope != query.scope) {
            return WorkflowCycleGuardDiagnostic.unavailable(
                if (loaded.code == WorkflowCycleGuardLookupCode.OUTCOME_UNKNOWN) {
                    WorkflowCycleGuardDiagnosticCode.STORE_UNAVAILABLE
                } else {
                    WorkflowCycleGuardDiagnosticCode.RECEIPT_DRIFT
                },
            )
        }
        val record = loadedRecord ?: return WorkflowCycleGuardDiagnostic.unavailable(
            WorkflowCycleGuardDiagnosticCode.RECEIPT_DRIFT,
        )
        val remainingCycle = record.maximumPerCycle - record.perCycleCount
        val remainingInstance = record.maximumPerInstance - record.instanceOperationCount
        val code = when {
            remainingCycle == 0 || remainingInstance == 0 -> WorkflowCycleGuardDiagnosticCode.EXHAUSTED
            remainingCycle <= nearLimit(record.maximumPerCycle) ||
                remainingInstance <= nearLimit(record.maximumPerInstance) ->
                WorkflowCycleGuardDiagnosticCode.NEAR_LIMIT
            else -> WorkflowCycleGuardDiagnosticCode.HEALTHY
        }
        return WorkflowCycleGuardDiagnostic.observed(code, record, remainingCycle, remainingInstance)
    }

    private fun authorizeCommand(
        command: WorkflowCycleGuardCommand,
        phase: WorkflowCycleGuardPhase,
    ): AuthorizationEvidence? = authorizeScope(
        command.callContext,
        command.scope,
        command.requestDigest,
        command.requestedAtEpochMilli,
        command.scope.operation.authorizationAction,
        phase,
    )

    private fun authorizeObserved(
        context: WorkflowTrustedCallContext,
        command: WorkflowCycleGuardCommand,
        baseDigest: String,
        observedAt: Long,
        action: WorkflowRuntimeAction,
        phase: WorkflowCycleGuardPhase,
    ): AuthorizationEvidence? = authorizeScope(
        context,
        command.scope,
        baseDigest,
        observedAt,
        action,
        phase,
    )

    private fun authorizeScope(
        context: WorkflowTrustedCallContext,
        scope: WorkflowCycleGuardScope,
        baseDigest: String,
        evaluatedAt: Long,
        action: WorkflowRuntimeAction,
        phase: WorkflowCycleGuardPhase,
    ): AuthorizationEvidence? {
        val exactDigest = WorkflowCycleGuardSupport.sha256(
            "flowweft-workflow-cycle-guard-authorization-request-v1",
            baseDigest,
            scope.scopeDigest,
            action.code,
            phase.code,
            evaluatedAt.toString(),
        )
        val authRequest = WorkflowRuntimeAuthorizationRequest.of(
            context,
            action,
            scope.instanceId,
            scope.definitionId,
            scope.definitionRef,
            scope.subject,
            exactDigest,
            evaluatedAt,
        )
        val decision = try {
            authorizationPort.authorize(authRequest)
        } catch (_: RuntimeException) {
            return null
        }
        if (decision.status != WorkflowRuntimeAuthorizationStatus.AUTHORIZED ||
            !decision.matches(authRequest, evaluatedAt) ||
            decision.authorityRevision.equals("latest", ignoreCase = true)
        ) return null
        return AuthorizationEvidence(decision, authorizationEvidence(decision))
    }

    private fun resolvePolicy(request: WorkflowCycleBudgetPolicyRequest): WorkflowCycleBudgetPolicy? {
        val policy = try {
            policyPort.resolve(request)
        } catch (_: RuntimeException) {
            return null
        } ?: return null
        return policy.takeIf { it.matches(request) }
    }

    private fun validateApplied(
        command: WorkflowCycleGuardCommand,
        request: WorkflowCycleGuardConsumeRequest,
        record: WorkflowCycleGuardRecord?,
    ): WorkflowCycleGuardResult {
        if (record == null || !record.matches(request) ||
            record.guardRevision != command.expectedGuardRevision + 1L
        ) return failure(WorkflowCycleGuardResultCode.RECEIPT_DRIFT, "applied-receipt-drift")
        return WorkflowCycleGuardResult.success(WorkflowCycleGuardResultCode.ALLOWED, record)
    }

    private fun validateReplay(
        command: WorkflowCycleGuardCommand,
        policy: WorkflowCycleBudgetPolicy,
        record: WorkflowCycleGuardRecord?,
    ): WorkflowCycleGuardResult {
        if (record == null || !record.matchesCommandAndPolicy(command, policy)) {
            return failure(WorkflowCycleGuardResultCode.RECEIPT_DRIFT, "replay-receipt-drift")
        }
        return WorkflowCycleGuardResult.success(WorkflowCycleGuardResultCode.REPLAYED, record)
    }

    private fun authorizationEvidence(decision: WorkflowRuntimeAuthorizationDecision): String =
        WorkflowCycleGuardSupport.sha256(
            "flowweft-workflow-cycle-guard-authorization-evidence-v1",
            decision.authorizationId,
            decision.tenantId,
            decision.actor.type,
            decision.actor.id,
            decision.action.code,
            decision.instanceId,
            decision.requestDigest,
            decision.status.code,
            decision.authorityRevision,
            decision.authorityDigest,
            decision.evaluatedAt.toString(),
            decision.validUntil.toString(),
        )

    private fun sameAuthority(
        first: WorkflowRuntimeAuthorizationDecision,
        second: WorkflowRuntimeAuthorizationDecision,
    ): Boolean = first.authorityRevision == second.authorityRevision &&
        first.authorityDigest == second.authorityDigest

    private fun valid(command: WorkflowCycleGuardCommand): Boolean =
        WorkflowCycleGuardOperation.supported(command.scope.operation) &&
            command.callContext.tenantId == command.scope.tenantId

    private fun nearLimit(maximum: Int): Int = maxOf(1, maximum / 5)

    private fun denied(): WorkflowCycleGuardResult = failure(
        WorkflowCycleGuardResultCode.AUTHORIZATION_DENIED,
        "authorization-denied",
    )

    private fun failure(code: WorkflowCycleGuardResultCode, diagnostic: String): WorkflowCycleGuardResult =
        WorkflowCycleGuardResult.failure(code, diagnostic)

    private class AuthorizationEvidence(
        val decision: WorkflowRuntimeAuthorizationDecision,
        val evidenceDigest: String,
    )

    private companion object {
        val RECONCILE_ACTION: WorkflowRuntimeAction = WorkflowRuntimeAction.of("reconcile-cycle-guard")
        val DIAGNOSE_ACTION: WorkflowRuntimeAction = WorkflowRuntimeAction.of("diagnose-cycle-guard")
    }
}
