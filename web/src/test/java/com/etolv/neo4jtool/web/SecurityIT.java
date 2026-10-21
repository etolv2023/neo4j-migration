package com.etolv.neo4jtool.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Users and the two-factor sign-in, against a real MySQL. Each test goes on from the one before. */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SecurityIT {

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4")).withDatabaseName("neo4j_tool");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        MYSQL.start();
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("tool.secret", () -> "integration-test-secret");
        registry.add("tool.admin.user", () -> "admin");
        registry.add("tool.admin.password", () -> "correct horse battery");
    }

    private static final RequestPostProcessor ADMIN = user("admin").roles("ADMIN");
    private static final String PASSWORD = "a long password";

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired UserStore users;
    @Autowired SettingsStore settings;

    @Test
    @Order(1)
    void anAdministratorAddsUsersAndBadInputIsRefused() throws Exception {
        addUser("bob", PASSWORD).andExpect(redirectedUrl("/users")).andExpect(flash().attribute("message", containsString("bob")));
        mvc.perform(get("/users").with(ADMIN)).andExpect(status().isOk())
                .andExpect(content().string(containsString("bob"))).andExpect(content().string(containsString("admin")));
        addUser("bob", PASSWORD).andExpect(flash().attribute("error", containsString("already")));
        addUser("short", "1234567").andExpect(flash().attribute("error", containsString("8 characters")));
        addUser("x y", PASSWORD).andExpect(flash().attribute("error", containsString("3 to 100")));
        mvc.perform(post("/users").param("username", "carl").param("password", PASSWORD).param("confirm", "different")
                .with(ADMIN).with(csrf())).andExpect(flash().attribute("error", containsString("different")));
        assertThat(users.exists("carl")).isFalse();
        assertThat(jdbc.sql("SELECT password_hash FROM users WHERE username = 'bob'").query(String.class).single()).startsWith("$2");
        assertThat(actions()).contains("user_created");
    }

    @Test
    @Order(2)
    void aSwitchedOffUserCannotSignInAndALiveSessionEnds() throws Exception {
        MockHttpSession bob = signIn("bob", PASSWORD);
        mvc.perform(get("/settings").session(bob)).andExpect(status().isOk());

        long id = idOf("bob");
        mvc.perform(post("/users/" + id + "/enabled").param("enabled", "false").with(ADMIN).with(csrf()))
                .andExpect(flash().attribute("message", containsString("no longer")));
        mvc.perform(get("/settings").session(bob)).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login"));
        mvc.perform(post("/login").param("username", "bob").param("password", PASSWORD).with(csrf()))
                .andExpect(redirectedUrl("/login?error"));
        assertThat(actions()).contains("user_disabled", "login_failed");

        mvc.perform(post("/users/" + id + "/enabled").param("enabled", "true").with(ADMIN).with(csrf()));
        signIn("bob", PASSWORD);
    }

    @Test
    @Order(3)
    void youCannotLockYourselfOutAndAnAdministratorResetsPasswordsOfOthers() throws Exception {
        long admin = idOf("admin");
        mvc.perform(post("/users/" + admin + "/enabled").param("enabled", "false").with(ADMIN).with(csrf()))
                .andExpect(flash().attribute("error", containsString("account you are using")));
        mvc.perform(post("/users/" + admin + "/delete").with(ADMIN).with(csrf()))
                .andExpect(flash().attribute("error", containsString("account you are using")));
        mvc.perform(post("/users/" + admin + "/password").param("password", "new password 1").param("confirm", "new password 1")
                .with(ADMIN).with(csrf())).andExpect(flash().attribute("error", containsString("Settings")));
        assertThat(users.isEnabled("admin")).isTrue();

        long bob = idOf("bob");
        mvc.perform(post("/users/" + bob + "/password").param("password", "short").param("confirm", "short").with(ADMIN).with(csrf()))
                .andExpect(flash().attribute("error", containsString("8 characters")));
        mvc.perform(post("/users/" + bob + "/password").param("password", "reset by admin").param("confirm", "reset by admin")
                .with(ADMIN).with(csrf())).andExpect(flash().attribute("message", containsString("bob")));
        mvc.perform(post("/login").param("username", "bob").param("password", PASSWORD).with(csrf())).andExpect(redirectedUrl("/login?error"));
        signIn("bob", "reset by admin");
        assertThat(actions()).contains("user_password_reset");
    }

    @Test
    @Order(4)
    void aUserTurnsOnTwoFactorWithACodeAndGetsRecoveryCodes() throws Exception {
        addUser("dave", PASSWORD);
        MockHttpSession session = signIn("dave", PASSWORD);

        mvc.perform(post("/settings/2fa/start").session(session).with(csrf())).andExpect(redirectedUrl("/settings"));
        String secret = pendingSecret(session);
        mvc.perform(get("/settings").session(session)).andExpect(content().string(containsString(secret)))
                .andExpect(content().string(containsString("data:image/svg+xml;base64,")));

        mvc.perform(post("/settings/2fa/enable").param("code", wrong(secret)).session(session).with(csrf()))
                .andExpect(flash().attribute("error", containsString("wrong")));
        assertThat(users.hasTwoFactor("dave")).isFalse();

        MvcResult done = mvc.perform(post("/settings/2fa/enable").param("code", right(secret, 0)).session(session).with(csrf()))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Recovery codes"))).andReturn();
        @SuppressWarnings("unchecked")
        List<String> codes = (List<String>) done.getModelAndView().getModel().get("codes");
        assertThat(codes).hasSize(8).doesNotHaveDuplicates().allMatch(c -> c.matches("[A-Z2-9]{5}-[A-Z2-9]{5}"));

        assertThat(users.hasTwoFactor("dave")).isTrue();
        assertThat(jdbc.sql("SELECT totp_secret_enc FROM users WHERE username = 'dave'").query(String.class).single())
                .isNotBlank().doesNotContain(secret);
        assertThat(jdbc.sql("SELECT code_hash FROM recovery_codes").query(String.class).list())
                .hasSize(8).allMatch(h -> h.startsWith("$2")).noneMatch(h -> codes.contains(h));
        assertThat(actions()).contains("two_factor_enabled");
        recovery = codes;
    }

    /** The recovery codes are shown once, so the test that saw them keeps them for the ones after it. */
    private static List<String> recovery;

    @Test
    @Order(5)
    void withTwoFactorThePasswordAloneOpensNothing() throws Exception {
        MockHttpSession session = passwordOnly("dave", PASSWORD);
        mvc.perform(get("/settings").session(session)).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login/code"));
        mvc.perform(get("/migrations").session(session)).andExpect(redirectedUrl("/login/code"));
        mvc.perform(post("/endpoints").param("name", "x").session(session).with(csrf())).andExpect(redirectedUrl("/login/code"));
        mvc.perform(get("/login/code").session(session)).andExpect(status().isOk()).andExpect(content().string(containsString("Second step")));
        assertThat(actions()).contains("password_ok");
        assertThat(jdbc.sql("SELECT count(*) FROM audit_log WHERE actor = 'dave' AND action = 'login'").query(Long.class).single())
                .isEqualTo(1);     // the sign-in before two-factor, not this one
    }

    @Test
    @Order(6)
    void aWrongCodeAndARepeatedCodeAreRefusedAndTheNextCodeLetsYouIn() throws Exception {
        String secret = secretOf("dave");
        MockHttpSession session = passwordOnly("dave", PASSWORD);
        mvc.perform(post("/login/code").param("code", wrong(secret)).session(session).with(csrf()))
                .andExpect(redirectedUrl("/login/code")).andExpect(flash().attribute("error", containsString("wrong")));
        // the code that turned two-factor on is spent
        mvc.perform(post("/login/code").param("code", right(secret, 0)).session(session).with(csrf()))
                .andExpect(redirectedUrl("/login/code"));
        mvc.perform(get("/settings").session(session)).andExpect(redirectedUrl("/login/code"));

        mvc.perform(post("/login/code").param("code", right(secret, 1)).session(session).with(csrf()))
                .andExpect(redirectedUrl("/"));
        mvc.perform(get("/settings").session(session)).andExpect(status().isOk());
        assertThat(actions()).contains("two_factor_failed");
        assertThat(jdbc.sql("SELECT detail FROM audit_log WHERE actor = 'dave' AND action = 'login' ORDER BY id DESC LIMIT 1")
                .query(String.class).single()).contains("authenticator code");
    }

    @Test
    @Order(7)
    void aRecoveryCodeWorksOnce() throws Exception {
        String code = recoveryCodes("dave").get(0);
        MockHttpSession first = passwordOnly("dave", PASSWORD);
        mvc.perform(post("/login/code").param("code", code.toLowerCase()).session(first).with(csrf())).andExpect(redirectedUrl("/"));
        mvc.perform(get("/settings").session(first)).andExpect(status().isOk());

        MockHttpSession second = passwordOnly("dave", PASSWORD);
        mvc.perform(post("/login/code").param("code", code).session(second).with(csrf()))
                .andExpect(redirectedUrl("/login/code")).andExpect(flash().attribute("error", containsString("recovery")));
        mvc.perform(get("/settings").session(second)).andExpect(redirectedUrl("/login/code"));
        assertThat(actions()).contains("recovery_code_used");
        assertThat(jdbc.sql("SELECT count(*) FROM recovery_codes WHERE used_at IS NULL").query(Long.class).single()).isEqualTo(7);
    }

    @Test
    @Order(8)
    void fiveWrongCodesLockTheSecondStepEvenForAGoodCode() throws Exception {
        String secret = secretOf("dave");
        MockHttpSession session = passwordOnly("dave", PASSWORD);
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/login/code").param("code", wrong(secret)).session(session).with(csrf())).andExpect(redirectedUrl("/login/code"));
        }
        mvc.perform(post("/login/code").param("code", recoveryCodes("dave").get(1)).session(session).with(csrf()))
                .andExpect(redirectedUrl("/login/code")).andExpect(flash().attribute("error", containsString("Too many")));
        mvc.perform(get("/settings").session(session)).andExpect(redirectedUrl("/login/code"));
        assertThat(actions()).contains("two_factor_locked");
    }

    @Test
    @Order(9)
    void anAdministratorTurnsOffTwoFactorForAUserWhoLostThePhone() throws Exception {
        mvc.perform(post("/users/" + idOf("dave") + "/two-factor/disable").with(ADMIN).with(csrf()))
                .andExpect(flash().attribute("message", containsString("dave")));
        assertThat(users.hasTwoFactor("dave")).isFalse();
        assertThat(jdbc.sql("SELECT count(*) FROM recovery_codes").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT totp_secret_enc IS NULL FROM users WHERE username = 'dave'").query(Boolean.class).single()).isTrue();
        signIn("dave", PASSWORD);
        assertThat(actions()).contains("two_factor_reset");
    }

    @Test
    @Order(10)
    void aUserTurnsTheirOwnTwoFactorOffWithThePassword() throws Exception {
        MockHttpSession session = signIn("dave", PASSWORD);
        enrol(session);
        mvc.perform(post("/settings/2fa/disable").param("password", "wrong").session(session).with(csrf()))
                .andExpect(flash().attribute("error", containsString("password")));
        assertThat(users.hasTwoFactor("dave")).isTrue();
        mvc.perform(post("/settings/2fa/disable").param("password", PASSWORD).session(session).with(csrf()))
                .andExpect(flash().attribute("message", containsString("off")));
        assertThat(users.hasTwoFactor("dave")).isFalse();
        assertThat(actions()).contains("two_factor_disabled");
    }

    @Test
    @Order(11)
    void whenRequiredAUserWithoutTwoFactorMustSetItUpAfterThePassword() throws Exception {
        // an administrator without two-factor of their own cannot turn the requirement on
        mvc.perform(settingsForm(true).with(user("bob").roles("ADMIN")).with(csrf()))
                .andExpect(flash().attribute("error", containsString("your own account")));
        assertThat(settings.get().requireTwoFactor()).isFalse();

        MockHttpSession dave = signIn("dave", PASSWORD);
        enrol(dave);
        mvc.perform(settingsForm(true).session(dave).with(csrf())).andExpect(flash().attribute("message", containsString("saved")));
        assertThat(settings.get().requireTwoFactor()).isTrue();
        mvc.perform(post("/settings/2fa/disable").param("password", PASSWORD).session(dave).with(csrf()))
                .andExpect(flash().attribute("error", containsString("required")));
        assertThat(users.hasTwoFactor("dave")).isTrue();

        MockHttpSession bob = passwordOnly("bob", "reset by admin");
        mvc.perform(get("/settings").session(bob)).andExpect(redirectedUrl("/login/setup"));
        MvcResult page = mvc.perform(get("/login/setup").session(bob)).andExpect(status().isOk())
                .andExpect(content().string(containsString("data:image/svg+xml;base64,"))).andReturn();
        String secret = pendingSecret(bob);
        assertThat(page.getResponse().getContentAsString()).contains(secret);
        mvc.perform(post("/login/setup").param("code", wrong(secret)).session(bob).with(csrf())).andExpect(redirectedUrl("/login/setup"));
        mvc.perform(get("/settings").session(bob)).andExpect(redirectedUrl("/login/setup"));
        mvc.perform(post("/login/setup").param("code", right(secret, 0)).session(bob).with(csrf()))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Recovery codes")));
        mvc.perform(get("/settings").session(bob)).andExpect(status().isOk());
        assertThat(users.hasTwoFactor("bob")).isTrue();

        // and the requirement can be lifted again
        mvc.perform(settingsForm(false).session(dave).with(csrf()));
        assertThat(settings.get().requireTwoFactor()).isFalse();
    }

    @Test
    @Order(12)
    void deletingAUserRemovesTheirRecoveryCodes() throws Exception {
        long bob = idOf("bob");
        assertThat(jdbc.sql("SELECT count(*) FROM recovery_codes").query(Long.class).single()).isPositive();
        mvc.perform(post("/users/" + bob + "/delete").with(ADMIN).with(csrf())).andExpect(flash().attribute("message", containsString("deleted")));
        assertThat(users.exists("bob")).isFalse();
        assertThat(jdbc.sql("SELECT count(*) FROM recovery_codes WHERE user_id = ?").param(bob).query(Long.class).single()).isZero();
        assertThat(actions()).contains("user_deleted");
        mvc.perform(post("/login").param("username", "bob").param("password", "reset by admin").with(csrf()))
                .andExpect(redirectedUrl("/login?error"));
    }

    // ---- helpers ----

    private org.springframework.test.web.servlet.ResultActions addUser(String name, String password) throws Exception {
        return mvc.perform(post("/users").param("username", name).param("password", password).param("confirm", password)
                .with(ADMIN).with(csrf()));
    }

    /** Password sign-in; for a user without two-factor this is a complete sign-in. */
    private MockHttpSession signIn(String name, String password) throws Exception {
        MockHttpSession session = passwordOnly(name, password);
        mvc.perform(get("/settings").session(session)).andExpect(status().isOk());
        return session;
    }

    private MockHttpSession passwordOnly(String name, String password) throws Exception {
        MvcResult result = mvc.perform(post("/login").param("username", name).param("password", password).with(csrf()))
                .andExpect(status().is3xxRedirection()).andReturn();
        assertThat(result.getResponse().getRedirectedUrl()).doesNotContain("error");
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    /** Turns two-factor on for the signed-in user and returns the recovery codes. */
    private void enrol(MockHttpSession session) throws Exception {
        mvc.perform(post("/settings/2fa/start").session(session).with(csrf()));
        String secret = pendingSecret(session);
        mvc.perform(post("/settings/2fa/enable").param("code", right(secret, 0)).session(session).with(csrf())).andExpect(status().isOk());
    }

    private static String pendingSecret(MockHttpSession session) {
        return ((TwoFactorService.Enrolment) session.getAttribute(TwoFactorController.PENDING)).secret();
    }

    /** The code of the current 30-second step, or of one step later. */
    private static String right(String secret, int stepsAhead) {
        return Totp.code(Base32.decode(secret), Totp.stepAt(System.currentTimeMillis()) + stepsAhead, Totp.DIGITS);
    }

    /** Six digits that no code of the accepted window equals. */
    private static String wrong(String secret) {
        List<String> valid = List.of(right(secret, -1), right(secret, 0), right(secret, 1));
        for (int i = 0; i < 1_000_000; i++) {
            String candidate = String.format("%06d", i * 7919 % 1_000_000);
            if (!valid.contains(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException();
    }

    @Autowired SecretBox box;

    private String secretOf(String user) {
        return box.decrypt(users.twoFactor(user).secretEncrypted());
    }

    private List<String> recoveryCodes(String user) {
        return recovery;
    }

    private long idOf(String name) {
        return jdbc.sql("SELECT id FROM users WHERE username = ?").param(name).query(Long.class).single();
    }

    private List<String> actions() {
        return jdbc.sql("SELECT action FROM audit_log").query(String.class).list();
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder settingsForm(boolean requireTwoFactor) {
        var request = post("/settings").param("runner", "DOCKER").param("dockerImage", "neo4j:5.26.29-community").param("neo4jHome", "")
                .param("workDir", System.getProperty("java.io.tmpdir")).param("outputDir", System.getProperty("java.io.tmpdir") + "/out")
                .param("backupDir", System.getProperty("java.io.tmpdir") + "/backups").param("tempHeap", "512m")
                .param("tempPageCache", "128m").param("startTimeoutMinutes", "10").param("orgProperty", "org_id")
                .param("batchSize", "1000").param("sampleSize", "20");
        if (requireTwoFactor) {
            request.param("requireTwoFactor", "true");
        }
        return request;
    }
}
