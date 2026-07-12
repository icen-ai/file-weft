package ai.icen.fw.runtime.plugin

import ai.icen.fw.spi.plugin.FileWeftPlugin

class RuntimeServiceLoadedPlugin : FileWeftPlugin {
    override fun id(): String = ID

    companion object {
        const val ID = "runtime-service-loader-test"
    }
}
