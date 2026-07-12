package ai.icen.fw.dev.platform

import java.util.concurrent.ConcurrentHashMap

enum class DevPlatformFaultMode {
    AVAILABLE,
    RETRYABLE_FAILURE,
    PERMANENT_FAILURE,
}

class DevPlatformFaultControl {
    private val modes = ConcurrentHashMap<String, DevPlatformFaultMode>()

    fun current(targetId: String = DEFAULT_TARGET): DevPlatformFaultMode = modes[targetId] ?: DevPlatformFaultMode.AVAILABLE

    fun set(targetId: String = DEFAULT_TARGET, next: DevPlatformFaultMode): DevPlatformFaultMode =
        modes.put(targetId, next) ?: DevPlatformFaultMode.AVAILABLE

    private companion object {
        const val DEFAULT_TARGET = "default"
    }
}
