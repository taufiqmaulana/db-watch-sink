package io.datasync.server.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordsTest {

    @Test
    void hashesAreSaltedAndVerify() {
        String hash = Passwords.hash("correct horse");
        assertThat(hash).startsWith("$argon2id$v=19$m=19456,t=2,p=1$").isNotEqualTo(Passwords.hash("correct horse"));
        assertThat(Passwords.verify("correct horse", hash)).isTrue();
        assertThat(Passwords.verify("wrong horse", hash)).isFalse();
        assertThat(Passwords.verify("anything", null)).isFalse();
    }
}
