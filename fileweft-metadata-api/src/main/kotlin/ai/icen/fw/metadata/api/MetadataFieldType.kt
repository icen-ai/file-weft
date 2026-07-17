package ai.icen.fw.metadata.api

/**
 * Portable metadata field types supported by the FlowWeft metadata contract.
 *
 * Values are deliberately represented as strings at the public boundary so
 * Java 8 callers and storage/transport adapters do not need Kotlin-specific
 * types. The runtime is responsible for validation and canonicalization.
 */
enum class MetadataFieldType {
    STRING,
    NUMBER,
    BOOLEAN,
    DATE,
    ENUM,
    STRING_LIST,
}
