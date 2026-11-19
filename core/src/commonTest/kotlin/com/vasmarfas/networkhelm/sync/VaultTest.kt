package com.vasmarfas.networkhelm.sync

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class VaultTest {
    private val device = "0123456789abcdef0123456789abcdef"
    private val password = "correct horse battery staple"

    // the vault carries its parameters; small ones keep the tests fast
    private suspend fun created(secret: ByteArray? = null) = createVault(password, secret, device, 1, 8192)

    @Test
    fun aVaultOpensWithItsPasswordOrMasterKeyAndNotWithAnotherPassword() = runTest {
        val (file, keys) = created()
        val opened = unlock(file, password, null)
        assertContentEquals(keys.key, opened.key)
        assertContentEquals(keys.master, opened.master)
        assertEquals(keys.vault, opened.vault)
        assertEquals(1, opened.epoch)
        assertContentEquals(keys.key, unlock(file, keys.master).key)
        val wrong = assertFailsWith<DeviceException> { unlock(file, "$password!", null) }
        assertEquals("the password does not open the vault", wrong.message)
        assertEquals("this key does not open the vault", assertFailsWith<DeviceException> { unlock(file, ByteArray(32)) }.message)
    }

    @Test
    fun aPasswordOutsideAsciiGivesTheKeyOfItsUtf8() = runTest {
        val (file, keys) = createVault("пароль хранилища", null, device, 1, 8192)
        assertContentEquals(keys.key, unlock(file, "пароль хранилища", null).key)
        assertFailsWith<DeviceException> { unlock(file, "пароль хранилищ", null) }
        // й typed as one letter and as и with a breve, as some systems give it, is one password
        val (composed, made) = createVault("ключ й", null, device, 1, 8192)
        assertContentEquals(made.key, unlock(composed, "ключ й", null).key)
    }

    @Test
    fun aVaultMadeWithASecretKeyNeedsItAsWell() = runTest {
        val secret = ByteArray(SECRET) { 5 }
        val (file, keys) = created(secret)
        assertEquals(true, vaultHeader(file).password?.secret)
        assertEquals("the vault needs its secret key as well as the password", assertFailsWith<DeviceException> { unlock(file, password, null) }.message)
        assertEquals("the password does not open the vault", assertFailsWith<DeviceException> { unlock(file, password, ByteArray(SECRET) { 6 }) }.message)
        assertContentEquals(keys.key, unlock(file, password, secret).key)
    }

    @Test
    fun aNewPasswordKeepsTheKeyOfTheDataAndTheOldOneNoLongerOpens() = runTest {
        val (file, keys) = created()
        val (rewrapped, changed) = rewrap(keys, vaultHeader(file), "the new password of the vault", null, device, 1, 8192)
        assertContentEquals(keys.key, changed.key)
        assertEquals(keys.epoch, changed.epoch)
        assertEquals(2L, vaultHeader(rewrapped).version)
        assertContentEquals(keys.key, unlock(rewrapped, "the new password of the vault", null).key)
        assertFailsWith<DeviceException> { unlock(rewrapped, password, null) }
        // a device that kept the key of the data reads what is written after the change without the new password
        assertEquals(emptyList(), readState(keys, device, writeState(changed, device, 2, emptyList())).content.records)
    }

    @Test
    fun aNewKeyOfTheDataShutsOutTheFilesOfTheEpochBefore() = runTest {
        val (file, keys) = created()
        val before = writeState(keys, device, 1, emptyList())
        val (rekeyed, changed) = rekey(keys, vaultHeader(file), "the new password of the vault", null, device, 1, 8192)
        assertEquals(2, changed.epoch)
        assertFalse(keys.key.contentEquals(changed.key))
        assertContentEquals(changed.key, unlock(rekeyed, "the new password of the vault", null).key)
        assertEquals("${statePath(device)} was written with an earlier key of the vault", assertFailsWith<DeviceException> { readState(changed, device, before) }.message)
        val after = writeState(changed, device, 2, emptyList())
        val error = assertFailsWith<DeviceException> { readState(keys, device, after) }
        assertEquals(ErrorCategory.PermissionDenied, error.category)
        assertEquals("the key of the vault was changed on another device: enter the new password", error.message)
    }

    @Test
    fun aVaultThatAsksForTooMuchOrForANewerReaderIsRefusedBeforeArgon2idRuns() = runTest {
        val (file, _) = created()
        val header = headerOf(file)
        val lock = checkNotNull(header["password"]).jsonObject
        val greedy = withHeader(file, JsonObject(header + ("password" to JsonObject(lock + ("memory" to JsonPrimitive(2_000_000_000))))))
        val error = assertFailsWith<DeviceException> { unlock(greedy, password, null) }
        assertEquals(ErrorCategory.Unsupported, error.category)
        assertEquals("the vault asks for Argon2id with 1 passes and 2000000000 bytes of memory, which this app does not run", error.message)
        // no more memory than the app gives a vault itself
        val doubled = withHeader(file, JsonObject(header + ("password" to JsonObject(lock + ("memory" to JsonPrimitive(2 * MEMORY))))))
        assertFailsWith<DeviceException> { unlock(doubled, password, null) }
        val newer = withHeader(file, JsonObject(header + ("reader" to JsonPrimitive(2))))
        assertEquals("vault was written by a newer version of the app: update it to synchronise", assertFailsWith<DeviceException> { vaultHeader(newer) }.message)
    }

    @Test
    fun aTransferCodeCarriesTheMasterKeyAndTheServiceAndNothingOfItLeaksIntoAnError() = runTest {
        val secret = ByteArray(SECRET) { 5 }
        val (file, keys) = created(secret)
        val code = transferCode(keys.vault, keys.master, secret, buildJsonObject { put("url", "https://dav.example.org/") })
        val received = transfer(code)
        assertEquals(keys.vault, received.vault)
        assertContentEquals(keys.key, unlock(file, Base64.decode(received.master)).key)
        assertContentEquals(secret, Base64.decode(checkNotNull(received.secret)))
        assertEquals("https://dav.example.org/", received.service?.get("url")?.jsonPrimitive?.content)
        for (broken in listOf("", "nhs1:", "nhs1:!!!", code.dropLast(4), "nhs2:" + code.removePrefix("nhs1:"))) {
            val error = assertFailsWith<DeviceException> { transfer(broken) }
            assertEquals("the code is not one of NetworkHelm synchronisation", error.message)
        }
    }

    @Test
    fun neitherAnErrorNorTheTextOfTheKeysCarriesASecret() = runTest {
        val secret = ByteArray(SECRET) { 5 }
        val (file, keys) = created(secret)
        val code = transferCode(keys.vault, keys.master, secret, null)
        val texts = listOf(
            assertFailsWith<DeviceException> { unlock(file, "$password!", secret) }.message,
            assertFailsWith<DeviceException> { transfer(code.dropLast(4)) }.message,
            keys.toString(),
            transfer(code).toString(),
        ).joinToString("\n")
        listOf(password, Base64.encode(keys.master), Base64.encode(keys.key), Base64.encode(secret), code.removePrefix("nhs1:").take(24)).forEachIndexed { index, secretText ->
            assertFalse(secretText in texts, "secret $index")
        }
    }

    @Test
    fun aVaultAndAStateWrittenElsewhereAreReadAndWrittenAlikeOnEveryPlatform() = runTest {
        val vault = Base64.decode(VAULT_FILE)
        val keys = unlock(vault, password, null)
        assertEquals("5f0c2a9e41d7b3885f0c2a9e41d7b388", keys.vault)
        assertContentEquals(ByteArray(32) { (0x40 + it).toByte() }, keys.key)
        val state = readState(keys, device, Base64.decode(STATE_FILE))
        assertEquals(7L, state.header.version)
        assertEquals(listOf("future.thing", "router", "setting", "snippet"), state.content.records.map { it.kind })
        assertEquals(Record("setting", "theme", Hlc(1763400000001, 0, device), data = JsonObject(mapOf("value" to JsonPrimitive("Legacy")))), state.content.records[2])
        assertEquals(Record("snippet", "d8f0f771d93c90d6ef9eb317b1ee5d07", Hlc(0, 0, device), deleted = true), state.content.records[3])

        // the same salt and nonce give the same bytes on every platform
        val header = vaultHeader(vault)
        val wrapped = "{\"key\":\"${Base64.encode(keys.key)}\"}".encodeToByteArray()
        assertEquals(VAULT_FILE, Base64.encode(seal(header, VAULT, wrapped, vaultKey(keys.master), Base64.decode(header.salt), Base64.decode(header.nonce))))
        val content = fileJson.encodeToString(StateContent.serializer(), state.content).encodeToByteArray()
        val written = seal(state.header, statePath(device), content, stateKey(keys.key), Base64.decode(state.header.salt), Base64.decode(state.header.nonce))
        assertEquals(STATE_FILE, Base64.encode(written))
    }
}

