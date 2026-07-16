package ai.icen.fw.reliability.runtime

import ai.icen.fw.reliability.api.ReliabilityAction
import ai.icen.fw.reliability.api.ReliabilityBackupCreateRequest
import ai.icen.fw.reliability.api.ReliabilityBackupManifest
import ai.icen.fw.reliability.api.ReliabilityBackupVerifyRequest
import ai.icen.fw.reliability.api.ReliabilityCallContext
import ai.icen.fw.reliability.api.ReliabilityCleanTargetProof
import ai.icen.fw.reliability.api.ReliabilityDrillRequest
import ai.icen.fw.reliability.api.ReliabilityEnvironmentRef
import ai.icen.fw.reliability.api.ReliabilityManifestVerificationReceipt
import ai.icen.fw.reliability.api.ReliabilityOperationKind
import ai.icen.fw.reliability.api.ReliabilityPrincipalRef
import ai.icen.fw.reliability.api.ReliabilityProviderDescriptor
import ai.icen.fw.reliability.api.ReliabilityPurpose
import ai.icen.fw.reliability.api.ReliabilityRecoveryObjectiveSet
import ai.icen.fw.reliability.api.ReliabilityResourceRef
import ai.icen.fw.reliability.api.ReliabilityRestoreRequest
import ai.icen.fw.reliability.api.ReliabilityVersionFence

class ReliabilityAuthorizedCallFactory(
    private val authorization: ReliabilityRuntimeAuthorizationPort,
    private val identifiers: ReliabilityRuntimeIdPort,
) {
    fun create(
        invocation: ReliabilityTrustedInvocation,
        operationCode: String,
        argumentDigest: String,
        boundIdempotencyDigest: String = invocation.idempotencyDigest,
    ): ReliabilityCallContext {
        val seed = ReliabilityRuntimeSupport.digest("flowweft-reliability-runtime-call-seed-v1")
            .text(invocation.invocationDigest)
            .text(operationCode)
            .text(argumentDigest)
            .text(boundIdempotencyDigest)
            .finish()
        val requestId = ReliabilityRuntimeSupport.opaque(
            identifiers.nextId(
                ReliabilityRuntimeIdRequest.of(ReliabilityRuntimeIdKind.REQUEST, invocation.tenantId, seed, 0),
            ),
            "Reliability runtime id provider returned an invalid request id.",
        )
        val request = ReliabilityRuntimeAuthorizationRequest.of(
            invocation, operationCode, argumentDigest, requestId,
        )
        val snapshot = authorization.authorize(request)
        return ReliabilityCallContext.of(
            requestId,
            invocation.tenantId,
            invocation.principal,
            invocation.purpose,
            invocation.action,
            invocation.resource,
            snapshot,
            ReliabilityRuntimeSupport.sha256(
                boundIdempotencyDigest, "Reliability runtime bound idempotency digest is invalid.",
            ),
            invocation.requestedAtEpochMilli,
            invocation.deadlineEpochMilli,
        )
    }
}

class ReliabilityCreateCommand private constructor(
    val invocation: ReliabilityTrustedInvocation,
    val source: ReliabilityEnvironmentRef,
    val sourceVersionFence: ReliabilityVersionFence,
    providerId: String,
    val executionDeadlineEpochMilli: Long,
) {
    val providerId: String = ReliabilityRuntimeSupport.code(providerId, "Reliability provider id is invalid.")

    init {
        require(invocation.purpose == ReliabilityPurpose.CREATE_BACKUP &&
            invocation.action == ReliabilityAction.CREATE_BACKUP &&
            invocation.tenantId == source.tenantId && invocation.resource == source.resource &&
            sourceVersionFence.resource == source.resource
        ) { "Reliability create command is not bound to its exact source." }
        require(executionDeadlineEpochMilli > invocation.requestedAtEpochMilli &&
            executionDeadlineEpochMilli - invocation.requestedAtEpochMilli <=
            ReliabilityBackupCreateRequest.MAX_ASYNC_EXECUTION_MILLIS
        ) { "Reliability create command execution deadline is invalid." }
    }

    companion object {
        @JvmStatic
        fun of(
            invocation: ReliabilityTrustedInvocation,
            source: ReliabilityEnvironmentRef,
            sourceVersionFence: ReliabilityVersionFence,
            providerId: String,
            executionDeadlineEpochMilli: Long,
        ): ReliabilityCreateCommand = ReliabilityCreateCommand(
            invocation, source, sourceVersionFence, providerId, executionDeadlineEpochMilli,
        )
    }
}

