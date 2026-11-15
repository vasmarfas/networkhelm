@file:OptIn(ExperimentalForeignApi::class)

package com.vasmarfas.networkhelm.ssh

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.ssh.libssh2.LIBSSH2_CHANNEL
import com.vasmarfas.networkhelm.ssh.libssh2.LIBSSH2_CHANNEL_PACKET_DEFAULT
import com.vasmarfas.networkhelm.ssh.libssh2.LIBSSH2_CHANNEL_WINDOW_DEFAULT
import com.vasmarfas.networkhelm.ssh.libssh2.LIBSSH2_ERROR_AUTHENTICATION_FAILED
import com.vasmarfas.networkhelm.ssh.libssh2.LIBSSH2_ERROR_EAGAIN
import com.vasmarfas.networkhelm.ssh.libssh2.LIBSSH2_ERROR_PUBLICKEY_UNVERIFIED
import com.vasmarfas.networkhelm.ssh.libssh2.LIBSSH2_ERROR_SOCKET_DISCONNECT
import com.vasmarfas.networkhelm.ssh.libssh2.LIBSSH2_ERROR_SOCKET_RECV
import com.vasmarfas.networkhelm.ssh.libssh2.LIBSSH2_ERROR_SOCKET_SEND
import com.vasmarfas.networkhelm.ssh.libssh2.LIBSSH2_FXF_CREAT
import com.vasmarfas.networkhelm.ssh.libssh2.LIBSSH2_FXF_READ
import com.vasmarfas.networkhelm.ssh.libssh2.LIBSSH2_FXF_TRUNC
import com.vasmarfas.networkhelm.ssh.libssh2.LIBSSH2_FXF_WRITE
import com.vasmarfas.networkhelm.ssh.libssh2.LIBSSH2_HOSTKEY_HASH_SHA256
import com.vasmarfas.networkhelm.ssh.libssh2.LIBSSH2_SESSION
import com.vasmarfas.networkhelm.ssh.libssh2.LIBSSH2_SESSION_BLOCK_INBOUND
import com.vasmarfas.networkhelm.ssh.libssh2.LIBSSH2_SESSION_BLOCK_OUTBOUND
import com.vasmarfas.networkhelm.ssh.libssh2.LIBSSH2_SFTP
import com.vasmarfas.networkhelm.ssh.libssh2.LIBSSH2_SFTP_ATTRIBUTES
import com.vasmarfas.networkhelm.ssh.libssh2.LIBSSH2_SFTP_HANDLE
import com.vasmarfas.networkhelm.ssh.libssh2.LIBSSH2_SFTP_OPENDIR
import com.vasmarfas.networkhelm.ssh.libssh2.LIBSSH2_SFTP_OPENFILE
import com.vasmarfas.networkhelm.ssh.libssh2.LIBSSH2_SFTP_S_IFDIR
import com.vasmarfas.networkhelm.ssh.libssh2.LIBSSH2_SFTP_S_IFMT
import com.vasmarfas.networkhelm.ssh.libssh2.SSH_DISCONNECT_BY_APPLICATION
import com.vasmarfas.networkhelm.ssh.libssh2.libssh2_channel_close
import com.vasmarfas.networkhelm.ssh.libssh2.libssh2_channel_direct_tcpip_ex
import com.vasmarfas.networkhelm.ssh.libssh2.libssh2_channel_eof
import com.vasmarfas.networkhelm.ssh.libssh2.libssh2_channel_free
import com.vasmarfas.networkhelm.ssh.libssh2.libssh2_channel_get_exit_status
import com.vasmarfas.networkhelm.ssh.libssh2.libssh2_channel_open_ex
import com.vasmarfas.networkhelm.ssh.libssh2.libssh2_channel_process_startup
import com.vasmarfas.networkhelm.ssh.libssh2.libssh2_channel_read_ex
import com.vasmarfas.networkhelm.ssh.libssh2.libssh2_channel_request_pty_ex
import com.vasmarfas.networkhelm.ssh.libssh2.libssh2_channel_request_pty_size_ex
import com.vasmarfas.networkhelm.ssh.libssh2.libssh2_channel_send_eof
import com.vasmarfas.networkhelm.ssh.libssh2.libssh2_channel_wait_closed
import com.vasmarfas.networkhelm.ssh.libssh2.libssh2_channel_write_ex
import com.vasmarfas.networkhelm.ssh.libssh2.libssh2_hostkey_hash
import com.vasmarfas.networkhelm.ssh.libssh2.libssh2_session_block_directions
import com.vasmarfas.networkhelm.ssh.libssh2.libssh2_session_disconnect_ex
import com.vasmarfas.networkhelm.ssh.libssh2.libssh2_session_free
import com.vasmarfas.networkhelm.ssh.libssh2.libssh2_session_handshake
import com.vasmarfas.networkhelm.ssh.libssh2.libssh2_session_hostkey
import com.vasmarfas.networkhelm.ssh.libssh2.libssh2_session_init_ex
import com.vasmarfas.networkhelm.ssh.libssh2.libssh2_session_last_errno
import com.vasmarfas.networkhelm.ssh.libssh2.libssh2_session_last_error
import com.vasmarfas.networkhelm.ssh.libssh2.libssh2_session_set_blocking
import com.vasmarfas.networkhelm.ssh.libssh2.libssh2_sftp_close_handle
import com.vasmarfas.networkhelm.ssh.libssh2.libssh2_sftp_init
import com.vasmarfas.networkhelm.ssh.libssh2.libssh2_sftp_mkdir_ex
import com.vasmarfas.networkhelm.ssh.libssh2.libssh2_sftp_open_ex
import com.vasmarfas.networkhelm.ssh.libssh2.libssh2_sftp_read
import com.vasmarfas.networkhelm.ssh.libssh2.libssh2_sftp_readdir_ex
import com.vasmarfas.networkhelm.ssh.libssh2.libssh2_sftp_rename_ex
import com.vasmarfas.networkhelm.ssh.libssh2.libssh2_sftp_rmdir_ex
import com.vasmarfas.networkhelm.ssh.libssh2.libssh2_sftp_shutdown
import com.vasmarfas.networkhelm.ssh.libssh2.libssh2_sftp_unlink_ex
import com.vasmarfas.networkhelm.ssh.libssh2.libssh2_sftp_write
import com.vasmarfas.networkhelm.ssh.libssh2.libssh2_userauth_password_ex
import com.vasmarfas.networkhelm.ssh.libssh2.libssh2_userauth_publickey_frommemory
import com.vasmarfas.networkhelm.transport.ByteStream
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocPointerTo
import kotlinx.cinterop.convert
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.refTo
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.readByteArray
import platform.posix.AF_UNSPEC
import platform.posix.EINPROGRESS
import platform.posix.F_GETFL
import platform.posix.F_SETFL
import platform.posix.O_NONBLOCK
import platform.posix.POLLIN
import platform.posix.POLLOUT
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_ERROR
import platform.posix.SO_NOSIGPIPE
import platform.posix.addrinfo
import platform.posix.connect
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.freeaddrinfo
import platform.posix.gai_strerror
import platform.posix.getaddrinfo
import platform.posix.getsockopt
import platform.posix.memset
import platform.posix.poll
import platform.posix.pollfd
import platform.posix.setsockopt
import platform.posix.socket
import platform.posix.socklen_tVar
import platform.posix.strerror
import kotlin.io.encoding.Base64
import platform.posix.close as closeSocket

