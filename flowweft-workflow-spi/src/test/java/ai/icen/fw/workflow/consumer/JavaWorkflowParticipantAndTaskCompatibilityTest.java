package ai.icen.fw.workflow.consumer;

import ai.icen.fw.workflow.api.WorkflowDefinitionRef;
import ai.icen.fw.workflow.api.WorkflowInstanceRef;
import ai.icen.fw.workflow.api.WorkflowPredicateInputMapping;
import ai.icen.fw.workflow.api.WorkflowPredicateInputSourceKind;
import ai.icen.fw.workflow.api.WorkflowPredicateRef;
import ai.icen.fw.workflow.api.WorkflowPrincipalRef;
import ai.icen.fw.workflow.api.WorkflowSubjectRef;
import ai.icen.fw.workflow.api.WorkflowSubjectSnapshot;
import ai.icen.fw.workflow.spi.WorkflowCompensationStatus;
import ai.icen.fw.workflow.spi.WorkflowDecision;
import ai.icen.fw.workflow.spi.WorkflowDecisionDescriptor;
import ai.icen.fw.workflow.spi.WorkflowDecisionDescriptorRequest;
import ai.icen.fw.workflow.spi.WorkflowDecisionDescriptorResult;
import ai.icen.fw.workflow.spi.WorkflowDecisionOutcome;
import ai.icen.fw.workflow.spi.WorkflowDecisionRequest;
import ai.icen.fw.workflow.spi.WorkflowDecisionResult;
import ai.icen.fw.workflow.spi.WorkflowEgressProfileRef;
import ai.icen.fw.workflow.spi.WorkflowFormDescriptor;
import ai.icen.fw.workflow.spi.WorkflowFormDescriptorRequest;
import ai.icen.fw.workflow.spi.WorkflowFormDescriptorResult;
import ai.icen.fw.workflow.spi.WorkflowFormRef;
import ai.icen.fw.workflow.spi.WorkflowFormValidationIssue;
import ai.icen.fw.workflow.spi.WorkflowFormValidationOperation;
import ai.icen.fw.workflow.spi.WorkflowFormValidationReport;
import ai.icen.fw.workflow.spi.WorkflowFormValidationRequest;
import ai.icen.fw.workflow.spi.WorkflowFormValidationResult;
import ai.icen.fw.workflow.spi.WorkflowOrganizationRef;
import ai.icen.fw.workflow.spi.WorkflowOrganizationRelationshipDecision;
import ai.icen.fw.workflow.spi.WorkflowOrganizationRelationshipKind;
import ai.icen.fw.workflow.spi.WorkflowOrganizationRelationshipRequest;
import ai.icen.fw.workflow.spi.WorkflowOrganizationRelationshipResult;
import ai.icen.fw.workflow.spi.WorkflowOrganizationSnapshot;
import ai.icen.fw.workflow.spi.WorkflowOrganizationSnapshotRequest;
import ai.icen.fw.workflow.spi.WorkflowOrganizationSnapshotResult;
import ai.icen.fw.workflow.spi.WorkflowPayloadValidationReceipt;
import ai.icen.fw.workflow.spi.WorkflowProviderCallContext;
import ai.icen.fw.workflow.spi.WorkflowProviderFailure;
import ai.icen.fw.workflow.spi.WorkflowProviderOutcome;
import ai.icen.fw.workflow.spi.WorkflowSchemaRef;
import ai.icen.fw.workflow.spi.WorkflowSecretHandle;
import ai.icen.fw.workflow.spi.WorkflowServiceTaskCompensationDescriptor;
import ai.icen.fw.workflow.spi.WorkflowServiceTaskCompensationRequest;
import ai.icen.fw.workflow.spi.WorkflowServiceTaskCompensationResult;
import ai.icen.fw.workflow.spi.WorkflowServiceTaskDescriptor;
import ai.icen.fw.workflow.spi.WorkflowServiceTaskIdempotencyMode;
import ai.icen.fw.workflow.spi.WorkflowServiceTaskRequest;
import ai.icen.fw.workflow.spi.WorkflowServiceTaskResult;
import ai.icen.fw.workflow.spi.WorkflowServiceTaskRetryPolicy;
import ai.icen.fw.workflow.spi.WorkflowServiceTaskStatus;
import ai.icen.fw.workflow.spi.WorkflowStructuredPayload;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Java interop coverage for the Organization, ServiceTask, Decision and Form SPI groups.
 *
 * <p>These four SPI files expose their public types via {@code @JvmStatic} factories, {@code
 * @JvmField} constants and Kotlin properties (translated to {@code getXxx()} accessors). This test
 * proves each public type is constructible and fail-closed from pure Java, mirroring the Kotlin
 * contract tests in {@code ai.icen.fw.workflow.spi}. Each {@code @Test} method focuses on one SPI
 * group.
 */