class ReliabilityVerifyCommand private constructor(
    val invocation: ReliabilityTrustedInvocation,
    val manifest: ReliabilityBackupManifest,
    val manifestVersionFence: ReliabilityVersionFence,
    providerId: String,
    val executionDeadlineEpochMilli: Long,
) {
    val providerId: String = ReliabilityRuntimeSupport.code(providerId, "Reliability provider id is invalid.")

    init {
        require(invocation.purpose == ReliabilityPurpose.VERIFY_BACKUP &&
            invocation.action == ReliabilityAction.VERIFY_BACKUP &&
            invocation.tenantId == manifest.content.sourceEnvironment.tenantId &&
            invocation.resource == manifest.resource && manifestVersionFence.resource == manifest.resource
        ) { "Reliability verify command is not bound to its exact manifest." }
        require(executionDeadlineEpochMilli > invocation.requestedAtEpochMilli &&
            executionDeadlineEpochMilli - invocation.requestedAtEpochMilli <=
            ReliabilityBackupCreateRequest.MAX_ASYNC_EXECUTION_MILLIS
        ) { "Reliability verify command execution deadline is invalid." }
    }

    companion object {
        @JvmStatic
        @JvmOverloads
        fun of(
            invocation: ReliabilityTrustedInvocation,
            manifest: ReliabilityBackupManifest,
            manifestVersionFence: ReliabilityVersionFence,
            providerId: String,
            executionDeadlineEpochMilli: Long = defaultExecutionDeadline(invocation.requestedAtEpochMilli),
        ): ReliabilityVerifyCommand = ReliabilityVerifyCommand(
            invocation, manifest, manifestVersionFence, providerId, executionDeadlineEpochMilli,
        )

        private fun defaultExecutionDeadline(startedAtEpochMilli: Long): Long =
            if (Long.MAX_VALUE - startedAtEpochMilli < ReliabilityBackupCreateRequest.MAX_ASYNC_EXECUTION_MILLIS) {
                Long.MAX_VALUE
            } else {
                startedAtEpochMilli + ReliabilityBackupCreateRequest.MAX_ASYNC_EXECUTION_MILLIS
            }
    }
}

class ReliabilityRestoreCommand private constructor(
    val invocation: ReliabilityTrustedInvocation,
    val manifest: ReliabilityBackupManifest,
    val verification: ReliabilityManifestVerificationReceipt,
    val target: ReliabilityEnvironmentRef,
    val cleanTargetProof: ReliabilityCleanTargetProof,
    val targetVersionFence: ReliabilityVersionFence,
    val recoveryReferenceEpochMilli: Long,
    providerId: String,
    val executionDeadlineEpochMilli: Long,
) {
    val providerId: String = ReliabilityRuntimeSupport.code(providerId, "Reliability provider id is invalid.")

    init {
        require(invocation.purpose == ReliabilityPurpose.RESTORE &&
            invocation.action == ReliabilityAction.RESTORE_CLEAN_TARGET &&
            invocation.tenantId == target.tenantId && invocation.resource == target.resource &&
            manifest.content.sourceEnvironment.tenantId == target.tenantId &&
            targetVersionFence.resource == target.resource && cleanTargetProof.target == target
        ) { "Reliability restore command is not bound to its exact manifest and target." }
        require(executionDeadlineEpochMilli > invocation.requestedAtEpochMilli &&
            executionDeadlineEpochMilli - invocation.requestedAtEpochMilli <=
            ReliabilityBackupCreateRequest.MAX_ASYNC_EXECUTION_MILLIS
        ) { "Reliability restore command execution deadline is invalid." }
    }

    companion object {
        @JvmStatic
        fun of(
            invocation: ReliabilityTrustedInvocation,
            manifest: ReliabilityBackupManifest,
            verification: ReliabilityManifestVerificationReceipt,
            target: ReliabilityEnvironmentRef,
            cleanTargetProof: ReliabilityCleanTargetProof,
            targetVersionFence: ReliabilityVersionFence,
            recoveryReferenceEpochMilli: Long,
            providerId: String,
            executionDeadlineEpochMilli: Long,
        ): ReliabilityRestoreCommand = ReliabilityRestoreCommand(
            invocation,
            manifest,
            verification,
            target,
            cleanTargetProof,
            targetVersionFence,
            recoveryReferenceEpochMilli,
            providerId,
            executionDeadlineEpochMilli,
        )
    }
}