actual suspend fun connectSsh(host: String, port: Int, user: String, auth: SshAuth, acceptHostKey: (HostKey) -> Boolean, timeoutMillis: Int): SshSession {
    val socket = withContext(Dispatchers.IO) { openSocket(host, port, timeoutMillis) }
    val session = libssh2_session_init_ex(null, null, null, null)
    if (session == null) {
        closeSocket(socket)
        throw DeviceException(ErrorCategory.General, "libssh2 could not start a session")
    }
    val ssh = Libssh2Session(session, socket)
    try {
        withTimeoutOrNull(timeoutMillis.toLong()) { ssh.call("handshake") { libssh2_session_handshake(session, socket).toLong() } }
            ?: throw IOException("$host:$port did not finish the SSH handshake in $timeoutMillis ms")
        val key = ssh.hostKey()
        if (!acceptHostKey(key)) throw HostKeyRejectedException(key)
        ssh.login(user, auth)
        return ssh
    } catch (e: Throwable) {
        ssh.close()
        throw e
    }
}

private fun openSocket(host: String, port: Int, timeoutMillis: Int): Int = memScoped {
    val hints = alloc<addrinfo>()
    memset(hints.ptr, 0, sizeOf<addrinfo>().convert())
    hints.ai_family = AF_UNSPEC
    hints.ai_socktype = SOCK_STREAM
    val found = allocPointerTo<addrinfo>()
    val resolved = getaddrinfo(host, port.toString(), hints.ptr, found.ptr)
    if (resolved != 0) throw IOException("$host: ${gai_strerror(resolved)?.toKString()}")
    try {
        var address = found.value
        var failure = "no address"
        while (address != null) {
            val info = address.pointed
            val socket = socket(info.ai_family, info.ai_socktype, info.ai_protocol)
            if (socket >= 0) {
                val one = alloc<IntVar>()
                one.value = 1
                setsockopt(socket, SOL_SOCKET, SO_NOSIGPIPE, one.ptr, sizeOf<IntVar>().convert())
                fcntl(socket, F_SETFL, fcntl(socket, F_GETFL) or O_NONBLOCK)
                val reason = if (connect(socket, info.ai_addr, info.ai_addrlen) == 0) null else if (errno != EINPROGRESS) lastError() else connectError(socket, timeoutMillis)
                if (reason == null) return socket
                failure = reason
                closeSocket(socket)
            }
            address = info.ai_next
        }
        throw IOException("$host:$port: $failure")
    } finally {
        freeaddrinfo(found.value)
    }
}

