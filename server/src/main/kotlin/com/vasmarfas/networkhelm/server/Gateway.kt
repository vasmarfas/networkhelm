package com.vasmarfas.networkhelm.server

import com.vasmarfas.networkhelm.routeros.schema.FileSchemaCache
import com.vasmarfas.networkhelm.transport.FileTrustStore
import io.ktor.network.tls.certificates.buildKeyStore
import io.ktor.network.tls.certificates.saveToFile
import kotlinx.coroutines.Job
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.UnknownHostException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyStore
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.concurrent.Semaphore

@Serializable
data class Account(
    val user: String,
    val salt: String,
    val check: String,
    val keyStorePassword: String,
    val totp: String? = null,
    val confirmed: Boolean = false,
) {
    val otpauth: String? get() = totp?.let { "otpauth://totp/NetworkHelm:$user?secret=$it&issuer=NetworkHelm" }
}

class Session(val expires: Long, val master: Master) {
    internal val connections = HashSet<Job>()
}

fun madeUpPassword(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(15))

sealed interface Login {
    class In(val token: String) : Login

    class SetUp(val otpauth: String) : Login

    data object Refused : Login
}

class Gateway private constructor(val home: File, val public: Boolean, val asksCode: Boolean, private var account: Account, private val now: () -> Long, private val derive: (String, ByteArray) -> Master) {
    private var totp = totpOf(account)
    private val sessions = HashMap<String, Session>()
    private val failures = HashMap<String, List<Long>>()
    private val derivations = Semaphore(2, true)
    val trust = FileTrustStore(File(home, "known-routers"))
    val schemas = FileSchemaCache(File(home, "schema"))

    private fun totpOf(account: Account) = account.totp?.takeIf { asksCode }?.let { Totp(fromBase32(it), now) }

    private var checked = 0L
    private var stamp = 0L

    // the console edits gateway.json behind a running gateway; every message asks, so the file is read when it changes and every few seconds
    private fun refresh() {
        val modified = File(home, "gateway.json").lastModified()
        val time = now()
        if (modified == stamp && time - checked < REREAD) return
        stamp = modified
        checked = time
        val changed = Gateway.account(home)?.takeIf { it != account } ?: return
        account = changed
        totp = totpOf(changed)
        sessions.values.forEach(::revoke)
        sessions.clear()
    }

    val settingUp: Boolean
        @Synchronized get() {
            refresh()
            return totp != null && !account.confirmed
        }