class JavaWorkflowParticipantAndTaskCompatibilityTest {

    // ------------------------------------------------------------------
    // Organization SPI: snapshot/ref/relationship kinds + fail-closed authority binding
    // ------------------------------------------------------------------
    @Test
    void organizationSpiFactoriesAndRelationshipChecksAreUsableFromPureJava() {
        WorkflowProviderCallContext context = organizationContext("participant-membership");

        WorkflowOrganizationSnapshot snapshot = WorkflowOrganizationSnapshot.of(
            "corp.directory", "directory-r19", 1_000L, 1_100L
        );
        WorkflowOrganizationSnapshotRequest snapshotRequest =
            WorkflowOrganizationSnapshotRequest.of(context);

        // The snapshot authority must match the provider context for a relationship check, and the
        // completion time must fall inside the snapshot validity window. The provider id and the
        // snapshot authority id are the same ("corp.directory").
        WorkflowOrganizationSnapshotResult snapshotResult = WorkflowOrganizationSnapshotResult.success(
            snapshotRequest, snapshot, 1_050L
        );
        assertSame(snapshot, snapshotResult.getSnapshot());
        assertEquals("directory-r19", snapshotResult.getSnapshot().getRevision());
        assertEquals(snapshot.getSnapshotDigest(), snapshotResult.getReceipt().getResultDigest());

        WorkflowPrincipalRef principal = WorkflowPrincipalRef.of("USER", "reviewer-a");
        WorkflowOrganizationRef permission =
            WorkflowOrganizationRef.of("permission", "legal.document.approve");
        WorkflowOrganizationRelationshipRequest request = WorkflowOrganizationRelationshipRequest.of(
            context, snapshot, principal, permission,
            WorkflowOrganizationRelationshipKind.HAS_PERMISSION, 1_010L
        );

        // @JvmField constants are plain Java fields; the cached enum-like singletons are identity-equal.
        assertSame(WorkflowOrganizationRelationshipKind.HAS_PERMISSION, request.getRelationship());
        assertEquals("has-permission", request.getRelationship().getCode());
        // of(...) normalizes known codes back to the canonical singleton.
        assertSame(
            WorkflowOrganizationRelationshipKind.HOLDS_POSITION,
            WorkflowOrganizationRelationshipKind.of("holds-position")
        );
        assertEquals("directory-r19", request.getSnapshot().getRevision());

        // Changing the relationship changes the request digest (it is part of the digested payload).
        assertNotEquals(
            request.getRequestDigest(),
            WorkflowOrganizationRelationshipRequest.of(
                context, snapshot, principal, permission,
                WorkflowOrganizationRelationshipKind.ASSIGNED_ROLE, 1_010L
            ).getRequestDigest()
        );

        WorkflowOrganizationRelationshipDecision decision =
            WorkflowOrganizationRelationshipDecision.of(true, digest('e'));
        assertEquals(true, decision.getVerified());
        WorkflowOrganizationRelationshipResult relationshipResult =
            WorkflowOrganizationRelationshipResult.success(request, decision, 1_050L, 1_100L);
        assertSame(decision, relationshipResult.getDecision());

        // Fail-closed: a snapshot issued by a different authority is rejected even if everything else matches.
        assertThrows(
            IllegalArgumentException.class,
            () -> WorkflowOrganizationRelationshipRequest.of(
                context,
                WorkflowOrganizationSnapshot.of("other.directory", "directory-r19", 1_000L, 1_100L),
                principal,
                permission,
                WorkflowOrganizationRelationshipKind.HAS_PERMISSION,
                1_010L
            )
        );

        // A snapshot whose authority differs from the provider context cannot be returned as success.
        assertThrows(
            IllegalArgumentException.class,
            () -> WorkflowOrganizationSnapshotResult.success(
                snapshotRequest,
                WorkflowOrganizationSnapshot.of("other.directory", "directory-r19", 1_000L, 1_100L),
                1_050L
            )
        );

        // Effective time outside the call window (1000..1200) is rejected.
        assertThrows(
            IllegalArgumentException.class,
            () -> WorkflowOrganizationRelationshipRequest.of(
                context, snapshot, principal, permission,
                WorkflowOrganizationRelationshipKind.HAS_PERMISSION, 9_999L
            )
        );
    }

