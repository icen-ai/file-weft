package ai.icen.fw.migration.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.ArrayList
import java.util.Arrays
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FlowWeftMigrationCliTest {
    @Test
    fun `all lines execute once in fixed legacy then workflow order`() {
        val calls = ArrayList<String>()
        val result = execute(
            arguments("--lines=all"),
            factory = FlowWeftMigrationActionFactory { line, _ ->
                object : FlowWeftMigrationAction {
                    override fun migrate(): Int {
                        calls += line.id
                        return if (line == FlowWeftMigrationLine.LEGACY) 30 else 3
                    }

                    override fun validate() = error("not expected")
                }
            },
        )

        assertEquals(FlowWeftMigrationExitCode.SUCCESS, result.code)
        assertEquals(listOf("legacy", "workflow"), calls)
        assertTrue(result.output.contains("\"migrationsExecuted\":33"))
        assertTrue(result.output.contains("\"lineResults\":{\"legacy\":30,\"workflow\":3}"))
        assertFalse(result.output.contains("jdbc:"))
        assertFalse(result.output.contains("secret-value"))
    }

    @Test
    fun `validation preserves order and reports zero executed migrations`() {
        val calls = ArrayList<String>()
        val result = execute(
            arguments("--mode=validate", "--lines=legacy,workflow"),
            factory = FlowWeftMigrationActionFactory { line, _ ->
                object : FlowWeftMigrationAction {
                    override fun migrate(): Int = error("not expected")
                    override fun validate() { calls += line.id }
                }
            },
        )

        assertEquals(FlowWeftMigrationExitCode.SUCCESS, result.code)
        assertEquals(listOf("legacy", "workflow"), calls)
        assertTrue(result.output.contains("\"migrationsExecuted\":0"))
    }

    @Test
    fun `environment-only configuration supports a workflow-only validation Job`() {
        var validated = false
        val environment = mapOf(
            "FLOWWEFT_MIGRATION_JDBC_URL" to "jdbc:postgresql://localhost:5432/fileweft",
            "FLOWWEFT_MIGRATION_JDBC_USER" to "migration_user",
            "FLOWWEFT_MIGRATION_JDBC_PASSWORD" to "secret-value",
            "FLOWWEFT_MIGRATION_SCHEMA" to "workflow_only",
            "FLOWWEFT_MIGRATION_MODE" to "validate",
            "FLOWWEFT_MIGRATION_LINES" to "workflow",
            "FLOWWEFT_MIGRATION_CREATE_SCHEMA" to "false",
        )
        val result = execute(
            emptyArray(),
            environment,
            FlowWeftMigrationActionFactory { line, options ->
                assertEquals(FlowWeftMigrationLine.WORKFLOW, line)
                assertEquals(FlowWeftMigrationMode.VALIDATE, options.mode)
                assertEquals("workflow_only", options.schema)
                object : FlowWeftMigrationAction {
                    override fun migrate(): Int = error("not expected")
                    override fun validate() { validated = true }
                }
            },
        )

        assertEquals(FlowWeftMigrationExitCode.SUCCESS, result.code)
        assertTrue(validated)
        assertTrue(result.output.contains("\"lineResults\":{\"workflow\":0}"))
        assertFalse(result.output.contains("migration_user"))
        assertFalse(result.output.contains("secret-value"))
    }

    @Test
    fun `workflow only schema creation and password arguments fail before runner creation`() {
        var factories = 0
        val workflowCreate = execute(
            arguments("--lines=workflow", "--create-schema=true"),
            factory = FlowWeftMigrationActionFactory { _, _ ->
                factories++
                error("must not run")
            },
        )
        val passwordArgument = execute(
            arguments("--password=secret-value"),
            factory = FlowWeftMigrationActionFactory { _, _ -> error("must not run") },
        )

        assertEquals(FlowWeftMigrationExitCode.INVALID_CONFIGURATION, workflowCreate.code)
        assertEquals(FlowWeftMigrationExitCode.INVALID_CONFIGURATION, passwordArgument.code)
        assertEquals(0, factories)
        assertTrue(workflowCreate.error.contains("\"code\":\"INVALID_CONFIGURATION\""))
        assertFalse(passwordArgument.error.contains("secret-value"))
    }

    @Test
    fun `embedded URL credentials and ambiguous secret sources are rejected without leakage`() {
        val embedded = execute(
            arguments("--url=jdbc:postgresql://localhost/db?password=secret-value"),
            factory = FlowWeftMigrationActionFactory { _, _ -> error("must not run") },
        )
        val secretFile = Files.createTempFile("flowweft-migration-secret", ".txt")
        Files.write(secretFile, "file-secret\n".toByteArray(StandardCharsets.UTF_8))
        try {
            val ambiguous = execute(
                arguments("--password-file=$secretFile"),
                factory = FlowWeftMigrationActionFactory { _, _ -> error("must not run") },
            )
            assertEquals(FlowWeftMigrationExitCode.INVALID_CONFIGURATION, ambiguous.code)
            assertFalse(ambiguous.error.contains("file-secret"))
            assertFalse(ambiguous.error.contains("secret-value"))
        } finally {
            Files.deleteIfExists(secretFile)
        }
        assertEquals(FlowWeftMigrationExitCode.INVALID_CONFIGURATION, embedded.code)
        assertFalse(embedded.error.contains("secret-value"))
    }

    @Test
    fun `encoded and vendor credential URL properties are rejected`() {
        val urls = listOf(
            "jdbc:postgresql://localhost/db?pass%77ord=secret-value",
            "jdbc:postgresql://localhost/db?ssl-password=secret-value",
            "jdbc:mysql://localhost/db?accessToken=secret-value",
            "jdbc:kingbase8://localhost/db?access-key-secret=secret-value",
            "jdbc:postgresql://user:secret-value@localhost/db",
        )

        urls.forEach { url ->
            val result = execute(
                arguments("--url=$url"),
                factory = FlowWeftMigrationActionFactory { _, _ -> error("must not run") },
            )
            assertEquals(FlowWeftMigrationExitCode.INVALID_CONFIGURATION, result.code, url)
            assertFalse(result.error.contains("secret-value"), url)
            assertFalse(result.error.contains("jdbc:"), url)
        }
    }

    @Test
    fun `migration line selection is explicit unique and fixed`() {
        val invalid = listOf(
            "workflow,legacy",
            "legacy,legacy",
            "legacy,agent",
            "legacy, workflow",
            "",
        )

        invalid.forEach { lines ->
            val result = execute(
                arguments("--lines=$lines"),
                factory = FlowWeftMigrationActionFactory { _, _ -> error("must not run") },
            )
            assertEquals(FlowWeftMigrationExitCode.INVALID_CONFIGURATION, result.code, lines)
        }
    }

    @Test
    fun `bounded UTF-8 password file is accepted and oversized file is rejected safely`() {
        val secretFile = Files.createTempFile("flowweft-migration-secret", ".txt")
        val oversized = Files.createTempFile("flowweft-migration-secret-oversized", ".txt")
        Files.write(secretFile, "file-secret\r\n".toByteArray(StandardCharsets.UTF_8))
        Files.write(oversized, ByteArray(16 * 1024 + 1) { 'x'.code.toByte() })
        var password: CharArray? = null
        try {
            val accepted = execute(
                arguments("--password-file=$secretFile"),
                emptyMap(),
                FlowWeftMigrationActionFactory { _, options ->
                    password = options.passwordCopy()
                    object : FlowWeftMigrationAction {
                        override fun migrate(): Int = 0
                        override fun validate() = Unit
                    }
                },
            )
            assertEquals(FlowWeftMigrationExitCode.SUCCESS, accepted.code)
            assertEquals("file-secret", password?.concatToString())

            val rejected = execute(
                arguments("--password-file=$oversized"),
                emptyMap(),
                FlowWeftMigrationActionFactory { _, _ -> error("must not run") },
            )
            assertEquals(FlowWeftMigrationExitCode.INVALID_CONFIGURATION, rejected.code)
            assertFalse(rejected.error.contains(oversized.toString()))
        } finally {
            password?.let { Arrays.fill(it, '\u0000') }
            Files.deleteIfExists(secretFile)
            Files.deleteIfExists(oversized)
        }
    }

    @Test
    fun `migration failure is sanitized and stops later lines`() {
        val calls = ArrayList<String>()
        val result = execute(
            arguments("--lines=all"),
            factory = FlowWeftMigrationActionFactory { line, _ ->
                object : FlowWeftMigrationAction {
                    override fun migrate(): Int {
                        calls += line.id
                        throw IllegalStateException("jdbc:postgresql://db?password=secret-value")
                    }

                    override fun validate() = error("not expected")
                }
            },
        )

        assertEquals(FlowWeftMigrationExitCode.MIGRATION_FAILED, result.code)
        assertEquals(listOf("legacy"), calls)
        assertTrue(result.error.contains("\"code\":\"MIGRATION_FAILED\""))
        assertTrue(result.error.contains("\"failedLine\":\"legacy\""))
        assertFalse(result.error.contains("jdbc:"))
        assertFalse(result.error.contains("secret-value"))
    }

    @Test
    fun `linkage failure has the deterministic migration failure code without a stack trace`() {
        val result = execute(
            arguments(),
            factory = FlowWeftMigrationActionFactory { _, _ ->
                throw NoClassDefFoundError("jdbc:postgresql://db?password=secret-value")
            },
        )

        assertEquals(FlowWeftMigrationExitCode.MIGRATION_FAILED, result.code)
        assertTrue(result.error.contains("\"failedLine\":\"legacy\""))
        assertFalse(result.error.contains("NoClassDefFoundError"))
        assertFalse(result.error.contains("secret-value"))
    }

    @Test
    fun `help succeeds without configuration or database access`() {
        val result = execute(
            arrayOf("--help"),
            emptyMap(),
            FlowWeftMigrationActionFactory { _, _ -> error("must not run") },
        )

        assertEquals(FlowWeftMigrationExitCode.SUCCESS, result.code)
        assertTrue(result.output.contains("--password-file"))
    }

    private fun arguments(vararg replacements: String): Array<String> {
        val values = linkedMapOf(
            "url" to "--url=jdbc:postgresql://localhost:5432/fileweft",
            "user" to "--user=fileweft",
            "schema" to "--schema=public",
            "mode" to "--mode=migrate",
            "lines" to "--lines=legacy",
            "create-schema" to "--create-schema=false",
        )
        replacements.forEach { replacement ->
            val name = replacement.substringAfter("--").substringBefore('=')
            values[name] = replacement
        }
        return values.values.toTypedArray()
    }

    private fun execute(
        args: Array<String>,
        environment: Map<String, String> = mapOf("FLOWWEFT_MIGRATION_JDBC_PASSWORD" to "secret-value"),
        factory: FlowWeftMigrationActionFactory,
    ): Result {
        val output = ByteArrayOutputStream()
        val error = ByteArrayOutputStream()
        val code = PrintStream(output, true, "UTF-8").use { out ->
            PrintStream(error, true, "UTF-8").use { err ->
                FlowWeftMigrationCli.execute(args, environment, out, err, factory)
            }
        }
        return Result(
            code,
            output.toString("UTF-8"),
            error.toString("UTF-8"),
        )
    }

    private class Result(val code: Int, val output: String, val error: String)
}
