package ai.icen.fw.web.runtime.v1.document

import ai.icen.fw.web.api.v1.document.DocumentMetadataCommand
import java.util.LinkedHashMap

/** Strict multipart `field=value` decoder shared by both Spring generations. */
class DocumentMetadataApiInputs private constructor() {
    companion object {
        @JvmStatic
        fun parse(
            schemaIds: List<String>?,
            entries: List<String>?,
        ): DocumentMetadataCommand? {
            val suppliedSchemaIds = schemaIds.orEmpty()
            val suppliedEntries = entries.orEmpty()
            if (suppliedSchemaIds.isEmpty() && suppliedEntries.isEmpty()) {
                return null
            }
            require(suppliedSchemaIds.size == 1) {
                "Metadata schema id must be supplied exactly once when metadata is present."
            }
            require(suppliedEntries.size <= MAX_FIELDS) { "Document metadata contains too many fields." }
            val values = LinkedHashMap<String, String>(suppliedEntries.size)
            suppliedEntries.forEach { entry ->
                val separator = entry.indexOf('=')
                require(separator > 0) { "Each metadata entry must use field=value format." }
                val fieldName = entry.substring(0, separator)
                val fieldValue = entry.substring(separator + 1)
                require(values.put(fieldName, fieldValue) == null) {
                    "Document metadata field names must be unique."
                }
            }
            return DocumentMetadataCommand(suppliedSchemaIds.single(), values)
        }

        private const val MAX_FIELDS: Int = 128
    }
}
