package ai.icen.fw.migration.cli

import ai.icen.fw.persistence.migration.FlywayMigrationRunner
import ai.icen.fw.workflow.persistence.migration.WorkflowFlywayMigrationRunner
import java.io.InputStream
import java.io.PrintStream
import java.io.PrintWriter
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Arrays
import java.util.Collections
import java.util.Locale
import java.util.Properties
import java.util.logging.Logger
import javax.sql.DataSource
import kotlin.system.exitProcess

/** Process exit codes are stable so Kubernetes Jobs and release automation can classify failures. */
object FlowWeftMigrationExitCode {
    const val SUCCESS: Int = 0
    const val INVALID_CONFIGURATION: Int = 2
    const val MIGRATION_FAILED: Int = 10
}

enum class FlowWeftMigrationMode {
    MIGRATE,
    VALIDATE,
}

enum class FlowWeftMigrationLine(val id: String) {
    LEGACY("legacy"),
    WORKFLOW("workflow"),
}

internal class FlowWeftMigrationOptions(
    val jdbcUrl: String,
    val user: String,
    password: CharArray,
    val schema: String,
    val mode: FlowWeftMigrationMode,
    lines: Collection<FlowWeftMigrationLine>,
    val createSchema: Boolean,
) : AutoCloseable {
    private val passwordSnapshot = password.copyOf()
    val lines: List<FlowWeftMigrationLine> = Collections.unmodifiableList(ArrayList(lines))

    init {
        require(this.lines.isNotEmpty()) { "At least one migration line is required." }
        require(this.lines.size == this.lines.toSet().size) { "Migration lines must be unique." }
        require(this.lines == FIXED_ORDER.filter { it in this.lines }) {
            "Migration lines must use the fixed legacy,workflow order."
        }
        require(!createSchema || mode == FlowWeftMigrationMode.MIGRATE) {
            "Schema creation is available only in migrate mode."
        }
        require(!createSchema || FlowWeftMigrationLine.LEGACY in this.lines) {
            "Workflow-only migration cannot create a schema; pre-create it explicitly."
        }
    }

    fun passwordCopy(): CharArray = passwordSnapshot.copyOf()

    override fun close() {
        Arrays.fill(passwordSnapshot, '\u0000')
    }

    companion object {
        val FIXED_ORDER: List<FlowWeftMigrationLine> = listOf(
            FlowWeftMigrationLine.LEGACY,
            FlowWeftMigrationLine.WORKFLOW,
        )
    }
}

internal interface FlowWeftMigrationAction : AutoCloseable {
    fun migrate(): Int

    fun validate()

    override fun close() = Unit
}

internal fun interface FlowWeftMigrationActionFactory {
    fun create(line: FlowWeftMigrationLine, options: FlowWeftMigrationOptions): FlowWeftMigrationAction
}

internal object FlowWeftMigrationCli {
    private const val MAX_SECRET_BYTES = 16 * 1024
    private val SAFE_SCHEMA = Regex("[A-Za-z_][A-Za-z0-9_$]*")
    private val JDBC_PROPERTY = Regex("(?:[?;&])([^?;&=]+)=")
    private val AUTHORITY_CREDENTIAL = Regex("(?i)^jdbc:[a-z][a-z0-9+.-]*://[^/@]+@")

    fun execute(
        args: Array<String>,
        environment: Map<String, String>,
        out: PrintStream,
        err: PrintStream,
        factory: FlowWeftMigrationActionFactory = JDBC_ACTION_FACTORY,
    ): Int {
        if (args.size == 1 && args[0] == "--help") {
            out.println(USAGE)
            return FlowWeftMigrationExitCode.SUCCESS
        }
        val options = try {
            parse(args, environment)
        } catch (failure: MigrationConfigurationException) {
            err.println(configurationFailureJson(failure.safeReason))
            return FlowWeftMigrationExitCode.INVALID_CONFIGURATION
        }

        options.use { exact ->
            var activeLine: FlowWeftMigrationLine? = null
            return try {
                val executedByLine = LinkedHashMap<FlowWeftMigrationLine, Int>()
                exact.lines.forEach { line ->
                    activeLine = line
                    factory.create(line, exact).use { action ->
                        executedByLine[line] = when (exact.mode) {
                            FlowWeftMigrationMode.MIGRATE -> action.migrate()
                            FlowWeftMigrationMode.VALIDATE -> {
                                action.validate()
                                0
                            }
                        }
                    }
                }
                out.println(successJson(exact, executedByLine))
                FlowWeftMigrationExitCode.SUCCESS
            } catch (_: Exception) {
                // JDBC/Flyway exceptions can contain URLs, SQL, headers or credentials. Never echo them.
                err.println(migrationFailureJson(exact, activeLine))
                FlowWeftMigrationExitCode.MIGRATION_FAILED
            } catch (_: LinkageError) {
                // A missing/incompatible driver or Flyway runtime is an operational migration failure too.
                err.println(migrationFailureJson(exact, activeLine))
                FlowWeftMigrationExitCode.MIGRATION_FAILED
            }
        }
    }

