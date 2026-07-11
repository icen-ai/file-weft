package com.fileweft.web.runtime.v1.document

import com.fileweft.application.document.DocumentDetailView
import com.fileweft.application.document.DocumentFolderReadScope
import com.fileweft.application.document.DocumentPageCursor
import com.fileweft.application.document.DocumentPageRequest
import com.fileweft.application.document.DocumentPageResult
import com.fileweft.application.document.DocumentQueryRepository
import com.fileweft.application.document.DocumentQueryService
import com.fileweft.application.document.DocumentSummaryView
import com.fileweft.application.document.DocumentVersionView
import com.fileweft.application.transaction.ApplicationTransaction
import com.fileweft.core.context.TenantContext
import com.fileweft.core.id.Identifier
import com.fileweft.domain.document.LifecycleState
import com.fileweft.spi.authorization.AuthorizationDecision
import com.fileweft.spi.authorization.AuthorizationProvider
import com.fileweft.spi.authorization.AuthorizationRequest
import com.fileweft.spi.identity.UserIdentity
import com.fileweft.spi.identity.UserRealmProvider
import com.fileweft.spi.tenant.TenantProvider

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
            ),
        )
    }
}
