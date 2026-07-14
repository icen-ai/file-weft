package ai.icen.fw.metadata.api

/** Resolves a schema for a trusted application context. */
interface MetadataSchemaResolver {
    fun resolve(context: MetadataSchemaContext): MetadataSchema?
}

/** Validates and canonicalizes metadata without performing persistence or I/O. */
interface MetadataProcessor {
    fun process(
        context: MetadataSchemaContext,
        metadata: Map<String, String>,
    ): Map<String, String>
}
