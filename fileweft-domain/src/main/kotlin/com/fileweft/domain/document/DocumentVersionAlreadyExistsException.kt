package com.fileweft.domain.document

/** The supplied business version number already belongs to this document. */
class DocumentVersionAlreadyExistsException(
    val versionNumber: String,
) : DocumentConflictException("Document version '$versionNumber' already exists.")
