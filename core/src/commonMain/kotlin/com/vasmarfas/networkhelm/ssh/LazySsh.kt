package com.vasmarfas.networkhelm.ssh

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile

class LazySsh(private val probe: (suspend () -> SshReach)? = null, private val open: suspend () -> SshSession) : SshSession {
    private val lock = Mutex()
    private val probing = Mutex()
    @Volatile
    private var session: SshSession? = null
    @Volatile
    private var known: SshReach? = null
    @Volatile
    private var closed = false

    private suspend fun session() = lock.withLock {
        session?.takeIf { it.connected } ?: open().also {
            session?.close()
            session = it
            known = SshReach.Open
            if (closed) it.close()
        }
    }

    override suspend fun reach(fresh: Boolean): SshReach {
        known?.takeIf { !fresh }?.let { return it }
        return probing.withLock {
            known?.takeIf { !fresh } ?: (probe?.invoke() ?: SshReach.Open).also { known = it }
        }
    }

    override suspend fun exec(command: String) = session().exec(command)

    override suspend fun exec(command: String, input: String) = session().exec(command, input)

    override suspend fun process(command: String) = session().process(command)

    override suspend fun shell(term: String, columns: Int, rows: Int) = session().shell(term, columns, rows)

    override suspend fun sftp() = session().sftp()

    override suspend fun forward(host: String, port: Int) = session().forward(host, port)

    override fun close() {
        closed = true
        session?.close()
    }
}
