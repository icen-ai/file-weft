package ai.icen.fw.application.metadata

import ai.icen.fw.application.security.ApplicationAuthorization
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.metadata.api.MetadataSchema
import ai.icen.fw.metadata.api.MetadataSchemaContext
import ai.icen.fw.metadata.api.MetadataSchemaResolver
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.tenant.TenantProvider

/** Trusted-context and authorization-gated query for one current schema. */
class MetadataSchemaQueryService(
    private val tenantProvider: TenantProvider,
    userRealmProvider: UserRealmProvider,
    authorizationProvider: AuthorizationProvider,
    private val schemas: MetadataSchemaResolver,
) {
    private val authorization = ApplicationAuthorization(userRealmProvider, authorizationProvider)

    fun findCurrent(schemaId: String): MetadataSchema {
        val tenant = tenantProvider.currentTenant()
        val context = MetadataSchemaContext(
            tenantId = tenant.tenantId.value,
            schemaId = schemaId,
            resourceType = SCHEMA_RESOURCE_TYPE,
            operation = READ_OPERATION,
        )
        authorization.requireAction(
            tenant.tenantId,
            Identifier(context.schemaId),
            AUTHORIZATION_RESOURCE_TYPE,
            AUTHORIZATION_ACTION,
        )
        val schema = try {
            schemas.resolve(context)
        } catch (_: RuntimeException) {
            throw MetadataConfigurationException()
        } ?: throw MetadataSchemaUnavailableException()
        if (schema.id != context.schemaId) throw MetadataConfigurationException()
        return schema
    }

    private companion object {
        const val SCHEMA_RESOURCE_TYPE: String = "metadata-schema"
        const val READ_OPERATION: String = "read"
        const val AUTHORIZATION_RESOURCE_TYPE: String = "METADATA_SCHEMA"
        const val AUTHORIZATION_ACTION: String = "metadata:schema:read"
    }
}