    // ------------------------------------------------------------------
    // ServiceTask SPI: descriptor/request/result + compensation, with egress/secret fail-closed guards
    // ------------------------------------------------------------------
    @Test
    void serviceTaskSpiFactoriesEnforceEgressAndSecretFailClosedFromPureJava() {
        WorkflowSchemaRef inputSchema = schema("schema-1", 'a');
        WorkflowSchemaRef outputSchema = schema("schema-2", 'b');
        WorkflowServiceTaskRetryPolicy retryPolicy = WorkflowServiceTaskRetryPolicy.of(
            3, 100L, 1_000L, 2_000, Collections.singletonList("timeout")
        );
        WorkflowServiceTaskCompensationDescriptor compensation =
            WorkflowServiceTaskCompensationDescriptor.of("erp-reversal", "1", digest('c'));
        WorkflowEgressProfileRef egressProfile =
            WorkflowEgressProfileRef.of("erp-prod", "7", digest('d'));
        WorkflowServiceTaskDescriptor descriptor = WorkflowServiceTaskDescriptor.of(
            "provider-a",
            "r1",
            "erp-posting",
            "1",
            "post-expense",
            inputSchema,
            outputSchema,
            200L,
            retryPolicy,
            WorkflowServiceTaskIdempotencyMode.REQUIRED,
            compensation,
            Collections.singletonList(egressProfile),
            Collections.singletonList("erp-token")
        );
        assertEquals("provider-a", descriptor.getProviderId());
        assertEquals("r1", descriptor.getProviderRevision());
        assertEquals(200L, descriptor.getTimeoutMillis());
        assertEquals(
            WorkflowServiceTaskIdempotencyMode.REQUIRED,
            descriptor.getIdempotencyMode()
        );
        assertNotNull(descriptor.getDescriptorDigest());

        // Context window (200ms) must fit inside the descriptor timeout. requestedAt=1000, deadline=1200.
        WorkflowProviderCallContext context = serviceContext();
        WorkflowStructuredPayload input = validatedPayload(inputSchema, 0);
        WorkflowSubjectSnapshot subject = subject();
        WorkflowSecretHandle secretHandle = WorkflowSecretHandle.of(
            "vault-key-1", "erp-token", "3", digest('5'), 1_200L
        );

        WorkflowServiceTaskRequest request = WorkflowServiceTaskRequest.of(
            context,
            descriptor,
            WorkflowDefinitionRef.of("expense", "1", digest('4')),
            WorkflowInstanceRef.of("instance-1", 2L),
            subject,
            WorkflowPrincipalRef.of("user", "u1"),
            "attempt-1",
            "idem-1",
            1,
            input,
            descriptor.getAllowedEgressProfiles(),
            Collections.singletonList(secretHandle)
        );
        assertEquals(1, request.getAttemptNumber());
        assertEquals(1, request.getSecretHandles().size());
        assertEquals("erp-token", request.getSecretHandles().get(0).getPurpose());

        // Fail-closed: omitting the declared egress profile set is rejected (egress must be a subset
        // of the descriptor's allowed set, but secrets must satisfy the descriptor exactly, so an
        // empty egress + empty secrets list fails on the missing required secret purposes).
        assertThrows(
            IllegalArgumentException.class,
            () -> WorkflowServiceTaskRequest.of(
                context,
                descriptor,
                WorkflowDefinitionRef.of("expense", "1", digest('4')),
                WorkflowInstanceRef.of("instance-1", 2L),
                subject,
                null,
                "attempt-1",
                "idem-1",
                1,
                input,
                Collections.emptyList(),
                Collections.emptyList()
            )
        );

        // Fail-closed: a secret handle id must be an opaque reference (no "://"), so URLs are rejected.
        assertThrows(
            IllegalArgumentException.class,
            () -> WorkflowSecretHandle.of(
                "https://vault/key", "erp-token", "3", digest('5'), 1_200L
            )
        );

        // Because the descriptor is compensatable, a successful result requires external receipt
        // evidence (ref + digest together).
        WorkflowStructuredPayload output = validatedPayload(outputSchema, 0);
        assertThrows(
            IllegalArgumentException.class,
            () -> WorkflowServiceTaskResult.success(request, output, 1_050L, 1_100L)
        );
        WorkflowServiceTaskResult success = WorkflowServiceTaskResult.success(
            request, output, "erp-receipt-1", digest('e'), 1_050L, 1_100L
        );
        assertSame(WorkflowServiceTaskStatus.SUCCEEDED, success.getStatus());
        assertEquals("erp-receipt-1", success.getExternalReceiptRef());

        // Failure results must keep status/retry-policy consistent: a retryable status requires a
        // declared retryable failure code AND a remaining attempt.
        assertThrows(
            IllegalArgumentException.class,
            () -> WorkflowServiceTaskResult.failure(
                request,
                WorkflowServiceTaskStatus.RETRYABLE_FAILURE,
                WorkflowProviderOutcome.UNAVAILABLE,
                WorkflowProviderFailure.of("timeout", false),
                null,
                null,
                1_050L,
                1_100L
            )
        );
        WorkflowServiceTaskResult retryable = WorkflowServiceTaskResult.failure(
            request,
            WorkflowServiceTaskStatus.RETRYABLE_FAILURE,
            WorkflowProviderOutcome.UNAVAILABLE,
            WorkflowProviderFailure.of("timeout", true),
            null,
            null,
            1_050L,
            1_100L
        );
        assertSame(WorkflowServiceTaskStatus.RETRYABLE_FAILURE, retryable.getStatus());
        assertNull(retryable.getOutput());

        // Compensation path: requires the compensatable descriptor, exact secret purposes, and an
        // authorized egress subset.
        WorkflowServiceTaskCompensationRequest compensationRequest =
            WorkflowServiceTaskCompensationRequest.of(
                context,
                descriptor,
                WorkflowInstanceRef.of("instance-1", 2L),
                digest('1'),
                digest('2'),
                "compensation-idem-1",
                "erp-receipt-1",
                digest('3'),
                descriptor.getAllowedEgressProfiles(),
                Collections.singletonList(secretHandle)
            );
        WorkflowServiceTaskCompensationResult compensated =
            WorkflowServiceTaskCompensationResult.success(
                compensationRequest, WorkflowCompensationStatus.COMPENSATED, 1_050L, 1_100L
            );
        assertSame(WorkflowCompensationStatus.COMPENSATED, compensated.getStatus());

        // Compensation failure: OUTCOME_UNKNOWN must be non-retryable; a retryable failure is rejected.
        assertThrows(
            IllegalArgumentException.class,
            () -> WorkflowServiceTaskCompensationResult.failure(
                compensationRequest,
                WorkflowCompensationStatus.OUTCOME_UNKNOWN,
                WorkflowProviderOutcome.FAILED,
                WorkflowProviderFailure.of("reconcile", true),
                1_050L,
                1_100L
            )
        );
    }

