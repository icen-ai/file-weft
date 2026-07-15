package ai.icen.fw.buildlogic

import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.RecordComponentVisitor
import org.objectweb.asm.Type
import org.objectweb.asm.TypePath
import java.io.File
import java.lang.reflect.Array as ReflectArray
import java.util.TreeMap
import java.util.jar.JarFile

/** Extracts the externally linkable JVM surface without loading any candidate class. */
object JvmApiExtractor {
    @JvmStatic
    fun extract(jarFile: File, artifactId: String, baselineVersion: String): JvmApiSnapshot {
        require(jarFile.isFile && jarFile.length() > 0L) {
            "Candidate API JAR is missing or empty: ${jarFile.absolutePath}"
        }
        val records = mutableListOf<JvmApiRecord>()
        val owners = linkedSetOf<String>()
        JarFile(jarFile, false).use { jar ->
            val classEntries = jar.entries().asSequence()
                .filter { entry -> !entry.isDirectory && entry.name.endsWith(".class") }
                .sortedBy { entry -> entry.name }
                .toList()
            require(classEntries.isNotEmpty()) { "Candidate API JAR contains no classes: ${jarFile.absolutePath}" }
            classEntries.forEach { entry ->
                val visitor = ApiClassVisitor()
                jar.getInputStream(entry).use { input ->
                    ClassReader(input).accept(visitor, ClassReader.SKIP_CODE or ClassReader.SKIP_FRAMES)
                }
                visitor.records().takeIf { extracted -> extracted.isNotEmpty() }?.let { extracted ->
                    val owner = extracted.first { record -> record.kind == JvmApiSnapshot.CLASS }.owner
                    require(owners.add(owner)) {
                        "Candidate API JAR exposes duplicate public class '$owner': ${jarFile.absolutePath}"
                    }
                    records += extracted
                }
            }
        }
        return JvmApiSnapshot(artifactId, baselineVersion, records.distinct().sorted())
    }

    @JvmStatic
    fun sha256(file: File): String = JvmApiProvenance.sha256(file)

    private class ApiClassVisitor : ClassVisitor(Opcodes.ASM9) {
        private var className: String = ""
        private var classAccess: Int = 0
        private var innerClassAccess: Int? = null
        private var classSignature: String? = null
        private var superName: String? = null
        private var interfaces: List<String> = emptyList()
        private val memberRecords = mutableListOf<JvmApiRecord>()
        private val annotationRecords = mutableListOf<JvmApiRecord>()
        private val classAnnotations = mutableListOf<CanonicalAnnotation>()
        private val permittedSubclasses = mutableListOf<String>()

        override fun visit(
            version: Int,
            access: Int,
            name: String,
            signature: String?,
            superName: String?,
            interfaces: Array<out String>?,
        ) {
            className = binaryName(name)
            classAccess = access
            classSignature = signature
            this.superName = superName?.let(::binaryName)
            this.interfaces = interfaces.orEmpty().map(::binaryName).sorted()
        }

        override fun visitInnerClass(name: String?, outerName: String?, innerName: String?, access: Int) {
            if (name != null && binaryName(name) == className) innerClassAccess = access
        }

        override fun visitPermittedSubclass(permittedSubclass: String) {
            permittedSubclasses += binaryName(permittedSubclass)
        }

        override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor =
            annotationVisitor(descriptor) { annotation ->
                classAnnotations += annotation
                annotationRecords += annotationRecord(
                    owner = className,
                    target = "CLASS",
                    descriptor = descriptor,
                    visible = visible,
                    annotation = annotation,
                )
            }

        override fun visitTypeAnnotation(
            typeRef: Int,
            typePath: TypePath?,
            descriptor: String,
            visible: Boolean,
        ): AnnotationVisitor = annotationVisitor(descriptor) { annotation ->
            annotationRecords += annotationRecord(
                owner = className,
                target = "CLASS_TYPE:$typeRef:${typePath.orEmpty()}",
                descriptor = descriptor,
                visible = visible,
                annotation = annotation,
            )
        }

