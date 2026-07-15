package ai.icen.fw.workflow.spi

import ai.icen.fw.workflow.api.WorkflowDefinitionRef
import ai.icen.fw.workflow.api.WorkflowInstanceRef
import ai.icen.fw.workflow.api.WorkflowPrincipalRef
import ai.icen.fw.workflow.api.WorkflowSubjectRef
import ai.icen.fw.workflow.api.WorkflowSubjectSnapshot
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WorkflowSpiContractsTest {
    @Test
    fun `provider receipt is bound to exact tenant revision deadline limits and request`() {
        val context = context(providerRevision = "provider-r7")
        val receipt = WorkflowProviderReceipt.success(
            context,
            digest('b'),
            digest('c'),
            1_050L,
            1_100L,
        )

        assertEquals("tenant-a", receipt.tenantId)
        assertEquals("provider-r7", receipt.providerRevision)
        assertEquals(context.contextDigest, receipt.contextDigest)
        assertEquals(digest('b'), receipt.requestDigest)
        assertNotEquals(context(providerRevision = "provider-r8").contextDigest, receipt.contextDigest)
    }

    @Test
    fun `structured payload and definition document defensively copy bytes`() {
        val schema = schema()
        val source = "{}".toByteArray()
        val payload = WorkflowStructuredPayload.of(schema, source)
        source[0] = 'x'.code.toByte()

        assertContentEquals("{}".toByteArray(), payload.bytes())
        val returned = payload.bytes()
        returned[0] = 'y'.code.toByte()
        assertContentEquals("{}".toByteArray(), payload.bytes())
        assertFalse(payload.validated)

        val validated = validatedPayload(schema, fieldCount = 0)
        assertTrue(validated.validated)
        assertEquals(0, validated.fieldCount)
        assertNotEquals(validated.canonicalPayloadDigest, validated.contentDigest)
        assertFailsWith<IllegalArgumentException> {
            WorkflowStructuredPayload.validated(
                schema,
                "{}".toByteArray(),
                WorkflowPayloadValidationReceipt.of(
                    "schema-validator",
                    "r1",
                    schema,
                    digest('0'),
                    0,
                    digest('e'),
                ),
            )
        }

        val format = WorkflowDefinitionFormatRef.of(
            "flowweft-neutral",
            "1",
            WorkflowDefinitionMediaType.FLOWWEFT_JSON,
            digest('d'),
        )
        val document = WorkflowDefinitionDocument.of(format, "{}".toByteArray())
        val documentBytes = document.bytes()
        documentBytes[0] = 'z'.code.toByte()
        assertContentEquals("{}".toByteArray(), document.bytes())
    }

    @Test
    fun `unknown or lossy codec values remain non executable`() {
        val format = WorkflowDefinitionFormatRef.of(
            "flowweft-neutral",
            "1",
            WorkflowDefinitionMediaType.FLOWWEFT_JSON,
            digest('d'),
        )
        val unknownEntry = WorkflowConformanceEntry.of(
            "node-1",
            "service-task",
            WorkflowConformanceStatus.of("future-status"),
            "future-semantics",
        )
        val lossyEntry = WorkflowConformanceEntry.of(
            "node-2",
            "boundary-event",
            WorkflowConformanceStatus.LOSSY,
            "not-round-trippable",
        )
        val supportedEntry = WorkflowConformanceEntry.of(
            "node-3",
            "human-task",
            WorkflowConformanceStatus.SUPPORTED,
            "exact",
        )

        assertFalse(
            WorkflowDefinitionConformanceReport.of(
                format,
                digest('e'),
                digest('f'),
                listOf(unknownEntry),
            ).executable,
        )
        assertFalse(
            WorkflowDefinitionConformanceReport.of(
                format,
                digest('e'),
                digest('f'),
                listOf(supportedEntry),
            ).executable,
        )
        val feature = WorkflowConformanceFeatureRef.of("node-3", "human-task")
        val coverage = WorkflowConformanceCoverage.complete(1, listOf(feature))
        assertTrue(
            WorkflowDefinitionConformanceReport.complete(
                format,
                digest('e'),
                digest('f'),
                listOf(supportedEntry),
                coverage,
            ).executable,
        )
        assertFailsWith<IllegalArgumentException> {
            WorkflowDefinitionConformanceReport.complete(
                format,
                digest('e'),
                digest('f'),
                listOf(supportedEntry),
                WorkflowConformanceCoverage.complete(
                    1,
                    listOf(WorkflowConformanceFeatureRef.of("another-node", "human-task")),
                ),
            )
        }
        assertFalse(
            WorkflowDefinitionConformanceReport.of(
                format,
                digest('e'),
                digest('f'),
                listOf(lossyEntry),
            ).executable,
        )
        assertFailsWith<IllegalArgumentException> {
            WorkflowDefinitionCodecDescriptor.of(
                "codec-provider",
                "r1",
                "codec",
                "1",
                listOf(
                    WorkflowDefinitionFormatRef.of(
                        "future-format",
                        "1",
                        WorkflowDefinitionMediaType.of("application/future+json"),
                        digest('a'),
                    ),
                ),
                1024,
                16,
            )
        }
    }

    @Test
    fun `service task accepts only declared egress and exact expiring secret handles`() {
        val descriptor = serviceDescriptor()
        val call = context(providerRevision = "r1")
        val input = validatedPayload(descriptor.inputSchema)
        val subject = subject()

        val request = WorkflowServiceTaskRequest.of(
            call,
            descriptor,
            WorkflowDefinitionRef.of("expense", "1", digest('4')),
            WorkflowInstanceRef.of("instance-1", 2L),
            subject,
            WorkflowPrincipalRef.of("user", "u1"),
            "attempt-1",
            "idem-1",
            1,
            input,
            descriptor.allowedEgressProfiles,
            listOf(WorkflowSecretHandle.of("vault-key-1", "erp-token", "3", digest('5'), 1_200L)),
        )

        assertFailsWith<IllegalArgumentException> {
            WorkflowServiceTaskRequest.of(
                call,
                descriptor,
                WorkflowDefinitionRef.of("expense", "1", digest('4')),
                WorkflowInstanceRef.of("instance-1", 2L),
                subject,
                null,
                "attempt-1",
                "idem-1",
                1,
                input,
                emptyList(),
                emptyList(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            WorkflowSecretHandle.of("https://vault/key", "erp-token", "3", digest('5'), 1_200L)
        }
        val output = validatedPayload(descriptor.outputSchema)
        assertFailsWith<IllegalArgumentException> {
            WorkflowServiceTaskResult.success(request, output, 1_050L, 1_100L)
        }
        assertEquals(
            "erp-receipt-1",
            WorkflowServiceTaskResult.success(
                request,
                output,
                "erp-receipt-1",
                digest('e'),
                1_050L,
                1_100L,
            ).externalReceiptRef,
        )
    }

    @Test
    fun `service and compensation failure status stay consistent with retry policy`() {
        val descriptor = serviceDescriptor()
        val request = serviceRequest(descriptor, attemptNumber = 1)

        assertFailsWith<IllegalArgumentException> {
            WorkflowServiceTaskResult.failure(
                request,
                WorkflowServiceTaskStatus.RETRYABLE_FAILURE,
                WorkflowProviderOutcome.UNAVAILABLE,
                WorkflowProviderFailure.of("timeout", false),
                null,
                null,
                1_050L,
                1_100L,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            WorkflowServiceTaskResult.failure(
                request,
                WorkflowServiceTaskStatus.RETRYABLE_FAILURE,
                WorkflowProviderOutcome.UNAVAILABLE,
                WorkflowProviderFailure.of("not-declared", true),
                null,
                null,
                1_050L,
                1_100L,
            )
        }
        assertEquals(
            WorkflowServiceTaskStatus.RETRYABLE_FAILURE,
            WorkflowServiceTaskResult.failure(
                request,
                WorkflowServiceTaskStatus.RETRYABLE_FAILURE,
                WorkflowProviderOutcome.UNAVAILABLE,
                WorkflowProviderFailure.of("timeout", true),
                null,
                null,
                1_050L,
                1_100L,
            ).status,
        )
        assertFailsWith<IllegalArgumentException> {
            WorkflowServiceTaskResult.failure(
                serviceRequest(descriptor, attemptNumber = 3),
                WorkflowServiceTaskStatus.RETRYABLE_FAILURE,
                WorkflowProviderOutcome.UNAVAILABLE,
                WorkflowProviderFailure.of("timeout", true),
                null,
                null,
                1_050L,
                1_100L,
            )
        }

        val compensationRequest = WorkflowServiceTaskCompensationRequest.of(
            context(providerRevision = "r1"),
            descriptor,
            WorkflowInstanceRef.of("instance-1", 2L),
            digest('1'),
            digest('2'),
            "compensation-idem-1",
            "erp-receipt-1",
            digest('3'),
            descriptor.allowedEgressProfiles,
            listOf(WorkflowSecretHandle.of("vault-key-1", "erp-token", "3", digest('5'), 1_200L)),
        )
        assertFailsWith<IllegalArgumentException> {
            WorkflowServiceTaskCompensationResult.failure(
                compensationRequest,
                WorkflowCompensationStatus.OUTCOME_UNKNOWN,
                WorkflowProviderOutcome.FAILED,
                WorkflowProviderFailure.of("reconcile", true),
                1_050L,
                1_100L,
            )
        }
    }

    @Test
    fun `calendar version is independent from provider deployment revision`() {
        val calendar = WorkflowBusinessCalendarRef.of("provider-a", "china-mainland", "2026.7", digest('6'))
        val request = WorkflowBusinessTimeRequest.isWorkingInstant(
            context(providerRevision = "deployment-42"),
            calendar,
            1_010L,
        )

        assertEquals(calendar, request.calendar)
        assertFailsWith<IllegalArgumentException> {
            WorkflowBusinessTimeResult.success(
                request,
                WorkflowBusinessTimeValue.instant(1_010L, "calendar-r1"),
                1_050L,
                1_100L,
            )
        }
        assertTrue(
            WorkflowBusinessTimeResult.success(
                request,
                WorkflowBusinessTimeValue.working(true, "calendar-r1"),
                1_050L,
                1_100L,
            ).value!!.workingInstant!!,
        )
        val next = WorkflowBusinessTimeRequest.nextWorkingInstant(
            context(providerRevision = "deployment-42"),
            calendar,
            1_010L,
        )
        assertFailsWith<IllegalArgumentException> {
            WorkflowBusinessTimeResult.success(
                next,
                WorkflowBusinessTimeValue.instant(1_009L, "calendar-r1"),
                1_050L,
                1_100L,
            )
        }
    }

    @Test
    fun `form field limit is enforced from exact validation evidence`() {
        val dataSchema = schema('4')
        val descriptor = WorkflowFormDescriptor.of(
            "provider-a",
            "expense-form",
            "1",
            dataSchema,
            null,
            1_024,
            1,
        )
        val request = WorkflowFormValidationRequest.of(
            context(),
            descriptor,
            subject(),
            WorkflowPrincipalRef.of("user", "u1"),
            WorkflowFormValidationOperation.SUBMIT,
            WorkflowStructuredPayload.of(dataSchema, "{}".toByteArray()),
        )
        val report = WorkflowFormValidationReport.valid(validatedPayload(dataSchema, fieldCount = 2))

        assertFailsWith<IllegalArgumentException> {
            WorkflowFormValidationResult.success(request, report, 1_050L, 1_100L)
        }
    }

    @Test
    fun `electronic signature and witness are separate typed boundaries`() {
        val statement = WorkflowAttestationStatement.of(
            WorkflowDefinitionRef.of("legal-review", "2", digest('7')),
            WorkflowInstanceRef.of("instance-7", 3L),
            null,
            subject(),
            WorkflowPrincipalRef.of("user", "approver-1"),
            digest('8'),
            "attestation-idem-1",
            digest('9'),
        )
        val profile = WorkflowAttestationProfileRef.of("provider-a", "qualified-signature", "1", digest('a'))

        val signature = WorkflowElectronicSignatureRequest.of(context(), profile, statement)
        val witness = WorkflowWitnessRequest.of(context(), profile, statement)

        assertNotEquals(signature.requestDigest, witness.requestDigest)
        listOf("https://evidence.example/item", "https:opaque", "file:C", "../secret", "a/b", "a\\b").forEach {
            assertFailsWith<IllegalArgumentException> {
                WorkflowAttestationArtifactRef.of(it, "application/pdf", digest('b'), 12L)
            }
        }
    }

    private fun serviceRequest(
        descriptor: WorkflowServiceTaskDescriptor,
        attemptNumber: Int,
    ): WorkflowServiceTaskRequest = WorkflowServiceTaskRequest.of(
        context(providerRevision = "r1"),
        descriptor,
        WorkflowDefinitionRef.of("expense", "1", digest('4')),
        WorkflowInstanceRef.of("instance-1", 2L),
        subject(),
        WorkflowPrincipalRef.of("user", "u1"),
        "attempt-$attemptNumber",
        "idem-1",
        attemptNumber,
        validatedPayload(descriptor.inputSchema),
        descriptor.allowedEgressProfiles,
        listOf(WorkflowSecretHandle.of("vault-key-1", "erp-token", "3", digest('5'), 1_200L)),
    )

    private fun validatedPayload(
        schema: WorkflowSchemaRef,
        canonicalJson: String = "{}",
        fieldCount: Int = 0,
    ): WorkflowStructuredPayload {
        val raw = WorkflowStructuredPayload.of(schema, canonicalJson.toByteArray())
        val receipt = WorkflowPayloadValidationReceipt.of(
            "schema-validator",
            "r1",
            schema,
            raw.canonicalPayloadDigest,
            fieldCount,
            digest('e'),
        )
        return WorkflowStructuredPayload.validated(raw, receipt)
    }

    private fun context(providerRevision: String = "r1"): WorkflowProviderCallContext = WorkflowProviderCallContext.of(
        "request-1",
        "tenant-a",
        "provider-a",
        providerRevision,
        "contract-test",
        1_000L,
        1_200L,
        4_096,
        4_096,
        16,
    )

    private fun subject(): WorkflowSubjectSnapshot = WorkflowSubjectSnapshot.of(
        WorkflowSubjectRef.of("business-object", "expense-1"),
        "revision-3",
        digest('3'),
    )

    private fun schema(suffix: Char = '1'): WorkflowSchemaRef = WorkflowSchemaRef.of(
        "schema-provider",
        "schema-$suffix",
        "1",
        digest(suffix),
    )

    private fun serviceDescriptor(): WorkflowServiceTaskDescriptor = WorkflowServiceTaskDescriptor.of(
        "provider-a",
        "r1",
        "erp-posting",
        "1",
        "post-expense",
        schema('1'),
        schema('2'),
        200L,
        WorkflowServiceTaskRetryPolicy.of(3, 100L, 1_000L, 2_000, listOf("timeout")),
        WorkflowServiceTaskIdempotencyMode.REQUIRED,
        WorkflowServiceTaskCompensationDescriptor.of("erp-reversal", "1", digest('c')),
        listOf(WorkflowEgressProfileRef.of("erp-prod", "7", digest('d'))),
        listOf("erp-token"),
    )

    private fun digest(character: Char): String = character.toString().repeat(64)
}
