package com.vasmarfas.networkhelm.model

import kotlin.test.Test
import kotlin.test.assertEquals

class SensitiveTest {

    @Test
    fun passwordsAndKeysAreKnownByNameOnEveryPlatform() {
        val secrets = listOf(
            "password", "secret", "ipsec-secret", "shared-secret", "wpa2-pre-shared-key", "passphrase", "private-key", "preshared-key",
            "static-sta-private-key", "static-key-2", "authentication-password", "encryption-password", "tcp-md5-key", "auth-key",
            "private_key", "sae_password", "security.passphrase", "login user rd-test authentication encrypted-password",
        )
        assertEquals(secrets, secrets.filter { sensitive(it) })
        val plain = listOf("public-key", "key", "key-size", "password-format", "authentication", "auth-method", "use-ipsec", "name", "caller-id")
        assertEquals(emptyList(), plain.filter { sensitive(it) })
        assertEquals(true, sensitive("key", ValueType.Secret), "the type of the platform counts")
    }

    @Test
    fun aKeyChosenByItsNameIsNoSecret() {
        assertEquals(false, Argument("auth-key", ValueType.Text(), listOf("none")).sensitive())
        assertEquals(true, Argument("auth-key", ValueType.Text()).sensitive())
        assertEquals(true, Argument("key", ValueType.Secret, listOf("none")).sensitive(), "the type of the platform counts")
    }

    @Test
    fun linesOfSecretsAreSecretByTheirType() {
        val lines = ValueType.Many(ValueType.Secret, "\n")
        assertEquals(true, Argument("secret-env", lines).sensitive())
        assertEquals(true, sensitive("secret-env", lines))
        assertEquals(false, sensitive("secret-env"))
        assertEquals(false, Argument("env", ValueType.Many(ValueType.Text(), "\n")).sensitive())
    }
}
