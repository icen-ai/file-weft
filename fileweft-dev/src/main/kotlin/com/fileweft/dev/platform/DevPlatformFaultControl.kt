package com.fileweft.dev.platform

import java.util.concurrent.atomic.AtomicReference

enum class DevPlatformFaultMode {
    AVAILABLE,
    RETRYABLE_FAILURE,
    PERMANENT_FAILURE,
}

class DevPlatformFaultControl {
    private val mode = AtomicReference(DevPlatformFaultMode.AVAILABLE)

    fun current(): DevPlatformFaultMode = mode.get()

    fun set(next: DevPlatformFaultMode): DevPlatformFaultMode = mode.getAndSet(next)
}