    private fun parse(args: Array<String>, environment: Map<String, String>): FlowWeftMigrationOptions {
        val arguments = LinkedHashMap<String, String>()
        args.forEach { argument ->
            if (argument == "--help") throw MigrationConfigurationException("--help must be used alone.")
            val separator = argument.indexOf('=')
            if (!argument.startsWith("--") || separator <= 2 || separator == argument.lastIndex) {
                throw MigrationConfigurationException("Use only --name=value arguments.")
            }
            val name = argument.substring(2, separator)
            if (name == "password" || name == "jdbc-password") {
                throw MigrationConfigurationException("Passwords are forbidden in process arguments; use a secret file or environment.")
            }
            if (name !in ALLOWED_ARGUMENTS || arguments.put(name, argument.substring(separator + 1)) != null) {
                throw MigrationConfigurationException("An unknown or duplicate argument was supplied.")
            }
        }

        fun value(argument: String, variable: String): String? =
            arguments[argument]?.takeIf(String::isNotBlank) ?: environment[variable]?.takeIf(String::isNotBlank)

        val url = value("url", "FLOWWEFT_MIGRATION_JDBC_URL")
            ?: throw MigrationConfigurationException("FLOWWEFT_MIGRATION_JDBC_URL or --url is required.")
        requireSafeJdbcUrl(url)
        val user = value("user", "FLOWWEFT_MIGRATION_JDBC_USER")
            ?: throw MigrationConfigurationException("FLOWWEFT_MIGRATION_JDBC_USER or --user is required.")
        if (user.length > 256 || user.any(Character::isISOControl)) {
            throw MigrationConfigurationException("The JDBC user is invalid.")
        }
        val schema = value("schema", "FLOWWEFT_MIGRATION_SCHEMA")
            ?: throw MigrationConfigurationException("FLOWWEFT_MIGRATION_SCHEMA or --schema is required.")
        if (!SAFE_SCHEMA.matches(schema)) throw MigrationConfigurationException("The migration schema is invalid.")

        val mode = when (value("mode", "FLOWWEFT_MIGRATION_MODE")?.lowercase(Locale.ROOT)) {
            "migrate" -> FlowWeftMigrationMode.MIGRATE
            "validate" -> FlowWeftMigrationMode.VALIDATE
            else -> throw MigrationConfigurationException("Migration mode must be migrate or validate.")
        }
        val lines = parseLines(value("lines", "FLOWWEFT_MIGRATION_LINES"))
        val createSchema = parseBoolean(value("create-schema", "FLOWWEFT_MIGRATION_CREATE_SCHEMA") ?: "false")
        val passwordFile = value("password-file", "FLOWWEFT_MIGRATION_JDBC_PASSWORD_FILE")
        val environmentPassword = environment["FLOWWEFT_MIGRATION_JDBC_PASSWORD"]
        if (passwordFile != null && !environmentPassword.isNullOrEmpty()) {
            throw MigrationConfigurationException("Choose exactly one JDBC password source.")
        }
        val password = when {
            passwordFile != null -> readSecretFile(passwordFile)
            !environmentPassword.isNullOrEmpty() -> environmentPassword.toCharArray()
            else -> throw MigrationConfigurationException(
                "FLOWWEFT_MIGRATION_JDBC_PASSWORD_FILE/--password-file or password environment is required.",
            )
        }

        return try {
            FlowWeftMigrationOptions(url, user, password, schema, mode, lines, createSchema)
        } catch (failure: IllegalArgumentException) {
            throw MigrationConfigurationException(failure.message ?: "Migration options are inconsistent.")
        } finally {
            Arrays.fill(password, '\u0000')
        }
    }

    private fun parseLines(value: String?): List<FlowWeftMigrationLine> {
        val exact = value ?: throw MigrationConfigurationException("Migration lines must be explicit.")
        if (exact == "all") return FlowWeftMigrationOptions.FIXED_ORDER
        val values = exact.split(',')
        if (values.any { it.isEmpty() || it != it.trim() }) {
            throw MigrationConfigurationException("Migration lines must be legacy, workflow, or all.")
        }
        val requested = values.map { id ->
            FlowWeftMigrationLine.values().firstOrNull { it.id == id }
                ?: throw MigrationConfigurationException("Migration lines must be legacy, workflow, or all.")
        }
        if (requested.size != requested.toSet().size || requested != FlowWeftMigrationOptions.FIXED_ORDER.filter {
                it in requested
            }
        ) {
            throw MigrationConfigurationException("Migration lines must be unique and in fixed legacy,workflow order.")
        }
        return requested
    }

