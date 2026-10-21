package com.etolv.neo4jtool.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SecretBoxTest {

    private static SecretBox box(String secret) {
        return new SecretBox(new ToolProperties(secret, new ToolProperties.Admin("admin", "long-enough-password")));
    }

    @Test
    void decryptsWhatItEncrypted() {
        SecretBox box = box("a-secret-of-16-chars");

        String stored = box.encrypt("p@ss wörd");

        assertThat(stored).doesNotContain("p@ss");
        assertThat(box.decrypt(stored)).isEqualTo("p@ss wörd");
    }

    @Test
    void sameTextEncryptsDifferentlyEachTime() {
        SecretBox box = box("a-secret-of-16-chars");

        assertThat(box.encrypt("same")).isNotEqualTo(box.encrypt("same"));
    }

    @Test
    void aDifferentSecretCannotReadIt() {
        String stored = box("a-secret-of-16-chars").encrypt("x");

        assertThatThrownBy(() -> box("another-secret-16ch").decrypt(stored))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("TOOL_SECRET");
    }

    @Test
    void tamperedDataIsRejected() {
        SecretBox box = box("a-secret-of-16-chars");
        byte[] bytes = java.util.Base64.getDecoder().decode(box.encrypt("x"));
        bytes[bytes.length - 1] ^= 1;

        assertThatThrownBy(() -> box.decrypt(java.util.Base64.getEncoder().encodeToString(bytes)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shortSecretsAndWeakAdminPasswordsStopStartUp() {
        assertThatThrownBy(() -> box("short")).hasMessageContaining("TOOL_SECRET");
        assertThatThrownBy(() -> new ToolProperties("a-secret-of-16-chars", new ToolProperties.Admin("admin", "")))
                .hasMessageContaining("TOOL_ADMIN_PASSWORD");
    }
}
