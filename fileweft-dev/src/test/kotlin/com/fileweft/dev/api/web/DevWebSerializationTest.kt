package com.fileweft.dev.api.web

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DevWebSerializationTest {
    @Test
    fun `deserializes Kotlin login DTOs`() {
        val request: DevLoginRequest = jacksonObjectMapper().readValue(
            """{"username":"editor@alpha","password":"dev-editor"}""",
        )

        assertEquals("editor@alpha", request.username)
        assertEquals("dev-editor", request.password)
    }
}
