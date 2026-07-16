package ai.icen.fw.workflow.persistence.jdbc

import ai.icen.fw.workflow.api.WorkflowDefinition
import ai.icen.fw.workflow.api.WorkflowDefinitionStatus
import ai.icen.fw.workflow.api.WorkflowNodeDefinition
import ai.icen.fw.workflow.api.WorkflowNodeKind
import ai.icen.fw.workflow.api.WorkflowPrincipalRef
import ai.icen.fw.workflow.api.WorkflowSubjectRef
import ai.icen.fw.workflow.api.WorkflowSubjectSnapshot
import ai.icen.fw.workflow.api.WorkflowTransitionDefinition
import ai.icen.fw.workflow.domain.WorkflowCommandContext
import ai.icen.fw.workflow.domain.WorkflowDefinitionExecutionReceipt
import ai.icen.fw.workflow.domain.WorkflowDefinitionIndex
import ai.icen.fw.workflow.domain.WorkflowDomainEngine
import ai.icen.fw.workflow.domain.WorkflowExecutionIds
import ai.icen.fw.workflow.domain.WorkflowEffectCode
import ai.icen.fw.workflow.domain.WorkflowIdempotencyReceipt
import ai.icen.fw.workflow.domain.WorkflowStartCommand
import ai.icen.fw.workflow.runtime.WorkflowRuntimeAtomicCommit
import ai.icen.fw.workflow.runtime.WorkflowRuntimeCommitCode
import ai.icen.fw.workflow.runtime.WorkflowRuntimeDefinitionRecord
import ai.icen.fw.workflow.runtime.WorkflowReadyEffectJobClaimRequest
import ai.icen.fw.workflow.runtime.WorkflowEffectCoordinator
import ai.icen.fw.workflow.runtime.WorkflowEffectDeliveryStatus
import ai.icen.fw.workflow.runtime.WorkflowEffectExecutionPhase
import ai.icen.fw.workflow.runtime.WorkflowEffectJobExecutionMode
import ai.icen.fw.workflow.runtime.WorkflowEffectJobResultCheckpoint
import ai.icen.fw.workflow.runtime.WorkflowEffectJobStoreCode
import ai.icen.fw.workflow.runtime.WorkflowEffectJobStoredResult
import ai.icen.fw.workflow.runtime.WorkflowEffectObservedOutcome
import ai.icen.fw.workflow.runtime.WorkflowRuntimeAction
import ai.icen.fw.workflow.runtime.WorkflowRuntimeAuthorizationDecision
import ai.icen.fw.workflow.runtime.WorkflowRuntimeAuthorizationPort
import ai.icen.fw.workflow.runtime.WorkflowRuntimeAuthorizationRequest
import ai.icen.fw.workflow.runtime.WorkflowRuntimeAuthorizationStatus
import ai.icen.fw.workflow.runtime.WorkflowRuntimeHumanDecisionReceiptRequest
import ai.icen.fw.workflow.runtime.WorkflowTrustedCallContext
import java.io.InputStreamReader
import kotlin.test.assertContentEquals
import org.h2.jdbcx.JdbcDataSource
import org.h2.tools.RunScript
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class JdbcWorkflowRuntimePersistenceH2Test {
    @Test
    fun `atomic start persists state event effect idempotency and tenant isolation`() {
        val dataSource = JdbcDataSource().apply {
            setURL(
                "jdbc:h2:mem:workflow-${System.nanoTime()};" +
                    "MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            )
            user = "sa"
            password = ""
        }
        dataSource.connection.use { connection ->
            listOf(
                "/ai/icen/fw/workflow/db/migration/mysql/V030__create_flowweft_workflow_runtime.sql",
                "/ai/icen/fw/workflow/db/migration/mysql/V032__create_flowweft_workflow_human_collaboration.sql",
                "/ai/icen/fw/workflow/db/migration/mysql/V033__fence_flowweft_workflow_effect_jobs.sql",
            ).forEach { path ->
                val resource = requireNotNull(javaClass.getResourceAsStream(path))
                InputStreamReader(resource, Charsets.UTF_8).use { reader -> RunScript.execute(connection, reader) }
            }
        }

        val definition = definition()
        val index = WorkflowDefinitionIndex.compile(definition)
        val executionReceipt = WorkflowDefinitionExecutionReceipt.of(
            "receipt-deployment",
            TENANT,
            DEFINITION_ID,
            definition.ref,
            definition.schemaVersion,
            DIGEST_B,
            1L,
            10_000L,
        )
        val definitionStore = JdbcWorkflowDefinitionStore(dataSource, WorkflowJdbcDialect.MYSQL)
        definitionStore.install(WorkflowRuntimeDefinitionRecord.of(index, executionReceipt), 1L)

        val started = WorkflowDomainEngine.start(
            index,
            WorkflowStartCommand.of(
                context(),
                TENANT,
                INSTANCE_ID,
                DEFINITION_ID,
                definition.ref,
                SUBJECT,
                WorkflowPrincipalRef.of("user", "starter"),
                executionReceipt,
            ),
        )
        val atomic = WorkflowRuntimeAtomicCommit.fromDomain(
            started,
            DIGEST_C,
            0L,
            null,
            null,
            10L,
        )
        val persistence = JdbcWorkflowRuntimePersistence(dataSource, WorkflowJdbcDialect.MYSQL)
        val committed = persistence.commit(atomic)
        assertSame(WorkflowRuntimeCommitCode.COMMITTED, committed.code)

        val snapshot = persistence.loadCommandSnapshot(TENANT, INSTANCE_ID, IDEMPOTENCY_KEY, 11L)
        assertEquals(started.state!!.stateDigest, snapshot.state!!.stateDigest)
        assertEquals(DIGEST_C, snapshot.idempotency!!.logicalRequestDigest)
        assertNotNull(persistence.loadDefinition(TENANT, DEFINITION_ID, definition.ref))
        assertNotNull(persistence.loadEffect(TENANT, started.effects.single().effectId, 11L))

        val queue = JdbcWorkflowReadyEffectJobQueue(dataSource, WorkflowJdbcDialect.MYSQL)
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "UPDATE fw_wf_instance SET status = 'suspended' WHERE tenant_id = ? AND id = ?",
            ).use { statement ->
                statement.setString(1, TENANT)
                statement.setString(2, INSTANCE_ID)
                assertEquals(1, statement.executeUpdate())
            }
        }
        assertTrue(queue.claimReady(
            WorkflowReadyEffectJobClaimRequest.of(
                TENANT,
                WorkflowEffectCode.SERVICE_TASK,
                "worker-suspended",
                "claim-suspended",
                11L,
                20L,
                1,
            ),
        ).isEmpty())
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "UPDATE fw_wf_instance SET status = 'waiting' WHERE tenant_id = ? AND id = ?",
            ).use { statement ->
                statement.setString(1, TENANT)
                statement.setString(2, INSTANCE_ID)
                assertEquals(1, statement.executeUpdate())
            }
        }
        val firstRequest = WorkflowReadyEffectJobClaimRequest.of(
            TENANT,
            WorkflowEffectCode.SERVICE_TASK,
            "worker-a",
            "claim-a",
            11L,
            20L,
            1,
        )
        val firstClaim = queue.claimReady(firstRequest).single()
        assertEquals(started.effects.single().effectId, firstClaim.effectId)
        assertEquals(firstRequest.requestDigest, firstClaim.claimRequestDigest)
        assertEquals(firstClaim.claimReceiptDigest, queue.loadClaims(firstRequest, 12L).single().claimReceiptDigest)
        assertTrue(queue.claimReady(
            WorkflowReadyEffectJobClaimRequest.of(
                TENANT,
                WorkflowEffectCode.SERVICE_TASK,
                "worker-b",
                "claim-b",
                12L,
                19L,
                1,
            ),
        ).isEmpty())
        val reclaimed = queue.claimReady(
            WorkflowReadyEffectJobClaimRequest.of(
                TENANT,
                WorkflowEffectCode.SERVICE_TASK,
                "worker-b",
                "claim-c",
                21L,
                30L,
                1,
            ),
        ).single()
        assertTrue(reclaimed.lease.fencingToken > firstClaim.lease.fencingToken)

        val workerContext = WorkflowTrustedCallContext.of(
            TENANT,
            WorkflowPrincipalRef.of("service", "workflow-worker"),
            "worker-authentication",
            DIGEST_A,
        )
        val coordinator = WorkflowEffectCoordinator(
            object : WorkflowRuntimeAuthorizationPort {
                override fun authorize(request: WorkflowRuntimeAuthorizationRequest) =
                    WorkflowRuntimeAuthorizationDecision.of(
                        "authorization-${request.action.code}",
                        request.callContext.tenantId,
                        request.callContext.actor,
                        request.action,
                        request.instanceId,
                        request.requestDigest,
                        WorkflowRuntimeAuthorizationStatus.AUTHORIZED,
                        "revision-1",
                        DIGEST_A,
                        request.evaluatedAt,
                        100L,
                    )

                override fun issueHumanDecisionReceipt(request: WorkflowRuntimeHumanDecisionReceiptRequest) =
                    throw UnsupportedOperationException()
            },
            persistence,
        )
        val effectClaim = coordinator.claim(
            workerContext,
            reclaimed.effectId,
            reclaimed.lease.workerId,
            reclaimed.lease.leaseId,
            reclaimed.expectedEffectVersion,
            reclaimed.lease.fencingToken,
            21L,
            reclaimed.lease.expiresAt,
        )
        val premature = WorkflowEffectJobStoredResult.of(
            WorkflowEffectObservedOutcome.SUCCEEDED,
            "premature-result-v1",
            DIGEST_C,
            byteArrayOf(0, 0xff.toByte()),
            null,
            21L,
        )
        assertSame(
            WorkflowEffectJobStoreCode.LEASE_MISMATCH,
            queue.storeResult(
                WorkflowEffectJobResultCheckpoint.of(
                    reclaimed,
                    requireNotNull(effectClaim.record).version,
                    premature,
                    21L,
                ),
            ).code,
        )
        val checkpointed = coordinator.checkpoint(
            workerContext,
            reclaimed.effectId,
            requireNotNull(effectClaim.record).version,
            reclaimed.lease.leaseId,
            reclaimed.lease.fencingToken,
            1L,
            WorkflowEffectExecutionPhase.PROVIDER_CALL_STARTED,
            DIGEST_B,
            22L,
        )
        val storedResult = WorkflowEffectJobStoredResult.of(
            WorkflowEffectObservedOutcome.SUCCEEDED,
            "test-result-v1",
            DIGEST_C,
            byteArrayOf(0, 1, 0x7f, 0x80.toByte(), 0xff.toByte(), 0),
            null,
            23L,
        )
        queue.storeResult(
            WorkflowEffectJobResultCheckpoint.of(
                reclaimed,
                checkpointed.record!!.version,
                storedResult,
                23L,
            ),
        )

        // Simulate a process crash after result persistence but before recordEffectOutcome.
        val recovered = queue.claimReady(
            WorkflowReadyEffectJobClaimRequest.of(
                TENANT,
                WorkflowEffectCode.SERVICE_TASK,
                "worker-c",
                "claim-recovery",
                31L,
                40L,
                1,
            ),
        ).single()
        assertSame(WorkflowEffectJobExecutionMode.APPLY_SUCCEEDED_RESULT, recovered.mode)
        assertEquals(DIGEST_C, recovered.storedResult!!.resultDigest)
        assertContentEquals(storedResult.bytes(), recovered.storedResult!!.bytes())
        val recoveredEffect = persistence.loadEffect(TENANT, recovered.effectId, 31L)!!
        assertSame(WorkflowEffectDeliveryStatus.SUCCEEDED, recoveredEffect.status)
        assertEquals(DIGEST_C, recoveredEffect.outcomeDigest)

        val otherTenant = persistence.loadCommandSnapshot("tenant-other", INSTANCE_ID, IDEMPOTENCY_KEY, 11L)
        assertNull(otherTenant.state)
        assertNull(otherTenant.idempotency)

        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT COUNT(*) FROM fw_wf_event WHERE tenant_id = ?").use { statement ->
                statement.setString(1, TENANT)
                statement.executeQuery().use { result -> result.next(); assertEquals(started.events.size, result.getInt(1)) }
            }
            connection.prepareStatement("SELECT COUNT(*) FROM fw_wf_job WHERE tenant_id = ?").use { statement ->
                statement.setString(1, TENANT)
                statement.executeQuery().use { result -> result.next(); assertEquals(started.effects.size, result.getInt(1)) }
            }
            connection.prepareStatement(
                "SELECT COUNT(*) FROM fw_wf_human_collaboration_event WHERE tenant_id = ?",
            ).use { statement ->
                statement.setString(1, TENANT)
                statement.executeQuery().use { result -> result.next(); assertEquals(0, result.getInt(1)) }
            }
        }
    }

    @Test
    fun `expired started provider call without durable result becomes unknown and is never reclaimed`() {
        val dataSource = JdbcDataSource().apply {
            setURL(
                "jdbc:h2:mem:workflow-unknown-${System.nanoTime()};" +
                    "MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            )
            user = "sa"
            password = ""
        }
        dataSource.connection.use { connection ->
            listOf(
                "/ai/icen/fw/workflow/db/migration/mysql/V030__create_flowweft_workflow_runtime.sql",
                "/ai/icen/fw/workflow/db/migration/mysql/V032__create_flowweft_workflow_human_collaboration.sql",
                "/ai/icen/fw/workflow/db/migration/mysql/V033__fence_flowweft_workflow_effect_jobs.sql",
            ).forEach { path ->
                InputStreamReader(requireNotNull(javaClass.getResourceAsStream(path)), Charsets.UTF_8).use { reader ->
                    RunScript.execute(connection, reader)
                }
            }
        }
        val definition = definition()
        val index = WorkflowDefinitionIndex.compile(definition)
        val executionReceipt = WorkflowDefinitionExecutionReceipt.of(
            "receipt-unknown",
            TENANT,
            DEFINITION_ID,
            definition.ref,
            definition.schemaVersion,
            DIGEST_B,
            1L,
            10_000L,
        )
        JdbcWorkflowDefinitionStore(dataSource, WorkflowJdbcDialect.MYSQL).install(
            WorkflowRuntimeDefinitionRecord.of(index, executionReceipt),
            1L,
        )
        val started = WorkflowDomainEngine.start(
            index,
            WorkflowStartCommand.of(
                context(),
                TENANT,
                INSTANCE_ID,
                DEFINITION_ID,
                definition.ref,
                SUBJECT,
                WorkflowPrincipalRef.of("user", "starter"),
                executionReceipt,
            ),
        )
        val persistence = JdbcWorkflowRuntimePersistence(dataSource, WorkflowJdbcDialect.MYSQL)
        assertSame(
            WorkflowRuntimeCommitCode.COMMITTED,
            persistence.commit(WorkflowRuntimeAtomicCommit.fromDomain(started, DIGEST_C, 0L, null, null, 10L)).code,
        )
        val queue = JdbcWorkflowReadyEffectJobQueue(dataSource, WorkflowJdbcDialect.MYSQL)
        val claim = queue.claimReady(
            WorkflowReadyEffectJobClaimRequest.of(
                TENANT,
                WorkflowEffectCode.SERVICE_TASK,
                "unknown-worker",
                "unknown-claim",
                11L,
                20L,
                1,
            ),
        ).single()
        val coordinator = WorkflowEffectCoordinator(allowingAuthorization(), persistence)
        val effectClaim = coordinator.claim(
            workerContext(),
            claim.effectId,
            claim.lease.workerId,
            claim.lease.leaseId,
            claim.expectedEffectVersion,
            claim.lease.fencingToken,
            11L,
            claim.lease.expiresAt,
        )
        val checkpoint = coordinator.checkpoint(
            workerContext(),
            claim.effectId,
            requireNotNull(effectClaim.record).version,
            claim.lease.leaseId,
            claim.lease.fencingToken,
            1L,
            WorkflowEffectExecutionPhase.PROVIDER_CALL_STARTED,
            DIGEST_B,
            12L,
        )
        assertNotNull(checkpoint.record)

        val afterCrash = queue.claimReady(
            WorkflowReadyEffectJobClaimRequest.of(
                TENANT,
                WorkflowEffectCode.SERVICE_TASK,
                "recovery-worker",
                "recovery-claim",
                21L,
                30L,
                1,
            ),
        )

        assertTrue(afterCrash.isEmpty())
        val unknown = requireNotNull(persistence.loadEffect(TENANT, claim.effectId, 21L))
        assertSame(WorkflowEffectDeliveryStatus.OUTCOME_UNKNOWN, unknown.status)
        assertNotNull(unknown.outcomeDigest)
        assertNull(queue.loadClaim(TENANT, claim.jobId, 21L))
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT job_status, failure_digest FROM fw_wf_job WHERE tenant_id = ? AND id = ?",
            ).use { statement ->
                statement.setString(1, TENANT)
                statement.setString(2, claim.jobId)
                statement.executeQuery().use { result ->
                    assertTrue(result.next())
                    assertEquals(WorkflowEffectDeliveryStatus.OUTCOME_UNKNOWN.code, result.getString(1))
                    assertNotNull(result.getString(2))
                }
            }
            connection.prepareStatement(
                "SELECT COUNT(*) FROM fw_wf_effect_result WHERE tenant_id = ? AND effect_id = ?",
            ).use { statement ->
                statement.setString(1, TENANT)
                statement.setString(2, claim.effectId)
                statement.executeQuery().use { result ->
                    assertTrue(result.next())
                    assertEquals(0, result.getInt(1))
                }
            }
        }
    }

    private fun workerContext(): WorkflowTrustedCallContext = WorkflowTrustedCallContext.of(
        TENANT,
        WorkflowPrincipalRef.of("service", "workflow-worker"),
        "worker-authentication",
        DIGEST_A,
    )

    private fun allowingAuthorization(): WorkflowRuntimeAuthorizationPort = object : WorkflowRuntimeAuthorizationPort {
        override fun authorize(request: WorkflowRuntimeAuthorizationRequest) = WorkflowRuntimeAuthorizationDecision.of(
            "authorization-${request.action.code}",
            request.callContext.tenantId,
            request.callContext.actor,
            request.action,
            request.instanceId,
            request.requestDigest,
            WorkflowRuntimeAuthorizationStatus.AUTHORIZED,
            "revision-1",
            DIGEST_A,
            request.evaluatedAt,
            100L,
        )

        override fun issueHumanDecisionReceipt(request: WorkflowRuntimeHumanDecisionReceiptRequest) =
            throw UnsupportedOperationException()
    }

    private fun context(): WorkflowCommandContext = WorkflowCommandContext.of(
        "command-start",
        IDEMPOTENCY_KEY,
        0L,
        10L,
        64,
        WorkflowExecutionIds.of(
            (0 until 8).map { "token-$it" },
            (0 until 8).map { "execution-$it" },
            (0 until 4).map { "work-$it" },
            (0 until 8).map { "effect-$it" },
            (0 until 32).map { "event-$it" },
            (0 until 4).map { "scope-$it" },
        ),
        WorkflowIdempotencyReceipt.fresh(TENANT, INSTANCE_ID, IDEMPOTENCY_KEY, 10L),
    )

    private fun definition(): WorkflowDefinition = WorkflowDefinition.of(
        TENANT,
        DEFINITION_ID,
        "service-flow",
        "v1",
        1,
        WorkflowDefinitionStatus.PUBLISHED,
        "服务任务流程",
        null,
        listOf(
            WorkflowNodeDefinition.of("start", WorkflowNodeKind.START, "开始", null),
            WorkflowNodeDefinition.serviceTask("service", "服务", null, DIGEST_A, DIGEST_B),
            WorkflowNodeDefinition.of("end", WorkflowNodeKind.END, "结束", null),
        ),
        listOf(
            WorkflowTransitionDefinition.unconditional("start-service", "start", "service"),
            WorkflowTransitionDefinition.unconditional("service-end", "service", "end"),
        ),
    )

    private companion object {
        const val TENANT = "tenant-tianjin"
        const val INSTANCE_ID = "instance-001"
        const val DEFINITION_ID = "definition-001"
        const val IDEMPOTENCY_KEY = "idempotency-start"
        const val DIGEST_A = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        const val DIGEST_B = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
        const val DIGEST_C = "1111111111111111111111111111111111111111111111111111111111111111"
        val SUBJECT = WorkflowSubjectSnapshot.of(
            WorkflowSubjectRef.of("business-record", "record-001"),
            "revision-1",
            DIGEST_A,
        )
    }
}