class ReliabilityDrillCommand private constructor(
    val invocation: ReliabilityTrustedInvocation,
    drillId: String,
    val manifest: ReliabilityBackupManifest,
    val verification: ReliabilityManifestVerificationReceipt,
    val target: ReliabilityEnvironmentRef,
    val cleanTargetProof: ReliabilityCleanTargetProof,
    val targetVersionFence: ReliabilityVersionFence,
    val simulatedFailureEpochMilli: Long,
    providerId: String,
    val executionDeadlineEpochMilli: Long,
) {
    val drillId: String = ReliabilityRuntimeSupport.opaque(drillId, "Reliability drill id is invalid.")
    val providerId: String = ReliabilityRuntimeSupport.code(providerId, "Reliability provider id is invalid.")

    init {
        require(invocation.purpose == ReliabilityPurpose.RUN_DRILL &&
            invocation.action == ReliabilityAction.RUN_DRILL && invocation.tenantId == target.tenantId &&
            invocation.resource == target.resource && manifest.content.sourceEnvironment.tenantId == target.tenantId &&
            targetVersionFence.resource == target.resource && cleanTargetProof.target == target
        ) { "Reliability drill command is not bound to its exact manifest and target." }
        require(executionDeadlineEpochMilli > invocation.requestedAtEpochMilli &&
            executionDeadlineEpochMilli - invocation.requestedAtEpochMilli <=
            ReliabilityBackupCreateRequest.MAX_ASYNC_EXECUTION_MILLIS
        ) { "Reliability drill command execution deadline is invalid." }
    }

    companion object {
        @JvmStatic
        fun of(
            invocation: ReliabilityTrustedInvocation,
            drillId: String,
            manifest: ReliabilityBackupManifest,
            verification: ReliabilityManifestVerificationReceipt,
            target: ReliabilityEnvironmentRef,
            cleanTargetProof: ReliabilityCleanTargetProof,
            targetVersionFence: ReliabilityVersionFence,
            simulatedFailureEpochMilli: Long,
            providerId: String,
            executionDeadlineEpochMilli: Long,
        ): ReliabilityDrillCommand = ReliabilityDrillCommand(
            invocation,
            drillId,
            manifest,
            verification,
            target,
            cleanTargetProof,
            targetVersionFence,
            simulatedFailureEpochMilli,
            providerId,
            executionDeadlineEpochMilli,
        )
    }
}

