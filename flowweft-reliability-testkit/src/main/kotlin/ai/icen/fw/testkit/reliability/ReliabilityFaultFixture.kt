package ai.icen.fw.testkit.reliability

import ai.icen.fw.reliability.runtime.ReliabilityRun
import ai.icen.fw.reliability.runtime.ReliabilityRuntimeFaultHook
import java.util.concurrent.atomic.AtomicBoolean

/** One-shot deterministic crash points. Hooks run only after the runtime repository call returned. */
class ReliabilityFaultFixture private constructor() : ReliabilityRuntimeFaultHook {
    private val afterIntent = AtomicBoolean()
    private val afterStarted = AtomicBoolean()
    private val afterReturned = AtomicBoolean()

    fun crashAfterIntentOnce() {
        afterIntent.set(true)
    }

    fun crashAfterCallStartedOnce() {
        afterStarted.set(true)
    }

    fun crashAfterProviderReturnedOnce() {
        afterReturned.set(true)
    }

    override fun afterIntentStored(run: ReliabilityRun) {
        if (afterIntent.compareAndSet(true, false)) error("simulated reliability crash after intent")
    }

    override fun afterCallStarted(run: ReliabilityRun) {
        if (afterStarted.compareAndSet(true, false)) error("simulated reliability crash after call started")
    }

    override fun afterProviderReturned(run: ReliabilityRun) {
        if (afterReturned.compareAndSet(true, false)) error("simulated reliability crash after provider returned")
    }

    companion object {
        @JvmStatic fun create(): ReliabilityFaultFixture = ReliabilityFaultFixture()
    }
}