// written with a vault password of "correct horse battery staple" by an implementation of the format apart from this one, on
// libsodium; the key of the data is 40..5f
private const val VAULT_FILE =
    "TkhTAAEBm3sia2luZCI6InZhdWx0IiwidmF1bHQiOiI1ZjBjMmE5ZTQxZDdiMzg4NWYwYzJhOWU0MWQ3YjM4OCIsImVwb2NoIjoxLCJhdXRob3IiOiIwMTIz" +
    "NDU2Nzg5YWJjZGVmMDEyMzQ1Njc4OWFiY2RlZiIsInZlcnNpb24iOjEsInNjaGVtYSI6eyJtYWpvciI6MSwibWlub3IiOjB9LCJzYWx0IjoiWUdGaVkyUmxa" +
    "bWRvYVdwcmJHMXViM0J4Y25OMGRYWjNlSGw2ZTN4OWZuOD0iLCJub25jZSI6ImdJR0NnNFNGaG9lSWlZcUxqSTJPajVDUmtwT1VsWmFYIiwiY29tbWl0bWVu" +
    "dCI6InE3bG1ra1gyZENxMlJzY0Jac2x6dmJNZ3RJMUtVejR6ZFo5R2ZBdkNnUGc9IiwicGFzc3dvcmQiOnsic2FsdCI6Ik1ERXlNelExTmpjNE9UbzdQRDAr" +
    "UHc9PSIsInBhc3NlcyI6MSwibWVtb3J5Ijo4MTkyLCJzZWNyZXQiOmZhbHNlfSwicmVhZGVyIjoxfWBR7e19Gqzhn9Q+pFtDZ7mjcKDjzv+ZTQUVqtnWo8Cf" +
    "Cy5NC5vbGjtLCq3af/iYucS9zOe0UdVjDXQwyvQg4WntadlFQFff5mOMd5kLZn5WHSOmnJy+f3gK05VsTxzuFD3ctQkSbR2/FMf4x5vwULtpV18wAYvjOygw" +
    "Ig7WZ3ayKInvy3vID0ovYvdv0gaa8dZelZ9qjP3UFNx4dC2d8mbMsxWpaIMJNYnCfKNBEyGrMgLRydn/yQvAKMM5tB0FjepnS1I0TzMy8CLDrj4Me6KfmK16" +
    "0pF/FILstOb0yJTrSamLOrKLdQohzT0jro3im0H6qx/ifgTb0/0JZZtW/MtothTKK6/NmiXJASse29hQVxFiNlN2bTagFS7LrXX2YvCGSsZW2UOjlFMbPdNS" +
    "nAqUMj8uBDlo1gYUq+K3Yt8NJVbYpqaO34/jpq33h+aBHWzbZz873hFPXEjndC34ESzaWr2xZV2LUfw4fY5DGtRSywsrBuxo1Hel8XtjLAxo8+7tFLIrBbaK" +
    "bq2fiSVHsj5cENDBJocrRlclmZMOvDEAFShzwcyHVB225yoaEI0I9oNbeSWeXf7FuOlyK3q284jfYAe+Z0Yn5iILIN2DtQheJR9q+WBgVChq5QnWBSepX+zj" +
    "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"