    private fun parseBoolean(value: String): Boolean = when (value.lowercase(Locale.ROOT)) {
        "true" -> true
        "false" -> false
        else -> throw MigrationConfigurationException("create-schema must be true or false.")
    }

    private fun requireSafeJdbcUrl(url: String) {
        if (url.length !in 1..4_096 || url.any { Character.isISOControl(it) } ||
            containsCredentialProperty(url) || AUTHORITY_CREDENTIAL.containsMatchIn(url)
        ) {
            throw MigrationConfigurationException("The JDBC URL is invalid or embeds credentials.")
        }
        val driver = DRIVER_CLASSES.entries.firstOrNull { url.startsWith(it.key) }
            ?: throw MigrationConfigurationException("The JDBC URL must target PostgreSQL, MySQL, or KingbaseES.")
        try {
            Class.forName(driver.value)
        } catch (_: ClassNotFoundException) {
            throw MigrationConfigurationException("The selected JDBC driver is unavailable.")
        } catch (_: SecurityException) {
            throw MigrationConfigurationException("The selected JDBC driver cannot be loaded in this runtime.")
        } catch (_: LinkageError) {
            throw MigrationConfigurationException("The selected JDBC driver is unavailable or incompatible.")
        }
    }

    private fun containsCredentialProperty(url: String): Boolean = JDBC_PROPERTY.findAll(url).any { match ->
        val decoded = try {
            URLDecoder.decode(match.groupValues[1], StandardCharsets.UTF_8.name())
        } catch (_: IllegalArgumentException) {
            return@any true
        }
        val normalized = decoded
            .lowercase(Locale.ROOT)
            .filter { character -> Character.isLetterOrDigit(character) }
        normalized in setOf("user", "username", "password", "passwd", "pwd") ||
            normalized.endsWith("password") ||
            normalized.endsWith("secret") ||
            normalized.endsWith("token") ||
            normalized.endsWith("credential") ||
            normalized.contains("accesskey")
    }

    private fun readSecretFile(path: String): CharArray {
        val secretPath = try {
            Paths.get(path)
        } catch (_: Exception) {
            throw MigrationConfigurationException("The JDBC password secret file cannot be read.")
        }
        val regularFile = try {
            Files.isRegularFile(secretPath)
        } catch (_: Exception) {
            false
        }
        if (!regularFile) {
            throw MigrationConfigurationException("The JDBC password secret file cannot be read.")
        }
        val bytes = try {
            Files.newInputStream(secretPath).use(::readBoundedSecret)
        } catch (failure: MigrationConfigurationException) {
            throw failure
        } catch (_: Exception) {
            throw MigrationConfigurationException("The JDBC password secret file cannot be read.")
        }
        try {
            if (bytes.isEmpty()) {
                throw MigrationConfigurationException("The JDBC password secret file size is invalid.")
            }
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val decoded = try {
                decoder.decode(ByteBuffer.wrap(bytes)).toString()
            } catch (_: Exception) {
                throw MigrationConfigurationException("The JDBC password secret file is not valid UTF-8.")
            }
            val value = when {
                decoded.endsWith("\r\n") -> decoded.dropLast(2)
                decoded.endsWith("\n") -> decoded.dropLast(1)
                else -> decoded
            }
            if (value.isEmpty() || value.any { it == '\u0000' || it == '\r' || it == '\n' }) {
                throw MigrationConfigurationException("The JDBC password secret file content is invalid.")
            }
            return value.toCharArray()
        } finally {
            Arrays.fill(bytes, 0)
        }
    }

    private fun readBoundedSecret(input: InputStream): ByteArray {
        val buffer = ByteArray(MAX_SECRET_BYTES + 1)
        var offset = 0
        try {
            while (offset < buffer.size) {
                val read = input.read(buffer, offset, buffer.size - offset)
                if (read < 0) break
                if (read == 0) continue
                offset += read
            }
            if (offset > MAX_SECRET_BYTES || input.read() >= 0) {
                throw MigrationConfigurationException("The JDBC password secret file size is invalid.")
            }
            return buffer.copyOf(offset)
        } finally {
            Arrays.fill(buffer, 0)
        }
    }

