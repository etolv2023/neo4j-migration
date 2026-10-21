package com.etolv.neo4jtool.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class TotpTest {

    /** The test key of RFC 6238, appendix B (SHA-1): the ASCII text 12345678901234567890. */
    private static final byte[] RFC_KEY = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    @Test
    void producesTheCodesOfRfc6238() {
        long[][] times = {{59, 0}, {1111111109L, 0}, {1111111111L, 0}, {1234567890L, 0}, {2000000000L, 0}, {20000000000L, 0}};
        String[] expected = {"94287082", "07081804", "14050471", "89005924", "69279037", "65353130"};
        for (int i = 0; i < times.length; i++) {
            assertThat(Totp.code(RFC_KEY, Totp.stepAt(times[i][0] * 1000), 8)).as("time " + times[i][0]).isEqualTo(expected[i]);
        }
    }

    @Test
    void sixDigitCodesAreTheLastSixDigitsOfTheEightDigitOnes() {
        // the same truncated value, taken modulo 10^6 instead of 10^8
        assertThat(Totp.code(RFC_KEY, Totp.stepAt(59_000), 6)).isEqualTo("287082");
        assertThat(Totp.code(RFC_KEY, Totp.stepAt(1111111109_000L), 6)).isEqualTo("081804");
    }

    @Test
    void base32MatchesRfc4648AndRoundTrips() {
        assertThat(Base32.encode("foobar".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MZXW6YTBOI");
        assertThat(Base32.encode("fo".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MZXQ");
        assertThat(new String(Base32.decode("mzxw 6ytb-oi=="), StandardCharsets.US_ASCII)).isEqualTo("foobar");
        assertThat(Base32.encode(RFC_KEY)).isEqualTo("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
        String secret = Totp.newSecret();
        assertThat(secret).hasSize(32).matches("[A-Z2-7]+");
        assertThat(Base32.decode(secret)).hasSize(20);
        assertThat(Totp.newSecret()).isNotEqualTo(secret);
        assertThatThrownBy(() -> Base32.decode("not base32!")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsTheCurrentStepAndOneStepEitherSideButNothingElse() {
        String secret = Base32.encode(RFC_KEY);
        long now = 1_700_000_000_000L;
        long step = Totp.stepAt(now);
        for (long offset : new long[]{-1, 0, 1}) {
            String code = Totp.code(RFC_KEY, step + offset, 6);
            assertThat(Totp.verify(secret, code, now, 0)).as("offset " + offset).isEqualTo(step + offset);
        }
        for (long offset : new long[]{-3, -2, 2, 3}) {
            String code = Totp.code(RFC_KEY, step + offset, 6);
            assertThat(Totp.verify(secret, code, now, 0)).as("offset " + offset).isEqualTo(-1);
        }
    }

    @Test
    void aCodeCannotBeUsedTwice() {
        String secret = Base32.encode(RFC_KEY);
        long now = 1_700_000_000_000L;
        long step = Totp.stepAt(now);
        String code = Totp.code(RFC_KEY, step, 6);
        long used = Totp.verify(secret, code, now, 0);
        assertThat(used).isEqualTo(step);
        assertThat(Totp.verify(secret, code, now, used)).isEqualTo(-1);
        // the code before it is older than the last one used, so it is refused as well
        assertThat(Totp.verify(secret, Totp.code(RFC_KEY, step - 1, 6), now, used)).isEqualTo(-1);
        // the next code is still fine
        assertThat(Totp.verify(secret, Totp.code(RFC_KEY, step + 1, 6), now, used)).isEqualTo(step + 1);
    }

    @Test
    void refusesAnythingThatIsNotSixDigits() {
        String secret = Base32.encode(RFC_KEY);
        long now = 1_700_000_000_000L;
        assertThat(Totp.verify(secret, null, now, 0)).isEqualTo(-1);
        assertThat(Totp.verify(secret, "", now, 0)).isEqualTo(-1);
        assertThat(Totp.verify(secret, "12345", now, 0)).isEqualTo(-1);
        assertThat(Totp.verify(secret, "1234567", now, 0)).isEqualTo(-1);
        assertThat(Totp.verify(secret, "12a456", now, 0)).isEqualTo(-1);
    }

    @Test
    void theAddressForTheAuthenticatorAppHasTheSecretAndEscapesTheName() {
        String uri = Totp.uri("Neo4j Migration", "ana@etolv.net", "ABCDEFGH");
        assertThat(uri).startsWith("otpauth://totp/Neo4j%20Migration:ana%40etolv.net?secret=ABCDEFGH&issuer=Neo4j%20Migration")
                .contains("algorithm=SHA1").contains("digits=6").contains("period=30");
    }

    @Test
    void theQrCodeIsAnInlineImage() {
        String image = Qr.svgDataUri(Totp.uri("Neo4j Migration", "ana", Totp.newSecret()));
        assertThat(image).startsWith("data:image/svg+xml;base64,");
        String svg = new String(java.util.Base64.getDecoder().decode(image.substring(image.indexOf(',') + 1)), StandardCharsets.UTF_8);
        assertThat(svg).startsWith("<svg").contains("viewBox").contains("<path");
    }

    @Test
    void fiveWrongCodesInFiveMinutesLockTheUserUntilTheOldestFailureIsOld() {
        MutableClock clock = new MutableClock();
        LoginThrottle throttle = new LoginThrottle(clock);
        for (int i = 0; i < 4; i++) {
            throttle.failed("ana");
            clock.advance(Duration.ofSeconds(10));
        }
        assertThat(throttle.lockedFor("ana")).isZero();
        throttle.failed("ana");
        assertThat(throttle.lockedFor("ana")).isPositive().isLessThanOrEqualTo(Duration.ofMinutes(5));
        assertThat(throttle.lockedFor("someone else")).isZero();
        clock.advance(Duration.ofMinutes(4));
        assertThat(throttle.lockedFor("ana")).isPositive();
        clock.advance(Duration.ofMinutes(1));      // the first failure is now five minutes old
        assertThat(throttle.lockedFor("ana")).isZero();
    }

    @Test
    void aGoodCodeClearsTheCount() {
        LoginThrottle throttle = new LoginThrottle(new MutableClock());
        for (int i = 0; i < 4; i++) {
            throttle.failed("ana");
        }
        throttle.succeeded("ana");
        throttle.failed("ana");
        assertThat(throttle.lockedFor("ana")).isZero();
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
