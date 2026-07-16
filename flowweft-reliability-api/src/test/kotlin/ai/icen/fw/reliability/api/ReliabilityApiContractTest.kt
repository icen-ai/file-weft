package ai.icen.fw.reliability.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class ReliabilityApiContractTest {
    @Test
    fun `error budget derives consumed and remaining fractions from exact bad events`() {
        val objective = sloObjective(targetPpm = 990_000L)
        val request = sloRequest(
            objective,
            ReliabilitySliObservation.of(objective.objectiveDigest, 8_000L, 9_000L, 995L, 1_000L, 9_100L),
        )

        val result = ReliabilityErrorBudgetEvaluation.evaluate(request)

        assertEquals(ReliabilitySloDataState.AVAILABLE, result.state)
        assertEquals(5L, result.observedBadCount)
        assertEquals(500_000L, result.burnRatePpm)
        assertEquals(500_000L, result.budgetConsumedPpm)
        assertEquals(500_000L, result.remainingBudgetPpm)
        assertTrue(result.satisfied)
    }

    @Test
    fun `burn rate uses one million ppm per one-times budget and safely supports high multipliers`() {
        val objective = sloObjective(targetPpm = 999_999L, minimumSamples = 1L)
        val observation = ReliabilitySliObservation.of(
            objective.objectiveDigest,
            8_000L,
            9_000L,
            999_000_000_000L,
            1_000_000_000_000L,
            9_100L,
        )

        val result = ReliabilityErrorBudgetEvaluation.evaluate(sloRequest(objective, observation))

        assertEquals(1_000_000_000L, result.burnRatePpm)
        assertEquals(1_000_000L, result.budgetConsumedPpm)
        assertEquals(0L, result.remainingBudgetPpm)
        assertFalse(result.satisfied)
    }

    @Test
    fun `one hundred percent objective has explicit zero or exhausted budget semantics`() {
        val objective = sloObjective(targetPpm = 1_000_000L)
        val perfect = ReliabilitySliObservation.of(
            objective.objectiveDigest, 8_000L, 9_000L, 1_000L, 1_000L, 9_100L,
        )
        val imperfect = ReliabilitySliObservation.of(
            objective.objectiveDigest, 8_000L, 9_000L, 999L, 1_000L, 9_100L,
        )

        val noConsumption = ReliabilityErrorBudgetEvaluation.evaluate(sloRequest(objective, perfect))
        val exhausted = ReliabilityErrorBudgetEvaluation.evaluate(sloRequest(objective, imperfect))

        assertEquals(0L, noConsumption.burnRatePpm)
        assertEquals(1_000_000L, noConsumption.remainingBudgetPpm)
        assertEquals(ReliabilityContractSupport.MAX_BURN_RATE_PPM, exhausted.burnRatePpm)
        assertEquals(0L, exhausted.remainingBudgetPpm)
    }

    @Test
    fun `missing stale insufficient and wrong-window evidence fail closed`() {
        val objective = sloObjective(targetPpm = 990_000L, maximumAge = 500L)
        val missing = ReliabilityErrorBudgetEvaluation.evaluate(sloRequest(objective, null))
        val staleObservation = ReliabilitySliObservation.of(
            objective.objectiveDigest, 8_000L, 9_000L, 999L, 1_000L, 9_100L,
        )
        val stale = ReliabilityErrorBudgetEvaluation.evaluate(
            sloRequest(objective, staleObservation, evaluatedAt = 10_000L),
        )
        val insufficientObservation = ReliabilitySliObservation.of(
            objective.objectiveDigest, 8_000L, 9_000L, 9L, 10L, 9_900L,
        )
        val insufficient = ReliabilityErrorBudgetEvaluation.evaluate(sloRequest(objective, insufficientObservation))
        val wrongWindow = ReliabilitySliObservation.of(
            objective.objectiveDigest, 8_001L, 9_001L, 999L, 1_000L, 9_900L,
        )
        val mismatched = ReliabilityErrorBudgetEvaluation.evaluate(sloRequest(objective, wrongWindow))

        listOf(missing, stale, insufficient, mismatched).forEach { result ->
            assertFalse(result.satisfied)
            assertNull(result.observedPpm)
            assertNull(result.budgetConsumedPpm)
        }
        assertEquals(ReliabilitySloDataState.MISSING, missing.state)
        assertEquals(ReliabilitySloDataState.STALE, stale.state)
        assertEquals(ReliabilitySloDataState.INSUFFICIENT, insufficient.state)
        assertEquals(ReliabilitySloDataState.MISMATCHED, mismatched.state)

        val policy = ReliabilityBurnRatePolicy.of(
            "alert", "1", digest('7'), objective.objectiveDigest, 1_000_000L, 2_000_000L,
        )
        assertEquals(
            ReliabilityAlertSeverity.CRITICAL,
            ReliabilityBurnRateAlert.evaluate(policy, missing, 10_001L).severity,
        )
    }

    @Test
    fun `single database workflow-only topology is a valid consistent-cut manifest`() {
        val fixture = recoveryFixture()

        assertEquals(1, fixture.manifest.content.artifacts.size)
        assertEquals(ReliabilityComponentKind.DATABASE, fixture.manifest.content.artifacts.single().scope.kind)
        assertTrue(fixture.manifest.manifestDigest.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `manifest rejects a component recovery point outside its RPO`() {
        val fixture = recoveryFixture()
        val oldArtifact = backupArtifact(
            fixture.scope,
            fixture.cut.cutDigest,
            recoveryPoint = fixture.cut.cutAtEpochMilli - 1_001L,
            capturedAt = fixture.cut.cutAtEpochMilli + 100L,
        )

        assertFailsWith<IllegalArgumentException> {
            ReliabilityBackupManifestContent.of(
                "manifest-old",
                "1",
                fixture.objectives,
                fixture.cut,
                listOf(oldArtifact),
                fixture.cut.cutAtEpochMilli + 200L,
            )
        }
    }

    @Test
    fun `restore rejects in-place wrong-target future and stale clean evidence`() {
        val fixture = recoveryFixture()
        val target = recoveryTarget("recovery-a", ReliabilityEnvironmentKind.RECOVERY)
        val context = context(
            ReliabilityPurpose.RESTORE,
            ReliabilityAction.RESTORE_CLEAN_TARGET,
            target.resource,
            requestedAt = 110_000L,
            deadline = 150_000L,
        )
        val verification = verification(fixture, validUntil = 160_000L)
        val targetFence = ReliabilityVersionFence.of(target.resource, 0L, digest('8'))
        val futureProof = cleanProof(target, observedAt = 111_000L, expiresAt = 150_000L)

        assertFailsWith<IllegalArgumentException> {
            ReliabilityRestoreRequest.toCleanTarget(
                context,
                fixture.manifest,
                verification,
                target,
                futureProof,
                targetFence,
                105_000L,
                110_500L,
                200_000L,
            )
        }

        val expiredProof = cleanProof(target, observedAt = 109_000L, expiresAt = 110_400L)
        assertFailsWith<IllegalArgumentException> {
            ReliabilityRestoreRequest.toCleanTarget(
                context,
                fixture.manifest,
                verification,
                target,
                expiredProof,
                targetFence,
                105_000L,
                110_500L,
                200_000L,
            )
        }

        val otherTarget = recoveryTarget("recovery-b", ReliabilityEnvironmentKind.RECOVERY)
        assertFailsWith<IllegalArgumentException> {
            ReliabilityRestoreRequest.toCleanTarget(
                context,
                fixture.manifest,
                verification,
                target,
                cleanProof(otherTarget, 109_000L, 150_000L),
                targetFence,
                105_000L,
                110_500L,
                200_000L,
            )
        }

        val sameLogicalTarget = ReliabilityEnvironmentRef.of(
            fixture.source.tenantId,
            fixture.source.environmentId,
            ReliabilityEnvironmentKind.RECOVERY,
            ReliabilityResourceRef.of(
                ReliabilityEnvironmentRef.RESOURCE_TYPE,
                fixture.source.environmentId,
                "recovery-1",
                digest('9'),
            ),
            fixture.source.topologyDigest,
        )
        val sameContext = context(
            ReliabilityPurpose.RESTORE,
            ReliabilityAction.RESTORE_CLEAN_TARGET,
            sameLogicalTarget.resource,
            requestedAt = 110_000L,
            deadline = 150_000L,
        )
        assertFailsWith<IllegalArgumentException> {
            ReliabilityRestoreRequest.toCleanTarget(
                sameContext,
                fixture.manifest,
                verification,
                sameLogicalTarget,
                cleanProof(sameLogicalTarget, 109_000L, 150_000L),
                ReliabilityVersionFence.of(sameLogicalTarget.resource, 0L, digest('a')),
                105_000L,
                110_500L,
                200_000L,
            )
        }

        assertFalse(ReliabilityAction.values().map { it.name }.contains("RESTORE_IN_PLACE"))
    }

    @Test
    fun `old backup restored today cannot falsely satisfy RPO and pre-provider delay counts toward RTO`() {
        val fixture = recoveryFixture(rpoMillis = 1_000L, rtoMillis = 4_000L)
        val target = recoveryTarget("recovery-old", ReliabilityEnvironmentKind.RECOVERY)
        val request = restoreRequest(fixture, target, recoveryReference = 105_000L, startedAt = 110_000L)

        val receipt = ReliabilityRestoreReceipt.of(
            request, "provider", "1", digest('b'), 112_000L,
        )

        val evaluation = receipt.assessment.evaluations.single()
        assertEquals(5_500L, evaluation.dataLossMillis)
        assertEquals(7_000L, evaluation.recoveryMillis)
        assertFalse(evaluation.rpoMet)
        assertFalse(evaluation.rtoMet)
        assertFalse(receipt.assessment.rpoMet)
        assertFalse(receipt.assessment.rtoMet)
    }

    @Test
    fun `fresh dispatch authorization can launch recovery that runs longer than five minutes`() {
        val fixture = recoveryFixture(rpoMillis = 1_000L, rtoMillis = 3_000_000L)
        val target = recoveryTarget("recovery-long", ReliabilityEnvironmentKind.RECOVERY)
        val request = restoreRequest(fixture, target, recoveryReference = 100_000L, startedAt = 110_000L)

        val completedAfterThirtyMinutes = 1_910_000L
        val receipt = ReliabilityRestoreReceipt.of(
            request, "provider", "1", digest('b'), completedAfterThirtyMinutes,
        )

        assertTrue(completedAfterThirtyMinutes > request.context.deadlineEpochMilli)
        assertTrue(receipt.assessment.rtoMet)
        assertTrue(receipt.assessment.rpoMet)
        assertFailsWith<IllegalArgumentException> {
            ReliabilityRestoreReceipt.of(
                request, "provider", "1", digest('b'), request.executionDeadlineEpochMilli,
            )
        }
    }

    @Test
    fun `isolated drill reports RPO and RTO from its simulated failure reference`() {
        val fixture = recoveryFixture(rpoMillis = 1_000L, rtoMillis = 20_000L)
        val target = recoveryTarget("drill-a", ReliabilityEnvironmentKind.DRILL)
        val drillContext = context(
            ReliabilityPurpose.RUN_DRILL,
            ReliabilityAction.RUN_DRILL,
            target.resource,
            requestedAt = 110_000L,
            deadline = 150_000L,
        )
        val request = ReliabilityDrillRequest.isolated(
            drillContext,
            "drill-1",
            fixture.manifest,
            verification(fixture, 160_000L),
            target,
            cleanProof(target, 109_000L, 120_000L),
            ReliabilityVersionFence.of(target.resource, 0L, digest('c')),
            100_000L,
            110_000L,
            200_000L,
        )

        val report = ReliabilityDrillReport.of(request, "provider", "1", digest('d'), 112_000L)

        assertTrue(report.assessment.rpoMet)
        assertTrue(report.assessment.rtoMet)
        assertEquals(500L, report.assessment.evaluations.single().dataLossMillis)
        assertEquals(12_000L, report.assessment.evaluations.single().recoveryMillis)
    }

    @Test
    fun `authorized service principal can reconcile exact original but not another tenant or target`() {
        val fixture = recoveryFixture()
        val source = fixture.source
        val originalContext = context(
            ReliabilityPurpose.CREATE_BACKUP,
            ReliabilityAction.CREATE_BACKUP,
            source.resource,
            principal = ReliabilityPrincipalRef.of("user", "departed-user"),
            requestedAt = 100_000L,
            deadline = 120_000L,
        )
        val fence = ReliabilityVersionFence.of(source.resource, 7L, digest('c'))
        val originalRequest = ReliabilityBackupCreateRequest.of(
            originalContext, fixture.objectives, fence, 100_100L, 200_000L,
        )
        val attempt = ReliabilityOperationAttemptReference.forBackup(
            originalRequest,
            "provider",
            "1",
            "provider-op-1",
        )
        val unknown = ReliabilityOutcomeUnknownReference.of(attempt, digest('e'), 120_100L)
        val serviceContext = context(
            ReliabilityPurpose.RECONCILE,
            ReliabilityAction.RECONCILE_OPERATION,
            source.resource,
            principal = ReliabilityPrincipalRef.of("service", "recovery-reconciler"),
            requestedAt = 130_000L,
            deadline = 140_000L,
        )

        val accepted = ReliabilityReconciliationRequest.exactOriginal(
            serviceContext, unknown, 130_100L,
        )
        assertNotEquals(originalContext.principal, accepted.context.principal)

        val crossTenant = context(
            ReliabilityPurpose.RECONCILE,
            ReliabilityAction.RECONCILE_OPERATION,
            source.resource,
            tenant = "tenant-b",
            principal = ReliabilityPrincipalRef.of("service", "recovery-reconciler"),
            requestedAt = 130_000L,
            deadline = 140_000L,
        )
        assertFailsWith<IllegalArgumentException> {
            ReliabilityReconciliationRequest.exactOriginal(crossTenant, unknown, 130_100L)
        }

        val other = recoveryTarget("other-resource", ReliabilityEnvironmentKind.RECOVERY)
        val wrongTarget = context(
            ReliabilityPurpose.RECONCILE,
            ReliabilityAction.RECONCILE_OPERATION,
            other.resource,
            principal = ReliabilityPrincipalRef.of("service", "recovery-reconciler"),
            requestedAt = 130_000L,
            deadline = 140_000L,
        )
        assertFailsWith<IllegalArgumentException> {
            ReliabilityReconciliationRequest.exactOriginal(wrongTarget, unknown, 130_100L)
        }
    }

    @Test
    fun `deadline is exclusive for dispatch and completion`() {
        val fixture = recoveryFixture()
        val context = context(
            ReliabilityPurpose.CREATE_BACKUP,
            ReliabilityAction.CREATE_BACKUP,
            fixture.source.resource,
            requestedAt = 100_000L,
            deadline = 120_000L,
        )
        val fence = ReliabilityVersionFence.of(fixture.source.resource, 1L, digest('f'))

        assertFailsWith<IllegalArgumentException> {
            ReliabilityBackupCreateRequest.of(context, fixture.objectives, fence, 120_000L, 130_000L)
        }

        val request = ReliabilityBackupCreateRequest.of(context, fixture.objectives, fence, 100_100L, 120_000L)
        assertFailsWith<IllegalArgumentException> {
            ReliabilityBackupCreationReceipt.of(
                request, fixture.manifest, "provider", "1", digest('1'), 120_000L,
            )
        }
    }

    @Test
    fun `provider outcome unknown requires and preserves exact original attempt`() {
        val fixture = recoveryFixture()
        val context = context(
            ReliabilityPurpose.CREATE_BACKUP,
            ReliabilityAction.CREATE_BACKUP,
            fixture.source.resource,
            requestedAt = 100_000L,
            deadline = 120_000L,
        )
        val fence = ReliabilityVersionFence.of(fixture.source.resource, 1L, digest('2'))
        val request = ReliabilityBackupCreateRequest.of(
            context, fixture.objectives, fence, 100_100L, 200_000L,
        )
        val attempt = ReliabilityOperationAttemptReference.forBackup(
            request,
            "provider",
            "1",
            "op-1",
        )
        val unknown = ReliabilityOutcomeUnknownReference.of(attempt, digest('4'), 120_100L)

        val result = ReliabilityProviderResult.outcomeUnknown<ReliabilityBackupCreationReceipt>(unknown)

        assertEquals(ReliabilityProviderResultStatus.OUTCOME_UNKNOWN, result.status)
        assertEquals(attempt.attemptDigest, result.outcomeUnknown?.originalAttempt?.attemptDigest)
        assertTrue(requireNotNull(result.failure).reconciliationRequired)
    }

    @Test
    fun `metric evidence exposes finite categorical dimensions only`() {
        val evidence = ReliabilityMetricEvidence.of(
            ReliabilityMetricCode.OPERATION_RESULT,
            ReliabilityMetricOutcome.SUCCESS,
            ReliabilityOperationKind.RESTORE,
            ReliabilityMetricComponentClass.DATABASE,
            10L,
        )

        assertEquals(ReliabilityMetricOutcome.SUCCESS, evidence.outcome)
        val propertyNames = evidence.javaClass.declaredFields.map { it.name }.toSet()
        assertFalse(propertyNames.any { name ->
            name.contains("tenant", true) || name.contains("resource", true) ||
                name.contains("provider", true) || name.contains("digest", true) || name == "value"
        })
    }

    @Test
    fun `capability and Doctor evidence fail closed without high-cardinality values`() {
        val resource = ReliabilityResourceRef.of("reliability-system", "tenant-a", "1", digest('1'))
        val capabilityContext = context(
            ReliabilityPurpose.DISCOVER_CAPABILITIES,
            ReliabilityAction.DISCOVER_CAPABILITIES,
            resource,
            requestedAt = 10_000L,
            deadline = 11_000L,
        )
        val request = ReliabilityCapabilityRequest.of(
            capabilityContext,
            listOf(ReliabilityCapability.CREATE_CONSISTENT_BACKUP),
            listOf(ReliabilityComponentKind.DATABASE),
        )
        val descriptor = ReliabilityProviderDescriptor.of(
            "provider",
            "1",
            "1",
            digest('2'),
            listOf(ReliabilityCapability.CREATE_CONSISTENT_BACKUP),
            listOf(ReliabilityComponentKind.DATABASE),
            8,
            9_000L,
            12_000L,
        )
        assertEquals(
            ReliabilityCapabilityStatus.AVAILABLE,
            ReliabilityCapabilityResult.available(request, descriptor, 10_100L).status,
        )

        val missingKind = ReliabilityCapabilityRequest.of(
            capabilityContext,
            listOf(ReliabilityCapability.CREATE_CONSISTENT_BACKUP),
            listOf(ReliabilityComponentKind.SEARCH_INDEX),
        )
        assertFailsWith<IllegalArgumentException> {
            ReliabilityCapabilityResult.available(missingKind, descriptor, 10_100L)
        }

        val doctorContext = context(
            ReliabilityPurpose.INSPECT_DOCTOR,
            ReliabilityAction.INSPECT_DOCTOR,
            resource,
            requestedAt = 20_000L,
            deadline = 21_000L,
        )
        val doctorRequest = ReliabilityDoctorRequest.of(doctorContext, ReliabilityDoctorMode.RECOVERABILITY)
        val report = ReliabilityDoctorReport.of(
            doctorRequest,
            "provider",
            "1",
            ReliabilityDoctorStatus.DEGRADED,
            listOf(
                ReliabilityDoctorFinding.of(
                    ReliabilityDoctorFindingCode.RECOVERY_OBJECTIVE_AT_RISK,
                    ReliabilityDoctorSeverity.WARNING,
                    ReliabilityMetricComponentClass.DATABASE,
                ),
            ),
            20_100L,
            21_100L,
        )
        assertEquals(ReliabilityDoctorStatus.DEGRADED, report.status)
    }

    private fun sloObjective(
        targetPpm: Long,
        minimumSamples: Long = 100L,
        maximumAge: Long = 2_000L,
    ): ReliabilitySloObjective = ReliabilitySloObjective.of(
        "availability-slo",
        "1",
        digest('1'),
        ReliabilityResourceRef.of("service", "api", "1", digest('2')),
        ReliabilitySliKind.AVAILABILITY,
        targetPpm,
        1_000L,
        minimumSamples,
        maximumAge,
        0L,
        100_000L,
    )

    private fun sloRequest(
        objective: ReliabilitySloObjective,
        observation: ReliabilitySliObservation?,
        evaluatedAt: Long = 10_000L,
    ): ReliabilitySloEvaluationRequest {
        val context = context(
            ReliabilityPurpose.EVALUATE_SLO,
            ReliabilityAction.EVALUATE_SLO,
            objective.resource,
            requestedAt = evaluatedAt,
            deadline = evaluatedAt + 1_000L,
        )
        return ReliabilitySloEvaluationRequest.of(
            context,
            objective,
            observation,
            8_000L,
            9_000L,
            evaluatedAt,
        )
    }

    private fun recoveryFixture(
        rpoMillis: Long = 1_000L,
        rtoMillis: Long = 10_000L,
    ): RecoveryFixture {
        val source = sourceEnvironment()
        val scope = ReliabilityComponentScope.of(
            ReliabilityComponentKind.DATABASE, "workflow-db", "1", digest('3'),
        )
        val objective = ReliabilityRecoveryObjective.of(scope, rpoMillis, rtoMillis)
        val objectives = ReliabilityRecoveryObjectiveSet.of(
            "recovery-policy",
            "1",
            digest('4'),
            source,
            listOf(objective),
            0L,
            1_000_000L,
        )
        val cut = ReliabilityConsistentCut.of(
            "cut-1", source, objectives.topologyDigest, 100_000L,
        )
        val artifact = backupArtifact(scope, cut.cutDigest, 99_500L, 100_100L)
        val content = ReliabilityBackupManifestContent.of(
            "manifest-1", "1", objectives, cut, listOf(artifact), 100_200L,
        )
        val key = ReliabilityKeyReference.of("kms", "key-1", "1", digest('5'))
        val seal = ReliabilityImmutableManifestSeal.of(
            content, "1", "ed25519", key, digest('6'), 100_300L,
        )
        return RecoveryFixture(source, scope, objectives, cut, ReliabilityBackupManifest.of(content, seal))
    }

    private fun sourceEnvironment(): ReliabilityEnvironmentRef {
        val resource = ReliabilityResourceRef.of(
            ReliabilityEnvironmentRef.RESOURCE_TYPE, "production-a", "1", digest('0'),
        )
        return ReliabilityEnvironmentRef.of(
            "tenant-a", "production-a", ReliabilityEnvironmentKind.PRODUCTION, resource, digest('a'),
        )
    }

    private fun recoveryTarget(
        id: String,
        kind: ReliabilityEnvironmentKind,
    ): ReliabilityEnvironmentRef {
        val resource = ReliabilityResourceRef.of(
            ReliabilityEnvironmentRef.RESOURCE_TYPE, id, "1", digest('7'),
        )
        return ReliabilityEnvironmentRef.of("tenant-a", id, kind, resource, digest('a'))
    }

    private fun backupArtifact(
        scope: ReliabilityComponentScope,
        cutDigest: String,
        recoveryPoint: Long,
        capturedAt: Long,
    ): ReliabilityBackupArtifact {
        val key = ReliabilityKeyReference.of("kms", "key-1", "1", digest('5'))
        val encryption = ReliabilityEncryptionDescriptor.of(
            "aes-256-gcm", key, digest('8'), digest('9'),
        )
        return ReliabilityBackupArtifact.immutableEncrypted(
            scope,
            "snapshot-1",
            "provider",
            "1",
            cutDigest,
            recoveryPoint,
            capturedAt,
            1_024L,
            digest('b'),
            encryption,
        )
    }

    private fun verification(
        fixture: RecoveryFixture,
        validUntil: Long,
    ): ReliabilityManifestVerificationReceipt {
        val verifyContext = context(
            ReliabilityPurpose.VERIFY_BACKUP,
            ReliabilityAction.VERIFY_BACKUP,
            fixture.manifest.resource,
            requestedAt = 101_000L,
            deadline = 102_000L,
        )
        val request = ReliabilityBackupVerifyRequest.of(
            verifyContext,
            fixture.manifest,
            ReliabilityVersionFence.of(fixture.manifest.resource, 1L, digest('c')),
            101_010L,
        )
        return ReliabilityManifestVerificationReceipt.of(
            request,
            "provider",
            "1",
            ReliabilityManifestVerificationStatus.VALID,
            true,
            true,
            true,
            true,
            true,
            digest('d'),
            101_100L,
            validUntil,
        )
    }

    private fun cleanProof(
        target: ReliabilityEnvironmentRef,
        observedAt: Long,
        expiresAt: Long,
    ): ReliabilityCleanTargetProof = ReliabilityCleanTargetProof.clean(
        "proof-${target.environmentId}", target, "clean-target-verifier", "1", digest('e'), observedAt, expiresAt,
    )

    private fun restoreRequest(
        fixture: RecoveryFixture,
        target: ReliabilityEnvironmentRef,
        recoveryReference: Long,
        startedAt: Long,
    ): ReliabilityRestoreRequest {
        val restoreContext = context(
            ReliabilityPurpose.RESTORE,
            ReliabilityAction.RESTORE_CLEAN_TARGET,
            target.resource,
            requestedAt = startedAt,
            deadline = 150_000L,
        )
        return ReliabilityRestoreRequest.toCleanTarget(
            restoreContext,
            fixture.manifest,
            verification(fixture, 160_000L),
            target,
            cleanProof(target, startedAt - 1_000L, 150_000L),
            ReliabilityVersionFence.of(target.resource, 0L, digest('f')),
            recoveryReference,
            startedAt,
            4_000_000L,
        )
    }

    private fun context(
        purpose: ReliabilityPurpose,
        action: ReliabilityAction,
        resource: ReliabilityResourceRef,
        tenant: String = "tenant-a",
        principal: ReliabilityPrincipalRef = ReliabilityPrincipalRef.of("user", "operator"),
        requestedAt: Long,
        deadline: Long,
    ): ReliabilityCallContext {
        val authorization = ReliabilityAuthorizationSnapshot.of(
            "auth-$requestedAt",
            tenant,
            principal,
            purpose,
            action,
            resource,
            "host-authz",
            "1",
            "1",
            digest('f'),
            requestedAt - 100L,
            deadline + 1_000L,
        )
        return ReliabilityCallContext.of(
            "request-$requestedAt",
            tenant,
            principal,
            purpose,
            action,
            resource,
            authorization,
            digest('a'),
            requestedAt,
            deadline,
        )
    }

    private fun digest(character: Char): String = character.toString().repeat(64)

    private data class RecoveryFixture(
        val source: ReliabilityEnvironmentRef,
        val scope: ReliabilityComponentScope,
        val objectives: ReliabilityRecoveryObjectiveSet,
        val cut: ReliabilityConsistentCut,
        val manifest: ReliabilityBackupManifest,
    )
}
