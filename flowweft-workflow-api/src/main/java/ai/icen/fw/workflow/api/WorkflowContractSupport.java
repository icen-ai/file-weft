package ai.icen.fw.workflow.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** JVM package-private support. It is deliberately absent from the published API surface. */
final class WorkflowContractSupport {
    static final int MAX_TYPE_UTF8_BYTES = 64;
    static final int MAX_REFERENCE_ID_UTF8_BYTES = 512;
    static final int MAX_SUBJECT_REVISION_UTF8_BYTES = 256;
    static final int MAX_DEFINITION_KEY_UTF8_BYTES = 256;
    static final int MAX_DEFINITION_VERSION_UTF8_BYTES = 128;
    static final int MAX_MACHINE_CODE_UTF8_BYTES = 128;
    static final int MAX_TITLE_UTF8_BYTES = 256;
    static final int MAX_DESCRIPTION_UTF8_BYTES = 8192;
    static final int MAX_ORGANIZATION_REVISION_UTF8_BYTES = 256;
    static final int MAX_SELECTORS = 32;
    static final int MAX_TIERS = 128;
    static final int MAX_PRINCIPALS = 256;
    static final int MAX_HUMAN_TASK_RULES = 32;
    static final int MAX_RESOLUTION_STAGES = 8;
    static final int MAX_PREDICATE_INPUTS = 32;
    static final int MAX_DEFINITION_NODES = 256;
    static final int MAX_DEFINITION_TRANSITIONS = 1024;
    static final int MAX_SCHEMA_VERSION = 65_535;
    static final int MAX_MANAGER_LEVEL = 16;
    static final int MAX_DELEGATION_HOPS = 4;
    static final long MAX_RESOLUTION_WINDOW_MILLIS = 300_000L;
    static final int SHA_256_HEX_LENGTH = 64;

    static final String SELECTOR_DIGEST_DOMAIN = "flowweft-workflow-participant-selector-v1";
    static final String REQUEST_DIGEST_DOMAIN = "flowweft-workflow-participant-resolution-request-v1";
    static final String TIER_DIGEST_DOMAIN = "flowweft-workflow-participant-tier-v1";
    static final String RESOLUTION_DIGEST_DOMAIN = "flowweft-workflow-participant-resolution-v1";
    static final String APPROVAL_POLICY_DIGEST_DOMAIN = "flowweft-workflow-approval-policy-v1";
    static final String HUMAN_TASK_CAPABILITIES_DIGEST_DOMAIN =
        "flowweft-workflow-human-task-capabilities-v1";
    static final String SEPARATION_OF_DUTIES_DIGEST_DOMAIN =
        "flowweft-workflow-separation-of-duties-v1";
    static final String HUMAN_TASK_RULE_DIGEST_DOMAIN = "flowweft-workflow-human-task-rule-v1";
    static final String HUMAN_TASK_POLICY_DIGEST_DOMAIN = "flowweft-workflow-human-task-policy-v1";
    static final String PREDICATE_INPUT_DIGEST_DOMAIN = "flowweft-workflow-predicate-input-v1";
    static final String PREDICATE_REF_DIGEST_DOMAIN = "flowweft-workflow-predicate-ref-v1";
    static final String NODE_DIGEST_DOMAIN = "flowweft-workflow-node-v1";
    static final String TRANSITION_DIGEST_DOMAIN = "flowweft-workflow-transition-v1";
    static final String DEFINITION_CONTENT_DIGEST_DOMAIN = "flowweft-workflow-definition-content-v1";
    static final String NODE_DESCRIPTOR_EMPTY_DIGEST_DOMAIN =
        "flowweft-workflow-node-descriptor-empty-v1";
    static final String NODE_PAYLOAD_EMPTY_DIGEST_DOMAIN =
        "flowweft-workflow-node-payload-empty-v1";

    static final String EMPTY_NODE_DESCRIPTOR_DIGEST =
        digest(NODE_DESCRIPTOR_EMPTY_DIGEST_DOMAIN).text("EMPTY").finish();
    static final String EMPTY_NODE_PAYLOAD_DIGEST =
        digest(NODE_PAYLOAD_EMPTY_DIGEST_DOMAIN).text("EMPTY").finish();

    private WorkflowContractSupport() {
    }

    /**
     * Validates the fixed FlowWeft identifier Unicode profile.
     *
     * This intentionally does not call Character category or whitespace methods. Their Unicode
     * database changes between supported JDKs. The explicit ranges below therefore remain stable
     * from JDK 8 through newer runtimes. Accepted text is not trimmed, case-folded or normalized.
     */
    static String requireText(String value, int maximumUtf8Bytes, String message) {
        return requireUnicodeText(value, maximumUtf8Bytes, false, message);
    }