private fun MemScope.connectError(socket: Int, timeoutMillis: Int): String? {
    val wait = alloc<pollfd>()
    wait.fd = socket
    wait.events = POLLOUT.toShort()
    wait.revents = 0
    if (poll(wait.ptr, 1u, timeoutMillis) == 0) return "no answer in $timeoutMillis ms"
    val code = alloc<IntVar>()
    val size = alloc<socklen_tVar>()
    size.value = sizeOf<IntVar>().convert()
    getsockopt(socket, SOL_SOCKET, SO_ERROR, code.ptr, size.ptr)
    return if (code.value == 0) null else strerror(code.value)?.toKString()
}

private fun lastError() = strerror(errno)?.toKString() ?: "errno $errno"

private class Libssh2Session(private val session: CPointer<LIBSSH2_SESSION>, private val socket: Int) : SshSession {
    private val lock = Mutex()

    // OpenSSH opens ten channels on one connection (MaxSessions): commands take six at most, the console and the files the rest
    private val commands = Semaphore(6)

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var freed = false

    init {
        libssh2_session_set_blocking(session, 0)
    }

    suspend fun call(what: String, call: () -> Long): Long {
        while (true) {
            val (result, wait) = locked(what) {
                val result = call()
                when {
                    result == LIBSSH2_ERROR_EAGAIN.toLong() -> result to libssh2_session_block_directions(session)
                    result < 0 -> throw failure(what, result.toInt())
                    else -> result to 0
                }
            }
            if (result != LIBSSH2_ERROR_EAGAIN.toLong()) return result
            await(wait)
        }
    }

    suspend fun <T : CPointed> open(what: String, call: () -> CPointer<T>?): CPointer<T> {
        while (true) {
            val (result, wait) = locked(what) {
                val result = call()
                val code = if (result == null) libssh2_session_last_errno(session) else 0
                when {
                    result != null -> result to 0
                    code == LIBSSH2_ERROR_EAGAIN -> null to libssh2_session_block_directions(session)
                    else -> throw failure(what, code)
                }
            }
            if (result != null) return result
            await(wait)
        }
    }

    suspend fun readSome(channel: CPointer<LIBSSH2_CHANNEL>, stream: Int, buffer: ByteArray, offset: Int, length: Int): Int = locked("read") {
        val read = buffer.usePinned { libssh2_channel_read_ex(channel, stream, it.addressOf(offset), length.convert()) }
        when {
            read > 0 -> read.toInt()
            read == LIBSSH2_ERROR_EAGAIN.toLong() -> 0
            read == 0L -> if (libssh2_channel_eof(channel) != 0) -1 else 0
            else -> throw failure("read", read.toInt())
        }
    }

    suspend fun read(channel: CPointer<LIBSSH2_CHANNEL>, buffer: ByteArray, offset: Int, length: Int): Int {
        while (true) {
            val read = readSome(channel, 0, buffer, offset, length)
            if (read != 0) return read
            await(LIBSSH2_SESSION_BLOCK_INBOUND)
        }
    }

