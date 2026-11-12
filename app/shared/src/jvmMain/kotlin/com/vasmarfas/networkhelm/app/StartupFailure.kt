package com.vasmarfas.networkhelm.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.LocalWindowExceptionHandlerFactory
import androidx.compose.ui.window.WindowExceptionHandler
import androidx.compose.ui.window.WindowExceptionHandlerFactory
import networkhelm.app.shared.generated.resources.Res
import networkhelm.app.shared.generated.resources.window_failed
import org.jetbrains.compose.resources.stringResource
import java.awt.Window
import java.awt.event.WindowEvent
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.time.LocalDateTime
import javax.swing.JOptionPane
import javax.swing.SwingUtilities

private val TEMPORARY = File(System.getProperty("java.io.tmpdir"))

private const val WINDOW_LOG = "NetworkHelm-error.log"

// the launcher of the installed app only says "Failed to launch JVM" when the program dies at its start, so the cause is kept in a file;
// the temporary folder of Linux is shared, so a link left under the name of the file is not followed
fun reportStartupFailure(error: Throwable, directory: File = TEMPORARY): File? = report(error, File(directory, "NetworkHelm-startup-error.log"))

private fun report(error: Throwable, file: File): File? {
    val text = buildString {
        appendLine("${LocalDateTime.now()} NetworkHelm ${System.getProperty("jpackage.app-version").orEmpty()}")
        appendLine("${System.getProperty("os.name")} ${System.getProperty("os.version")}, Java ${System.getProperty("java.version")}")
        appendLine()
        append(error.stackTraceToString())
    }
    return try {
        Files.writeString(file.toPath(), text, CREATE, TRUNCATE_EXISTING, NOFOLLOW_LINKS)
        file
    } catch (e: IOException) {
        null
    }
}

// the dialog of Compose shows only the message of the exception, "Unknown error" when it has none, and nothing while the frames of the
// window keep failing; here the stack trace goes into a file beside the log of the start, and the dialog says where
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun WindowFailures(content: @Composable () -> Unit) {
    val text = stringResource(Res.string.window_failed, File(TEMPORARY, WINDOW_LOG).path)
    CompositionLocalProvider(LocalWindowExceptionHandlerFactory provides remember(text) { windowFailures(text) }, content = content)
}

// Compose asks for a handler at every update of a window, so the windows that have failed are kept here: each reports once, then closes
@OptIn(ExperimentalComposeUiApi::class)
internal fun windowFailures(
    text: String,
    directory: File = TEMPORARY,
    show: (Window?, String) -> Unit = { parent, message ->
        val pane = object : JOptionPane(message, ERROR_MESSAGE) {
            override fun getMaxCharactersPerLineCount() = 100
        }
        pane.createDialog(parent, "NetworkHelm").apply {
            isVisible = true
            dispose()
        }
    },
): WindowExceptionHandlerFactory {
    val failed = mutableSetOf<Window>()
    return WindowExceptionHandlerFactory { window ->
        WindowExceptionHandler { error ->
            if (failed.add(window)) {
                report(error, File(directory, WINDOW_LOG))
                SwingUtilities.invokeLater {
                    show(window.takeIf { it.isDisplayable }, "$text\n\n$error")
                    window.dispatchEvent(WindowEvent(window, WindowEvent.WINDOW_CLOSING))
                }
            }
            throw error
        }
    }
}
