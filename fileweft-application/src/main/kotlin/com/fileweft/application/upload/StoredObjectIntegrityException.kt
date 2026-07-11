package com.fileweft.application.upload

/**
 * The storage adapter acknowledged an object that does not match the bytes the
 * application authorized. This is a server-side adapter/integrity failure, not
 * malformed client input.
 */
class StoredObjectIntegrityException @JvmOverloads constructor(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
