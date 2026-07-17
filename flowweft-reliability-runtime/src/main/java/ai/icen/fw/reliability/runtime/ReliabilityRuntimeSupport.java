package ai.icen.fw.reliability.runtime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

final class ReliabilityRuntimeSupport {
    static final int MAX_CODE_BYTES = 128;
    static final int MAX_ID_BYTES = 512;
    static final int MAX_REVISION_BYTES = 256;
    static final int MAX_ITEMS = 256;

    private ReliabilityRuntimeSupport() {
    }

    static String text(String value, int maximumBytes, String message) {
        Objects.requireNonNull(value, message);
        require(!value.isEmpty() && value.equals(value.trim()) &&
            value.getBytes(StandardCharsets.UTF_8).length <= maximumBytes, message);
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            require(!Character.isISOControl(codePoint) && Character.getType(codePoint) != Character.FORMAT, message);
            offset += Character.charCount(codePoint);
        }
        return value;
    }

    static String code(String value, String message) {
        text(value, MAX_CODE_BYTES, message);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            boolean alphaNumeric = character >= 'A' && character <= 'Z' ||
                character >= 'a' && character <= 'z' || character >= '0' && character <= '9';
            require(alphaNumeric || character == '.' || character == '_' || character == ':' ||
                character == '/' || character == '-', message);
        }
        return value;
    }

    static String opaque(String value, String message) {
        text(value, MAX_ID_BYTES, message);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            boolean alphaNumeric = character >= 'A' && character <= 'Z' ||
                character >= 'a' && character <= 'z' || character >= '0' && character <= '9';
            require(alphaNumeric || index > 0 &&
                (character == '.' || character == '_' || character == '~' || character == '-'), message);
        }
        require(!value.contains(".."), message);
        return value;
    }

    static String sha256(String value, String message) {
        Objects.requireNonNull(value, message);
        require(value.length() == 64, message);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            require(character >= '0' && character <= '9' || character >= 'a' && character <= 'f', message);
        }
        return value;
    }

    static String hashSecret(String value, String message) {
        text(value, MAX_ID_BYTES, message);
        return digest("flowweft-reliability-runtime-idempotency-v1").text(value).finish();
    }

    static <T> List<T> immutable(Collection<? extends T> values, int maximumSize, String message) {
        Objects.requireNonNull(values, message);
        require(values.size() <= maximumSize, message);
        ArrayList<T> copy = new ArrayList<T>(values.size());
        for (T value : values) {
            require(value != null, message);
            copy.add(value);
        }
        return Collections.unmodifiableList(copy);
    }

    static DigestWriter digest(String domain) {
        return new DigestWriter(domain);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    static final class DigestWriter {
        private final MessageDigest digest;
        private final byte[] buffer = new byte[8];
        private boolean finished;

        private DigestWriter(String domain) {
            code(domain, "Reliability runtime digest domain is invalid.");
            if (!domain.startsWith("flowweft-reliability-runtime-")) {
                throw new IllegalArgumentException("Reliability runtime digest domain is invalid.");
            }
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException("SHA-256 is unavailable.", impossible);
            }
            text(domain);
        }

        DigestWriter text(String value) {
            require(!finished, "Reliability runtime digest is finalized.");
            byte[] bytes = Objects.requireNonNull(value, "Reliability runtime digest value is null.")
                .getBytes(StandardCharsets.UTF_8);
            tagged(1);
            integerRaw(bytes.length);
            digest.update(bytes);
            return this;
        }

        DigestWriter optionalText(String value) {
            tagged(2);
            bool(value != null);
            if (value != null) text(value);
            return this;
        }

        DigestWriter integer(int value) {
            tagged(3);
            integerRaw(value);
            return this;
        }

        DigestWriter longValue(long value) {
            tagged(4);
            for (int index = 0; index < 8; index++) buffer[index] = (byte) (value >>> (56 - index * 8));
            digest.update(buffer, 0, 8);
            return this;
        }

        DigestWriter bool(boolean value) {
            tagged(5);
            tagged(value ? 1 : 0);
            return this;
        }

        String finish() {
            require(!finished, "Reliability runtime digest is finalized.");
            finished = true;
            byte[] bytes = digest.digest();
            char[] alphabet = "0123456789abcdef".toCharArray();
            char[] output = new char[bytes.length * 2];
            for (int index = 0; index < bytes.length; index++) {
                int value = bytes[index] & 0xff;
                output[index * 2] = alphabet[value >>> 4];
                output[index * 2 + 1] = alphabet[value & 0x0f];
            }
            return new String(output);
        }

        private void integerRaw(int value) {
            buffer[0] = (byte) (value >>> 24);
            buffer[1] = (byte) (value >>> 16);
            buffer[2] = (byte) (value >>> 8);
            buffer[3] = (byte) value;
            digest.update(buffer, 0, 4);
        }

        private void tagged(int value) {
            require(!finished, "Reliability runtime digest is finalized.");
            buffer[0] = (byte) value;
            digest.update(buffer, 0, 1);
        }
    }
}
