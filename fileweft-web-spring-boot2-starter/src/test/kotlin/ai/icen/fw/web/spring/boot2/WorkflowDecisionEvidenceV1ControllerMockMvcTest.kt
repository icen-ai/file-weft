package ai.icen.fw.web.spring.boot2

import com.fasterxml.jackson.databind.ObjectMapper
import ai.icen.fw.application.document.DocumentFolderReadScope
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.application.workflow.DocumentWorkflowDecisionEvidencePageResult
import ai.icen.fw.application.workflow.DocumentWorkflowPageRequest
import ai.icen.fw.application.workflow.WorkflowDecisionEvidenceQueryRepository
import ai.icen.fw.application.workflow.WorkflowDecisionEvidenceQueryService
import ai.icen.fw.application.workflow.WorkflowDecisionEvidenceView
import ai.icen.fw.application.workflow.WorkflowDecisionTaskEvidenceView
import ai.icen.fw.core.context.TenantContext
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.domain.workflow.WorkflowState
import ai.icen.fw.domain.workflow.WorkflowTaskState
import ai.icen.fw.spi.authorization.AuthorizationDecision
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.authorization.AuthorizationRequest
import ai.icen.fw.spi.identity.UserIdentity
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.tenant.TenantProvider
import ai.icen.fw.web.runtime.v1.V1ApiResponseFactory
import ai.icen.fw.web.runtime.v1.workflow.WorkflowDecisionEvidenceApiReadFacade
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import kotlin.test.assertEquals

class WorkflowDecisionEvidenceV1ControllerMockMvcTest {
    @Test
    fun `returns the same privileged redacted decision projection as Boot 3`() {
        val fixture = Boot2WorkflowDecisionEvidenceFixture()

        mvc(fixture).perform(get(PATH).param("limit", "2"))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(jsonPath("$.code").value("OK"))
            .andExpect(jsonPath("$.data.items[0].id").value("workflow-1"))
            .andExpect(jsonPath("$.data.items[0].tasks[0].decisionOperatorId").value("reviewer-a"))
            .andExpect(jsonPath("$.data.items[0].tasks[0].decisionOperatorName").value("审批人甲"))
            .andExpect(jsonPath("$.data.items[0].tasks[0].decidedTime").value(20))
            .andExpect(jsonPath("$.data.items[0].tasks[0].decisionEvidenceRecorded").value(true))
            .andExpect(jsonPath("$.data.items[0].tasks[0].assigneeId").doesNotExist())
            .andExpect(jsonPath("$.data.items[0].tasks[0].comment").doesNotExist())
            .andExpect(jsonPath("$.data.items[0].tasks[0].tenantId").doesNotExist())

        assertEquals(2, fixture.requests.single().limit)
        assertEquals(listOf("document:audit", "document:read"), fixture.authorizationRequests.map { it.action.name })
    }

    @Test
    fun `maps invalid limits before authorization or persistence`() {
        val fixture = Boot2WorkflowDecisionEvidenceFixture()
        listOf("0", "101", "001", "-1", "1000").forEach { invalid ->
            mvc(fixture).perform(get(PATH).param("limit", invalid))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(content().string(not(containsString(invalid))))
        }
        assertEquals(0, fixture.requests.size)
        assertEquals(0, fixture.authorizationRequests.size)
    }

    @Test
    fun `maps denied and hidden documents without leaking context`() {
        val denied = Boot2WorkflowDecisionEvidenceFixture().apply { deniedAction = "document:read" }
        mvc(denied).perform(get(PATH))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("FORBIDDEN"))
            .andExpect(jsonPath("$.message").value("Access denied."))
        assertEquals(0, denied.requests.size)

        val hidden = Boot2WorkflowDecisionEvidenceFixture().apply { documentMissing = true }
        mvc(hidden).perform(get("/fileweft/v1/documents/private-document/workflow-decisions"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("NOT_FOUND"))
            .andExpect(content().string(not(containsString("private-document"))))
    }

    private fun mvc(fixture: Boot2WorkflowDecisionEvidenceFixture): MockMvc = MockMvcBuilders.standaloneSetup(
        WorkflowDecisionEvidenceV1Controller(
            WorkflowDecisionEvidenceApiReadFacade(fixture.service()),
            V1ApiResponseFactory(),
            null,
        ),
    ).setMessageConverters(MappingJackson2HttpMessageConverter(ObjectMapper())).build()

    private companion object {
        const val PATH = "/fileweft/v1/documents/document-1/workflow-decisions"
    }
}

internal class Boot2WorkflowDecisionEvidenceFixture {
    var deniedAction: String? = null
    var documentMissing = false
    val authorizationRequests = mutableListOf<AuthorizationRequest>()
    val requests = mutableListOf<DocumentWorkflowPageRequest>()

    fun service() = WorkflowDecisionEvidenceQueryService(
        tenantProvider = object : TenantProvider {
            override fun currentTenant(): TenantContext = TenantContext(Identifier("tenant-1"))
        },
        userRealmProvider = object : UserRealmProvider {
            override fun currentUser(): UserIdentity = UserIdentity(Identifier("reviewer-a"), "审批人甲")
            override fun findUser(userId: Identifier): UserIdentity? = null
        },
        authorizationProvider = object : AuthorizationProvider {
            override fun authorize(request: AuthorizationRequest): AuthorizationDecision {
                authorizationRequests += request
                return AuthorizationDecision(request.action.name != deniedAction, "host-policy=secret")
            }
        },
        queries = object : WorkflowDecisionEvidenceQueryRepository {
            override fun findDocumentWorkflowDecisionEvidencePage(
                tenantId: Identifier,
                documentId: Identifier,
                request: DocumentWorkflowPageRequest,
                folderReadScope: DocumentFolderReadScope?,
            ): DocumentWorkflowDecisionEvidencePageResult? {
                requests += request
                if (documentMissing) return null
                return DocumentWorkflowDecisionEvidencePageResult(
                    documentId,
                    listOf(
                        WorkflowDecisionEvidenceView(
                            Identifier("workflow-1"), documentId, "DOCUMENT_REVIEW", WorkflowState.APPROVED,
                            10, 20,
                            listOf(
                                WorkflowDecisionTaskEvidenceView(
                                    Identifier("task-1"), WorkflowTaskState.APPROVED, 10, 20,
                                    Identifier("reviewer-a"), "审批人甲", 20,
                                ),
                            ),
                        ),
                    ),
                )
            }
        },
        transaction = object : ApplicationTransaction {
            override fun <T> execute(action: () -> T): T = action()
        },
    )
}
