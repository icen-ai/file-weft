package ai.icen.fw.metadata.runtime

import ai.icen.fw.metadata.api.MetadataSchema

/**
 * Explicit contribution of a non-current schema version to the default
 * in-memory registry.
 *
 * Spring hosts should expose current versions as [MetadataSchema] beans and
 * wrap retained exact historical versions in this type. Keeping the roles
 * distinct prevents two versions of one schema from both being selected as
 * current while still allowing Doctor to validate persisted history.
 */
class HistoricalMetadataSchema(
    val schema: MetadataSchema,
)