        override fun visitField(
            access: Int,
            name: String,
            descriptor: String,
            signature: String?,
            value: Any?,
        ): FieldVisitor? {
            if (!isExternallyVisible(access)) return null
            val attributes = linkedMapOf(
                "access" to accessText(access, AccessTarget.FIELD),
            )
            signature?.let { attributes["signature"] = it }
            value?.let { attributes["constant"] = scalarValue(it).render() }
            memberRecords += JvmApiRecord(
                JvmApiSnapshot.FIELD,
                className,
                name,
                descriptor,
                attributes,
            )
            val target = "FIELD:$name:$descriptor"
            return object : FieldVisitor(Opcodes.ASM9) {
                override fun visitAnnotation(annotationDescriptor: String, visible: Boolean): AnnotationVisitor =
                    annotationVisitor(annotationDescriptor) { annotation ->
                        annotationRecords += annotationRecord(
                            className,
                            target,
                            annotationDescriptor,
                            visible,
                            annotation,
                        )
                    }

                override fun visitTypeAnnotation(
                    typeRef: Int,
                    typePath: TypePath?,
                    annotationDescriptor: String,
                    visible: Boolean,
                ): AnnotationVisitor = annotationVisitor(annotationDescriptor) { annotation ->
                    annotationRecords += annotationRecord(
                        className,
                        "$target:TYPE:$typeRef:${typePath.orEmpty()}",
                        annotationDescriptor,
                        visible,
                        annotation,
                    )
                }
            }
        }

        override fun visitMethod(
            access: Int,
            name: String,
            descriptor: String,
            signature: String?,
            exceptions: Array<out String>?,
        ): MethodVisitor? {
            if (!isExternallyVisible(access)) return null
            val attributes = linkedMapOf(
                "access" to accessText(access, AccessTarget.METHOD),
            )
            signature?.let { attributes["signature"] = it }
            exceptions.orEmpty().map(::binaryName).sorted().takeIf { it.isNotEmpty() }?.let { values ->
                attributes["exceptions"] = values.joinToString(",")
            }
            memberRecords += JvmApiRecord(
                JvmApiSnapshot.METHOD,
                className,
                name,
                descriptor,
                attributes,
            )
            val target = "METHOD:$name:$descriptor"
            return object : MethodVisitor(Opcodes.ASM9) {
                override fun visitAnnotation(annotationDescriptor: String, visible: Boolean): AnnotationVisitor =
                    annotationVisitor(annotationDescriptor) { annotation ->
                        annotationRecords += annotationRecord(
                            className,
                            target,
                            annotationDescriptor,
                            visible,
                            annotation,
                        )
                    }

                override fun visitTypeAnnotation(
                    typeRef: Int,
                    typePath: TypePath?,
                    annotationDescriptor: String,
                    visible: Boolean,
                ): AnnotationVisitor = annotationVisitor(annotationDescriptor) { annotation ->
                    annotationRecords += annotationRecord(
                        className,
                        "$target:TYPE:$typeRef:${typePath.orEmpty()}",
                        annotationDescriptor,
                        visible,
                        annotation,
                    )
                }

                override fun visitParameterAnnotation(
                    parameter: Int,
                    annotationDescriptor: String,
                    visible: Boolean,
                ): AnnotationVisitor = annotationVisitor(annotationDescriptor) { annotation ->
                    annotationRecords += annotationRecord(
                        className,
                        "$target:PARAMETER:$parameter",
                        annotationDescriptor,
                        visible,
                        annotation,
                    )
                }

                override fun visitAnnotationDefault(): AnnotationVisitor = SingleValueVisitor { value ->
                    memberRecords += JvmApiRecord(
                        JvmApiSnapshot.ANNOTATION_DEFAULT,
                        className,
                        name,
                        descriptor,
                        mapOf("value" to value.render()),
                    )
                }
            }
        }