private const val STATE_FILE =
    "TkhTAAEBOXsia2luZCI6InN0YXRlIiwidmF1bHQiOiI1ZjBjMmE5ZTQxZDdiMzg4NWYwYzJhOWU0MWQ3YjM4OCIsImVwb2NoIjoxLCJhdXRob3IiOiIwMTIz" +
    "NDU2Nzg5YWJjZGVmMDEyMzQ1Njc4OWFiY2RlZiIsInZlcnNpb24iOjcsInNjaGVtYSI6eyJtYWpvciI6MSwibWlub3IiOjB9LCJzYWx0Ijoib0tHaW82U2xw" +
    "cWVvcWFxcnJLMnVyN0N4c3JPMHRiYTN1TG02dTd5OXZyOD0iLCJub25jZSI6IndNSEN3OFRGeHNmSXljckx6TTNPejlEUjB0UFUxZGJYIiwiY29tbWl0bWVu" +
    "dCI6Im14OGpjU0I2Y2RaNksvYjNQMmxQckdVb0s0N0pRZEp6TlQ3dlhrQWtlRms9In0LOR+Vcul3xlOJikYgP9aMyctriR4TUBy6vwyXiBoC3ddOr6GRjRix" +
    "tKNHE1/UkEolVAc/l5S4aaA7c6NOjiv+vDydBcDVKnLn67aKQSO94Sj2McNu0sh7BvkfhviGurjeS99riE81GM1G4ig9d3O68QIoqCtBEYgAJi8yJZmK4GGe" +
    "UTorayXf59DdWWQrazfFSNUr6VJniAoYT8bY1NGcpyJqV3NNl15JZpkqB1IojqRUR7Z+X2RGUAOveZt8cLSFdK+aBXZFNRNs5ZyorkBeAj3n+zGy8tKAmN2y" +
    "d9Bu3zybsQLho6FTQwrX0j0O/0DD/GBivNJg3n5zFwwQpOgaQzTugvGHc2i8j55IuuZZzyeht+WGgvB81uD6+I7yV6v+qCNYzajVpzS5QG0wb+m9v+MGdvAC" +
    "JAvoAu53RlnovJMC+fffc5E5N/7DLVm8MnPXyzmovBuc426QlfTgVlRGzWMbsg0a0IaCwNz8JSA7qmrenK/NoeMLuWskt+Cq0qrktF434xwUcMP0bJ0Haqg+" +
    "p3OAWXlOqQse4nzCDtpdntVR9G5/mlLknd6q5xBKvWAKLUGZ+GG9n+/N1gIJU9qnuUX/HCRZcVaFo/SYozC8msJhvHiMQqzLCPi24brWef9sQM6dBTlJRPEP" +
    "GE9+NgjIb6BqL/zO0A+OdtimHUnmA+VvvFlGT7bYm3qZrRqM5WeYuUFurt7a1VM35BM6VnUVwd0siRiVbFx/bHFCROAN0GUSILJ+EvQIy3ZFksn+RfIkFmvB" +
    "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"
