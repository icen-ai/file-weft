package com.fileweft.domain.document

/** A title or version edit was requested after the document stopped being editable. */
class DocumentNotEditableException(
    val currentState: LifecycleState,
) : DocumentConflictException("Document cannot be edited while it is $currentState.")
