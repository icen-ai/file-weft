package ai.icen.fw.governance.runtime

import ai.icen.fw.governance.api.GovernanceAuthorizationSnapshot
import ai.icen.fw.governance.api.GovernanceCallContext
import ai.icen.fw.governance.api.GovernanceDeletionStage
import ai.icen.fw.governance.api.GovernancePrincipalRef
import ai.icen.fw.governance.api.GovernancePurpose
import ai.icen.fw.governance.api.GovernanceResourceRef
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class GovernanceDeletionTargetLedgerTest {
    @Test
    fun `target text rejects unpaired surrogates but accepts valid supplementary code points`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            GovernanceDeletionTargetItem.of(
                1,
                GovernanceDeletionTargetItemKind.OBJECT_CONTENT,
                "file-object-1",
                "\uD800",
                digest('7'),
                "storage-provider",
                "provider-r1",
            )
        }
        assertContains(requireNotNull(failure.message), "revision is invalid")

        GovernanceDeletionTargetItem.of(
            1,
            GovernanceDeletionTargetItemKind.OBJECT_CONTENT,
            "file-object-1",
            "版本-\uD83D\uDE80",
            digest('7'),
            "storage-provider",
            "provider-r1",
        )
    }

    @Test
    fun `semantic duplicate items cannot bypass manifest validation by changing ordinal`() {
        val first = item(1)
        val duplicateAtAnotherOrdinal = item(2)

        assertEquals(first.itemIdentityDigest, duplicateAtAnotherOrdinal.itemIdentityDigest)
        assertNotEquals(first.itemBindingDigest, duplicateAtAnotherOrdinal.itemBindingDigest)

        val digestFailure = assertFailsWith<IllegalArgumentException> {
            GovernanceDeletionTargetManifest.calculateTargetDigest(
                GovernanceDeletionStage.PURGE_OBJECT_CONTENT,
                "target-r1",
                listOf(first, duplicateAtAnotherOrdinal),
            )
        }
        assertContains(requireNotNull(digestFailure.message), "semantically duplicate")

        val target = GovernanceDeletionTarget.of(
            GovernanceDeletionStage.PURGE_OBJECT_CONTENT,
            "target-object-1",
            "target-r1",
            digest('4'),
        )
        val manifestFailure = assertFailsWith<IllegalArgumentException> {
            GovernanceDeletionTargetManifest.of(
                planningRequest("tenant-a", "user-a", "request-a", "authorization-a"),
                target,
                listOf(first, duplicateAtAnotherOrdinal),
            )
        }
        assertContains(requireNotNull(manifestFailure.message), "semantically duplicate")
    }

    @Test
    fun `stable preparation identity turns same key request swaps into conflict`() {
        val first = planningRequest("tenant-a", "user-a", "request-a", "authorization-a")
        val changedPrincipalAndAuthorization = planningRequest(
            "tenant-a",
            "user-b",
            "request-b",
            "authorization-b",
        )

        assertNotEquals(first.requestDigest, changedPrincipalAndAuthorization.requestDigest)
        assertEquals(
            GovernanceDeletionTargetManifest.calculatePlanningIdentityDigest(first),
            GovernanceDeletionTargetManifest.calculatePlanningIdentityDigest(changedPrincipalAndAuthorization),
        )
        val targetItem = item(1)
        val targetRevision = "target-r1"
        val target = GovernanceDeletionTarget.of(
            GovernanceDeletionStage.PURGE_OBJECT_CONTENT,
            "target-idempotency-1",
            targetRevision,
            GovernanceDeletionTargetManifest.calculateTargetDigest(
                GovernanceDeletionStage.PURGE_OBJECT_CONTENT,
                targetRevision,
                listOf(targetItem),
            ),
        )
        val firstManifest = GovernanceDeletionTargetManifest.of(first, target, listOf(targetItem))
        val swappedManifest = GovernanceDeletionTargetManifest.of(
            changedPrincipalAndAuthorization,
            target,
            listOf(targetItem),
        )
        val repository = InMemoryGovernanceDeletionTargetLedger()

        assertEquals(GovernanceStoreCode.STORED, repository.createIfAbsent(firstManifest).code)
        assertEquals(GovernanceStoreCode.CONFLICT, repository.createIfAbsent(swappedManifest).code)
    }

    @Test
    fun `rehydration rejects a provider start after its authorized deadline`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            rehydrateOperation(
                status = GovernanceDeletionTargetItemOperationStatus.STARTED,
                version = 2L,
                startedAtEpochMilli = 201L,
                outcome = null,
                reconciliationRequestDigest = null,
                updatedAtEpochMilli = 201L,
            )
        }

        assertContains(requireNotNull(failure.message), "outside its authorized call window")
    }

    @Test
    fun `rehydration rejects unreachable started and terminal versions`() {
        val startedFailure = assertFailsWith<IllegalArgumentException> {
            rehydrateOperation(
                status = GovernanceDeletionTargetItemOperationStatus.STARTED,
                version = 3L,
                startedAtEpochMilli = 120L,
                outcome = null,
                reconciliationRequestDigest = null,
                updatedAtEpochMilli = 120L,
            )
        }
        assertContains(requireNotNull(startedFailure.message), "Started governance target item operation is invalid")

        val startedSource = rehydrateOperation(
            status = GovernanceDeletionTargetItemOperationStatus.STARTED,
            version = 2L,
            startedAtEpochMilli = 120L,
            outcome = null,
            reconciliationRequestDigest = null,
            updatedAtEpochMilli = 120L,
        )
        val providerOutcome = GovernanceDeletionTargetItemOutcome.verifiedAbsent(
            startedSource,
            "receipt-1",
            digest('5'),
            160L,
        )
        val terminalFailure = assertFailsWith<IllegalArgumentException> {
            rehydrateOperation(
                status = GovernanceDeletionTargetItemOperationStatus.VERIFIED_ABSENT,
                version = 4L,
                startedAtEpochMilli = 120L,
                outcome = providerOutcome,
                reconciliationRequestDigest = null,
                updatedAtEpochMilli = 160L,
            )
        }
        assertContains(requireNotNull(terminalFailure.message), "Resolved governance target item operation is invalid")

        val prematureReconciliationFailure = assertFailsWith<IllegalArgumentException> {
            rehydrateOperation(
                status = GovernanceDeletionTargetItemOperationStatus.VERIFIED_ABSENT,
                version = 3L,
                startedAtEpochMilli = 120L,
                outcome = providerOutcome,
                reconciliationRequestDigest = digest('6'),
                reconciliationRequestedAtEpochMilli = 130L,
                reconciliationStartedAtEpochMilli = 140L,
                reconciliationDeadlineEpochMilli = 180L,
                updatedAtEpochMilli = 160L,
            )
        }
        assertContains(
            requireNotNull(prematureReconciliationFailure.message),
            "Resolved governance target item operation is invalid",
        )
    }

    @Test
    fun `provider outcomes bind the exact source state and may complete after the call deadline`() {
        val binding = executionBinding()
        val first = GovernanceDeletionTargetItemOperation.markStarted(
            preparedOperation(binding, item(1), "provider-operation-1"),
            1_050L,
        )
        val second = GovernanceDeletionTargetItemOperation.markStarted(
            preparedOperation(binding, item(2, "file-object-2", digest('6')), "provider-operation-2"),
            1_050L,
        )
        val lateButKnown = GovernanceDeletionTargetItemOutcome.verifiedAbsent(
            first,
            "receipt-late",
            digest('5'),
            1_500L,
        )

        val recorded = GovernanceDeletionTargetItemOperation.recordProviderOutcome(first, lateButKnown)
        assertEquals(GovernanceDeletionTargetItemOperationStatus.VERIFIED_ABSENT, recorded.status)
        val mismatch = assertFailsWith<IllegalArgumentException> {
            GovernanceDeletionTargetItemOperation.recordProviderOutcome(second, lateButKnown)
        }
        assertContains(requireNotNull(mismatch.message), "exact started operation")
    }

    @Test
    fun `in memory ledger rejects provider operation reference reuse across items`() {
        val first = item(1)
        val second = item(2, "file-object-2", digest('6'))
        val request = planningRequest("tenant-a", "user-a", "request-a", "authorization-a")
        val targetRevision = "target-r1"
        val target = GovernanceDeletionTarget.of(
            GovernanceDeletionStage.PURGE_OBJECT_CONTENT,
            "target-object-reference-1",
            targetRevision,
            GovernanceDeletionTargetManifest.calculateTargetDigest(
                GovernanceDeletionStage.PURGE_OBJECT_CONTENT,
                targetRevision,
                listOf(first, second),
            ),
        )
        val manifest = GovernanceDeletionTargetManifest.of(request, target, listOf(first, second))
        val binding = executionBinding(manifest)
        val repository = InMemoryGovernanceDeletionTargetLedger()

        assertEquals(GovernanceStoreCode.STORED, repository.createIfAbsent(manifest).code)
        assertEquals(
            GovernanceStoreCode.STORED,
            repository.prepare(preparedOperation(binding, first, "provider-operation-1")).code,
        )
        assertEquals(
            GovernanceStoreCode.CONFLICT,
            repository.prepare(preparedOperation(binding, second, "provider-operation-1")).code,
        )
    }

    @Test
    fun `equal target and operation references remain isolated across tenants`() {
        val targetItem = item(1)
        val targetRevision = "target-r1"
        val target = GovernanceDeletionTarget.of(
            GovernanceDeletionStage.PURGE_OBJECT_CONTENT,
            "shared-target-reference",
            targetRevision,
            GovernanceDeletionTargetManifest.calculateTargetDigest(
                GovernanceDeletionStage.PURGE_OBJECT_CONTENT,
                targetRevision,
                listOf(targetItem),
            ),
        )
        val tenantA = GovernanceDeletionTargetManifest.of(
            planningRequest("tenant-a", "user-a", "shared-request", "shared-authorization"),
            target,
            listOf(targetItem),
        )
        val tenantB = GovernanceDeletionTargetManifest.of(
            planningRequest("tenant-b", "user-a", "shared-request", "shared-authorization"),
            target,
            listOf(targetItem),
        )
        val repository = InMemoryGovernanceDeletionTargetLedger()

        assertEquals(GovernanceStoreCode.STORED, repository.createIfAbsent(tenantA).code)
        assertEquals(GovernanceStoreCode.STORED, repository.createIfAbsent(tenantB).code)
        assertEquals(
            GovernanceStoreCode.STORED,
            repository.prepare(
                preparedOperation(executionBinding(tenantA), targetItem, "shared-provider-operation"),
            ).code,
        )
        assertEquals(
            GovernanceStoreCode.STORED,
            repository.prepare(
                preparedOperation(executionBinding(tenantB), targetItem, "shared-provider-operation"),
            ).code,
        )
    }

    private fun rehydrateOperation(
        status: GovernanceDeletionTargetItemOperationStatus,
        version: Long,
        startedAtEpochMilli: Long?,
        outcome: GovernanceDeletionTargetItemOutcome?,
        reconciliationRequestDigest: String?,
        reconciliationRequestedAtEpochMilli: Long? = null,
        reconciliationStartedAtEpochMilli: Long? = null,
        reconciliationDeadlineEpochMilli: Long? = null,
        updatedAtEpochMilli: Long,
    ): GovernanceDeletionTargetItemOperation {
        val binding = executionBinding()
        val itemBindingDigest = digest('1')
        val operationKeyDigest = GovernanceDeletionTargetItemOperation.calculateOperationKeyDigest(
            binding,
            itemBindingDigest,
        )
        val stateDigest = GovernanceRuntimeSupport.digest(
            "flowweft-governance-runtime-target-item-operation-v1",
        )
            .text(operationKeyDigest)
            .text(binding.bindingDigest)
            .text("storage-provider")
            .text("provider-r1")
            .text("operation-1")
            .text(digest('2'))
            .longValue(200L)
            .text(status.code)
            .longValue(version)
            .longValue(100L)
            .optionalText(startedAtEpochMilli?.toString())
            .optionalText(outcome?.outcomeDigest)
            .optionalText(reconciliationRequestDigest)
            .optionalText(reconciliationRequestedAtEpochMilli?.toString())
            .optionalText(reconciliationStartedAtEpochMilli?.toString())
            .optionalText(reconciliationDeadlineEpochMilli?.toString())
            .longValue(updatedAtEpochMilli)
            .finish()
        return GovernanceDeletionTargetItemOperation.rehydrate(
            binding,
            itemBindingDigest,
            "storage-provider",
            "provider-r1",
            "operation-1",
            digest('2'),
            200L,
            status,
            version,
            100L,
            startedAtEpochMilli,
            outcome,
            reconciliationRequestDigest,
            reconciliationRequestedAtEpochMilli,
            reconciliationStartedAtEpochMilli,
            reconciliationDeadlineEpochMilli,
            updatedAtEpochMilli,
            operationKeyDigest,
            stateDigest,
        )
    }

    private fun executionBinding(): GovernanceDeletionTargetExecutionBinding {
        val tenantId = "tenant-a"
        val planDigest = digest('a')
        val stepDigest = digest('b')
        val planningRequestDigest = digest('c')
        val planningIdentityDigest = digest('d')
        val stage = GovernanceDeletionStage.PURGE_OBJECT_CONTENT
        val targetRef = "target-object-1"
        val targetRevision = "target-r1"
        val targetDigest = digest('e')
        val manifestDigest = digest('f')
        val preparationDigest = GovernanceDeletionTargetManifest.calculatePreparationDigest(
            planningIdentityDigest,
            stage,
        )
        val bindingDigest = GovernanceRuntimeSupport.digest(
            "flowweft-governance-runtime-target-execution-binding-v1",
        )
            .text(tenantId)
            .text(planDigest)
            .text(stepDigest)
            .text(planningRequestDigest)
            .text(planningIdentityDigest)
            .text(preparationDigest)
            .text(stage.name)
            .text(targetRef)
            .text(targetRevision)
            .text(targetDigest)
            .text(manifestDigest)
            .finish()
        return GovernanceDeletionTargetExecutionBinding.rehydrate(
            tenantId,
            planDigest,
            stepDigest,
            planningRequestDigest,
            planningIdentityDigest,
            stage,
            targetRef,
            targetRevision,
            targetDigest,
            manifestDigest,
            preparationDigest,
            bindingDigest,
        )
    }

    private fun executionBinding(
        manifest: GovernanceDeletionTargetManifest,
    ): GovernanceDeletionTargetExecutionBinding {
        val planDigest = digest('b')
        val stepDigest = digest('c')
        val preparationDigest = manifest.preparationDigest
        val bindingDigest = GovernanceRuntimeSupport.digest(
            "flowweft-governance-runtime-target-execution-binding-v1",
        )
            .text(manifest.tenantId)
            .text(planDigest)
            .text(stepDigest)
            .text(manifest.planningRequestDigest)
            .text(manifest.planningIdentityDigest)
            .text(preparationDigest)
            .text(manifest.stage.name)
            .text(manifest.targetRef)
            .text(manifest.targetRevision)
            .text(manifest.targetDigest)
            .text(manifest.manifestDigest)
            .finish()
        return GovernanceDeletionTargetExecutionBinding.rehydrate(
            manifest.tenantId,
            planDigest,
            stepDigest,
            manifest.planningRequestDigest,
            manifest.planningIdentityDigest,
            manifest.stage,
            manifest.targetRef,
            manifest.targetRevision,
            manifest.targetDigest,
            manifest.manifestDigest,
            preparationDigest,
            bindingDigest,
        )
    }

    private fun preparedOperation(
        binding: GovernanceDeletionTargetExecutionBinding,
        item: GovernanceDeletionTargetItem,
        operationReference: String,
    ): GovernanceDeletionTargetItemOperation {
        val executionRequestDigest = digest('d')
        val operationKeyDigest = GovernanceDeletionTargetItemOperation.calculateOperationKeyDigest(
            binding,
            item.itemBindingDigest,
        )
        val stateDigest = GovernanceRuntimeSupport.digest(
            "flowweft-governance-runtime-target-item-operation-v1",
        )
            .text(operationKeyDigest)
            .text(binding.bindingDigest)
            .text(item.providerId)
            .text(item.providerRevision)
            .text(operationReference)
            .text(executionRequestDigest)
            .longValue(1_100L)
            .text(GovernanceDeletionTargetItemOperationStatus.PREPARED.code)
            .longValue(1L)
            .longValue(1_000L)
            .optionalText(null)
            .optionalText(null)
            .optionalText(null)
            .optionalText(null)
            .optionalText(null)
            .optionalText(null)
            .longValue(1_000L)
            .finish()
        return GovernanceDeletionTargetItemOperation.rehydrate(
            binding,
            item.itemBindingDigest,
            item.providerId,
            item.providerRevision,
            operationReference,
            executionRequestDigest,
            1_100L,
            GovernanceDeletionTargetItemOperationStatus.PREPARED,
            1L,
            1_000L,
            null,
            null,
            null,
            null,
            null,
            null,
            1_000L,
            operationKeyDigest,
            stateDigest,
        )
    }

    private fun item(
        ordinal: Int,
        itemReference: String = "file-object-1",
        itemDigest: String = digest('7'),
    ): GovernanceDeletionTargetItem = GovernanceDeletionTargetItem.of(
        ordinal,
        GovernanceDeletionTargetItemKind.OBJECT_CONTENT,
        itemReference,
        "storage-r1",
        itemDigest,
        "storage-provider",
        "provider-r1",
    )

    private fun planningRequest(
        tenantId: String,
        principalId: String,
        requestId: String,
        authorizationId: String,
    ): GovernanceDeletionTargetRequest {
        val principal = GovernancePrincipalRef.of("user", principalId)
        val resource = GovernanceResourceRef.of("document", "document-1", "resource-r1", digest('8'))
        val purpose = GovernancePurpose.PLAN_SECURE_DELETION
        val authorization = GovernanceAuthorizationSnapshot.of(
            authorizationId,
            tenantId,
            principal,
            purpose,
            resource,
            "host-authorization",
            "authority-r1",
            "authorization-r1",
            digest('9'),
            900L,
            2_000L,
        )
        val context = GovernanceCallContext.of(
            requestId,
            tenantId,
            principal,
            purpose,
            authorization,
            "shared-idempotency-key",
            1_000L,
            1_100L,
        )
        return GovernanceDeletionTargetRequest.of(context, digest('a'))
    }

    private fun digest(character: Char): String = character.toString().repeat(64)
}