    suspend fun write(channel: CPointer<LIBSSH2_CHANNEL>, bytes: ByteArray) {
        var sent = 0
        while (sent < bytes.size) {
            val written = locked("write") {
                val written = bytes.usePinned { libssh2_channel_write_ex(channel, 0, it.addressOf(sent), (bytes.size - sent).convert()) }
                if (written < 0 && written != LIBSSH2_ERROR_EAGAIN.toLong()) throw failure("write", written.toInt())
                written
            }
            if (written > 0) sent += written.toInt() else await(LIBSSH2_SESSION_BLOCK_OUTBOUND)
        }
    }

    fun release(channel: CPointer<LIBSSH2_CHANNEL>) = later {
        withTimeoutOrNull(5_000) { call("close") { libssh2_channel_close(channel).toLong() } }
        call("free") { libssh2_channel_free(channel).toLong() }
    }

    // nobody waits for this work, and an exception left in a coroutine of its own would end the app on iOS
    fun later(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: DeviceException) {
                return@launch
            }
        }
    }

    suspend fun hostKey(): HostKey = locked("host key") {
        memScoped {
            val length = alloc<ULongVar>()
            val type = alloc<IntVar>()
            val blob = libssh2_session_hostkey(session, length.ptr, type.ptr)?.readBytes(length.value.toInt())
                ?: throw DeviceException(ErrorCategory.General, "the router sent no host key")
            // the key blob starts with its type as an SSH string: a 4-byte length and the name
            val size = (blob[0].toInt() and 0xff shl 24) or (blob[1].toInt() and 0xff shl 16) or (blob[2].toInt() and 0xff shl 8) or (blob[3].toInt() and 0xff)
            val hash = libssh2_hostkey_hash(session, LIBSSH2_HOSTKEY_HASH_SHA256)?.readBytes(32)
                ?: throw DeviceException(ErrorCategory.General, "libssh2 gave no SHA-256 of the host key")
            HostKey(blob.decodeToString(4, 4 + size), "SHA256:" + Base64.withPadding(Base64.PaddingOption.ABSENT).encode(hash))
        }
    }

    suspend fun login(user: String, auth: SshAuth) {
        val name = user.encodeToByteArray()
        when (auth) {
            is SshAuth.Password -> call("login") {
                libssh2_userauth_password_ex(session, user, name.size.convert(), auth.password, auth.password.encodeToByteArray().size.convert(), null).toLong()
            }
            is SshAuth.Key -> call("login") {
                libssh2_userauth_publickey_frommemory(
                    session,
                    user.cstr,
                    name.size.convert(),
                    null,
                    0u,
                    auth.privateKey.refTo(0),
                    auth.privateKey.size.convert(),
                    auth.passphrase?.cstr,
                ).toLong()
            }
            SshAuth.Agent -> throw DeviceException(ErrorCategory.Unsupported, "iOS has no SSH agent")
        }
    }

    private suspend fun channel(): CPointer<LIBSSH2_CHANNEL> = open("channel") {
        libssh2_channel_open_ex(session, "session", 7u, LIBSSH2_CHANNEL_WINDOW_DEFAULT.convert(), LIBSSH2_CHANNEL_PACKET_DEFAULT.convert(), null, 0u)
    }

    private suspend fun <T> started(channel: CPointer<LIBSSH2_CHANNEL>, start: suspend () -> T): T = try {
        start()
    } catch (e: Throwable) {
        release(channel)
        throw e
    }

    override suspend fun exec(command: String): ExecResult = commands.withPermit { execute(command, null) }

    override suspend fun exec(command: String, input: String): ExecResult = commands.withPermit { execute(command, input.encodeToByteArray()) }

    private suspend fun execute(command: String, input: ByteArray?): ExecResult {
        val channel = channel()
        val result = started(channel) {
            call("exec") { libssh2_channel_process_startup(channel, "exec", 4u, command, command.encodeToByteArray().size.convert()).toLong() }
            if (input != null) {
                write(channel, input)
                call("exec") { libssh2_channel_send_eof(channel).toLong() }
            }
            val output = Buffer()
            val error = Buffer()
            val buffer = ByteArray(16_384)
            var ended = false
            while (!ended) {
                val out = readSome(channel, 0, buffer, 0, buffer.size)
                if (out > 0) output.write(buffer, 0, out)
                val err = readSome(channel, 1, buffer, 0, buffer.size)
                if (err > 0) error.write(buffer, 0, err)
                ended = out < 0 && err <= 0
                if (out == 0 && err == 0) await(LIBSSH2_SESSION_BLOCK_INBOUND)
            }
            call("exec") { libssh2_channel_close(channel).toLong() }
            call("exec") { libssh2_channel_wait_closed(channel).toLong() }
            ExecResult(output.readByteArray().decodeToString(), error.readByteArray().decodeToString(), locked("exec") { libssh2_channel_get_exit_status(channel) })
        }
        release(channel)
        return result
    }

    override suspend fun process(command: String): ByteStream {
        val channel = channel()
        return started(channel) {
            call("exec") { libssh2_channel_process_startup(channel, "exec", 4u, command, command.encodeToByteArray().size.convert()).toLong() }
            ChannelStream(this, channel)
        }
    }

    override suspend fun shell(term: String, columns: Int, rows: Int): ShellChannel {
        val channel = channel()
        return started(channel) {
            call("terminal") { libssh2_channel_request_pty_ex(channel, term, term.length.convert(), null, 0u, columns, rows, 0, 0).toLong() }
            call("shell") { libssh2_channel_process_startup(channel, "shell", 5u, null, 0u).toLong() }
            ShellStream(this, channel)
        }
    }

    override suspend fun sftp(): SftpClient = Libssh2Sftp(this, open("sftp") { libssh2_sftp_init(session) })

    override suspend fun forward(host: String, port: Int): ByteStream =
        ChannelStream(this, open("forward") { libssh2_channel_direct_tcpip_ex(session, host, port, "127.0.0.1", 22) })

    override fun close() = later {
        try {
            withTimeoutOrNull(2_000) {
                call("disconnect") { libssh2_session_disconnect_ex(session, SSH_DISCONNECT_BY_APPLICATION, "closed", "").toLong() }
            }
        } finally {
            lock.withLock {
                if (!freed) {
                    freed = true
                    libssh2_session_free(session)
                    closeSocket(socket)
                }
            }
            scope.cancel()
        }
    }

    suspend fun <T> locked(what: String, block: () -> T): T = lock.withLock {
        if (freed) throw DeviceException(ErrorCategory.ConnectionLost, "$what: the SSH session is closed")
        block()
    }

    fun failure(what: String, code: Int): DeviceException {
        val message = memScoped {
            val text = allocPointerTo<ByteVar>()
            libssh2_session_last_error(session, text.ptr, null, 0)
            text.value?.toKString().orEmpty()
        }
        val category = when (code) {
            LIBSSH2_ERROR_AUTHENTICATION_FAILED, LIBSSH2_ERROR_PUBLICKEY_UNVERIFIED -> ErrorCategory.PermissionDenied
            LIBSSH2_ERROR_SOCKET_DISCONNECT, LIBSSH2_ERROR_SOCKET_RECV, LIBSSH2_ERROR_SOCKET_SEND -> ErrorCategory.ConnectionLost
            else -> ErrorCategory.General
        }
        return DeviceException(category, "$what: $message ($code)")
    }

    // a channel may take the data of another one off the socket into libssh2's buffers, so the wait is short
    suspend fun await(directions: Int) = withContext(Dispatchers.IO) {
        memScoped {
            val wait = alloc<pollfd>()
            wait.fd = socket
            wait.events = ((if (directions and LIBSSH2_SESSION_BLOCK_INBOUND != 0) POLLIN else 0) or
                (if (directions and LIBSSH2_SESSION_BLOCK_OUTBOUND != 0) POLLOUT else 0)).toShort()
            wait.revents = 0
            poll(wait.ptr, 1u, 20)
        }
    }

    suspend fun resize(channel: CPointer<LIBSSH2_CHANNEL>, columns: Int, rows: Int) =
        call("terminal") { libssh2_channel_request_pty_size_ex(channel, columns, rows, 0, 0).toLong() }
}