    /**
     * Validates bounded human-readable content while permitting internal TAB, LF and CR.
     * Boundary whitespace, all other controls, format characters and noncharacters remain invalid.
     */
    static String requireMultilineText(String value, int maximumUtf8Bytes, String message) {
        return requireUnicodeText(value, maximumUtf8Bytes, true, message);
    }

    private static String requireUnicodeText(
        String value,
        int maximumUtf8Bytes,
        boolean allowInternalLineWhitespace,
        String message
    ) {
        Objects.requireNonNull(value, message);
        require(maximumUtf8Bytes > 0, "Maximum UTF-8 byte count must be positive.");
        require(!value.isEmpty(), message);

        int utf8Bytes = 0;
        int offset = 0;
        int firstCodePoint = -1;
        int lastCodePoint = -1;
        while (offset < value.length()) {
            char first = value.charAt(offset);
            final int codePoint;
            if (first >= 0xD800 && first <= 0xDBFF) {
                require(offset + 1 < value.length(), message);
                char second = value.charAt(offset + 1);
                require(second >= 0xDC00 && second <= 0xDFFF, message);
                codePoint = 0x10000 + ((first - 0xD800) << 10) + (second - 0xDC00);
                offset += 2;
            } else {
                require(first < 0xDC00 || first > 0xDFFF, message);
                codePoint = first;
                offset += 1;
            }

            boolean permittedLineWhitespace = allowInternalLineWhitespace &&
                (codePoint == 0x0009 || codePoint == 0x000A || codePoint == 0x000D);
            require(permittedLineWhitespace || !isRejectedControlOrFormat(codePoint), message);
            require(!isUnicodeNoncharacter(codePoint), message);
            if (firstCodePoint < 0) {
                firstCodePoint = codePoint;
            }
            lastCodePoint = codePoint;

            utf8Bytes += utf8Width(codePoint);
            require(utf8Bytes <= maximumUtf8Bytes, message);
        }

        require(!isBoundaryWhitespace(firstCodePoint) && !isBoundaryWhitespace(lastCodePoint), message);
        return value;
    }