    private fun successJson(
        options: FlowWeftMigrationOptions,
        executedByLine: Map<FlowWeftMigrationLine, Int>,
    ): String {
        var total = 0
        executedByLine.values.forEach { executed -> total = Math.addExact(total, executed) }
        return "{\"status\":\"success\",\"mode\":\"${options.mode.name.lowercase(Locale.ROOT)}\"," +
            "\"lines\":[${options.lines.joinToString(",") { "\"${it.id}\"" }}]," +
            "\"lineResults\":{${executedByLine.entries.joinToString(",") { "\"${it.key.id}\":${it.value}" }}}," +
            "\"migrationsExecuted\":$total}"
    }

    private fun configurationFailureJson(reason: String): String =
        "{\"status\":\"failed\",\"code\":\"INVALID_CONFIGURATION\",\"reason\":${jsonString(reason)}}"

    private fun migrationFailureJson(
        options: FlowWeftMigrationOptions,
        failedLine: FlowWeftMigrationLine?,
    ): String =
        "{\"status\":\"failed\",\"code\":\"MIGRATION_FAILED\"," +
            "\"mode\":\"${options.mode.name.lowercase(Locale.ROOT)}\"," +
            "\"failedLine\":${failedLine?.let { "\"${it.id}\"" } ?: "null"}}"

    private fun jsonString(value: String): String = buildString(value.length + 2) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character < ' ') {
                    append("\\u")
                    append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }

    private val JDBC_ACTION_FACTORY = FlowWeftMigrationActionFactory { line, options ->
        val password = options.passwordCopy()
        val dataSource = try {
            SecretDriverManagerDataSource(options.jdbcUrl, options.user, password)
        } finally {
            Arrays.fill(password, '\u0000')
        }
        try {
            when (line) {
                FlowWeftMigrationLine.LEGACY -> {
                    val runner = FlywayMigrationRunner(dataSource, options.schema, options.createSchema)
                    object : FlowWeftMigrationAction {
                        override fun migrate(): Int = runner.migrate()
                        override fun validate() = runner.validate()
                        override fun close() = dataSource.close()
                    }
                }
                FlowWeftMigrationLine.WORKFLOW -> {
                    val runner = WorkflowFlywayMigrationRunner(dataSource, options.schema)
                    object : FlowWeftMigrationAction {
                        override fun migrate(): Int = runner.migrate()
                        override fun validate() = runner.validate()
                        override fun close() = dataSource.close()
                    }
                }
            }
        } catch (failure: Exception) {
            dataSource.close()
            throw failure
        } catch (failure: LinkageError) {
            dataSource.close()
            throw failure
        }
    }

    private val DRIVER_CLASSES = linkedMapOf(
        "jdbc:postgresql:" to "org.postgresql.Driver",
        "jdbc:mysql:" to "com.mysql.cj.jdbc.Driver",
        "jdbc:kingbase8:" to "com.kingbase8.Driver",
    )
    private val ALLOWED_ARGUMENTS = setOf("url", "user", "schema", "mode", "lines", "create-schema", "password-file")
    private const val USAGE = """Usage: flowweft-migration-cli --url=<jdbc-url> --user=<user> --schema=<schema> --mode=<migrate|validate> --lines=<legacy|workflow|legacy,workflow|all> [--create-schema=<true|false>] [--password-file=<path>]

Passwords are never accepted as command arguments. Prefer a mounted secret file through
FLOWWEFT_MIGRATION_JDBC_PASSWORD_FILE; FLOWWEFT_MIGRATION_JDBC_PASSWORD is the fallback."""
}

private class MigrationConfigurationException(val safeReason: String) : RuntimeException(safeReason)

private class SecretDriverManagerDataSource(
    private val url: String,
    private val user: String,
    password: CharArray,
) : DataSource, AutoCloseable {
    private val password = password.copyOf()
    private var logWriter: PrintWriter? = null
    private var loginTimeout: Int = 0

    override fun getConnection(): Connection = connection(user, password)

    override fun getConnection(username: String, password: String): Connection {
        val secret = password.toCharArray()
        return try {
            connection(username, secret)
        } finally {
            Arrays.fill(secret, '\u0000')
        }
    }

    private fun connection(username: String, secret: CharArray): Connection {
        val properties = Properties()
        properties.setProperty("user", username)
        properties.setProperty("password", String(secret))
        return try {
            DriverManager.getConnection(url, properties)
        } finally {
            properties.remove("password")
        }
    }

    override fun getLogWriter(): PrintWriter? = logWriter
    override fun setLogWriter(out: PrintWriter?) { logWriter = out }
    override fun setLoginTimeout(seconds: Int) { loginTimeout = seconds }
    override fun getLoginTimeout(): Int = loginTimeout
    override fun getParentLogger(): Logger = Logger.getLogger("ai.icen.fw.migration.cli")

    override fun <T : Any?> unwrap(iface: Class<T>): T {
        if (iface.isInstance(this)) return iface.cast(this)
        throw SQLException("FlowWeft migration DataSource does not wrap ${iface.name}.")
    }

    override fun isWrapperFor(iface: Class<*>): Boolean = iface.isInstance(this)

    override fun close() {
        Arrays.fill(password, '\u0000')
    }
}

fun main(args: Array<String>) {
    exitProcess(FlowWeftMigrationCli.execute(args, System.getenv(), System.out, System.err))
}
