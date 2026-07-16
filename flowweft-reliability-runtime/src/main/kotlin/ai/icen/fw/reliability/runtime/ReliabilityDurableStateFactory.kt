package ai.icen.fw.reliability.runtime

import ai.icen.fw.reliability.api.ReliabilityAction
import ai.icen.fw.reliability.api.ReliabilityBackupCreateRequest
import ai.icen.fw.reliability.api.ReliabilityBackupManifest
import ai.icen.fw.reliability.api.ReliabilityBackupVerifyRequest
import ai.icen.fw.reliability.api.ReliabilityCleanTargetProof
import ai.icen.fw.reliability.api.ReliabilityDrillRequest
import ai.icen.fw.reliability.api.ReliabilityEnvironmentRef
import ai.icen.fw.reliability.api.ReliabilityManifestVerificationReceipt
import ai.icen.fw.reliability.api.ReliabilityOperationAttemptReference
import ai.icen.fw.reliability.api.ReliabilityOperationKind
import ai.icen.fw.reliability.api.ReliabilityOutcomeUnknownReference
import ai.icen.fw.reliability.api.ReliabilityPrincipalRef
import ai.icen.fw.reliability.api.ReliabilityPurpose
import ai.icen.fw.reliability.api.ReliabilityRecoveryObjectiveSet
import ai.icen.fw.reliability.api.ReliabilityResourceRef
import ai.icen.fw.reliability.api.ReliabilityRestoreRequest
import ai.icen.fw.reliability.api.ReliabilityVersionFence

/**
 * The only public construction boundary for restoring runtime aggregates from durable storage.
 *
 * Every method rebuilds the canonical model, reruns all constructor invariants and then compares
 * the resulting digest with the independently persisted digest. Persistence adapters must not use
 * reflection, Java serialization, unsafe allocation, or process-local object caches to restore
 * reliability state.
 */
object ReliabilityDurableStateFactory {
    @JvmStatic
    fun rehydrateIntent(
        kind: ReliabilityOperationKind,
        tenantId: String,
        principal: ReliabilityPrincipalRef,
        purpose: ReliabilityPurpose,
        action: ReliabilityAction,
        resource: ReliabilityResourceRef,
        idempotencyDigest: String,
        argumentDigest: String,
        providerId: String,
        providerRevision: String,
        providerDescriptorDigest: String,
        topologySnapshotDigest: String?,
        objectives: ReliabilityRecoveryObjectiveSet?,
        manifest: ReliabilityBackupManifest?,
        verification: ReliabilityManifestVerificationReceipt?,
        environment: ReliabilityEnvironmentRef,
        cleanTargetProof: ReliabilityCleanTargetProof?,
        versionFence: ReliabilityVersionFence,
        recoveryReferenceEpochMilli: Long?,
        drillId: String?,
        submittedAtEpochMilli: Long,
        executionDeadlineEpochMilli: Long,
        expectedIntentDigest: String,
    ): ReliabilityOperationIntent {
        val restored = ReliabilityOperationIntent.rehydrate(
            kind,
            tenantId,
            principal,
            purpose,
            action,
            resource,
            idempotencyDigest,
            argumentDigest,
            providerId,
            providerRevision,
            providerDescriptorDigest,
            topologySnapshotDigest,
            objectives,
            manifest,
            verification,
            environment,
            cleanTargetProof,
            versionFence,
            recoveryReferenceEpochMilli,
            drillId,
            submittedAtEpochMilli,
            executionDeadlineEpochMilli,
        )
        requireExpectedDigest(expectedIntentDigest, restored.intentDigest, "intent")
        return restored
    }

    @JvmStatic
    fun rehydrateDispatch(
        kind: ReliabilityOperationKind,
        providerId: String,
        providerRevision: String,
        providerDescriptorDigest: String,
        createRequest: ReliabilityBackupCreateRequest?,
        verifyRequest: ReliabilityBackupVerifyRequest?,
        restoreRequest: ReliabilityRestoreRequest?,
        drillRequest: ReliabilityDrillRequest?,
        originalAttempt: ReliabilityOperationAttemptReference,
        expectedDispatchDigest: String,
    ): ReliabilityDispatch {
        val restored = ReliabilityDispatch.rehydrate(
            kind,
            providerId,
            providerRevision,
            providerDescriptorDigest,
            createRequest,
            verifyRequest,
            restoreRequest,
            drillRequest,
            originalAttempt,
        )
        requireExpectedDigest(expectedDispatchDigest, restored.dispatchDigest, "dispatch")
        return restored
    }

    @JvmStatic
    fun rehydrateRun(
        runId: String,
        intent: ReliabilityOperationIntent,
        status: ReliabilityRunStatus,
        version: Long,
        lease: ReliabilityRunLease?,
        dispatch: ReliabilityDispatch?,
        outcomeUnknown: ReliabilityOutcomeUnknownReference?,
        outcome: ReliabilityRunOutcome?,
        failure: ReliabilityRunFailure?,
        cancellationRequested: Boolean,
        createdAtEpochMilli: Long,
        updatedAtEpochMilli: Long,
        expectedStateDigest: String,
    ): ReliabilityRun {
        if (dispatch != null) {
            require(dispatch.kind == intent.kind &&
                dispatch.providerId == intent.providerId &&
                dispatch.providerRevision == intent.providerRevision &&
                dispatch.providerDescriptorDigest == intent.providerDescriptorDigest
            ) { "Reliability durable run dispatch is not bound to its intent." }
        }
        if (outcomeUnknown != null) {
            require(dispatch != null &&
                outcomeUnknown.originalAttempt.attemptDigest == dispatch.originalAttempt.attemptDigest
            ) { "Reliability durable run uncertainty is not bound to its dispatch." }
        }
        val restored = ReliabilityRun.rehydrate(
            runId,
            intent,
            status,
            version,
            lease,
            dispatch,
            outcomeUnknown,
            outcome,
            failure,
            cancellationRequested,
            createdAtEpochMilli,
            updatedAtEpochMilli,
        )
        requireExpectedDigest(expectedStateDigest, restored.stateDigest, "run")
        return restored
    }

    @JvmStatic
    fun rehydrateSloSchedule(
        scheduleId: String,
        tenantId: String,
        policyBindingDigest: String,
        objectiveResource: ReliabilityResourceRef,
        cadenceMillis: Long,
        nextEvaluationAtEpochMilli: Long,
        version: Long,
        lease: ReliabilityRunLease?,
        lastRecord: ReliabilitySloEvaluationRecord?,
        updatedAtEpochMilli: Long,
        expectedStateDigest: String,
    ): ReliabilitySloSchedule {
        val restored = ReliabilitySloSchedule.of(
            scheduleId,
            tenantId,
            policyBindingDigest,
            objectiveResource,
            cadenceMillis,
            nextEvaluationAtEpochMilli,
            version,
            lease,
            lastRecord,
            updatedAtEpochMilli,
        )
        requireExpectedDigest(expectedStateDigest, restored.stateDigest, "SLO schedule")
        return restored
    }

    private fun requireExpectedDigest(expected: String, actual: String, subject: String) {
        val canonical = ReliabilityRuntimeSupport.sha256(
            expected,
            "Reliability durable $subject expected digest is invalid.",
        )
        require(canonical == actual) { "Reliability durable $subject digest does not match persisted state." }
    }
}
