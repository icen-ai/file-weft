package ai.icen.fw.dev.api.web

import ai.icen.fw.dev.api.service.DevOperationsService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api")
class DevOperationsController(
    private val operations: DevOperationsService,
) {
    @GetMapping("/health")
    fun health(): Map<String, String> = mapOf("status" to "UP", "application" to "fileweft-dev")

    @PostMapping("/outbox/process")
    fun processOutbox(@RequestParam(defaultValue = "20") limit: Int) = operations.processOutbox(limit)

    @PostMapping("/tasks/process")
    fun processTasks(@RequestParam(defaultValue = "20") limit: Int) = operations.processTasks(limit)
}