        override fun visitRecordComponent(
            name: String,
            descriptor: String,
            signature: String?,
        ): RecordComponentVisitor {
            val attributes = linkedMapOf<String, String>()
            signature?.let { attributes["signature"] = it }
            memberRecords += JvmApiRecord(
                JvmApiSnapshot.RECORD_COMPONENT,
                className,
                name,
                descriptor,
                attributes,
            )
            val target = "RECORD_COMPONENT:$name:$descriptor"
            return object : RecordComponentVisitor(Opcodes.ASM9) {
                override fun visitAnnotation(annotationDescriptor: String, visible: Boolean): AnnotationVisitor =
                    annotationVisitor(annotationDescriptor) { annotation ->
                        annotationRecords += annotationRecord(
                            className,
                            target,
                            annotationDescriptor,
                            visible,
                            annotation,
                        )
                    }

                override fun visitTypeAnnotation(
                    typeRef: Int,
                    typePath: TypePath?,
                    annotationDescriptor: String,
                    visible: Boolean,
                ): AnnotationVisitor = annotationVisitor(annotationDescriptor) { annotation ->
                    annotationRecords += annotationRecord(
                        className,
                        "$target:TYPE:$typeRef:${typePath.orEmpty()}",
                        annotationDescriptor,
                        visible,
                        annotation,
                    )
                }
            }
        }

        fun records(): List<JvmApiRecord> {
            val effectiveAccess = innerClassAccess ?: classAccess
            if (!isExternallyVisible(effectiveAccess)) return emptyList()
            val classAttributes = linkedMapOf(
                "access" to accessText(effectiveAccess, AccessTarget.CLASS),
                "kind" to classKind(effectiveAccess),
            )
            classSignature?.let { classAttributes["signature"] = it }
            superName?.let { classAttributes["super"] = it }
            if (interfaces.isNotEmpty()) classAttributes["interfaces"] = interfaces.joinToString(",")
            val records = mutableListOf(
                JvmApiRecord(JvmApiSnapshot.CLASS, className, "", "", classAttributes),
            )
            permittedSubclasses.sorted().forEach { permitted ->
                records += JvmApiRecord(
                    JvmApiSnapshot.PERMITTED_SUBCLASS,
                    className,
                    permitted,
                    "",
                )
            }
            classAnnotations.firstOrNull { annotation -> annotation.descriptor == KOTLIN_METADATA_DESCRIPTOR }
                ?.let { metadata ->
                    records += JvmApiRecord(
                        JvmApiSnapshot.KOTLIN_METADATA,
                        className,
                        "",
                        KOTLIN_METADATA_DESCRIPTOR,
                        buildMap {
                            metadata.values["k"]?.let { put("kind", it.render()) }
                            metadata.values["pn"]?.let { put("packageName", it.render()) }
                            metadata.values["xs"]?.let { put("facade", it.render()) }
                            metadata.values["xi"]?.let { put("extraInt", it.render()) }
                        },
                    )
                }
            records += memberRecords
            records += annotationRecords
            return records.distinct().sorted()
        }
    }

    private data class CanonicalAnnotation(
        val descriptor: String,
        val values: Map<String, CanonicalValue>,
    ) {
        fun renderValues(): String = values.toSortedMap().entries.joinToString(",", prefix = "{", postfix = "}") {
            (name, value) -> "${quoted(name)}=${value.render()}"
        }
    }

    private sealed interface CanonicalValue {
        fun render(): String
    }

    private data class ScalarValue(private val type: String, private val value: String) : CanonicalValue {
        override fun render(): String = "$type:${quoted(value)}"
    }

    private data class EnumValue(private val descriptor: String, private val value: String) : CanonicalValue {
        override fun render(): String = "enum(${quoted(descriptor)},${quoted(value)})"
    }

    private data class NestedAnnotationValue(private val annotation: CanonicalAnnotation) : CanonicalValue {
        override fun render(): String = "annotation(${quoted(annotation.descriptor)},${annotation.renderValues()})"
    }

    private data class ArrayValue(private val values: List<CanonicalValue>) : CanonicalValue {
        override fun render(): String = values.joinToString(",", prefix = "[", postfix = "]") { value ->
            value.render()
        }
    }

