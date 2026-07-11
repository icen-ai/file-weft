package com.fileweft.dev.platform

import com.fileweft.starter.boot3.FileWeftAutoConfiguration
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication

/** Isolated downstream HTTP platform used only by the repository development stack. */
@SpringBootApplication(exclude = [FileWeftAutoConfiguration::class])
@EnableConfigurationProperties(DevPlatformProperties::class)
class DevPlatformApplication

fun main(args: Array<String>) {
    runApplication<DevPlatformApplication>(*args)
}