    // ------------------------------------------------------------------
    // Decision SPI: descriptor/outcome/decision and the fail-closed predicate-descriptor binding
    // ------------------------------------------------------------------
    @Test
    void decisionSpiFactoriesBindPredicateDescriptorsFromPureJava() {
        // The decision descriptor digest is the canonical claim carried by a WorkflowPredicateRef;
        // both must agree on provider/predicate/version/digest or the request is rejected.
        WorkflowSchemaRef inputSchema = schema("decision-input", 'a');
        WorkflowDecisionDescriptor descriptor = WorkflowDecisionDescriptor.of(
            "provider-a", "approval-rule", "1", inputSchema, true
        );
        assertEquals("provider-a", descriptor.getProviderId());
        assertEquals("approval-rule", descriptor.getPredicateId());
        assertEquals(true, descriptor.getDeterministic());

        WorkflowProviderCallContext context = decisionContext();
        WorkflowPredicateRef predicate = WorkflowPredicateRef.of(
            "provider-a",
            "approval-rule",
            "1",
            descriptor.getDescriptorDigest(),
            Collections.singletonList(
                WorkflowPredicateInputMapping.of(
                    "amount", WorkflowPredicateInputSourceKind.WORKFLOW_VARIABLE, "expense.amount"
                )
            )
        );
        WorkflowDecisionDescriptorRequest descriptorRequest =
            WorkflowDecisionDescriptorRequest.of(context, predicate);
        assertEquals(predicate, descriptorRequest.getPredicate());

        WorkflowDecisionDescriptorResult descriptorResult = WorkflowDecisionDescriptorResult.success(
            descriptorRequest, descriptor, 1_050L, 1_100L
        );
        assertSame(descriptor, descriptorResult.getDescriptor());

        // Fail-closed: a success descriptor whose provider differs from the predicate provider is rejected.
        assertThrows(
            IllegalArgumentException.class,
            () -> WorkflowDecisionDescriptorResult.success(
                descriptorRequest,
                WorkflowDecisionDescriptor.of(
                    "provider-b", "approval-rule", "1", inputSchema, true
                ),
                1_050L,
                1_100L
            )
        );

        // Evaluation path requires trusted (validated) input bound to the exact descriptor schema.
        WorkflowStructuredPayload validatedInput = validatedPayload(inputSchema, 0);
        WorkflowDecisionRequest decisionRequest = WorkflowDecisionRequest.of(
            context,
            WorkflowDefinitionRef.of("approval", "1", digest('4')),
            subject(),
            WorkflowPrincipalRef.of("user", "approver-1"),
            predicate,
            descriptor,
            validatedInput
        );
        WorkflowDecision matched = WorkflowDecision.of(
            WorkflowDecisionOutcome.MATCHED, "rule-7", digest('9')
        );
        WorkflowDecisionResult matchedResult = WorkflowDecisionResult.success(
            decisionRequest, matched, 1_050L, 1_100L
        );
        assertSame(WorkflowDecisionOutcome.MATCHED, matchedResult.getDecision().getOutcome());

        WorkflowDecisionResult notMatchedResult = WorkflowDecisionResult.failure(
            decisionRequest,
            WorkflowProviderOutcome.DENIED,
            WorkflowProviderFailure.of("no-evidence", false),
            1_050L,
            1_100L
        );
        assertNull(notMatchedResult.getDecision());

        // Outcome constants are @JvmField; of(...) normalizes known codes to the singletons.
        assertSame(WorkflowDecisionOutcome.MATCHED, WorkflowDecisionOutcome.of("matched"));
        assertSame(WorkflowDecisionOutcome.NOT_MATCHED, WorkflowDecisionOutcome.of("not-matched"));

        // An evidence digest that is not a canonical SHA-256 (wrong length) is rejected.
        assertThrows(
            IllegalArgumentException.class,
            () -> WorkflowDecision.of(
                WorkflowDecisionOutcome.MATCHED, "rule-7", "not-a-sha256"
            )
        );
    }