    private class CanonicalAnnotationVisitor(
        private val descriptor: String,
        private val onComplete: (CanonicalAnnotation) -> Unit,
    ) : AnnotationVisitor(Opcodes.ASM9) {
        private val values = TreeMap<String, CanonicalValue>()

        override fun visit(name: String?, value: Any?) {
            put(name, scalarValue(value))
        }

        override fun visitEnum(name: String?, descriptor: String, value: String) {
            put(name, EnumValue(descriptor, value))
        }

        override fun visitAnnotation(name: String?, descriptor: String): AnnotationVisitor =
            CanonicalAnnotationVisitor(descriptor) { annotation ->
                put(name, NestedAnnotationValue(annotation))
            }

        override fun visitArray(name: String?): AnnotationVisitor = ArrayValueVisitor { value -> put(name, value) }

        override fun visitEnd() {
            onComplete(CanonicalAnnotation(descriptor, values.toMap()))
        }

        private fun put(name: String?, value: CanonicalValue) {
            val key = name.orEmpty()
            require(values.put(key, value) == null) {
                "Annotation $descriptor contains duplicate element '$key'."
            }
        }
    }

    private class ArrayValueVisitor(
        private val onComplete: (ArrayValue) -> Unit,
    ) : AnnotationVisitor(Opcodes.ASM9) {
        private val values = mutableListOf<CanonicalValue>()

        override fun visit(name: String?, value: Any?) {
            values += scalarValue(value)
        }

        override fun visitEnum(name: String?, descriptor: String, value: String) {
            values += EnumValue(descriptor, value)
        }

        override fun visitAnnotation(name: String?, descriptor: String): AnnotationVisitor =
            CanonicalAnnotationVisitor(descriptor) { annotation -> values += NestedAnnotationValue(annotation) }

        override fun visitArray(name: String?): AnnotationVisitor = ArrayValueVisitor { value -> values += value }

        override fun visitEnd() {
            onComplete(ArrayValue(values.toList()))
        }
    }

    private class SingleValueVisitor(
        private val onComplete: (CanonicalValue) -> Unit,
    ) : AnnotationVisitor(Opcodes.ASM9) {
        private var value: CanonicalValue? = null

        override fun visit(name: String?, value: Any?) {
            set(scalarValue(value))
        }

        override fun visitEnum(name: String?, descriptor: String, value: String) {
            set(EnumValue(descriptor, value))
        }

        override fun visitAnnotation(name: String?, descriptor: String): AnnotationVisitor =
            CanonicalAnnotationVisitor(descriptor) { annotation -> set(NestedAnnotationValue(annotation)) }

        override fun visitArray(name: String?): AnnotationVisitor = ArrayValueVisitor(::set)

        override fun visitEnd() {
            onComplete(value ?: ArrayValue(emptyList()))
        }

        private fun set(candidate: CanonicalValue) {
            require(value == null) { "Annotation default contains more than one root value." }
            value = candidate
        }
    }

    private fun annotationVisitor(
        descriptor: String,
        onComplete: (CanonicalAnnotation) -> Unit,
    ): AnnotationVisitor = CanonicalAnnotationVisitor(descriptor, onComplete)

    private fun annotationRecord(
        owner: String,
        target: String,
        descriptor: String,
        visible: Boolean,
        annotation: CanonicalAnnotation,
    ): JvmApiRecord = JvmApiRecord(
        JvmApiSnapshot.ANNOTATION,
        owner,
        target,
        descriptor,
        mapOf(
            "visible" to visible.toString(),
            "values" to annotation.renderValues(),
        ),
    )

