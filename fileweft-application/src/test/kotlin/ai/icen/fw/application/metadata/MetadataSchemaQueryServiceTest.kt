package ai.icen.fw.application.metadata

import ai.icen.fw.application.security.ApplicationForbiddenException
import ai.icen.fw.application.security.ApplicationUnauthenticatedException
import ai.icen.fw.core.context.TenantContext
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.metadata.api.MetadataSchema
import ai.icen.fw.metadata.api.MetadataSchemaContext
import ai.icen.fw.metadata.api.MetadataSchemaResolver
import ai.icen.fw.spi.authorization.AuthorizationDecision
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.authorization.AuthorizationRequest
import ai.icen.fw.spi.identity.UserIdentity
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.tenant.TenantProvider
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame

class MetadataSchemaQueryServiceTest {
    @Test
    fun `authorizes the trusted tenant and schema resource before returning the current schema`() {
        val schema = MetadataSchema("contract", "current", emptyList())
        val authorization = RecordingAuthorization(AuthorizationDecision(true))
        var capturedContext: MetadataSchemaContext? = null
        val service = service(
            users = currentUser(),
            authorization = authorization,
            resolver = recordingResolver { context ->
                capturedContext = context
                schema
            },
        )

        assertSame(schema, service.findCurrent("contract"))

        val request = authorization.lastRequest
        assertEquals(Identifier("user-a"), request?.subject?.id)
        assertEquals("USER", request?.subject?.type)
        assertEquals(Identifier("contract"), request?.resource?.id)
        assertEquals("METADATA_SCHEMA", request?.resource?.type)
        assertEquals(Identifier("tenant-a"), request?.resource?.tenantId)
        assertEquals("metadata:schema:read", request?.action?.name)
        assertEquals("tenant-a", capturedContext?.tenantId)
        assertEquals("contract", capturedContext?.schemaId)
        assertNull(capturedContext?.schemaVersion)
        assertEquals("metadata-schema", capturedContext?.resourceType)
        assertEquals("read", capturedContext?.operation)
    }

    @Test
    fun `fails unauthenticated without consulting policy or resolver`() {
        val authorization = RecordingAuthorization(AuthorizationDecision(true))
        var resolverCalled = false
        val service = service(
            users = noCurrentUser(),
            authorization = authorization,
            resolver = recordingResolver {
                resolverCalled = true
                MetadataSchema("private-schema", "1", emptyList())
            },
        )

        assertThrows<ApplicationUnauthenticatedException> {
            service.findCurrent("private-schema")
        }

        assertNull(authorization.lastRequest)
        assertFalse(resolverCalled)
    }

    @Test
    fun `fails forbidden after the exact policy request and before consulting resolver`() {
        val authorization = RecordingAuthorization(AuthorizationDecision(false, "host-only detail"))
        var resolverCalled = false
        val service = service(
            users = currentUser(),
            authorization = authorization,
            resolver = recordingResolver {
                resolverCalled = true
                MetadataSchema("private-schema", "1", emptyList())
            },
        )

        assertThrows<ApplicationForbiddenException> {
            service.findCurrent("private-schema")
        }

        val request = authorization.lastRequest
        assertEquals(Identifier("user-a"), request?.subject?.id)
        assertEquals(Identifier("private-schema"), request?.resource?.id)
        assertEquals("METADATA_SCHEMA", request?.resource?.type)
        assertEquals(Identifier("tenant-a"), request?.resource?.tenantId)
        assertEquals("metadata:schema:read", request?.action?.name)
        assertFalse(resolverCalled)
    }

    private fun service(
        users: UserRealmProvider,
        authorization: AuthorizationProvider,
        resolver: MetadataSchemaResolver,
    ): MetadataSchemaQueryService = MetadataSchemaQueryService(
        tenantProvider = tenant(),
        userRealmProvider = users,
        authorizationProvider = authorization,
        schemas = resolver,
    )

    private fun tenant(): TenantProvider = object : TenantProvider {
        override fun currentTenant(): TenantContext = TenantContext(Identifier("tenant-a"))
    }

    private fun currentUser(): UserRealmProvider = object : UserRealmProvider {
        override fun currentUser(): UserIdentity = UserIdentity(Identifier("user-a"), "User A")
        override fun findUser(userId: Identifier): UserIdentity? = null
    }

    private fun noCurrentUser(): UserRealmProvider = object : UserRealmProvider {
        override fun currentUser(): UserIdentity? = null
        override fun findUser(userId: Identifier): UserIdentity? = null
    }

    private fun recordingResolver(
        resolve: (MetadataSchemaContext) -> MetadataSchema?,
    ): MetadataSchemaResolver = object : MetadataSchemaResolver {
        override fun resolve(context: MetadataSchemaContext): MetadataSchema? = resolve(context)
    }

    private class RecordingAuthorization(
        private val decision: AuthorizationDecision,
    ) : AuthorizationProvider {
        var lastRequest: AuthorizationRequest? = null

        override fun authorize(request: AuthorizationRequest): AuthorizationDecision {
            lastRequest = request
            return decision
        }
    }
}
