package ai.icen.fw.persistence.jdbc

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.core.result.DoctorCheckResult
import ai.icen.fw.core.result.DoctorReport
import ai.icen.fw.core.result.DoctorStatus

/**
 * Stable persistence codec owned by FlowWeft rather than the host's Jackson
 * naming or Identifier serializers. Explicit tree fields also keep reports
 * readable when a host changes its ObjectMapper configuration after upgrade.
 */
internal class DoctorReportJsonCodec(
    @Suppress("UNUSED_PARAMETER") hostObjectMapper: ObjectMapper,
) {
    private val objectMapper: ObjectMapper = FILEWEFT_OBJECT_MAPPER

    fun serialize(report: DoctorReport): String {
        validateIdentifier(report.tenantId.value, "tenantId")
        val documentId = requireNotNull(report.documentId) {
            "A persisted Doctor task report must be document scoped."
        }
        validateIdentifier(documentId.value, "documentId")
        check(report.checks.size <= MAX_CHECKS) { "Doctor report exceeds the persisted check boundary." }

        val root = objectMapper.createObjectNode()
        root.put(SCHEMA_VERSION_FIELD, SCHEMA_VERSION)
        root.put(TENANT_ID_FIELD, report.tenantId.value)
        root.put(DOCUMENT_ID_FIELD, documentId.value)
        root.put(INSPECTED_AT_FIELD, report.inspectedAt)
        root.put(REPORT_STATUS_FIELD, report.status.name)
        val checks = root.putArray(CHECKS_FIELD)
        report.checks.forEach { check ->
            validateCheck(check)
            checks.addObject().also { node ->
                node.put(CHECKER_NAME_FIELD, check.checkerName)
                node.put(CHECK_STATUS_FIELD, check.status.name)
                node.put(REASON_FIELD, check.reason)
                check.repairSuggestion?.let { node.put(REPAIR_FIELD, it) }
                val evidence = node.putObject(EVIDENCE_FIELD)
                check.evidence.forEach { (key, value) -> evidence.put(key, value) }
            }
        }
        return objectMapper.writeValueAsString(root).also { raw ->
            check(raw.length <= MAX_REPORT_JSON_LENGTH) { "Doctor report exceeds the persisted JSON boundary." }
        }
    }

    fun deserialize(
        rawJson: String,
        expectedTenantId: Identifier,
        expectedDocumentId: Identifier,
        storedStatus: String?,
    ): DoctorReport {
        check(rawJson.length <= MAX_REPORT_JSON_LENGTH) { "Stored Doctor report exceeds the read boundary." }
        val root = try {
            objectMapper.readTree(rawJson)
        } catch (failure: Exception) {
            throw IllegalStateException("Stored Doctor report is not valid JSON.", failure)
        }
        check(root.isObject) { "Stored Doctor report must be a JSON object." }
        root.optionalField(SCHEMA_VERSION_FIELD, LEGACY_SCHEMA_VERSION_FIELD)?.let { version ->
            check(
                version.isIntegralNumber &&
                    version.canConvertToInt() &&
                    version.intValue() == SCHEMA_VERSION,
            ) {
                "Stored Doctor report uses an unsupported schema version."
            }
        }
        val tenantId = Identifier(root.requiredIdentifier(TENANT_ID_FIELD, LEGACY_TENANT_ID_FIELD))
        val documentId = Identifier(root.requiredIdentifier(DOCUMENT_ID_FIELD, LEGACY_DOCUMENT_ID_FIELD))
        check(tenantId == expectedTenantId && documentId == expectedDocumentId) {
            "Stored Doctor report does not belong to the requested tenant and document."
        }
        val checksNode = root.requiredField(CHECKS_FIELD)
        check(checksNode.isArray && checksNode.size() <= MAX_CHECKS) {
            "Stored Doctor report has an invalid check collection."
        }
        val checks = checksNode.map(::parseCheck)
        val inspectedAtNode = root.requiredField(INSPECTED_AT_FIELD, LEGACY_INSPECTED_AT_FIELD)
        check(inspectedAtNode.isIntegralNumber && inspectedAtNode.canConvertToLong()) {
            "Stored Doctor report has an invalid inspection time."
        }
        val report = DoctorReport(tenantId, documentId, checks, inspectedAtNode.longValue())
        check(storedStatus == null || report.status.name == storedStatus) {
            "Stored Doctor report status does not match its record."
        }
        return report
    }

    private fun parseCheck(node: JsonNode): DoctorCheckResult {
        check(node.isObject) { "Stored Doctor check must be a JSON object." }
        val checkerName = node.requiredText(CHECKER_NAME_FIELD, MAX_CHECKER_NAME_LENGTH, LEGACY_CHECKER_NAME_FIELD)
        val reason = node.requiredText(REASON_FIELD, MAX_REASON_LENGTH)
        val status = try {
            DoctorStatus.valueOf(node.requiredText(CHECK_STATUS_FIELD, MAX_STATUS_LENGTH))
        } catch (failure: IllegalArgumentException) {
            throw IllegalStateException("Stored Doctor check has an unknown status.", failure)
        }
        val evidenceNode = node.optionalField(EVIDENCE_FIELD)
        val evidence = LinkedHashMap<String, String>()
        if (evidenceNode != null && !evidenceNode.isNull) {
            check(evidenceNode.isObject && evidenceNode.size() <= MAX_EVIDENCE_ENTRIES) {
                "Stored Doctor check has invalid evidence."
            }
            evidenceNode.fields().forEach { (key, value) ->
                check(key.isNotBlank() && key.length <= MAX_EVIDENCE_KEY_LENGTH && value.isTextual) {
                    "Stored Doctor evidence has an invalid entry."
                }
                val text = value.textValue()
                check(text.length <= MAX_EVIDENCE_VALUE_LENGTH) { "Stored Doctor evidence value is too large." }
                evidence[key] = text
            }
        }
        val repair = node.optionalField(REPAIR_FIELD, LEGACY_REPAIR_FIELD)
            ?.takeUnless(JsonNode::isNull)
            ?.let { value ->
                check(value.isTextual && value.textValue().isNotBlank() && value.textValue().length <= MAX_REPAIR_LENGTH) {
                    "Stored Doctor repair suggestion is invalid."
                }
                value.textValue()
            }
        return DoctorCheckResult(checkerName, status, reason, evidence, repair)
    }

    private fun validateCheck(result: DoctorCheckResult) {
        validateText(result.checkerName, "checkerName", MAX_CHECKER_NAME_LENGTH)
        validateText(result.reason, "reason", MAX_REASON_LENGTH)
        result.repairSuggestion?.let { validateText(it, "repairSuggestion", MAX_REPAIR_LENGTH) }
        check(result.evidence.size <= MAX_EVIDENCE_ENTRIES) {
            "Doctor check evidence exceeds the persisted boundary."
        }
        result.evidence.forEach { (key, value) ->
            validateText(key, "evidence key", MAX_EVIDENCE_KEY_LENGTH)
            check(value.length <= MAX_EVIDENCE_VALUE_LENGTH) { "Doctor evidence value exceeds the persisted boundary." }
        }
    }

    private fun JsonNode.requiredIdentifier(vararg fields: String): String {
        val node = requiredField(*fields)
        val value = when {
            node.isTextual -> node.textValue()
            node.isObject && node.get(IDENTIFIER_VALUE_FIELD)?.isTextual == true ->
                node.get(IDENTIFIER_VALUE_FIELD).textValue()
            else -> throw IllegalStateException("Stored Doctor report has an invalid ${fields.first()}.")
        }
        validateIdentifier(value, fields.first())
        return value
    }

    private fun JsonNode.requiredText(field: String, maximumLength: Int, vararg aliases: String): String {
        val node = requiredField(field, *aliases)
        check(node.isTextual) { "Stored Doctor check has an invalid $field." }
        return node.textValue().also { value -> validateText(value, field, maximumLength) }
    }

    private fun JsonNode.requiredField(vararg fields: String): JsonNode =
        optionalField(*fields) ?: throw IllegalStateException("Stored Doctor report is missing ${fields.first()}.")

    private fun JsonNode.optionalField(vararg fields: String): JsonNode? =
        fields.asSequence().mapNotNull { field -> get(field) }.firstOrNull()

    private fun validateIdentifier(value: String, field: String) {
        validateText(value, field, MAX_IDENTIFIER_LENGTH)
    }

    private fun validateText(value: String, field: String, maximumLength: Int) {
        check(value.isNotBlank() && value.length <= maximumLength) {
            "Doctor report field $field is invalid."
        }
    }

    private companion object {
        /**
         * Persistence JSON is a FlowWeft wire format. Never reuse the host
         * mapper here: host naming, polymorphic typing, pretty printing, and
         * number-as-string features must not alter stored records.
         */
        val FILEWEFT_OBJECT_MAPPER: ObjectMapper = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.LOWER_CAMEL_CASE)
            .deactivateDefaultTyping()
            .disable(JsonGenerator.Feature.WRITE_NUMBERS_AS_STRINGS)
            .disable(SerializationFeature.INDENT_OUTPUT)
            .disable(SerializationFeature.WRAP_ROOT_VALUE)
            .disable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .disable(DeserializationFeature.UNWRAP_ROOT_VALUE)
            .disable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .disable(DeserializationFeature.USE_LONG_FOR_INTS)
            .build()

        const val SCHEMA_VERSION = 1
        const val SCHEMA_VERSION_FIELD = "schemaVersion"
        const val LEGACY_SCHEMA_VERSION_FIELD = "schema_version"
        const val TENANT_ID_FIELD = "tenantId"
        const val LEGACY_TENANT_ID_FIELD = "tenant_id"
        const val DOCUMENT_ID_FIELD = "documentId"
        const val LEGACY_DOCUMENT_ID_FIELD = "document_id"
        const val INSPECTED_AT_FIELD = "inspectedAt"
        const val LEGACY_INSPECTED_AT_FIELD = "inspected_at"
        const val REPORT_STATUS_FIELD = "status"
        const val CHECKS_FIELD = "checks"
        const val CHECKER_NAME_FIELD = "checkerName"
        const val LEGACY_CHECKER_NAME_FIELD = "checker_name"
        const val CHECK_STATUS_FIELD = "status"
        const val REASON_FIELD = "reason"
        const val REPAIR_FIELD = "repairSuggestion"
        const val LEGACY_REPAIR_FIELD = "repair_suggestion"
        const val EVIDENCE_FIELD = "evidence"
        const val IDENTIFIER_VALUE_FIELD = "value"

        const val MAX_REPORT_JSON_LENGTH = 1_048_576
        const val MAX_IDENTIFIER_LENGTH = 256
        const val MAX_CHECKS = 64
        const val MAX_CHECKER_NAME_LENGTH = 128
        const val MAX_STATUS_LENGTH = 32
        const val MAX_REASON_LENGTH = 2_048
        const val MAX_REPAIR_LENGTH = 4_096
        const val MAX_EVIDENCE_ENTRIES = 32
        const val MAX_EVIDENCE_KEY_LENGTH = 128
        const val MAX_EVIDENCE_VALUE_LENGTH = 1_024
    }
}
