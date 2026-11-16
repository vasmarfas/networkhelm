package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.openwrt.quote
import com.vasmarfas.networkhelm.ssh.ExecResult
import com.vasmarfas.networkhelm.ssh.SshSession

// a command as root: the login is root, sudo lets the login in without a password, or sudo -S takes the password of the login
internal class Root(private val ssh: SshSession, private val password: String?) {
    private enum class Way { Login, Sudo, Password }

    private var way: Way? = null

    suspend fun exec(command: String): ExecResult = when (way()) {
        Way.Login -> ssh.exec(command)
        Way.Sudo -> ssh.exec("sudo -n sh -c ${quote(command)}")
        Way.Password -> ssh.exec("sudo -S -p '' sh -c ${quote(command)}", "$password\n")
    }

    // the login is root itself: its commands leave no line of sudo in the log
    suspend fun login(): Boolean = way() == Way.Login

    suspend fun output(command: String): String {
        val result = exec(command)
        if (result.exitStatus != 0) throw DeviceException(ErrorCategory.Api, result.error.trim().ifEmpty { result.output.trim() }.ifEmpty { "exit ${result.exitStatus}" })
        return result.output
    }

    private suspend fun way(): Way = way ?: when {
        ssh.exec("id -u").output.trim() == "0" -> Way.Login
        else -> {
            val sudo = ssh.exec("sudo -n true")
            when {
                sudo.exitStatus == 0 -> Way.Sudo
                sudo.exitStatus == 127 -> throw DeviceException(ErrorCategory.PermissionDenied, "this needs root, and the host has no sudo")
                password == null -> throw DeviceException(ErrorCategory.PermissionDenied, "this needs root: sudo asks for a password, and the login went with a key")
                ssh.exec("sudo -S -p '' true", "$password\n").exitStatus == 0 -> Way.Password
                else -> throw DeviceException(ErrorCategory.PermissionDenied, "this needs root, and sudo did not take the password of the login")
            }
        }
    }.also { way = it }
}

// the words of a line as sh splits them: quotes keep spaces, a backslash keeps the character after it
internal fun words(line: String): List<String> {
    val words = mutableListOf<String>()
    val word = StringBuilder()
    var quote: Char? = null
    var open = false
    var escaped = false
    for (c in line) {
        when {
            escaped -> {
                word.append(c)
                escaped = false
            }
            quote == '\'' -> if (c == '\'') quote = null else word.append(c)
            c == '\\' -> {
                escaped = true
                open = true
            }
            quote == '"' -> if (c == '"') quote = null else word.append(c)
            c == '\'' || c == '"' -> {
                quote = c
                open = true
            }
            c.isWhitespace() -> if (open) {
                words += word.toString()
                word.clear()
                open = false
            }
            else -> {
                word.append(c)
                open = true
            }
        }
    }
    if (quote != null || escaped) throw DeviceException(ErrorCategory.ArgumentValue, "a quote is left open in $line")
    if (open) words += word.toString()
    return words
}
