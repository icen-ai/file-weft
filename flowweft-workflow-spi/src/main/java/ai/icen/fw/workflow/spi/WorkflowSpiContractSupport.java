package ai.icen.fw.workflow.spi;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Package-private, JDK-stable validation and digest support for Workflow SPI values. */
final class WorkflowSpiContractSupport {
    static final int MAX_CODE_UTF8_BYTES = 128;
    static final int MAX_ID_UTF8_BYTES = 512;
    static final int MAX_REVISION_UTF8_BYTES = 256;
    static final int MAX_TEXT_UTF8_BYTES = 2048;
    static final int MAX_ITEMS = 256;
    static final int MAX_ISSUES = 128;
    static final int MAX_PAYLOAD_BYTES = 4 * 1024 * 1024;
    static final long MAX_CALL_WINDOW_MILLIS = 300_000L;
    static final int SHA_256_HEX_LENGTH = 64;

    private WorkflowSpiContractSupport() {
    }

    static String requireText(String value, int maximumUtf8Bytes, String message) {
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
            require(!isRejectedControlOrFormat(codePoint) && !isUnicodeNoncharacter(codePoint), message);
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
        requireText(value, MAX_CODE_UTF8_BYTES, message);
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

    static String requireOpaqueReference(String value, String message) {
        requireText(value, MAX_ID_UTF8_BYTES, message);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            boolean alphaNumeric = (character >= 'A' && character <= 'Z') ||
                (character >= 'a' && character <= 'z') ||
                (character >= '0' && character <= '9');
            boolean valid = alphaNumeric || (index > 0 &&
                (character == '.' || character == '_' || character == '~' || character == '-'));
            require(valid, message);
        }
        require(!value.contains(".."), message);
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

    static byte[] immutableBytes(byte[] value, int maximumBytes, String message) {
        Objects.requireNonNull(value, message);
        require(value.length > 0 && value.length <= maximumBytes, message);
        return value.clone();
    }

    static <T> List<T> immutableList(Collection<? extends T> values, int maximumSize, String message) {
        Objects.requireNonNull(values, message);
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

    static String failureDigest(String domain, WorkflowProviderFailure failure) {
        return digest(domain).text(failure.getCode()).booleanValue(failure.getRetryable()).finish();
    }

    private static int utf8Width(int codePoint) {
        if (codePoint <= 0x7F) return 1;
        if (codePoint <= 0x7FF) return 2;
        if (codePoint <= 0xFFFF) return 3;
        return 4;
    }

    private static boolean isBoundaryWhitespace(int codePoint) {
        return (codePoint >= 0x0009 && codePoint <= 0x000D) || codePoint == 0x0020 ||
            codePoint == 0x0085 || codePoint == 0x00A0 || codePoint == 0x1680 ||
            (codePoint >= 0x2000 && codePoint <= 0x200A) || codePoint == 0x2028 ||
            codePoint == 0x2029 || codePoint == 0x202F || codePoint == 0x205F ||
            codePoint == 0x3000;
    }

    private static boolean isRejectedControlOrFormat(int codePoint) {
        if ((codePoint >= 0x0000 && codePoint <= 0x001F) ||
            (codePoint >= 0x007F && codePoint <= 0x009F)) return true;
        return codePoint == 0x00AD || (codePoint >= 0x0600 && codePoint <= 0x0605) ||
            codePoint == 0x061C || codePoint == 0x06DD || codePoint == 0x070F ||
            (codePoint >= 0x0890 && codePoint <= 0x0891) || codePoint == 0x08E2 ||
            codePoint == 0x180E || (codePoint >= 0x200B && codePoint <= 0x200F) ||
            (codePoint >= 0x202A && codePoint <= 0x202E) ||
            (codePoint >= 0x2060 && codePoint <= 0x206F) || codePoint == 0xFEFF ||
            (codePoint >= 0xFFF9 && codePoint <= 0xFFFB) || codePoint == 0x110BD ||
            codePoint == 0x110CD || (codePoint >= 0x13430 && codePoint <= 0x1345F) ||
            (codePoint >= 0x1BCA0 && codePoint <= 0x1BCAF) ||
            (codePoint >= 0x1D173 && codePoint <= 0x1D17A) ||
            (codePoint >= 0xE0000 && codePoint <= 0xE007F);
    }

    private static boolean isUnicodeNoncharacter(int codePoint) {
        return (codePoint >= 0xFDD0 && codePoint <= 0xFDEF) || (codePoint & 0xFFFE) == 0xFFFE;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    static final class DigestWriter {
        private final MessageDigest digest;
        private final byte[] numericBuffer = new byte[8];
        private boolean finished;

        private DigestWriter(String domain) {
            requireMachineCode(domain, "Workflow SPI digest domain is invalid.");
            require(domain.startsWith("flowweft-workflow-spi-"), "Workflow SPI digest domain is invalid.");
            this.digest = newSha256();
            text(domain);
        }

        DigestWriter text(String value) {
            require(!finished, "Workflow SPI digest writer has already been finalized.");
            Objects.requireNonNull(value, "Workflow SPI digest text must not be null.");
            byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            taggedByte(1);
            rawInteger(bytes.length);
            digest.update(bytes);
            return this;
        }

        DigestWriter optionalText(String value) {
            taggedByte(2);
            booleanValue(value != null);
            if (value != null) text(value);
            return this;
        }

        DigestWriter bytes(byte[] value) {
            require(!finished, "Workflow SPI digest writer has already been finalized.");
            Objects.requireNonNull(value, "Workflow SPI digest bytes must not be null.");
            taggedByte(3);
            rawInteger(value.length);
            digest.update(value);
            return this;
        }

        DigestWriter integer(int value) {
            taggedByte(4);
            rawInteger(value);
            return this;
        }

        DigestWriter longValue(long value) {
            taggedByte(5);
            for (int index = 0; index < 8; index++) {
                numericBuffer[index] = (byte) (value >>> (56 - index * 8));
            }
            digest.update(numericBuffer, 0, 8);
            return this;
        }

        DigestWriter booleanValue(boolean value) {
            taggedByte(6);
            taggedByte(value ? 1 : 0);
            return this;
        }

        String finish() {
            require(!finished, "Workflow SPI digest writer has already been finalized.");
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
            require(!finished, "Workflow SPI digest writer has already been finalized.");
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
