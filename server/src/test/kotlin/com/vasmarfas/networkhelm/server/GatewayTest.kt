package com.vasmarfas.networkhelm.server

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GatewayTest {

    @Test
    fun aLockedAddressDoesNotDeriveAnotherPasswordKey() {
        val calls = AtomicInteger()
        val home = createTempDirectory("rd-test-gateway-limit").toFile()
        val gateway = Gateway.open(home, public = false, code = false, password = "rd-test-password", announce = {}, derive = { _, _ ->
            calls.incrementAndGet()
            Master(ByteArray(32))
        })
        repeat(8) { assertEquals(Login.Refused, gateway.login("192.0.2.1", "admin", "wrong", "")) }
        assertEquals(5, calls.get())
    }

    @Test
    fun aLockIsWrittenDownOnceHoweverManyLoginsItRefuses() {
        var clock = 1_800_000_000_000L
        val home = createTempDirectory("rd-test-gateway-limit").toFile()
        val gateway = Gateway.open(home, public = false, code = false, password = "rd-test-password", announce = {}, now = { clock }, derive = { _, _ -> Master(ByteArray(32)) })
        fun written() = File(home, "logins.log").readLines().map { it.substringAfterLast(' ') }
        repeat(4) { gateway.login("192.0.2.1", "admin", "wrong", "") }
        clock += 60_000L
        repeat(4) { gateway.login("192.0.2.1", "admin", "wrong", "") }
        assertEquals(List(5) { "password" } + "locked", written())
        clock += 9 * 60_000L
        repeat(8) { gateway.login("192.0.2.1", "admin", "wrong", "") }
        assertEquals(List(5) { "password" } + "locked" + List(4) { "password" } + "locked", written())
    }

    @Test
    fun concurrentFailuresReserveTheirAttemptsAndLimitPasswordDerivations() {
        val calls = AtomicInteger()
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val home = createTempDirectory("rd-test-gateway-limit").toFile()
        val gateway = Gateway.open(home, public = false, code = false, password = "rd-test-password", announce = {}, derive = { _, _ ->
            calls.incrementAndGet()
            maximum.accumulateAndGet(active.incrementAndGet(), ::maxOf)
            entered.countDown()
            try {
                check(release.await(5, TimeUnit.SECONDS))
                Master(ByteArray(32))
            } finally {
                active.decrementAndGet()
            }
        })
        val threads = Executors.newFixedThreadPool(12)
        try {
            val results = List(12) { threads.submit<Login> { gateway.login("192.0.2.1", "admin", "wrong", "") } }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            release.countDown()
            results.forEach { assertEquals(Login.Refused, it.get(5, TimeUnit.SECONDS)) }
            assertEquals(5, calls.get())
            assertEquals(2, maximum.get())
        } finally {
            release.countDown()
            threads.shutdownNow()
        }
    }

    @Test
    fun checkingASessionDoesNotWaitForAPasswordDerivation() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val home = createTempDirectory("rd-test-gateway-limit").toFile()
        val gateway = Gateway.open(home, public = false, code = false, password = "rd-test-password", announce = {}, derive = { password, salt ->
            if (password == "slow") {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            Master.of(password, salt)
        })
        val token = (gateway.login("192.0.2.1", "admin", "rd-test-password", "") as Login.In).token
        val threads = Executors.newFixedThreadPool(2)
        try {
            val login = threads.submit<Login> { gateway.login("192.0.2.2", "admin", "slow", "") }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertNotNull(threads.submit<Session?> { gateway.session(token) }.get(1, TimeUnit.SECONDS))
            release.countDown()
            assertEquals(Login.Refused, login.get(5, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            threads.shutdownNow()
        }
    }
}
