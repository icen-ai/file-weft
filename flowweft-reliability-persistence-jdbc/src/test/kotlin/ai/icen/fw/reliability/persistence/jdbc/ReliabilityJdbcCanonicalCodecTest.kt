package ai.icen.fw.reliability.persistence.jdbc

import ai.icen.fw.reliability.api.ReliabilityAction
import ai.icen.fw.reliability.api.ReliabilityAuthorizationSnapshot
import ai.icen.fw.reliability.api.ReliabilityBurnRateAlert
import ai.icen.fw.reliability.api.ReliabilityBurnRatePolicy
import ai.icen.fw.reliability.api.ReliabilityCallContext
import ai.icen.fw.reliability.api.ReliabilityErrorBudgetEvaluation
import ai.icen.fw.reliability.api.ReliabilityPrincipalRef
import ai.icen.fw.reliability.api.ReliabilityPurpose
import ai.icen.fw.reliability.api.ReliabilityResourceRef
import ai.icen.fw.reliability.api.ReliabilitySliKind
import ai.icen.fw.reliability.api.ReliabilitySliObservation
import ai.icen.fw.reliability.api.ReliabilitySloEvaluationRequest
import ai.icen.fw.reliability.api.ReliabilitySloObjective
import ai.icen.fw.reliability.runtime.ReliabilityRunLease
import ai.icen.fw.reliability.runtime.ReliabilitySloEvaluationRecord
import ai.icen.fw.reliability.runtime.ReliabilitySloSchedule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ReliabilityJdbcCanonicalCodecTest {
    @Test
    fun `round trips an independently digest-checked schedule canonically`() {
        val schedule = ReliabilitySloSchedule.of(
            "schedule-1",
            "tenant-1",
            DIGEST_A,
            ReliabilityResourceRef.of("service", "service-1", "revision-1", DIGEST_B),
            60_000L,
            120_000L,
            0L,
            null,
            null,
            1_000L,
        )

        val bytes = ReliabilityJdbcCanonicalCodec.encodeSchedule(schedule)
        val restored = ReliabilityJdbcCanonicalCodec.decodeSchedule(bytes)

        assertEquals(schedule.stateDigest, restored.stateDigest)
        assertEquals(schedule.objectiveResource, restored.objectiveResource)
        assertEquals(bytes.toList(), ReliabilityJdbcCanonicalCodec.encodeSchedule(restored).toList())
    }

    @Test
    fun `rejects truncation and trailing bytes instead of accepting partial state`() {
        val schedule = ReliabilitySloSchedule.of(
            "schedule-1",
            "tenant-1",
            DIGEST_A,
            ReliabilityResourceRef.of("service", "service-1", "revision-1", DIGEST_B),
            60_000L,
            120_000L,
            0L,
            null,
            null,
            1_000L,
        )
        val bytes = ReliabilityJdbcCanonicalCodec.encodeSchedule(schedule)

        assertFailsWith<IllegalArgumentException> {
            ReliabilityJdbcCanonicalCodec.decodeSchedule(bytes.copyOf(bytes.size - 1))
        }
        assertFailsWith<IllegalArgumentException> {
            ReliabilityJdbcCanonicalCodec.decodeSchedule(bytes + 0.toByte())
        }
    }

    @Test
    fun `round trips complete SLO evidence without Java object serialization`() {
        val resource = ReliabilityResourceRef.of("service", "service-1", "revision-1", DIGEST_B)
        val principal = ReliabilityPrincipalRef.of("USER", "principal-1")
        val authorization = ReliabilityAuthorizationSnapshot.of(
            "authorization-1",
            "tenant-1",
            principal,
            ReliabilityPurpose.EVALUATE_SLO,
            ReliabilityAction.EVALUATE_SLO,
            resource,
            "host-policy",
            "revision-1",
            "authorization-revision-1",
            DIGEST_C,
            900L,
            2_000L,
        )
        val context = ReliabilityCallContext.of(
            "request-1",
            "tenant-1",
            principal,
            ReliabilityPurpose.EVALUATE_SLO,
            ReliabilityAction.EVALUATE_SLO,
            resource,
            authorization,
            DIGEST_D,
            1_000L,
            2_000L,
        )
        val objective = ReliabilitySloObjective.of(
            "slo-1", "1", DIGEST_A, resource, ReliabilitySliKind.AVAILABILITY,
            990_000L, 1_000L, 100L, 1_000L, 0L, 10_000L,
        )
        val observation = ReliabilitySliObservation.of(
            objective.objectiveDigest, 0L, 1_000L, 990L, 1_000L, 1_000L,
        )
        val request = ReliabilitySloEvaluationRequest.of(
            context, objective, observation, 0L, 1_000L, 1_500L,
        )
        val evaluation = ReliabilityErrorBudgetEvaluation.evaluate(request)
        val policy = ReliabilityBurnRatePolicy.of(
            "burn-1", "1", DIGEST_A, objective.objectiveDigest, 1_000_000L, 2_000_000L,
        )
        val alert = ReliabilityBurnRateAlert.evaluate(policy, evaluation, 1_500L)
        val record = ReliabilitySloEvaluationRecord.of(evaluation, alert)
        val schedule = ReliabilitySloSchedule.of(
            "schedule-1", "tenant-1", DIGEST_A, resource, 60_000L, 61_500L, 2L,
            ReliabilityRunLease.of("worker-1", 7L, 1_400L, 2_000L), record, 1_500L,
        )

        val restored = ReliabilityJdbcCanonicalCodec.decodeSchedule(
            ReliabilityJdbcCanonicalCodec.encodeSchedule(schedule),
        )

        assertEquals(schedule.stateDigest, restored.stateDigest)
        assertEquals(record.recordDigest, restored.lastRecord?.recordDigest)
        assertEquals(evaluation.evaluationDigest, restored.lastRecord?.evaluation?.evaluationDigest)
        assertEquals(alert.alertDigest, restored.lastRecord?.alert?.alertDigest)
    }

    companion object {
        private const val DIGEST_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        private const val DIGEST_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        private const val DIGEST_C = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        private const val DIGEST_D = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
    }
}
