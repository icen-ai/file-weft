package com.fileweft.domain.document

/**
 * Raised when a tenant already owns the supplied document business number.
 *
 * Document numbers are unique only within a tenant, which keeps tenant
 * boundaries intact while allowing different organizations to use the same
 * external numbering scheme.
 */
class DocumentNumberAlreadyExistsException(
    val documentNumber: String,
) : DocumentConflictException("A document with number '$documentNumber' already exists in the current tenant.")