    private fun scalarValue(value: Any?): CanonicalValue {
        if (value == null) return ScalarValue("null", "")
        if (value.javaClass.isArray) {
            val length = ReflectArray.getLength(value)
            return ArrayValue((0 until length).map { index -> scalarValue(ReflectArray.get(value, index)) })
        }
        return when (value) {
            is Type -> ScalarValue("type", value.descriptor)
            is String -> ScalarValue("string", value)
            is Char -> ScalarValue("char", value.code.toString())
            is Boolean -> ScalarValue("boolean", value.toString())
            is Byte -> ScalarValue("byte", value.toString())
            is Short -> ScalarValue("short", value.toString())
            is Int -> ScalarValue("int", value.toString())
            is Long -> ScalarValue("long", value.toString())
            is Float -> ScalarValue("floatBits", value.toRawBits().toUInt().toString(16))
            is Double -> ScalarValue("doubleBits", value.toRawBits().toULong().toString(16))
            else -> throw IllegalArgumentException("Unsupported ASM annotation/constant value ${value.javaClass.name}.")
        }
    }

    private fun quoted(value: String): String = buildString(value.length + 2) {
        append('"')
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u").append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }

    private fun accessText(access: Int, target: AccessTarget): String = buildList {
        target.flags.forEach { (mask, label) -> if (access and mask != 0) add(label) }
    }.joinToString(",")

    private fun classKind(access: Int): String = when {
        access and Opcodes.ACC_ANNOTATION != 0 -> "annotation"
        access and Opcodes.ACC_ENUM != 0 -> "enum"
        access and Opcodes.ACC_INTERFACE != 0 -> "interface"
        access and Opcodes.ACC_RECORD != 0 -> "record"
        else -> "class"
    }

    private fun isExternallyVisible(access: Int): Boolean =
        access and (Opcodes.ACC_PUBLIC or Opcodes.ACC_PROTECTED) != 0

    private fun binaryName(internalName: String): String = internalName.replace('/', '.')

    private fun TypePath?.orEmpty(): String = this?.toString().orEmpty()

    private enum class AccessTarget(
        val flags: List<Pair<Int, String>>,
    ) {
        CLASS(
            listOf(
                Opcodes.ACC_PUBLIC to "public",
                Opcodes.ACC_PROTECTED to "protected",
                Opcodes.ACC_PRIVATE to "private",
                Opcodes.ACC_STATIC to "static",
                Opcodes.ACC_FINAL to "final",
                Opcodes.ACC_SUPER to "super",
                Opcodes.ACC_INTERFACE to "interface",
                Opcodes.ACC_ABSTRACT to "abstract",
                Opcodes.ACC_SYNTHETIC to "synthetic",
                Opcodes.ACC_ANNOTATION to "annotation",
                Opcodes.ACC_ENUM to "enum",
                Opcodes.ACC_MODULE to "module",
                Opcodes.ACC_RECORD to "record",
            ),
        ),
        FIELD(
            listOf(
                Opcodes.ACC_PUBLIC to "public",
                Opcodes.ACC_PROTECTED to "protected",
                Opcodes.ACC_PRIVATE to "private",
                Opcodes.ACC_STATIC to "static",
                Opcodes.ACC_FINAL to "final",
                Opcodes.ACC_VOLATILE to "volatile",
                Opcodes.ACC_TRANSIENT to "transient",
                Opcodes.ACC_SYNTHETIC to "synthetic",
                Opcodes.ACC_ENUM to "enum",
            ),
        ),
        METHOD(
            listOf(
                Opcodes.ACC_PUBLIC to "public",
                Opcodes.ACC_PROTECTED to "protected",
                Opcodes.ACC_PRIVATE to "private",
                Opcodes.ACC_STATIC to "static",
                Opcodes.ACC_FINAL to "final",
                Opcodes.ACC_SYNCHRONIZED to "synchronized",
                Opcodes.ACC_BRIDGE to "bridge",
                Opcodes.ACC_VARARGS to "varargs",
                Opcodes.ACC_NATIVE to "native",
                Opcodes.ACC_ABSTRACT to "abstract",
                Opcodes.ACC_STRICT to "strict",
                Opcodes.ACC_SYNTHETIC to "synthetic",
            ),
        ),
    }

    private const val KOTLIN_METADATA_DESCRIPTOR = "Lkotlin/Metadata;"
}
