package com.fileweft.domain.document

class InvalidLifecycleTransitionException(
    val currentState: LifecycleState,
    val command: LifecycleCommand,
) : IllegalStateException("Cannot apply $command while document is $currentState.")