private open class ChannelStream(protected val ssh: Libssh2Session, protected val channel: CPointer<LIBSSH2_CHANNEL>) : ByteStream {
    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int = ssh.read(channel, buffer, offset, length)

    override suspend fun write(bytes: ByteArray) = ssh.write(channel, bytes)

    override fun close() = ssh.release(channel)
}

private class ShellStream(ssh: Libssh2Session, channel: CPointer<LIBSSH2_CHANNEL>) : ChannelStream(ssh, channel), ShellChannel {
    override fun resize(columns: Int, rows: Int) = ssh.later { ssh.resize(channel, columns, rows) }
}

private class Libssh2Sftp(private val ssh: Libssh2Session, private val sftp: CPointer<LIBSSH2_SFTP>) : SftpClient {
    override suspend fun list(path: String): List<RemoteFile> {
        val directory = handle(path, 0uL, 0L, LIBSSH2_SFTP_OPENDIR)
        try {
            val files = mutableListOf<RemoteFile>()
            val name = ByteArray(1024)
            memScoped {
                val attributes = alloc<LIBSSH2_SFTP_ATTRIBUTES>()
                while (true) {
                    val length = ssh.call("list") {
                        name.usePinned { libssh2_sftp_readdir_ex(directory, it.addressOf(0), name.size.convert(), null, 0uL, attributes.ptr) }.toLong()
                    }
                    if (length == 0L) break
                    val entry = name.decodeToString(0, length.toInt())
                    if (entry == "." || entry == "..") continue
                    val directoryFlag = attributes.permissions and LIBSSH2_SFTP_S_IFMT.convert() == LIBSSH2_SFTP_S_IFDIR.convert<ULong>()
                    files += RemoteFile(entry, attributes.filesize.toLong(), directoryFlag, attributes.mtime.toLong())
                }
            }
            return files
        } finally {
            closeHandle(directory)
        }
    }

