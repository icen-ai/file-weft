package ai.icen.fw.web.spring.boot3

import ai.icen.fw.application.catalog.DocumentCatalogAccessService
import ai.icen.fw.application.catalog.DocumentCatalogBindingCommand
import ai.icen.fw.core.context.TenantContext
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.domain.document.Document
import ai.icen.fw.spi.authorization.AuthorizationDecision
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.authorization.AuthorizationRequest
import ai.icen.fw.spi.catalog.DocumentCatalogFolder
import ai.icen.fw.spi.catalog.DocumentCatalogProvider
import ai.icen.fw.spi.identity.UserIdentity
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.tenant.TenantProvider
import ai.icen.fw.web.runtime.v1.V1ApiResponseFactory
import ai.icen.fw.web.runtime.v1.catalog.DocumentCatalogApiFacade
import ai.icen.fw.web.spring.boot3.v1.document.V1DocumentCatalogController
import ai.icen.fw.web.spring.boot3.v1.document.V1DocumentCatalogRequestFailureHandler
import com.fasterxml.jackson.databind.ObjectMapper
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import kotlin.test.assertEquals

class V1DocumentCatalogControllerTest {
    @Test
    fun `browses visible folders moves a document and rejects head`() {
        var movedTo: String? = null
        val mvc = mvc { documentId, folderId ->
            movedTo = folderId
            document(documentId)
        }

        mvc.perform(get("/fileweft/v1/catalog/folders").param("limit", "1"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.items[0].id").value("child"))
            .andExpect(jsonPath("$.data.items[0].parentFolderId").value("root"))
            .andExpect(jsonPath("$.data.nextCursor").isString)
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))

        mvc.perform(
            put("/fileweft/v1/documents/document-a/catalog-folder")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"folderId\":\"child\"}"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.documentId").value("document-a"))
        assertEquals("child", movedTo)

        mvc.perform(head("/fileweft/v1/catalog/folders"))
            .andExpect(status().isMethodNotAllowed)
            .andExpect(header().string(HttpHeaders.ALLOW, "GET"))
    }

    @Test
    fun `maps catalog MVC failures to the fixed safe envelope on Boot 3`() {
        val mvc = mvc { documentId, _ -> document(documentId) }

        mvc.perform(
            put("/fileweft/v1/documents/document-a/catalog-folder")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{"),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
            .andExpect(header().string(HttpHeaders.PRAGMA, "no-cache"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))

        mvc.perform(
            put("/fileweft/v1/documents/document-a/catalog-folder")
                .contentType(MediaType.TEXT_PLAIN)
                .content("child"),
        )
            .andExpect(status().isUnsupportedMediaType)
            .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"))

        mvc.perform(get("/fileweft/v1/catalog/folders").accept(MediaType.TEXT_PLAIN))
            .andExpect(status().isNotAcceptable)
            .andExpect(jsonPath("$.code").value("NOT_ACCEPTABLE"))

        mvc.perform(post("/fileweft/v1/catalog/folders"))
            .andExpect(status().isMethodNotAllowed)
            .andExpect(header().string(HttpHeaders.ALLOW, containsString("GET")))
    }

    private fun mvc(moveAction: (Identifier, String) -> Document): org.springframework.test.web.servlet.MockMvc {
        val responses = V1ApiResponseFactory()
        val handler = V1DocumentCatalogRequestFailureHandler(responses, null)
        return MockMvcBuilders.standaloneSetup(
            V1DocumentCatalogController(
                DocumentCatalogApiFacade(
                    access(),
                    object : DocumentCatalogBindingCommand {
                        override fun move(documentId: Identifier, folderId: String): Document =
                            moveAction(documentId, folderId)
                    },
                ),
                responses,
                null,
            ),
        )
            .setMessageConverters(MappingJackson2HttpMessageConverter(ObjectMapper()))
            .setHandlerExceptionResolvers(handler.catalogExceptionResolver())
            .build()
    }

    private fun access() = DocumentCatalogAccessService(
        object : TenantProvider {
            override fun currentTenant() = TenantContext(Identifier("tenant-a"))
        },
        object : UserRealmProvider {
            override fun currentUser() = UserIdentity(Identifier("user-a"), "User A")
            override fun findUser(userId: Identifier): UserIdentity? = null
        },
        object : AuthorizationProvider {
            override fun authorize(request: AuthorizationRequest) = AuthorizationDecision(true)
        },
        object : DocumentCatalogProvider {
            override fun listFolders(tenantId: Identifier) = listOf(
                DocumentCatalogFolder("root", null, "Root"),
                DocumentCatalogFolder("child", "root", "Child"),
            )
        },
    )

    private fun document(id: Identifier) = Document(
        id,
        Identifier("tenant-a"),
        Identifier("asset-a"),
        "DOC-1",
        "Document",
    )
}