    fun login(address: String, user: String, password: String, code: String): Login {
        val time: Long
        val expected = synchronized(this) {
            refresh()
            time = now()
            sessions.values.removeAll {
                (it.expires <= time).also { expired -> if (expired) revoke(it) }
            }
            failures.values.removeAll { times -> times.all { time - it >= WINDOW } }
            val recent = failures[address].orEmpty().filter { time - it < WINDOW }
            if (recent.size >= ATTEMPTS) {
                journal(time, address, user, "locked")
                return Login.Refused
            }
            failures[address] = recent + time
            account
        }
        derivations.acquire()
        val master = try {
            derive(password, Base64.getDecoder().decode(expected.salt))
        } finally {
            derivations.release()
        }
        return synchronized(this) {
            refresh()
            val totp = totp
            val result = when {
                user != account.user || !MessageDigest.isEqual(master.check, Base64.getDecoder().decode(account.check)) -> "password"
                totp != null && !account.confirmed && code.isBlank() -> "set-up"
                totp != null && !totp.accept(code) -> "code"
                else -> "ok"
            }
            journal(time, address, user, result)
            when (result) {
                "ok" -> Unit
                "set-up" -> {
                    failures[address] = failures[address].orEmpty().toMutableList().apply { remove(time) }
                    return Login.SetUp(account.otpauth ?: error("no secret"))
                }
                else -> return Login.Refused
            }
            failures.remove(address)
            if (totp != null && !account.confirmed) account = write(home, account.copy(confirmed = true))
            val token = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(32))
            sessions[token] = Session(now() + SESSION, master)
            Login.In(token)
        }
    }

    @Synchronized
    fun session(token: String?): Session? {
        refresh()
        val session = token?.let(sessions::get) ?: return null
        if (session.expires > now()) return session
        sessions.remove(token)
        revoke(session)
        return null
    }

    @Synchronized
    fun attach(token: String?, job: Job): Session? = session(token)?.also { it.connections += job }

    @Synchronized
    fun detach(session: Session, job: Job) {
        session.connections -= job
    }

    private fun revoke(session: Session) {
        session.connections.toList().forEach { it.cancel() }
        session.connections.clear()
    }

    @Synchronized
    fun logout(token: String?) {
        sessions.remove(token)?.let(::revoke)
    }

    fun keyStore(): KeyStore {
        val file = File(home, "gateway.jks")
        if (file.isFile) return KeyStore.getInstance(file, account.keyStorePassword.toCharArray())
        val keyStore = buildKeyStore {
            certificate(KEY_ALIAS) {
                password = account.keyStorePassword
                daysValid = 3650
                domains = listOf("localhost") + hostName()
                ipAddresses = addresses()
            }
        }
        keyStore.saveToFile(file, account.keyStorePassword)
        ownerOnly(file)
        return keyStore
    }

    val keyStorePassword: CharArray get() = account.keyStorePassword.toCharArray()

    private fun journal(time: Long, address: String, user: String, result: String) {
        File(home, "logins.log").appendText("${Instant.ofEpochMilli(time)} $address ${user.filter { !it.isISOControl() && it != ' ' }.take(64)} $result\n")
    }

    companion object {
        const val COOKIE = "networkhelm"
        const val KEY_ALIAS = "networkhelm"
        private const val ATTEMPTS = 5
        private const val WINDOW = 10 * 60_000L
        private const val SESSION = 12 * 60 * 60_000L
        private const val REREAD = 5_000L
        private val json = Json { prettyPrint = true }

        fun open(home: File, public: Boolean, code: Boolean, password: String?, announce: (String) -> Unit, now: () -> Long = System::currentTimeMillis, derive: (String, ByteArray) -> Master = Master::of): Gateway {
            var account = account(home)
            if (account == null) {
                val chosen = password ?: madeUpPassword()
                account = setPassword(home, chosen)
                if (password == null) announce("The password of admin, shown this once: $chosen")
            }
            val asksCode = code || public
            if (asksCode && account.totp == null) account = newCode(home)
            if (asksCode && !account.confirmed) {
                announce("The code for an authenticator app, until a login takes one of its codes (the login page shows it too): ${account.otpauth}")
            }
            return Gateway(home, public, asksCode, account, now, derive)
        }

        fun account(home: File): Account? = File(home, "gateway.json").takeIf { it.isFile }?.let { json.decodeFromString(Account.serializer(), it.readText()) }

        fun setPassword(home: File, password: String): Account = write(home, accountOf(account(home), password).first)

        fun changePassword(home: File, current: String?, password: String, announce: (String) -> Unit): Account {
            val old = account(home) ?: return setPassword(home, password).also {
                announce("The account of admin is made.")
                if (FileGatewayStore.putAside(home)) announce("secrets.json of an earlier account is put aside as secrets.json.old.")
            }
            val decoder = Base64.getDecoder()
            val from = current?.let { Master.of(it, decoder.decode(old.salt)) }
            require(from == null || MessageDigest.isEqual(from.check, decoder.decode(old.check))) { "the current password is not right" }
            val (renewed, to) = accountOf(old, password)
            val carried = from?.let { FileGatewayStore.reseal(home, it.sealing, to.sealing) }
            write(home, renewed)
            when {
                carried != null && carried > 0 -> announce("The saved passwords of routers ($carried) passed to the new password.")
                carried == null && FileGatewayStore.putAside(home) -> announce("The saved passwords of routers cannot be opened without the old password: secrets.json is put aside as secrets.json.old.")
            }
            return renewed
        }

        private fun accountOf(old: Account?, password: String): Pair<Account, Master> {
            val salt = randomBytes(16)
            val master = Master.of(password, salt)
            val encoder = Base64.getEncoder()
            val check = encoder.encodeToString(master.check)
            val account = old?.copy(salt = encoder.encodeToString(salt), check = check)
                ?: Account("admin", encoder.encodeToString(salt), check, encoder.encodeToString(randomBytes(18)))
            return account to master
        }

        fun newCode(home: File): Account = write(home, (account(home) ?: error("the gateway has no account yet")).copy(totp = base32(randomBytes(20)), confirmed = false))

        private fun write(home: File, account: Account): Account {
            home.mkdirs()
            val temporary = File(home, ".gateway.json.new")
            temporary.writeText(json.encodeToString(Account.serializer(), account))
            ownerOnly(temporary)
            Files.move(temporary.toPath(), File(home, "gateway.json").toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            return account
        }

        fun fingerprint(keyStore: KeyStore): String =
            MessageDigest.getInstance("SHA-256").digest(keyStore.getCertificate(KEY_ALIAS).encoded).joinToString(":") { "%02X".format(it) }

        // run as root for the service of another user, the file still belongs to the owner of the folder
        fun ownerOnly(file: File) {
            if ("posix" !in FileSystems.getDefault().supportedFileAttributeViews()) return
            Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------"))
            if (System.getProperty("user.name") == "root") Files.setOwner(file.toPath(), Files.getOwner(file.parentFile.toPath()))
        }

        private fun hostName(): List<String> = try {
            listOf(InetAddress.getLocalHost().hostName).filter { it != "localhost" }
        } catch (e: UnknownHostException) {
            emptyList()
        }

        private fun addresses(): List<InetAddress> =
            NetworkInterface.networkInterfaces().toList().flatMap { it.inetAddresses().toList() }.filter { !it.isLinkLocalAddress }
    }
}
