package com.vasmarfas.networkhelm.sync

import com.vasmarfas.networkhelm.model.DeviceException
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// a service on its fake, and what others do to the files behind it past the service: another device, a client of a cloud, the user.
// Paths are from networkhelm-sync and may be any name
internal class Fake(
    val name: String,
    val service: SyncService,
    val put: suspend (String, ByteArray) -> Unit,
    val remove: suspend (String) -> Unit,
)

internal const val FIRST_DEVICE = "0f8c4a1b2c3d4e5f60718293a4b5c6d7"
internal const val SECOND_DEVICE = "9a8b7c6d5e4f30211203f4e5d6c7b8a9"

private val ONE = "one".encodeToByteArray()
private val TWO = "two".encodeToByteArray()
private val THREE = "three".encodeToByteArray()

// the checks every service passes on its fake

internal suspend fun Fake.aFileGoesInComesOutAndGoesAway() {
    assertEquals(emptyList<String>(), service.list().map { it.path }, name)
    assertNull(service.read(VAULT), name)
    service.write(VAULT, ONE)
    service.write(statePath(FIRST_DEVICE), TWO)
    service.write(devicePath(FIRST_DEVICE), THREE)
    val listed = service.list().associate { it.path to it.tag }
    assertEquals(setOf(VAULT, statePath(FIRST_DEVICE), devicePath(FIRST_DEVICE)), listed.keys, name)
    assertContentEquals(TWO, service.read(statePath(FIRST_DEVICE))?.bytes, name)

    service.write(statePath(FIRST_DEVICE), THREE)
    val again = service.list().associate { it.path to it.tag }
    assertNotEquals(listed[statePath(FIRST_DEVICE)], again[statePath(FIRST_DEVICE)], name)
    assertEquals(listed[VAULT], again[VAULT], name)

    service.delete(statePath(FIRST_DEVICE))
    service.delete(statePath(FIRST_DEVICE))
    assertNull(service.read(statePath(FIRST_DEVICE)), name)
    assertEquals(setOf(VAULT, devicePath(FIRST_DEVICE)), service.list().map { it.path }.toSet(), name)
}

internal suspend fun Fake.theVaultIsWrittenWhereNoneIsOrOverTheOneReadBefore() {
    assertTrue(service.writeIf(VAULT, ONE, null), name)
    assertFalse(service.writeIf(VAULT, TWO, null), name)
    val read = assertNotNull(service.read(VAULT), name)
    assertContentEquals(ONE, read.bytes, name)

    // another device changed it after this one read it
    put(VAULT, THREE)
    assertFalse(service.writeIf(VAULT, TWO, read), name)
    val again = assertNotNull(service.read(VAULT), name)
    assertContentEquals(THREE, again.bytes, name)
    assertTrue(service.writeIf(VAULT, TWO, again), name)
    val last = assertNotNull(service.read(VAULT), name)
    assertContentEquals(TWO, last.bytes, name)

    remove(VAULT)
    assertFalse(service.writeIf(VAULT, ONE, last), name)
    assertNull(service.read(VAULT), name)
}

// what the list showed is gone or changed when the device comes to read it: the read gives what is there then
internal suspend fun Fake.aFileListedIsReadAsItIsWhenTheReadComes() {
    service.write(statePath(FIRST_DEVICE), ONE)
    service.write(statePath(SECOND_DEVICE), TWO)
    val listed = service.list().associate { it.path to it.tag }
    remove(statePath(FIRST_DEVICE))
    put(statePath(SECOND_DEVICE), THREE)
    assertNull(service.read(statePath(FIRST_DEVICE)), name)
    assertContentEquals(THREE, service.read(statePath(SECOND_DEVICE))?.bytes, name)
    assertNotEquals(listed[statePath(SECOND_DEVICE)], service.list().single().tag, name)
}

// breakNext makes the next exchange with the service break halfway
internal suspend fun Fake.aConnectionBrokenHalfwayFailsAndLeavesTheFileAsItWas(breakNext: () -> Unit) {
    service.write(statePath(FIRST_DEVICE), ONE)
    breakNext()
    assertFailsWith<DeviceException>(name) { service.write(statePath(FIRST_DEVICE), TWO) }
    breakNext()
    assertFailsWith<DeviceException>(name) { service.read(statePath(FIRST_DEVICE)) }
    assertContentEquals(ONE, service.read(statePath(FIRST_DEVICE))?.bytes, name)
}

internal suspend fun Fake.copiesOfConflictsAndFilesOfOthersAreNotListed() {
    service.write(VAULT, ONE)
    service.write(statePath(FIRST_DEVICE), TWO)
    val others = listOf(
        "state/$FIRST_DEVICE.sync-conflict-20261117-101500-7XQ4ZPA",
        "state/$FIRST_DEVICE (conflicted copy 2026-11-17)",
        "state/$FIRST_DEVICE-DESKTOP-4F2K",
        "state/${FIRST_DEVICE}_conflict-20261117-101500",
        "state/$FIRST_DEVICE (2)",
        "state/.${FIRST_DEVICE}8301547762.tmp",
        "state/0f8c4a1b",
        "vault (2)",
        "vault.sync-conflict-20261117-101500-7XQ4ZPA",
        ".stfolder",
        "desktop.ini",
        "devices/.DS_Store",
    )
    for (other in others) put(other, THREE)
    assertEquals(setOf(VAULT, statePath(FIRST_DEVICE)), service.list().map { it.path }.toSet(), name)
}

// a client of a cloud or the service gives back a copy from before: its tag differs from the last one, so the device reads it, and
// the version in it is refused as older than the one read before
internal suspend fun Fake.aCopyGivenBackFromBeforeIsRefused() {
    val keys = Keys("v1", 1, ByteArray(32), ByteArray(32) { 3 })
    val first = writeState(keys, FIRST_DEVICE, 1, emptyList())
    val second = writeState(keys, FIRST_DEVICE, 2, emptyList())
    service.write(statePath(FIRST_DEVICE), first)
    service.write(statePath(FIRST_DEVICE), second)
    val tag = service.list().single().tag
    val newest = assertNotNull(service.read(statePath(FIRST_DEVICE)), name)
    val read = readState(keys, FIRST_DEVICE, newest.bytes).header.version
    val seen = Seen().with(statePath(FIRST_DEVICE), read)

    put(statePath(FIRST_DEVICE), first)
    assertNotEquals(tag, service.list().single().tag, name)
    val back = assertNotNull(service.read(statePath(FIRST_DEVICE)), name)
    val version = readState(keys, FIRST_DEVICE, back.bytes).header.version
    assertFailsWith<DeviceException>(name) { seen.check(statePath(FIRST_DEVICE), version) }
}
