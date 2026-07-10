package com.fileweft.dev

import com.fileweft.dev.api.config.FileWeftDevProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

/** Development-only FileWeft acceptance application. */
@SpringBootApplication(scanBasePackages = ["com.fileweft.dev.api"])
@EnableScheduling
@EnableConfigurationProperties(FileWeftDevProperties::class)
class FileWeftDevApplication

fun main(args: Array<String>) {
    runApplication<FileWeftDevApplication>(*args)
}