    static String requireMachineCode(String value, String message) {
        requireText(value, MAX_MACHINE_CODE_UTF8_BYTES, message);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            boolean valid = (character >= 'A' && character <= 'Z') ||
                (character >= 'a' && character <= 'z') ||
                (character >= '0' && character <= '9') ||
                character == '.' || character == '_' || character == ':' ||
                character == '/' || character == '-';
            require(valid, message);
        }
        char first = value.charAt(0);
        require((first >= 'A' && first <= 'Z') || (first >= 'a' && first <= 'z') ||
            (first >= '0' && first <= '9'), message);
        return value;
    }

    static String requireCanonicalSha256(String value, String message) {
        Objects.requireNonNull(value, message);
        require(value.length() == SHA_256_HEX_LENGTH, message);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            require((character >= '0' && character <= '9') ||
                (character >= 'a' && character <= 'f'), message);
        }
        return value;
    }

    static <T> List<T> immutableList(Collection<? extends T> values, int maximumSize, String message) {
        Objects.requireNonNull(values, message);
        require(maximumSize >= 0, "Maximum collection size must not be negative.");
        require(values.size() <= maximumSize, message);
        ArrayList<T> copy = new ArrayList<T>(values.size());
        for (T value : values) {
            require(value != null, message);
            copy.add(value);
            require(copy.size() <= maximumSize, message);
        }
        return Collections.unmodifiableList(copy);
    }

    static DigestWriter digest(String domain) {
        return new DigestWriter(domain);
    }

    private static int utf8Width(int codePoint) {
        if (codePoint <= 0x7F) {
            return 1;
        }
        if (codePoint <= 0x7FF) {
            return 2;
        }
        if (codePoint <= 0xFFFF) {
            return 3;
        }
        return 4;
    }

    private static boolean isBoundaryWhitespace(int codePoint) {
        return (codePoint >= 0x0009 && codePoint <= 0x000D) ||
            codePoint == 0x0020 || codePoint == 0x0085 || codePoint == 0x00A0 ||
            codePoint == 0x1680 || (codePoint >= 0x2000 && codePoint <= 0x200A) ||
            codePoint == 0x2028 || codePoint == 0x2029 || codePoint == 0x202F ||
            codePoint == 0x205F || codePoint == 0x3000;
    }

    private static boolean isRejectedControlOrFormat(int codePoint) {
        if ((codePoint >= 0x0000 && codePoint <= 0x001F) ||
            (codePoint >= 0x007F && codePoint <= 0x009F)) {
            return true;
        }
        return codePoint == 0x00AD ||
            (codePoint >= 0x0600 && codePoint <= 0x0605) || codePoint == 0x061C ||
            codePoint == 0x06DD || codePoint == 0x070F ||
            (codePoint >= 0x0890 && codePoint <= 0x0891) || codePoint == 0x08E2 ||
            codePoint == 0x180E || (codePoint >= 0x200B && codePoint <= 0x200F) ||
            codePoint == 0x2028 || codePoint == 0x2029 ||
            (codePoint >= 0x202A && codePoint <= 0x202E) ||
            (codePoint >= 0x2060 && codePoint <= 0x206F) || codePoint == 0xFEFF ||
            (codePoint >= 0xFFF9 && codePoint <= 0xFFFB) || codePoint == 0x110BD ||
            codePoint == 0x110CD || (codePoint >= 0x13430 && codePoint <= 0x1345F) ||
            (codePoint >= 0x1BCA0 && codePoint <= 0x1BCAF) ||
            (codePoint >= 0x1D173 && codePoint <= 0x1D17A) ||
            (codePoint >= 0xE0000 && codePoint <= 0xE007F);
    }

    private static boolean isUnicodeNoncharacter(int codePoint) {
        return (codePoint >= 0xFDD0 && codePoint <= 0xFDEF) ||
            (codePoint & 0xFFFE) == 0xFFFE;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    static final class DigestWriter {
        private final MessageDigest digest;
        private final byte[] numericBuffer = new byte[8];
        private boolean finished;

        private DigestWriter(String domain) {
            requireMachineCode(domain, "Workflow digest domain is invalid.");
            require(domain.startsWith("flowweft-workflow-"), "Workflow digest domain is invalid.");
            this.digest = newSha256();
            text(domain);
        }

        DigestWriter text(String value) {
            require(!finished, "Workflow digest writer has already been finalized.");
            Objects.requireNonNull(value, "Workflow digest text must not be null.");
            requireWellFormedDigestText(value);
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            taggedByte(1);
            rawInteger(bytes.length);
            digest.update(bytes);
            return this;
        }

        DigestWriter optionalText(String value) {
            taggedByte(2);
            booleanValue(value != null);
            if (value != null) {
                text(value);
            }
            return this;
        }

        DigestWriter integer(int value) {
            taggedByte(3);
            rawInteger(value);
            return this;
        }

        DigestWriter longValue(long value) {
            taggedByte(4);
            for (int index = 0; index < 8; index++) {
                numericBuffer[index] = (byte) (value >>> (56 - index * 8));
            }
            digest.update(numericBuffer, 0, 8);
            return this;
        }

        DigestWriter booleanValue(boolean value) {
            taggedByte(5);
            taggedByte(value ? 1 : 0);
            return this;
        }

        String finish() {
            require(!finished, "Workflow digest writer has already been finalized.");
            finished = true;
            return toLowerHex(digest.digest());
        }

        private void rawInteger(int value) {
            numericBuffer[0] = (byte) (value >>> 24);
            numericBuffer[1] = (byte) (value >>> 16);
            numericBuffer[2] = (byte) (value >>> 8);
            numericBuffer[3] = (byte) value;
            digest.update(numericBuffer, 0, 4);
        }

        private void taggedByte(int value) {
            require(!finished, "Workflow digest writer has already been finalized.");
            numericBuffer[0] = (byte) value;
            digest.update(numericBuffer, 0, 1);
        }
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("The JVM does not provide SHA-256.", impossible);
        }
    }

    private static void requireWellFormedDigestText(String value) {
        int offset = 0;
        while (offset < value.length()) {
            char first = value.charAt(offset);
            final int codePoint;
            if (first >= 0xD800 && first <= 0xDBFF) {
                require(offset + 1 < value.length(), "Workflow digest text contains invalid Unicode.");
                char second = value.charAt(offset + 1);
                require(second >= 0xDC00 && second <= 0xDFFF,
                    "Workflow digest text contains invalid Unicode.");
                codePoint = 0x10000 + ((first - 0xD800) << 10) + (second - 0xDC00);
                offset += 2;
            } else {
                require(first < 0xDC00 || first > 0xDFFF,
                    "Workflow digest text contains invalid Unicode.");
                codePoint = first;
                offset += 1;
            }
            require(!isUnicodeNoncharacter(codePoint), "Workflow digest text contains invalid Unicode.");
        }
    }

    private static String toLowerHex(byte[] bytes) {
        char[] alphabet = "0123456789abcdef".toCharArray();
        char[] result = new char[bytes.length * 2];
        for (int index = 0; index < bytes.length; index++) {
            int value = bytes[index] & 0xFF;
            result[index * 2] = alphabet[value >>> 4];
            result[index * 2 + 1] = alphabet[value & 0x0F];
        }
        return new String(result);
    }
}
