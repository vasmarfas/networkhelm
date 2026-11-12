package com.vasmarfas.networkhelm.app

import androidx.compose.ui.ExperimentalComposeUiApi
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import java.nio.file.Files
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class StartupFailureTest {

    @Test
    fun aFailureAtTheStartLeavesItsStackTraceInTheLogFile() {
        val directory = Files.createTempDirectory("startup").toFile()
        try {
            val file = assertNotNull(reportStartupFailure(IllegalStateException("no window"), directory))
            assertEquals(directory, file.parentFile)
            val text = file.readText()
            assertContains(text, "IllegalStateException: no window")
            assertContains(text, "StartupFailureTest")
            assertContains(text, "Java ${System.getProperty("java.version")}")
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun aLinkUnderTheNameOfTheLogIsNotFollowed() {
        val directory = Files.createTempDirectory("startup").toFile()
        try {
            val target = File(directory, "target.txt").also { it.writeText("kept") }
            val link = File(directory, "NetworkHelm-startup-error.log")
            assumeTrue("links cannot be made here", runCatching { Files.createSymbolicLink(link.toPath(), target.toPath()) }.isSuccess)
            assertNull(reportStartupFailure(IllegalStateException("no window"), directory))
            assertEquals("kept", target.readText())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun aDirectoryThatCannotBeWrittenGivesNoFileAndNoException() {
        val directory = Files.createTempDirectory("startup").toFile()
        try {
            assertNull(reportStartupFailure(IllegalStateException("no window"), File(directory, "missing/inside")))
        } finally {
            directory.deleteRecursively()
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun aWindowWhoseFramesKeepFailingWritesTheFirstErrorShowsOneDialogAndClosesOnce() {
        assumeFalse("a window needs a display", GraphicsEnvironment.isHeadless())
        val directory = Files.createTempDirectory("window").toFile()
        val window = Frame()
        var closing = 0
        window.addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) {
                closing++
            }
        })
        val shown = mutableListOf<String>()
        try {
            val failures = windowFailures("written", directory) { _, message -> shown += message }
            for (frame in 1..2) {
                val error = IllegalStateException("frame $frame")
                assertSame(error, assertFailsWith<IllegalStateException> { failures.exceptionHandler(window).onException(error) })
            }
            SwingUtilities.invokeAndWait {}
            assertEquals(listOf("written\n\njava.lang.IllegalStateException: frame 1"), shown)
            assertEquals(1, closing)
            val text = File(directory, "NetworkHelm-error.log").readText()
            assertContains(text, "IllegalStateException: frame 1")
            assertContains(text, "StartupFailureTest")
        } finally {
            window.dispose()
            directory.deleteRecursively()
        }
    }
}
