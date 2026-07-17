package ai.icen.fw.workflow.document.selection

import ai.icen.fw.workflow.api.WorkflowDefinitionRef
import ai.icen.fw.workflow.api.WorkflowPrincipalRef
import ai.icen.fw.workflow.api.WorkflowSubjectRef
import ai.icen.fw.workflow.api.WorkflowSubjectSnapshot
import ai.icen.fw.workflow.document.DocumentWorkflowAction
import ai.icen.fw.workflow.document.DocumentWorkflowLifecycle
import ai.icen.fw.workflow.document.DocumentWorkflowRevisionPolicyRef
import ai.icen.fw.workflow.document.DocumentWorkflowSelection
import ai.icen.fw.workflow.document.DocumentWorkflowSelectionRequest
import ai.icen.fw.workflow.document.DocumentWorkflowSubjectRecord
import ai.icen.fw.workflow.document.DocumentWorkflowTemplateRef
import ai.icen.fw.workflow.runtime.WorkflowTrustedCallContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExactDocumentWorkflowSelectionRuntimeTest {
    @Test
    fun `selects only exact pinned policy result after authorization and fact recheck`() {
        val fixture = Fixture()

        val selected = fixture.runtime().select(fixture.request)

        assertEquals(fixture.expectedSelection, selected)
        assertEquals(listOf("prepare", "commit"), fixture.authorization.phases)
        assertEquals(2, fixture.facts.calls)
        assertEquals(2, fixture.policy.descriptorCalls)
        assertEquals(1, fixture.policy.selectionCalls)
    }

    @Test
    fun `missing provider is unsupported without falling back to weak approval`() {
        val fixture = Fixture()
        val runtime = ExactDocumentWorkflowSelectionRuntime(fixture.facts, fixture.authorization)

        assertNull(runtime.select(fixture.request))
        assertEquals(0, fixture.authorization.phases.size)
        assertEquals(0, fixture.facts.calls)
    }

    @Test
    fun `unknown classification remains unsupported`() {
        val fixture = Fixture(classification = "unknown-classification")
        fixture.policy.unsupportedClassification = "unknown-classification"

        assertNull(fixture.runtime().select(fixture.request))
        assertEquals(1, fixture.policy.selectionCalls)
    }

    @Test
    fun `authorization revocation at commit invalidates selection immediately`() {
        val fixture = Fixture()
        fixture.authorization.denyCommit = true

        assertNull(fixture.runtime().select(fixture.request))
        assertEquals(listOf("prepare", "commit"), fixture.authorization.phases)
    }

    @Test
    fun `metadata or classification drift is not cached or accepted`() {
        val fixture = Fixture()
        fixture.facts.driftOnSecondResolve = true

        assertNull(fixture.runtime().select(fixture.request))
        assertEquals(2, fixture.facts.calls)
    }

    @Test
    fun `provider descriptor drift fails closed`() {
        val fixture = Fixture()
        fixture.policy.driftDescriptorOnSecondRead = true

        assertNull(fixture.runtime().select(fixture.request))
        assertEquals(2, fixture.policy.descriptorCalls)
    }

    @Test
    fun `policy cannot substitute a different workflow than caller revalidated`() {
        val fixture = Fixture()
        fixture.policy.substituteSelection = fixture.selection(
            "different-definition",
            fixture.expectedSelection.authorityRevision,
        )

        assertNull(fixture.runtime().select(fixture.request))
    }

    private class Fixture(
        private val classification: String = "knowledge-document",
    ) {
        private val actor = WorkflowPrincipalRef.of("user", "alice")
        private val subject = WorkflowSubjectSnapshot.of(
            WorkflowSubjectRef.of("document", "document-1"),
            "version-1",
            DIGEST,
        )
        private val context = WorkflowTrustedCallContext.of(
            "tenant-1",
            actor,
            "authentication-1",
            DIGEST,
        )
        private val subjectRecord = DocumentWorkflowSubjectRecord.of(
            "tenant-1",
            actor,
            subject,
            DocumentWorkflowLifecycle.DRAFT,
            "subject-authority-1",
            NOW,
        )
        val descriptor = descriptor("configuration-a")
        private val authorityFacts = DocumentSelectionFacts.of(
            DIGEST,
            "tenant-1",
            actor,
            subject,
            classification,
            mapOf("document.risk" to "standard", "document.kind" to classification),
            "facts-authority-1",
            DIGEST,
            NOW,
            NOW,
        )
        val expectedSelection = selection(
            "definition-knowledge",
            DocumentSelectionAuthority.revision(
                descriptor,
                authorityFacts,
                POLICY_REVISION,
                AUTHORIZATION_REVISION,
            ),
        )
        val request = DocumentWorkflowSelectionRequest.of(
            context,
            DocumentWorkflowAction.SUBMIT,
            subjectRecord,
            expectedSelection,
            DIGEST,
            NOW,
        )
        val authorization = RecordingAuthorization()
        val facts = RecordingFacts(classification)
        val policy = RecordingPolicy(descriptor)

        fun runtime() = ExactDocumentWorkflowSelectionRuntime(facts, authorization, policy)

        fun selection(definitionId: String, authorityRevision: String): DocumentWorkflowSelection =
            DocumentWorkflowSelection.of(
                definitionId,
                WorkflowDefinitionRef.of(definitionId, "1", DIGEST),
                DocumentWorkflowTemplateRef.of("flowweft.knowledge-document", "1", DIGEST),
                DocumentWorkflowRevisionPolicyRef.of("1", DIGEST, "subject-revision"),
                authorityRevision,
            )

        private fun descriptor(configuration: String): DocumentSelectionPolicyDescriptor =
            DocumentSelectionPolicyDescriptor.of(
                "tenant-policy",
                "document-routing",
                "1",
                DIGEST,
                "1",
                if (configuration == "configuration-a") DIGEST else OTHER_DIGEST,
            )

        inner class RecordingAuthorization : DocumentSelectionAuthorizationPort {
            val phases = ArrayList<String>()
            var denyCommit = false

            override fun authorize(
                request: DocumentSelectionAuthorizationRequest,
            ): DocumentSelectionAuthorizationDecision {
                phases += request.phase.code
                val denied = denyCommit && request.phase == DocumentSelectionAuthorizationPhase.COMMIT
                return DocumentSelectionAuthorizationDecision.of(
                    "tenant-1",
                    actor,
                    request.requestDigest,
                    if (denied) {
                        DocumentSelectionAuthorizationStatus.DENIED
                    } else {
                        DocumentSelectionAuthorizationStatus.AUTHORIZED
                    },
                    AUTHORIZATION_REVISION,
                    DIGEST,
                    NOW,
                    NOW,
                )
            }
        }

        inner class RecordingFacts(
            private val initialClassification: String,
        ) : DocumentSelectionFactsPort {
            var calls = 0
            var driftOnSecondResolve = false

            override fun resolve(request: DocumentSelectionFactsRequest): DocumentSelectionFacts {
                calls++
                val metadata = if (driftOnSecondResolve && calls == 2) {
                    mapOf("document.risk" to "high", "document.kind" to initialClassification)
                } else {
                    mapOf("document.risk" to "standard", "document.kind" to initialClassification)
                }
                return DocumentSelectionFacts.of(
                    request.requestDigest,
                    "tenant-1",
                    actor,
                    subject,
                    initialClassification,
                    metadata,
                    "facts-authority-1",
                    DIGEST,
                    NOW,
                    NOW,
                )
            }
        }

        inner class RecordingPolicy(
            private val stableDescriptor: DocumentSelectionPolicyDescriptor,
        ) : DocumentSelectionPolicyProvider {
            var descriptorCalls = 0
            var selectionCalls = 0
            var driftDescriptorOnSecondRead = false
            var unsupportedClassification: String? = null
            var substituteSelection: DocumentWorkflowSelection? = null

            override fun descriptor(): DocumentSelectionPolicyDescriptor {
                descriptorCalls++
                return if (driftDescriptorOnSecondRead && descriptorCalls == 2) {
                    descriptor("configuration-b")
                } else {
                    stableDescriptor
                }
            }

            override fun select(request: DocumentSelectionPolicyRequest): DocumentSelectionPolicyResult {
                selectionCalls++
                if (request.facts.classification == unsupportedClassification) {
                    return DocumentSelectionPolicyResult.unsupported(
                        request.requestDigest,
                        request.descriptor.descriptorDigest,
                        POLICY_REVISION,
                        DIGEST,
                        "classification-unsupported",
                        NOW,
                        NOW,
                    )
                }
                return DocumentSelectionPolicyResult.selected(
                    request.requestDigest,
                    request.descriptor.descriptorDigest,
                    substituteSelection ?: request.selectionRequest.expectedSelection,
                    POLICY_REVISION,
                    DIGEST,
                    NOW,
                    NOW,
                )
            }
        }
    }

    private companion object {
        const val NOW = 1_000L
        const val POLICY_REVISION = "policy-revision-1"
        const val AUTHORIZATION_REVISION = "authorization-revision-1"
        const val DIGEST = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val OTHER_DIGEST = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
