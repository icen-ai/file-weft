package ai.icen.fw.buildlogic

import java.io.File
import java.nio.charset.StandardCharsets

data class JvmApiRecord(
    val kind: String,
    val owner: String,
    val name: String,
    val descriptor: String,
    val attributes: Map<String, String> = emptyMap(),
) : Comparable<JvmApiRecord> {
    init {
        require(kindPattern.matches(kind)) { "Invalid JVM API record kind '$kind'." }
        require(owner.isNotBlank() && !owner.contains('\t') && !owner.contains('\n') && !owner.contains('\r')) {
            "Invalid JVM API owner '$owner'."
        }
        require(!name.contains('\t') && !name.contains('\n') && !name.contains('\r')) {
            "Invalid JVM API record name '$name'."
        }
        require(!descriptor.contains('\t') && !descriptor.contains('\n') && !descriptor.contains('\r')) {
            "Invalid JVM API descriptor '$descriptor'."
        }
        require(attributes.keys.all(attributeKeyPattern::matches)) {
            "Invalid JVM API attribute key in ${attributes.keys}."
        }
    }

    val symbolKey: String
        get() = listOf(kind, owner, name, descriptor).joinToString("\u0000")

    fun render(): String = buildList {
        add(kind)
        add(escape(owner))
        add(escape(name))
        add(escape(descriptor))
        attributes.toSortedMap().forEach { (key, value) -> add("$key=${escape(value)}") }
    }.joinToString("\t")

    override fun compareTo(other: JvmApiRecord): Int = render().compareTo(other.render())

    companion object {
        private val kindPattern = Regex("^[A-Z][A-Z_]*$")
        private val attributeKeyPattern = Regex("^[a-z][a-zA-Z0-9]*$")

        @JvmStatic
        fun parse(line: String, sourceName: String, lineNumber: Int): JvmApiRecord {
            val columns = line.split('\t')
            require(columns.size >= 4) {
                "$sourceName line $lineNumber must contain at least four tab-separated columns."
            }
            val attributes = linkedMapOf<String, String>()
            columns.drop(4).forEach { column ->
                val separator = column.indexOf('=')
                require(separator > 0) {
                    "$sourceName line $lineNumber contains an invalid attribute '$column'."
                }
                val key = column.substring(0, separator)
                val value = unescape(column.substring(separator + 1), sourceName, lineNumber)
                require(attributes.put(key, value) == null) {
                    "$sourceName line $lineNumber contains duplicate attribute '$key'."
                }
            }
            return JvmApiRecord(
                kind = columns[0],
                owner = unescape(columns[1], sourceName, lineNumber),
                name = unescape(columns[2], sourceName, lineNumber),
                descriptor = unescape(columns[3], sourceName, lineNumber),
                attributes = attributes,
            )
        }

        private fun escape(value: String): String = buildString(value.length) {
            value.forEach { character ->
                when (character) {
                    '%' -> append("%25")
                    '\t' -> append("%09")
                    '\n' -> append("%0A")
                    '\r' -> append("%0D")
                    else -> append(character)
                }
            }
        }

        private fun unescape(value: String, sourceName: String, lineNumber: Int): String {
            val result = StringBuilder(value.length)
            var index = 0
            while (index < value.length) {
                if (value[index] != '%') {
                    result.append(value[index++])
                    continue
                }
                require(index + 2 < value.length) {
                    "$sourceName line $lineNumber contains a truncated percent escape."
                }
                val token = value.substring(index, index + 3)
                val decoded = when (token) {
                    "%25" -> '%'
                    "%09" -> '\t'
                    "%0A" -> '\n'
                    "%0D" -> '\r'
                    else -> throw IllegalArgumentException(
                        "$sourceName line $lineNumber contains unsupported percent escape '$token'.",
                    )
                }
                result.append(decoded)
                index += 3
            }
            return result.toString()
        }
    }
}

