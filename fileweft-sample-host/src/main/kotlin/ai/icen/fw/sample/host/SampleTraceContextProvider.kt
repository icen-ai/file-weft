package ai.icen.fw.sample.host

import ai.icen.fw.spi.observability.TraceContextProvider

/**
 * Sample host trace context provider. The sample host does not propagate an
 * active trace, so the provider returns null while still satisfying the SPI
 * contract.
 */
class SampleTraceContextProvider : TraceContextProvider {
    override fun currentTraceContext() = null
}
