package com.fileweft.web.runtime.v1.document

import com.fileweft.core.id.Identifier

/** Shared validation for opaque document identifiers entering the v1 transport boundary. */
internal object DocumentApiInputs {
    fun documentId(value: String): Identifier = identifier(value, "Document id")

    fun versionId(value: String): Identifier = identifier(value, "Document version id")

    fun workflowId(value: String): Identifier = identifier(value, "Workflow id")

    fun taskId(value: String): Identifier = identifier(value, "Workflow task id")

    private fun identifier(value: String, field: String): Identifier {
        require(value.isNotBlank()) { "$field must not be blank." }
        require(value.length <= MAX_IDENTIFIER_LENGTH) {
            "$field must not exceed $MAX_IDENTIFIER_LENGTH characters."
        }
        require(value.none(::isUnsafeControlCharacter)) { "$field must not contain control characters." }
        return Identifier(value)
    }

    private fun isUnsafeControlCharacter(character: Char): Boolean =
        character.code in 0..31 || character.code in 127..159

    private const val MAX_IDENTIFIER_LENGTH: Int = 128
}
