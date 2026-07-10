package com.fileweft.runtime.plugin

import com.fileweft.spi.plugin.FileWeftPlugin

class RuntimeServiceLoadedPlugin : FileWeftPlugin {
    override fun id(): String = ID

    companion object {
        const val ID = "runtime-service-loader-test"
    }
}
