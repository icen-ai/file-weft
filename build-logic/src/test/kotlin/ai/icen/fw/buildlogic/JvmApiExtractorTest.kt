package ai.icen.fw.buildlogic

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.nio.file.Files
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JvmApiExtractorTest {

    @Test
    fun `extractor emits a deterministic round trippable public API`() {
        val fixtureRoot = Files.createTempDirectory("flowweft-jvm-api-extractor-").toFile()
        try {
            val jar = fixtureRoot.resolve("fixture.jar")
            writeJar(
                jar,
                "example/Api.class" to publicApiBytes(),
                "example/Hidden.class" to hiddenClassBytes(),
            )

            val first = JvmApiExtractor.extract(jar, ARTIFACT, "1.0.0")
            val second = JvmApiExtractor.extract(jar, ARTIFACT, "1.0.0")

            assertEquals(first, second)
            assertEquals(setOf("example.Api"), first.publicClassNames)
            assertEquals(first.records.sorted(), first.records)
            assertEquals(first, JvmApiSnapshot.parse(first.render(), "round-trip fixture"))
            assertTrue(first.render().endsWith('\n'))
            assertTrue(!first.render().contains('\r'))
            assertTrue(first.render().contains("%25"), "Percent characters must be escaped in canonical records")

            val apiClass = first.records.single { record -> record.kind == JvmApiSnapshot.CLASS }
            assertEquals("class", apiClass.attributes["kind"])
            assertEquals("java.lang.Object", apiClass.attributes["super"])
            assertEquals("java.lang.Runnable", apiClass.attributes["interfaces"])

            val field = first.records.single { record ->
                record.kind == JvmApiSnapshot.FIELD && record.name == "ANSWER"
            }
            assertEquals("int:\"42\"", field.attributes["constant"])
            assertTrue(field.attributes.getValue("access").contains("static"))
            assertTrue(field.attributes.getValue("access").contains("final"))

            val method = first.records.single { record ->
                record.kind == JvmApiSnapshot.METHOD && record.name == "work"
            }
            assertEquals("java.io.IOException", method.attributes["exceptions"])
            assertTrue(first.records.any { record ->
                record.kind == JvmApiSnapshot.ANNOTATION && record.descriptor == "Lkotlin/Metadata;"
            })
            assertTrue(first.records.any { record ->
                record.kind == JvmApiSnapshot.KOTLIN_METADATA &&
                    record.attributes["kind"] == "int:\"1\"" &&
                    record.attributes["extraInt"] == "int:\"48\""
            })
        } finally {
            fixtureRoot.deleteRecursively()
        }
    }

    @Test
    fun `extractor rejects duplicate public classes including multi release shadows`() {
        val fixtureRoot = Files.createTempDirectory("flowweft-jvm-api-duplicate-").toFile()
        try {
            val jar = fixtureRoot.resolve("duplicate.jar")
            val bytes = publicApiBytes()
            writeJar(
                jar,
                "example/Api.class" to bytes,
                "META-INF/versions/11/example/Api.class" to bytes,
            )

            val failure = assertFailsWith<IllegalArgumentException> {
                JvmApiExtractor.extract(jar, ARTIFACT, "1.0.0")
            }
            assertTrue(failure.message.orEmpty().contains("duplicate public class"), failure.message)
        } finally {
            fixtureRoot.deleteRecursively()
        }
    }

    @Test
    fun `snapshot parser rejects malformed escapes and non canonical line endings`() {
        val prefix = buildString {
            append(JvmApiSnapshot.FORMAT_HEADER).append('\n')
            append("# artifactId=").append(ARTIFACT).append('\n')
            append("# baselineVersion=1.0.0\n")
        }
        val malformedEscape = prefix + "CLASS\texample%2FApi\t\t\taccess=public\tkind=class\n"
        val escapeFailure = assertFailsWith<IllegalArgumentException> {
            JvmApiSnapshot.parse(malformedEscape, "malicious escape fixture")
        }
        assertTrue(escapeFailure.message.orEmpty().contains("unsupported percent escape"), escapeFailure.message)

        val crlfFailure = assertFailsWith<IllegalArgumentException> {
            JvmApiSnapshot.parse(prefix.replace("\n", "\r\n"), "CRLF fixture")
        }
        assertTrue(crlfFailure.message.orEmpty().contains("LF line endings"), crlfFailure.message)
    }

    @Test
    fun `export manifest parser rejects unreviewed and malicious class sets`() {
        val fixtureRoot = Files.createTempDirectory("flowweft-jvm-api-exports-").toFile()
        try {
            val manifest = fixtureRoot.resolve("exports.manifest")
            val fixtures = listOf(
                exportText("pending", "example.Api") to "Pending",
                exportText("ready", "example.Zed", "example.Api") to "sorted",
                exportText("ready", "example..Leaked") to "invalid binary class name",
            )
            fixtures.forEach { (content, expectedMessage) ->
                manifest.writeText(content, Charsets.UTF_8)
                val failure = assertFailsWith<IllegalArgumentException> {
                    JvmApiExports.read(manifest)
                }
                assertTrue(failure.message.orEmpty().contains(expectedMessage), failure.message)
            }
        } finally {
            fixtureRoot.deleteRecursively()
        }
    }

    private fun publicApiBytes(): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT or Opcodes.ACC_SUPER,
            "example/Api",
            "<T:Ljava/lang/Object;>Ljava/lang/Object;",
            "java/lang/Object",
            arrayOf("java/lang/Runnable"),
        )

        writer.visitAnnotation("Lexample/Marker;", true).apply {
            visit("value", "50%\tline\n")
            visitEnd()
        }
        writer.visitAnnotation("Lkotlin/Metadata;", true).apply {
            visit("k", 1)
            visit("mv", intArrayOf(2, 1, 0))
            visit("xi", 48)
            visitEnd()
        }
        writer.visitField(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL,
            "ANSWER",
            "I",
            null,
            42,
        ).apply {
            visitAnnotation("Lexample/Marker;", false).apply {
                visit("value", "field")
                visitEnd()
            }
            visitEnd()
        }
        writer.visitMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT,
            "work",
            "(Ljava/lang/String;)Ljava/lang/String;",
            null,
            arrayOf("java/io/IOException"),
        ).apply {
            visitParameter("input", Opcodes.ACC_FINAL)
            visitAnnotation("Lexample/Marker;", true).apply {
                visit("value", "method")
                visitEnd()
            }
            visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun hiddenClassBytes(): ByteArray = ClassWriter(0).apply {
        visit(Opcodes.V1_8, Opcodes.ACC_SUPER, "example/Hidden", null, "java/lang/Object", null)
        visitEnd()
    }.toByteArray()

    private fun writeJar(target: java.io.File, vararg entries: Pair<String, ByteArray>) {
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
        }
        JarOutputStream(Files.newOutputStream(target.toPath()), manifest).use { output ->
            entries.forEach { (name, bytes) ->
                output.putNextEntry(JarEntry(name).apply { time = 0L })
                output.write(bytes)
                output.closeEntry()
            }
        }
    }

    private fun exportText(state: String, vararg classes: String): String = buildString {
        append(JvmApiExports.FORMAT_HEADER).append('\n')
        append("# artifactId=").append(ARTIFACT).append('\n')
        append("# state=").append(state).append('\n')
        classes.forEach { className -> append(className).append('\n') }
    }

    private companion object {
        const val ARTIFACT = "flowweft-example"
    }
}