    // ------------------------------------------------------------------
    // Form SPI: descriptor/ref + validation report (valid/invalid) with fail-closed normalization
    // ------------------------------------------------------------------
    @Test
    void formSpiFactoriesEnforceValidationFailClosedFromPureJava() {
        WorkflowSchemaRef dataSchema = schema("expense-data", '4');
        WorkflowFormDescriptor descriptor = WorkflowFormDescriptor.of(
            "provider-a", "expense-form", "1", dataSchema, null, 1_024, 1
        );
        assertEquals("expense-form", descriptor.getFormId());
        assertEquals(1_024, descriptor.getMaximumSubmissionBytes());
        assertEquals(1, descriptor.getMaximumFields());
        // The descriptor computes its own ref whose digest equals the descriptor digest.
        WorkflowFormRef formRef = descriptor.getRef();
        assertEquals(descriptor.getDescriptorDigest(), formRef.getDigest());

        WorkflowProviderCallContext context = formContext();
        WorkflowFormDescriptorRequest descriptorRequest =
            WorkflowFormDescriptorRequest.of(context, formRef);
        WorkflowFormDescriptorResult descriptorResult = WorkflowFormDescriptorResult.success(
            descriptorRequest, descriptor, 1_050L, 1_100L
        );
        assertSame(descriptor, descriptorResult.getDescriptor());

        // Read/submit operations are @JvmField enum-like singletons.
        assertSame(WorkflowFormValidationOperation.SUBMIT, WorkflowFormValidationOperation.of("submit"));
        assertSame(WorkflowFormValidationOperation.READ, WorkflowFormValidationOperation.of("read"));

        // A raw (untrusted) submission is accepted for validation; it must match the data schema.
        WorkflowFormValidationRequest validationRequest = WorkflowFormValidationRequest.of(
            context,
            descriptor,
            subject(),
            WorkflowPrincipalRef.of("user", "u1"),
            WorkflowFormValidationOperation.SUBMIT,
            WorkflowStructuredPayload.of(dataSchema, "{}".getBytes(StandardCharsets.UTF_8))
        );
        assertNotNull(validationRequest.getRequestDigest());

        // invalid(...): issues present, no normalized payload, validity matches its issue list.
        WorkflowFormValidationIssue issue = WorkflowFormValidationIssue.of("/amount", "required");
        WorkflowFormValidationReport invalidReport =
            WorkflowFormValidationReport.invalid(Collections.singletonList(issue));
        assertEquals(false, invalidReport.getValid());
        assertEquals(1, invalidReport.getIssues().size());
        assertNull(invalidReport.getNormalizedSubmission());

        WorkflowFormValidationResult invalidResult = WorkflowFormValidationResult.success(
            validationRequest, invalidReport, 1_050L, 1_100L
        );
        assertEquals(false, invalidResult.getReport().getValid());

        // Fail-closed: a valid report whose normalized submission carries more fields than the
        // descriptor's maximumFields (1) is rejected when the result is sealed.
        WorkflowStructuredPayload normalizedTooManyFields = validatedPayload(dataSchema, 2);
        WorkflowFormValidationReport overFieldReport =
            WorkflowFormValidationReport.valid(normalizedTooManyFields);
        assertThrows(
            IllegalArgumentException.class,
            () -> WorkflowFormValidationResult.success(validationRequest, overFieldReport, 1_050L, 1_100L)
        );

        // A submission whose schema differs from the descriptor's data schema is rejected at request time.
        WorkflowSchemaRef otherSchema = schema("other-data", 'f');
        assertThrows(
            IllegalArgumentException.class,
            () -> WorkflowFormValidationRequest.of(
                context,
                descriptor,
                subject(),
                WorkflowPrincipalRef.of("user", "u1"),
                WorkflowFormValidationOperation.SUBMIT,
                WorkflowStructuredPayload.of(otherSchema, "{}".getBytes(StandardCharsets.UTF_8))
            )
        );
    }