/** Immutable, serializable intent; exactly one operation payload is present. */
class ReliabilityOperationIntent private constructor(
    val kind: ReliabilityOperationKind,
    tenantId: String,
    val principal: ReliabilityPrincipalRef,
    val purpose: ReliabilityPurpose,
    val action: ReliabilityAction,
    val resource: ReliabilityResourceRef,
    idempotencyDigest: String,
    argumentDigest: String,
    providerId: String,
    providerRevision: String,
    providerDescriptorDigest: String,
    topologySnapshotDigest: String?,
    val objectives: ReliabilityRecoveryObjectiveSet?,
    val manifest: ReliabilityBackupManifest?,
    val verification: ReliabilityManifestVerificationReceipt?,
    val environment: ReliabilityEnvironmentRef,
    val cleanTargetProof: ReliabilityCleanTargetProof?,
    val versionFence: ReliabilityVersionFence,
    val recoveryReferenceEpochMilli: Long?,
    drillId: String?,
    val submittedAtEpochMilli: Long,
    val executionDeadlineEpochMilli: Long,
) {
    val tenantId: String = ReliabilityRuntimeSupport.text(
        tenantId, ReliabilityRuntimeSupport.MAX_ID_BYTES, "Reliability intent tenant is invalid.",
    )
    val idempotencyDigest: String = ReliabilityRuntimeSupport.sha256(
        idempotencyDigest, "Reliability intent idempotency digest is invalid.",
    )
    val argumentDigest: String = ReliabilityRuntimeSupport.sha256(
        argumentDigest, "Reliability intent argument digest is invalid.",
    )
    val providerId: String = ReliabilityRuntimeSupport.code(providerId, "Reliability intent provider is invalid.")
    val providerRevision: String = ReliabilityRuntimeSupport.text(
        providerRevision, ReliabilityRuntimeSupport.MAX_REVISION_BYTES, "Reliability provider revision is invalid.",
    )
    val providerDescriptorDigest: String = ReliabilityRuntimeSupport.sha256(
        providerDescriptorDigest, "Reliability provider descriptor digest is invalid.",
    )
    val topologySnapshotDigest: String? = topologySnapshotDigest?.let {
        ReliabilityRuntimeSupport.sha256(it, "Reliability topology snapshot digest is invalid.")
    }
    val drillId: String? = drillId?.let {
        ReliabilityRuntimeSupport.opaque(it, "Reliability intent drill id is invalid.")
    }
    val intentDigest: String

    init {
        require(tenantId == environment.tenantId && versionFence.resource == resource &&
            submittedAtEpochMilli >= 0L && executionDeadlineEpochMilli > submittedAtEpochMilli
        ) { "Reliability intent environment, resource, tenant, or fence is inconsistent." }
        when (kind) {
            ReliabilityOperationKind.CREATE_BACKUP -> require(
                purpose == ReliabilityPurpose.CREATE_BACKUP && action == ReliabilityAction.CREATE_BACKUP &&
                    resource == environment.resource &&
                    objectives != null && manifest == null && verification == null && cleanTargetProof == null &&
                    recoveryReferenceEpochMilli == null && drillId == null && topologySnapshotDigest != null,
            ) { "Reliability create intent payload is inconsistent." }
            ReliabilityOperationKind.VERIFY_BACKUP -> require(
                purpose == ReliabilityPurpose.VERIFY_BACKUP && action == ReliabilityAction.VERIFY_BACKUP &&
                    objectives == null && manifest != null && verification == null && cleanTargetProof == null &&
                    recoveryReferenceEpochMilli == null && drillId == null,
            ) { "Reliability verify intent payload is inconsistent." }
            ReliabilityOperationKind.RESTORE -> require(
                purpose == ReliabilityPurpose.RESTORE && action == ReliabilityAction.RESTORE_CLEAN_TARGET &&
                    resource == environment.resource &&
                    objectives == null && manifest != null && verification != null && cleanTargetProof != null &&
                    recoveryReferenceEpochMilli != null && drillId == null && topologySnapshotDigest != null,
            ) { "Reliability restore intent payload is inconsistent." }
            ReliabilityOperationKind.DRILL -> require(
                purpose == ReliabilityPurpose.RUN_DRILL && action == ReliabilityAction.RUN_DRILL &&
                    resource == environment.resource &&
                    objectives == null && manifest != null && verification != null && cleanTargetProof != null &&
                    recoveryReferenceEpochMilli != null && drillId != null && topologySnapshotDigest != null,
            ) { "Reliability drill intent payload is inconsistent." }
        }
        intentDigest = ReliabilityRuntimeSupport.digest("flowweft-reliability-runtime-operation-intent-v1")
            .text(kind.name)
            .text(this.tenantId)
            .text(principal.type)
            .text(principal.id)
            .text(purpose.name)
            .text(action.name)
            .text(resource.referenceDigest)
            .text(this.idempotencyDigest)
            .text(this.argumentDigest)
            .text(this.providerId)
            .text(this.providerRevision)
            .text(this.providerDescriptorDigest)
            .optionalText(this.topologySnapshotDigest)
            .optionalText(objectives?.objectiveSetDigest)
            .optionalText(manifest?.manifestDigest)
            .optionalText(verification?.receiptDigest)
            .text(environment.bindingDigest)
            .optionalText(cleanTargetProof?.proofDigest)
            .text(versionFence.fenceDigest)
            .longValue(recoveryReferenceEpochMilli ?: -1L)
            .optionalText(this.drillId)
            .longValue(submittedAtEpochMilli)
            .longValue(executionDeadlineEpochMilli)
            .finish()
    }

    internal fun rebuildRequest(context: ReliabilityCallContext, dispatchAtEpochMilli: Long): Any = when (kind) {
        ReliabilityOperationKind.CREATE_BACKUP -> ReliabilityBackupCreateRequest.of(
            context,
            requireNotNull(objectives),
            versionFence,
            dispatchAtEpochMilli,
            executionDeadlineEpochMilli,
        )
        ReliabilityOperationKind.VERIFY_BACKUP -> ReliabilityBackupVerifyRequest.of(
            context, requireNotNull(manifest), versionFence, dispatchAtEpochMilli,
        )
        ReliabilityOperationKind.RESTORE -> ReliabilityRestoreRequest.toCleanTarget(
            context,
            requireNotNull(manifest),
            requireNotNull(verification),
            environment,
            requireNotNull(cleanTargetProof),
            versionFence,
            requireNotNull(recoveryReferenceEpochMilli),
            dispatchAtEpochMilli,
            executionDeadlineEpochMilli,
        )
        ReliabilityOperationKind.DRILL -> ReliabilityDrillRequest.isolated(
            context,
            requireNotNull(drillId),
            requireNotNull(manifest),
            requireNotNull(verification),
            environment,
            requireNotNull(cleanTargetProof),
            versionFence,
            requireNotNull(recoveryReferenceEpochMilli),
            dispatchAtEpochMilli,
            executionDeadlineEpochMilli,
        )
    }

    /**
     * Compares the durable business command, deliberately excluding submission time and other
     * request-envelope values. A caller may retry the same digest key in a new short-lived
     * authorization window without turning an otherwise exact replay into a conflict.
     */
    internal fun sameIdempotentCommandAs(other: ReliabilityOperationIntent): Boolean =
        kind == other.kind && tenantId == other.tenantId && principal == other.principal &&
            purpose == other.purpose && action == other.action && resource == other.resource &&
            idempotencyDigest == other.idempotencyDigest && argumentDigest == other.argumentDigest &&
            providerId == other.providerId && providerRevision == other.providerRevision &&
            providerDescriptorDigest == other.providerDescriptorDigest

    override fun toString(): String = "ReliabilityOperationIntent(kind=$kind, <redacted>)"

    companion object {
        internal fun create(
            request: ReliabilityBackupCreateRequest,
            provider: ReliabilityProviderDescriptor,
            topology: ReliabilityTopologySnapshot,
        ): ReliabilityOperationIntent {
            val argument = ReliabilityRuntimeSupport.digest("flowweft-reliability-runtime-create-arguments-v1")
                .text(request.objectives.objectiveSetDigest)
                .text(request.versionFence.fenceDigest)
                .text(reliabilityTopologyBindingDigest(topology))
                .longValue(request.executionDeadlineEpochMilli)
                .finish()
            return ReliabilityOperationIntent(
                ReliabilityOperationKind.CREATE_BACKUP,
                request.context.tenantId,
                request.context.principal,
                request.context.purpose,
                request.context.action,
                request.context.resource,
                request.context.idempotencyDigest,
                argument,
                provider.providerId,
                provider.providerRevision,
                reliabilityProviderBindingDigest(provider),
                reliabilityTopologyBindingDigest(topology),
                request.objectives,
                null,
                null,
                request.objectives.environment,
                null,
                request.versionFence,
                null,
                null,
                request.context.requestedAtEpochMilli,
                request.executionDeadlineEpochMilli,
            )
        }

        internal fun verify(
            request: ReliabilityBackupVerifyRequest,
            provider: ReliabilityProviderDescriptor,
            executionDeadlineEpochMilli: Long,
        ): ReliabilityOperationIntent {
            val argument = ReliabilityRuntimeSupport.digest("flowweft-reliability-runtime-verify-arguments-v1")
                .text(request.manifest.manifestDigest)
                .text(request.versionFence.fenceDigest)
                .longValue(executionDeadlineEpochMilli)
                .finish()
            return ReliabilityOperationIntent(
                ReliabilityOperationKind.VERIFY_BACKUP,
                request.context.tenantId,
                request.context.principal,
                request.context.purpose,
                request.context.action,
                request.context.resource,
                request.context.idempotencyDigest,
                argument,
                provider.providerId,
                provider.providerRevision,
                reliabilityProviderBindingDigest(provider),
                null,
                null,
                request.manifest,
                null,
                request.manifest.content.sourceEnvironment,
                null,
                request.versionFence,
                null,
                null,
                request.context.requestedAtEpochMilli,
                executionDeadlineEpochMilli,
            )
        }

        internal fun restore(
            request: ReliabilityRestoreRequest,
            provider: ReliabilityProviderDescriptor,
            topology: ReliabilityTopologySnapshot,
        ): ReliabilityOperationIntent {
            val argument = ReliabilityRuntimeSupport.digest("flowweft-reliability-runtime-restore-arguments-v1")
                .text(request.manifest.manifestDigest)
                .text(request.verification.receiptDigest)
                .text(request.target.bindingDigest)
                .text(request.cleanTargetProof.proofDigest)
                .text(request.targetVersionFence.fenceDigest)
                .text(reliabilityTopologyBindingDigest(topology))
                .longValue(request.recoveryReferenceEpochMilli)
                .longValue(request.executionDeadlineEpochMilli)
                .finish()
            return ReliabilityOperationIntent(
                ReliabilityOperationKind.RESTORE,
                request.context.tenantId,
                request.context.principal,
                request.context.purpose,
                request.context.action,
                request.context.resource,
                request.context.idempotencyDigest,
                argument,
                provider.providerId,
                provider.providerRevision,
                reliabilityProviderBindingDigest(provider),
                reliabilityTopologyBindingDigest(topology),
                null,
                request.manifest,
                request.verification,
                request.target,
                request.cleanTargetProof,
                request.targetVersionFence,
                request.recoveryReferenceEpochMilli,
                null,
                request.context.requestedAtEpochMilli,
                request.executionDeadlineEpochMilli,
            )
        }

        internal fun drill(
            request: ReliabilityDrillRequest,
            provider: ReliabilityProviderDescriptor,
            topology: ReliabilityTopologySnapshot,
        ): ReliabilityOperationIntent {
            val argument = ReliabilityRuntimeSupport.digest("flowweft-reliability-runtime-drill-arguments-v1")
                .text(request.drillId)
                .text(request.manifest.manifestDigest)
                .text(request.verification.receiptDigest)
                .text(request.target.bindingDigest)
                .text(request.cleanTargetProof.proofDigest)
                .text(request.targetVersionFence.fenceDigest)
                .text(reliabilityTopologyBindingDigest(topology))
                .longValue(request.simulatedFailureEpochMilli)
                .longValue(request.executionDeadlineEpochMilli)
                .finish()
            return ReliabilityOperationIntent(
                ReliabilityOperationKind.DRILL,
                request.context.tenantId,
                request.context.principal,
                request.context.purpose,
                request.context.action,
                request.context.resource,
                request.context.idempotencyDigest,
                argument,
                provider.providerId,
                provider.providerRevision,
                reliabilityProviderBindingDigest(provider),
                reliabilityTopologyBindingDigest(topology),
                null,
                request.manifest,
                request.verification,
                request.target,
                request.cleanTargetProof,
                request.targetVersionFence,
                request.simulatedFailureEpochMilli,
                request.drillId,
                request.context.requestedAtEpochMilli,
                request.executionDeadlineEpochMilli,
            )
        }
    }
}

