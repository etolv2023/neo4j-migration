package com.etolv.neo4jtool.web;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Time-based one-time passwords (RFC 6238, HMAC-SHA1, 30 second steps): what Google Authenticator and the others compute. */
public final class Totp {

    public static final int STEP_SECONDS = 30;
    public static final int DIGITS = 6;
    /** A code from one step before or after the current one is accepted, to forgive a small clock difference. */
    public static final int WINDOW = 1;

    private static final SecureRandom RANDOM = new SecureRandom();

    private Totp() {
    }

    /** A new secret: 20 random bytes in Base32 (160 bits, the size RFC 4226 recommends). */
    public static String newSecret() {
        byte[] bytes = new byte[20];
        RANDOM.nextBytes(bytes);
        return Base32.encode(bytes);
    }

    public static long stepAt(long epochMillis) {
        return Math.floorDiv(epochMillis / 1000, (long) STEP_SECONDS);
    }

    /** The code of one step. */
    public static String code(byte[] key, long step, int digits) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] counter = new byte[8];
            for (int i = 7, s = 0; i >= 0; i--, s += 8) {
                counter[i] = (byte) (step >>> s);
            }
            byte[] hash = mac.doFinal(counter);
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24) | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8) | (hash[offset + 3] & 0xFF);
            long modulo = 1;
            for (int i = 0; i < digits; i++) {
                modulo *= 10;
            }
            return String.format("%0" + digits + "d", binary % modulo);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Checks a code typed by a person.
     *
     * @param lastUsedStep the step of the last code accepted for this user; a step at or before it is refused, so a code
     *                     cannot be used twice
     * @return the step that matched, or -1 when the code is wrong or was already used
     */
    public static long verify(String secret, String typed, long nowMillis, long lastUsedStep) {
        if (typed == null || !typed.matches("\\d{" + DIGITS + "}")) {
            return -1;
        }
        byte[] key = Base32.decode(secret);
        long now = stepAt(nowMillis);
        long matched = -1;
        for (long step = now - WINDOW; step <= now + WINDOW; step++) {
            // every candidate is compared, so the time taken does not tell which one matched
            boolean same = MessageDigest.isEqual(code(key, step, DIGITS).getBytes(StandardCharsets.US_ASCII),
                    typed.getBytes(StandardCharsets.US_ASCII));
            if (same && step > lastUsedStep) {
                matched = step;
            }
        }
        return matched;
    }

    /** The otpauth:// address an authenticator app reads from the QR code. */
    public static String uri(String issuer, String account, String secret) {
        return "otpauth://totp/" + encode(issuer) + ":" + encode(account) + "?secret=" + secret + "&issuer=" + encode(issuer)
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + STEP_SECONDS;
    }

    private static String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
