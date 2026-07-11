package com.fileweft.domain.document

class InvalidLifecycleTransitionException(
    val currentState: LifecycleState,
    val command: LifecycleCommand,
) : DocumentConflictException("Cannot apply $command while document is $currentState.")
