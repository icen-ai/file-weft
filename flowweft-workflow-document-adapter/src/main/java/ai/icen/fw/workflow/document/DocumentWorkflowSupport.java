package ai.icen.fw.workflow.document;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/** Package-private, JDK 8-stable validation and canonical digest support. */
final class DocumentWorkflowSupport {
    static final int MAX_CODE_BYTES = 128;
    static final int MAX_ID_BYTES = 512;
    static final int MAX_REVISION_BYTES = 256;
    static final int SHA_256_HEX_LENGTH = 64;

    private DocumentWorkflowSupport() {
    }

    static String text(String value, int maximumBytes, String message) {
        Objects.requireNonNull(value, message);
        require(!value.isEmpty() && maximumBytes > 0, message);
        int bytes = 0;
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
            if (firstCodePoint < 0) firstCodePoint = codePoint;
            lastCodePoint = codePoint;
            bytes += utf8Width(codePoint);
            require(bytes <= maximumBytes, message);
        }
        require(!isBoundaryWhitespace(firstCodePoint) && !isBoundaryWhitespace(lastCodePoint), message);
        return value;
    }

    static String code(String value, String message) {
        text(value, MAX_CODE_BYTES, message);
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

    static String sha256(String value, String message) {
        Objects.requireNonNull(value, message);
        require(value.length() == SHA_256_HEX_LENGTH, message);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            require((character >= '0' && character <= '9') ||
                (character >= 'a' && character <= 'f'), message);
        }
        return value;
    }

    static long nonNegative(long value, String message) {
        require(value >= 0L, message);
        return value;
    }

    static Digest digest(String domain) {
        return new Digest(domain);
    }

    private static int utf8Width(int codePoint) {
        if (codePoint <= 0x7F) return 1;
        if (codePoint <= 0x7FF) return 2;
        if (codePoint <= 0xFFFF) return 3;
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
            (codePoint >= 0x007F && codePoint <= 0x009F)) return true;
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
        if (!condition) throw new IllegalArgumentException(message);
    }

    static final class Digest {
        private final MessageDigest delegate;
        private final byte[] number = new byte[8];
        private boolean finished;

        Digest(String domain) {
            code(domain, "Document workflow digest domain is invalid.");
            require(domain.startsWith("flowweft-workflow-document-"),
                "Document workflow digest domain is invalid.");
            try {
                delegate = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException("SHA-256 is unavailable.", impossible);
            }
            text(domain);
        }

        Digest text(String value) {
            Objects.requireNonNull(value, "Document workflow digest text is null.");
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            tag(1);
            integer(bytes.length);
            delegate.update(bytes);
            return this;
        }

        Digest optional(String value) {
            tag(2);
            bool(value != null);
            if (value != null) text(value);
            return this;
        }

        Digest integer(int value) {
            tag(3);
            number[0] = (byte) (value >>> 24);
            number[1] = (byte) (value >>> 16);
            number[2] = (byte) (value >>> 8);
            number[3] = (byte) value;
            delegate.update(number, 0, 4);
            return this;
        }

        Digest longValue(long value) {
            tag(4);
            for (int index = 0; index < 8; index++) {
                number[index] = (byte) (value >>> (56 - index * 8));
            }
            delegate.update(number, 0, 8);
            return this;
        }

        Digest bool(boolean value) {
            tag(5);
            tag(value ? 1 : 0);
            return this;
        }

        String finish() {
            if (finished) throw new IllegalStateException("Document workflow digest is finalized.");
            finished = true;
            byte[] bytes = delegate.digest();
            char[] alphabet = "0123456789abcdef".toCharArray();
            char[] result = new char[bytes.length * 2];
            for (int index = 0; index < bytes.length; index++) {
                int value = bytes[index] & 0xFF;
                result[index * 2] = alphabet[value >>> 4];
                result[index * 2 + 1] = alphabet[value & 0x0F];
            }
            return new String(result);
        }

        private void tag(int value) {
            if (finished) throw new IllegalStateException("Document workflow digest is finalized.");
            number[0] = (byte) value;
            delegate.update(number, 0, 1);
        }
    }
}