    // ------------------------------------------------------------------
    // Shared fixtures (kept private so each @Test remains self-describing)
    // ------------------------------------------------------------------

    private static WorkflowProviderCallContext organizationContext(String purpose) {
        return WorkflowProviderCallContext.of(
            "organization-request", "tenant-a", "corp.directory", "provider-r7", purpose,
            1_000L, 1_200L, 4_096, 4_096, 16
        );
    }

    private static WorkflowProviderCallContext serviceContext() {
        return WorkflowProviderCallContext.of(
            "request-1", "tenant-a", "provider-a", "r1", "service-task",
            1_000L, 1_200L, 4_096, 4_096, 16
        );
    }

    private static WorkflowProviderCallContext decisionContext() {
        return WorkflowProviderCallContext.of(
            "request-1", "tenant-a", "provider-a", "r1", "decision",
            1_000L, 1_200L, 4_096, 4_096, 16
        );
    }

    private static WorkflowProviderCallContext formContext() {
        return WorkflowProviderCallContext.of(
            "request-1", "tenant-a", "provider-a", "r1", "form-validation",
            1_000L, 1_200L, 4_096, 4_096, 16
        );
    }

    private static WorkflowSchemaRef schema(String schemaId, char digestChar) {
        return WorkflowSchemaRef.of("schema-provider", schemaId, "1", digest(digestChar));
    }

    private static WorkflowSubjectSnapshot subject() {
        return WorkflowSubjectSnapshot.of(
            WorkflowSubjectRef.of("business-object", "expense-1"), "revision-3", digest('3')
        );
    }

    private static WorkflowStructuredPayload validatedPayload(WorkflowSchemaRef schema, int fieldCount) {
        WorkflowStructuredPayload raw = WorkflowStructuredPayload.of(
            schema, "{}".getBytes(StandardCharsets.UTF_8)
        );
        WorkflowPayloadValidationReceipt receipt = WorkflowPayloadValidationReceipt.of(
            "schema-validator", "r1", schema, raw.getCanonicalPayloadDigest(), fieldCount, digest('e')
        );
        return WorkflowStructuredPayload.validated(raw, receipt);
    }

    private static String digest(char character) {
        StringBuilder builder = new StringBuilder(64);
        for (int index = 0; index < 64; index++) {
            builder.append(character);
        }
        return builder.toString();
    }
}
