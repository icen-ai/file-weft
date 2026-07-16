package ai.icen.fw.workflow.runtime

import ai.icen.fw.workflow.api.WorkflowPrincipalRef
import ai.icen.fw.workflow.api.WorkflowSubjectRef
import ai.icen.fw.workflow.api.WorkflowSubjectSnapshot
import ai.icen.fw.workflow.spi.WorkflowBusinessCalendar
import ai.icen.fw.workflow.spi.WorkflowBusinessCalendarRef
import ai.icen.fw.workflow.spi.WorkflowBusinessTimeResult
import ai.icen.fw.workflow.spi.WorkflowBusinessTimeValue
import ai.icen.fw.workflow.spi.WorkflowProviderCallContext
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WorkflowBusinessCalendarRuntimeTest {
    @Test
    fun `authorized evaluation is rebound after provider call and returns durable evidence`() {
        val fixture = Fixture()
        val result = fixture.runtime.evaluate(fixture.command())

        assertEquals(WorkflowBusinessCalendarResultCode.SUCCEEDED, result.code)
        assertNotNull(result.evaluation)
        assertNull(result.diagnostic)
        assertEquals(2, fixture.authorization.calls)
        assertEquals(220L, result.evaluation?.value?.resultingEpochMilli)
    }

    @Test
    fun `mid-call revocation discards an otherwise valid provider result`() {
        val fixture = Fixture(revokeOnSecondAuthorization = true)
        val result = fixture.runtime.evaluate(fixture.command())

        assertEquals(WorkflowBusinessCalendarResultCode.AUTHORIZATION_DENIED, result.code)
        assertNull(result.evaluation)
        assertEquals("authorization-revoked", result.diagnostic?.code)
    }

    @Test
    fun `foreign receipt and calendar revision drift fail closed`() {
        val fixture = Fixture(foreignReceipt = true)
        val foreign = fixture.runtime.evaluate(fixture.command())
        assertEquals(WorkflowBusinessCalendarResultCode.RECEIPT_INVALID, foreign.code)

        val drift = Fixture(calendarRevision = "calendar-v2")
        val drifted = drift.runtime.evaluate(drift.command())
        assertEquals(WorkflowBusinessCalendarResultCode.RECEIPT_INVALID, drifted.code)
        assertEquals("provider-value-invalid", drifted.diagnostic?.code)
    }

    private class Fixture(
        revokeOnSecondAuthorization: Boolean = false,
        private val foreignReceipt: Boolean = false,
        private val calendarRevision: String = "calendar-v1",
    ) {
        val clock = MutableClock(100L)
        val authorization = TestAuthorization(revokeOnSecondAuthorization)
        val calendar = WorkflowBusinessCalendarRef.of("calendar-provider", "cn-workdays", "calendar-v1", sha('c'))
        private val provider = WorkflowBusinessCalendar { request ->
            clock.now = 120L
            val context = if (foreignReceipt) {
                WorkflowProviderCallContext.of(
                    "foreign-request",
                    request.context.tenantId,
                    request.context.providerId,
                    request.context.providerRevision,
                    request.context.purpose,
                    request.context.requestedAtEpochMilli,
                    request.context.deadlineEpochMilli,
                    request.context.maximumInputBytes,
                    request.context.maximumOutputBytes,
                    request.context.maximumItems,
                )
            } else {
                request.context
            }
            val rebound = ai.icen.fw.workflow.spi.WorkflowBusinessTimeRequest.addWorkingDuration(
                context,
                request.calendar,
                request.instantEpochMilli,
                request.workingDurationMillis!!,
            )
            CompletableFuture.completedFuture(
                WorkflowBusinessTimeResult.success(
                    rebound,
                    WorkflowBusinessTimeValue.instant(220L, calendarRevision),
                    120L,
                    200L,
                ),
            )
        }
        val runtime = WorkflowBusinessCalendarRuntime(
            authorization,
            provider,
            WorkflowBusinessCalendarProfile.of("calendar-provider", "provider-r1", 200L, 1024, 1024),
            clock,
        )

        fun command(): WorkflowBusinessCalendarCommand = WorkflowBusinessCalendarCommand.addWorkingDuration(
            trustedContext(),
            "calendar-request-1",
            "instance-1",
            subject(),
            calendar,
            150L,
            70L,
        )
    }

    private class TestAuthorization(
        private val revokeOnSecondAuthorization: Boolean,
    ) : WorkflowRuntimeAuthorizationPort {
        var calls: Int = 0

        override fun authorize(request: WorkflowRuntimeAuthorizationRequest): WorkflowRuntimeAuthorizationDecision {
            calls += 1
            val denied = revokeOnSecondAuthorization && calls > 1
            return WorkflowRuntimeAuthorizationDecision.of(
                "authorization-$calls",
                request.callContext.tenantId,
                request.callContext.actor,
                request.action,
                request.instanceId,
                request.requestDigest,
                if (denied) WorkflowRuntimeAuthorizationStatus.DENIED else WorkflowRuntimeAuthorizationStatus.AUTHORIZED,
                "authority-r$calls",
                sha(if (denied) 'd' else 'a'),
                request.evaluatedAt,
                1_000L,
            )
        }

        override fun issueHumanDecisionReceipt(
            request: WorkflowRuntimeHumanDecisionReceiptRequest,
        ): ai.icen.fw.workflow.domain.WorkflowHumanDecisionAuthorizationReceipt =
            throw UnsupportedOperationException("Not used by calendar evaluation.")
    }

    private class MutableClock(var now: Long) : WorkflowWorkerClock {
        override fun currentTimeMillis(): Long = now
    }

    companion object {
        private fun trustedContext(): WorkflowTrustedCallContext = WorkflowTrustedCallContext.of(
            "tenant-1",
            WorkflowPrincipalRef.of("user", "alice"),
            "authentication-1",
            sha('f'),
        )

        private fun subject(): WorkflowSubjectSnapshot = WorkflowSubjectSnapshot.of(
            WorkflowSubjectRef.of("case", "case-1"),
            "subject-r1",
            sha('b'),
        )

        private fun sha(character: Char): String = character.toString().repeat(64)
    }
}
