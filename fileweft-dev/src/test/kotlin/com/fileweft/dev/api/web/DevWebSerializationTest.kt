package com.fileweft.dev.api.web

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fileweft.dev.api.service.DevDocumentDetail
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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

    @Test
    fun `document read projection never contains raw Doctor records`() {
        val fields = DevDocumentDetail::class.java.declaredFields.map { field -> field.name }.toSet()

        assertFalse("doctorRecords" in fields)
        assertFalse("reportJson" in fields)
        assertFalse("report" in fields)
    }
}