    override suspend fun read(path: String): ByteArray {
        val file = handle(path, LIBSSH2_FXF_READ.convert(), 0L, LIBSSH2_SFTP_OPENFILE)
        try {
            val content = Buffer()
            val buffer = ByteArray(32_768)
            while (true) {
                val read = ssh.call("read") { buffer.usePinned { libssh2_sftp_read(file, it.addressOf(0), buffer.size.convert()) } }
                if (read == 0L) break
                content.write(buffer, 0, read.toInt())
            }
            return content.readByteArray()
        } finally {
            closeHandle(file)
        }
    }

    override suspend fun write(path: String, bytes: ByteArray) {
        // rw-r--r--, as a file put by the router's own SFTP server
        val file = handle(path, (LIBSSH2_FXF_WRITE or LIBSSH2_FXF_CREAT or LIBSSH2_FXF_TRUNC).convert(), 420L, LIBSSH2_SFTP_OPENFILE)
        try {
            var sent = 0
            while (sent < bytes.size) {
                val written = ssh.call("write") { bytes.usePinned { libssh2_sftp_write(file, it.addressOf(sent), (bytes.size - sent).convert()) } }
                sent += written.toInt()
            }
        } finally {
            closeHandle(file)
        }
    }

    override suspend fun remove(path: String) {
        try {
            ssh.call("remove") { libssh2_sftp_unlink_ex(sftp, path, path.encodeToByteArray().size.convert()).toLong() }
        } catch (e: DeviceException) {
            ssh.call("remove") { libssh2_sftp_rmdir_ex(sftp, path, path.encodeToByteArray().size.convert()).toLong() }
        }
    }

    override suspend fun rename(from: String, to: String) {
        ssh.call("rename") {
            libssh2_sftp_rename_ex(sftp, from, from.encodeToByteArray().size.convert(), to, to.encodeToByteArray().size.convert(), 0L).toLong()
        }
    }

    override suspend fun mkdir(path: String) {
        // rwxr-xr-x
        ssh.call("mkdir") { libssh2_sftp_mkdir_ex(sftp, path, path.encodeToByteArray().size.convert(), 493L).toLong() }
    }

    override fun close() = ssh.later { ssh.call("sftp") { libssh2_sftp_shutdown(sftp).toLong() } }

    private suspend fun handle(path: String, flags: ULong, mode: Long, type: Int): CPointer<LIBSSH2_SFTP_HANDLE> = ssh.open(path) {
        libssh2_sftp_open_ex(sftp, path, path.encodeToByteArray().size.convert(), flags, mode, type)
    }

    private suspend fun closeHandle(handle: CPointer<LIBSSH2_SFTP_HANDLE>) {
        ssh.call("close") { libssh2_sftp_close_handle(handle).toLong() }
    }
}
