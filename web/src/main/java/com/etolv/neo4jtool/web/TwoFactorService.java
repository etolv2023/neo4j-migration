package com.etolv.neo4jtool.web;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/** The second sign-in step: authenticator codes and one-time recovery codes. */
@Service
public class TwoFactorService {

    static final int RECOVERY_CODES = 8;
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    /** What a person needs to add the account to an authenticator app. */
    public record Enrolment(String secret, String qr) implements java.io.Serializable {
    }

    private final UserStore users;
    private final SecretBox box;
    private final PasswordEncoder encoder;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public TwoFactorService(UserStore users, SecretBox box, PasswordEncoder encoder) {
        this(users, box, encoder, Clock.systemUTC());
    }

    TwoFactorService(UserStore users, SecretBox box, PasswordEncoder encoder, Clock clock) {
        this.users = users;
        this.box = box;
        this.encoder = encoder;
        this.clock = clock;
    }

    public Enrolment newEnrolment(String username) {
        String secret = Totp.newSecret();
        return new Enrolment(secret, Qr.svgDataUri(Totp.uri("Neo4j Migration", username, secret)));
    }

    /**
     * Turns the second factor on when the code matches the secret that was shown.
     *
     * @return the recovery codes, shown once, or null when the code was wrong
     */
    public List<String> confirm(String username, String secret, String code) {
        long step = Totp.verify(secret, clean(code), clock.millis(), 0);
        if (step < 0) {
            return null;
        }
        users.enableTwoFactor(username, box.encrypt(secret), step);
        return newRecoveryCodes(username);
    }

    /** @return true when the typed text is a valid, unused authenticator code of the user */
    public boolean verifyCode(String username, String code) {
        UserStore.TwoFactor factor = users.twoFactor(username);
        if (!factor.enabled() || factor.secretEncrypted() == null) {
            return false;
        }
        long step = Totp.verify(box.decrypt(factor.secretEncrypted()), clean(code), clock.millis(), factor.lastStep());
        if (step < 0) {
            return false;
        }
        users.rememberStep(username, step);
        return true;
    }

    /** @return true when the typed text is one of the unused recovery codes; that code can never be used again */
    public boolean useRecoveryCode(String username, String typed) {
        String normalized = normalize(typed);
        if (normalized.length() != 10) {
            return false;
        }
        for (UserStore.RecoveryCode code : users.unusedRecoveryCodes(username)) {
            if (encoder.matches(normalized, code.hash())) {
                return users.useRecoveryCode(code.id());
            }
        }
        return false;
    }

    public int recoveryCodesLeft(String username) {
        return users.unusedRecoveryCodes(username).size();
    }

    private List<String> newRecoveryCodes(String username) {
        List<String> shown = new ArrayList<>();
        List<String> hashes = new ArrayList<>();
        for (int i = 0; i < RECOVERY_CODES; i++) {
            StringBuilder code = new StringBuilder();
            for (int c = 0; c < 10; c++) {
                code.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
            }
            hashes.add(encoder.encode(code.toString()));
            shown.add(code.substring(0, 5) + "-" + code.substring(5));
        }
        users.replaceRecoveryCodes(username, hashes);
        return shown;
    }

    /** Authenticator apps show "123 456"; people type it either way. */
    static String clean(String code) {
        return code == null ? "" : code.replaceAll("[\\s-]", "");
    }

    static String normalize(String code) {
        return clean(code).toUpperCase();
    }
}
