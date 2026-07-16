package ai.icen.fw.web.runtime.v1.document

import ai.icen.fw.application.document.DocumentDetailView
import ai.icen.fw.application.document.DocumentFolderReadScope
import ai.icen.fw.application.document.DocumentPageCursor
import ai.icen.fw.application.document.DocumentPageRequest
import ai.icen.fw.application.document.DocumentPageResult
import ai.icen.fw.application.document.DocumentQueryRepository
import ai.icen.fw.application.document.DocumentQueryService
import ai.icen.fw.application.document.DocumentSummaryView
import ai.icen.fw.application.document.DocumentVersionView
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.core.context.TenantContext
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.domain.document.LifecycleState
import ai.icen.fw.spi.authorization.AuthorizationDecision
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.authorization.AuthorizationRequest
import ai.icen.fw.spi.identity.UserIdentity
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.tenant.TenantProvider

class JavaRuntimeInteropFixtures private constructor() {
    companion object {
        @JvmStatic
        fun facade(): DocumentApiReadFacade = DocumentApiReadFacade(
            DocumentQueryService(
                tenantProvider = object : TenantProvider {
                    override fun currentTenant(): TenantContext = TenantContext(Identifier("tenant-java"))
                },
                userRealmProvider = object : UserRealmProvider {
                    override fun currentUser(): UserIdentity = UserIdentity(Identifier("user-java"), "Java User")
                    override fun findUser(userId: Identifier): UserIdentity? = null
                },
                authorizationProvider = object : AuthorizationProvider {
                    override fun authorize(request: AuthorizationRequest): AuthorizationDecision = AuthorizationDecision(true)
                },
                queries = object : DocumentQueryRepository {
                    override fun findDetail(
                        tenantId: Identifier,
                        documentId: Identifier,
                        folderReadScope: DocumentFolderReadScope?,
                    ): DocumentDetailView = DocumentDetailView(
                        DocumentSummaryView(
                            id = documentId,
                            documentNumber = "DOC-JAVA",
                            title = "Java document",
                            lifecycleState = LifecycleState.DRAFT,
                            createdTime = 1,
                            updatedTime = 2,
                            currentVersionId = Identifier("version-java"),
                            folderId = "inbox",
                        ),
                        listOf(DocumentVersionView(Identifier("version-java"), "1.0", "java.pdf", 8, 1, 2, "application/pdf")),
                    )

                    override fun findPage(
                        tenantId: Identifier,
                        request: DocumentPageRequest,
                        folderReadScope: DocumentFolderReadScope?,
                    ): DocumentPageResult = DocumentPageResult(
                        listOf(
                            DocumentSummaryView(
                                Identifier("document-java"), "DOC-JAVA", "Java document", LifecycleState.DRAFT,
                                1, 2, Identifier("version-java"), "inbox",
                            ),
                        ),
                        DocumentPageCursor(2, Identifier("document-java")),
                    )
                },
                transaction = object : ApplicationTransaction {
                    override fun <T> execute(action: () -> T): T = action()
                },
                folderReadAccess = null,
                deletionVisibilityGuard = visibleDeletionGuard(),
            ),
        )
    }
}