data class JvmApiSnapshot(
    val artifactId: String,
    val baselineVersion: String,
    val records: List<JvmApiRecord>,
) {
    init {
        require(artifactPattern.matches(artifactId)) { "Invalid JVM API artifact ID '$artifactId'." }
        require(versionPattern.matches(baselineVersion)) { "Invalid JVM API baseline version '$baselineVersion'." }
        require(records == records.sorted()) { "JVM API records must be sorted deterministically." }
        require(records.size == records.toSet().size) { "JVM API snapshot contains duplicate records." }
    }

    val publicClassNames: Set<String>
        get() = records.asSequence()
            .filter { record -> record.kind == CLASS }
            .map { record -> record.owner }
            .toSortedSet()

    fun render(): String = buildString {
        append(FORMAT_HEADER).append('\n')
        append("# artifactId=").append(artifactId).append('\n')
        append("# baselineVersion=").append(baselineVersion).append('\n')
        records.forEach { record -> append(record.render()).append('\n') }
    }

    companion object {
        const val FORMAT_HEADER = "# flowweft-jvm-api-v1"
        const val CLASS = "CLASS"
        const val FIELD = "FIELD"
        const val METHOD = "METHOD"
        const val RECORD_COMPONENT = "RECORD_COMPONENT"
        const val ANNOTATION = "ANNOTATION"
        const val ANNOTATION_DEFAULT = "ANNOTATION_DEFAULT"
        const val KOTLIN_METADATA = "KOTLIN_METADATA"
        const val PERMITTED_SUBCLASS = "PERMITTED_SUBCLASS"

        private val artifactPattern = Regex("^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$")
        private val versionPattern = Regex("^[0-9]+\\.[0-9]+\\.[0-9]+$")

        @JvmStatic
        fun read(file: File): JvmApiSnapshot {
            require(file.isFile && file.length() > 0L) {
                "JVM API baseline is missing or empty: ${file.absolutePath}"
            }
            val bytes = file.readBytes()
            require(bytes.none { byte -> byte == '\r'.code.toByte() }) {
                "JVM API baseline must use UTF-8 LF line endings: ${file.absolutePath}"
            }
            val text = bytes.toString(StandardCharsets.UTF_8)
            require(text.endsWith('\n')) {
                "JVM API baseline must end with exactly one LF: ${file.absolutePath}"
            }
            return parse(text, file.path)
        }

        @JvmStatic
        fun parse(text: String, sourceName: String = "JVM API baseline"): JvmApiSnapshot {
            require(!text.contains('\r')) { "$sourceName must use UTF-8 LF line endings." }
            require(text.endsWith('\n')) { "$sourceName must end with LF." }
            require(!text.endsWith("\n\n")) { "$sourceName must not contain a trailing blank line." }
            val lines = text.dropLast(1).split('\n')
            require(lines.size >= 3 && lines[0] == FORMAT_HEADER) {
                "$sourceName must start with '$FORMAT_HEADER'."
            }
            val artifactId = requiredHeader(lines[1], "artifactId", sourceName)
            val baselineVersion = requiredHeader(lines[2], "baselineVersion", sourceName)
            val records = lines.drop(3).mapIndexed { index, line ->
                require(line.isNotBlank()) { "$sourceName contains a blank record at line ${index + 4}." }
                JvmApiRecord.parse(line, sourceName, index + 4)
            }
            require(records == records.sorted()) { "$sourceName records are not sorted deterministically." }
            return JvmApiSnapshot(artifactId, baselineVersion, records)
        }

        private fun requiredHeader(line: String, key: String, sourceName: String): String {
            val prefix = "# $key="
            require(line.startsWith(prefix) && line.length > prefix.length) {
                "$sourceName must declare '$prefix<value>'."
            }
            return line.removePrefix(prefix)
        }
    }
}

data class JvmApiExports(
    val artifactId: String,
    val state: JvmApiBaselineState,
    val classNames: Set<String>,
) {
    fun render(): String = buildString {
        append(FORMAT_HEADER).append('\n')
        append("# artifactId=").append(artifactId).append('\n')
        append("# state=").append(state.name.lowercase()).append('\n')
        classNames.sorted().forEach { className -> append(className).append('\n') }
    }

    companion object {
        const val FORMAT_HEADER = "# flowweft-jvm-exports-v1"
        private val artifactPattern = Regex("^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$")
        private val classPattern = Regex(
            "^[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*$",
        )

        @JvmStatic
        fun read(file: File): JvmApiExports {
            require(file.isFile && file.length() > 0L) {
                "JVM API export manifest is missing or empty: ${file.absolutePath}"
            }
            val text = file.readText(StandardCharsets.UTF_8)
            require(!text.contains('\r') && text.endsWith('\n') && !text.endsWith("\n\n")) {
                "JVM API export manifest must use deterministic UTF-8 LF: ${file.absolutePath}"
            }
            val lines = text.dropLast(1).split('\n')
            require(lines.size >= 3 && lines[0] == FORMAT_HEADER) {
                "JVM API export manifest must start with '$FORMAT_HEADER': ${file.absolutePath}"
            }
            val artifactId = lines[1].removePrefix("# artifactId=")
            require(lines[1] == "# artifactId=$artifactId" && artifactPattern.matches(artifactId)) {
                "JVM API export manifest has an invalid artifact ID: ${file.absolutePath}"
            }
            val state = when (lines[2]) {
                "# state=pending" -> JvmApiBaselineState.PENDING
                "# state=ready" -> JvmApiBaselineState.READY
                else -> throw IllegalArgumentException(
                    "JVM API export manifest has invalid state '${lines[2]}': ${file.absolutePath}",
                )
            }
            val classNames = lines.drop(3).toSet()
            require(classNames.size == lines.size - 3) {
                "JVM API export manifest contains duplicate classes: ${file.absolutePath}"
            }
            require(classNames.all(classPattern::matches)) {
                "JVM API export manifest contains an invalid binary class name: ${file.absolutePath}"
            }
            require(classNames.toList() == classNames.sorted()) {
                "JVM API export classes must be sorted: ${file.absolutePath}"
            }
            if (state == JvmApiBaselineState.PENDING) {
                require(classNames.isEmpty()) {
                    "Pending JVM API export manifest must not contain unreviewed class names: ${file.absolutePath}"
                }
            }
            return JvmApiExports(artifactId, state, classNames)
        }
    }
}