internal fun reliabilityProviderBindingDigest(descriptor: ReliabilityProviderDescriptor): String {
    val writer = ReliabilityRuntimeSupport.digest("flowweft-reliability-runtime-provider-binding-v1")
        .text(descriptor.providerId)
        .text(descriptor.providerRevision)
        .text(descriptor.contractVersion)
        .text(descriptor.configurationDigest)
        .integer(descriptor.maximumComponentsPerManifest)
        .integer(descriptor.supportedCapabilities.size)
    descriptor.supportedCapabilities.sortedBy { it.name }.forEach { writer.text(it.name) }
    writer.integer(descriptor.supportedComponentKinds.size)
    descriptor.supportedComponentKinds.sortedBy { it.code }.forEach { writer.text(it.code) }
    return writer.finish()
}

internal fun reliabilityTopologyBindingDigest(snapshot: ReliabilityTopologySnapshot): String {
    val writer = ReliabilityRuntimeSupport.digest("flowweft-reliability-runtime-topology-binding-v1")
        .text(snapshot.environment.bindingDigest)
        .text(snapshot.sourceRevision)
        .text(snapshot.sourceDigest)
        .integer(snapshot.components.size)
    snapshot.components.sortedBy { it.scopeDigest }.forEach { writer.text(it.scopeDigest) }
    return writer.finish()
}
