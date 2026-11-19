package com.vasmarfas.networkhelm.sync

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import io.ktor.http.Url
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlin.io.encoding.Base64

enum class GitHost { GitHub, Gitea, GitLab }

// a file or a directory as the API lists it: GitHub and Gitea name a blob file with its sha, GitLab names it blob with its id
@Serializable
private class Entry(val name: String = "", val path: String = "", val type: String = "", val sha: String = "", val id: String = "")

@Serializable
private class Content(val content: String? = null, val sha: String = "", val size: Long = 0, @SerialName("last_commit_id") val lastCommit: String = "")

// a commit of one file on GitHub and Gitea, which take the blob it replaces
@Serializable
private class Change(val branch: String, val message: String, val content: String? = null, val sha: String? = null)

// a commit of one file on GitLab, which takes the last commit of the file it replaces
@Serializable
private class Commit(
    val branch: String,
    @SerialName("commit_message") val message: String,
    val content: String,
    val encoding: String = "base64",
    @SerialName("last_commit_id") val lastCommit: String? = null,
)

@Serializable
private class Refusal(val message: String = "")

// a repository on GitHub, Gitea (Forgejo as well) or GitLab, reached with a token for this repository alone; each write and delete is a
// commit to the branch. Api is the address of the API: https://api.github.com, https://<server>/api/v1, https://<server>/api/v4
class GitService(
    private val http: SyncHttp,
    private val host: GitHost,
    api: String,
    owner: String,
    repository: String,
    private val branch: String,
    token: String,
) : SyncService {
    private val server = Url(api).host
    private val base = api.trimEnd('/') + if (host == GitHost.GitLab) "/projects/${"$owner/$repository".encodeURLParameter()}/repository" else "/repos/$owner/$repository"
    private val ref = "ref=${branch.encodeURLParameter()}"
    private val login = mapOf("Authorization" to (if (host == GitHost.Gitea) "token $token" else "Bearer $token"), "Accept" to "application/json")

    override suspend fun list(): List<Listed> {
        val listed = if (host == GitHost.GitLab) tree() else listOf("", "devices/", "state/").flatMap { contents(it) }
        return listed.filter { syncFile(it.path) }
    }

    override suspend fun read(path: String): Stored? {
        val reply = send("GET", "${file(path)}?$ref")
        if (reply.status == 404) return null
        val content = parsed(reply, Content.serializer())
        if (host == GitHost.GitLab) return Stored(Base64.Mime.decode(content.content.orEmpty()), content.lastCommit)
        // GitHub sends a file over 1 MB without its content, and Gitea one over its limit: the blob holds it
        if (content.content.isNullOrEmpty() && content.size > 0) {
            val blob = send("GET", "$base/git/blobs/${content.sha}")
            return Stored(Base64.Mime.decode(parsed(blob, Content.serializer()).content.orEmpty()), content.sha)
        }
        return Stored(Base64.Mime.decode(content.content.orEmpty()), content.sha)
    }

    // only this device writes its files, so another version comes between the read and the commit only from a second copy of the app
    override suspend fun write(path: String, bytes: ByteArray) {
        repeat(2) {
            val current = read(path)
            if (writeIf(path, bytes, current)) return
        }
        throw DeviceException(ErrorCategory.General, "$path was changed on the service while it was written")
    }

    override suspend fun writeIf(path: String, bytes: ByteArray, over: Stored?): Boolean {
        val content = Base64.encode(bytes)
        val message = "$SYNC_FOLDER/$path"
        val reply = when (host) {
            GitHost.GitLab -> send(if (over == null) "POST" else "PUT", file(path), fileJson.encodeToString(Commit.serializer(), Commit(branch, message, content, lastCommit = over?.tag)))
            // Gitea makes a file with POST and changes it with PUT, GitHub does both with PUT
            GitHost.Gitea -> send(if (over == null) "POST" else "PUT", file(path), fileJson.encodeToString(Change.serializer(), Change(branch, message, content, over?.tag)))
            GitHost.GitHub -> send("PUT", file(path), fileJson.encodeToString(Change.serializer(), Change(branch, message, content, over?.tag)))
        }
        if (reply.status in 200..299) return true
        // each host tells of another version in its own way, 409, 422, 400 or 404: what the branch holds now decides
        if (reply.status !in 400..499) throw failed(reply)
        val current = read(path)
        if (if (over == null) current != null else current?.tag != over.tag) return false
        throw failed(reply)
    }

    override suspend fun delete(path: String) {
        val current = read(path) ?: return
        val message = "$SYNC_FOLDER/$path"
        val reply = if (host == GitHost.GitLab) {
            send("DELETE", "${file(path)}?branch=${branch.encodeURLParameter()}&commit_message=${message.encodeURLParameter()}")
        } else {
            send("DELETE", file(path), fileJson.encodeToString(Change.serializer(), Change(branch, message, sha = current.tag)))
        }
        if (reply.status !in 200..299 && read(path) != null) throw failed(reply)
    }

    private fun file(path: String) =
        if (host == GitHost.GitLab) "$base/files/${"$SYNC_FOLDER/$path".encodeURLParameter()}" else "$base/contents/$SYNC_FOLDER/$path"

    private suspend fun contents(directory: String): List<Listed> {
        val reply = send("GET", "$base/contents/${"$SYNC_FOLDER/$directory".trimEnd('/')}?$ref")
        if (reply.status == 404) return emptyList()
        return parsed(reply, ListSerializer(Entry.serializer())).filter { it.type == "file" }.map { Listed(directory + it.name, it.sha) }
    }

    // GitLab lists the files under the folder in pages and names the next page in a header
    private suspend fun tree(): List<Listed> {
        val listed = mutableListOf<Listed>()
        var page = "1"
        while (page.isNotEmpty()) {
            val reply = send("GET", "$base/tree?path=$SYNC_FOLDER&$ref&recursive=true&per_page=100&page=$page")
            if (reply.status == 404) return emptyList()
            parsed(reply, ListSerializer(Entry.serializer())).filter { it.type == "blob" }.mapTo(listed) { Listed(it.path.removePrefix("$SYNC_FOLDER/"), it.id) }
            page = reply.header("X-Next-Page").orEmpty()
        }
        return listed
    }

    private suspend fun send(method: String, url: String, json: String? = null): Reply {
        val reply = http.send(method, url, login, json?.let { outgoing(it.encodeToByteArray(), "application/json") })
        if (reply.status == 401) throw loginRefused(server)
        return reply
    }

    private fun <T> parsed(reply: Reply, serializer: KSerializer<T>): T {
        if (reply.status != 200) throw failed(reply)
        return try {
            fileJson.decodeFromString(serializer, reply.body.decodeToString())
        } catch (e: IllegalArgumentException) {
            throw DeviceException(ErrorCategory.Api, "$server did not answer as the API of $host")
        }
    }

    // GitHub, Gitea and GitLab say why in the message of their JSON
    private fun failed(reply: Reply): DeviceException {
        val message = try {
            fileJson.decodeFromString(Refusal.serializer(), reply.body.decodeToString()).message
        } catch (e: IllegalArgumentException) {
            ""
        }
        return answered(server, "${reply.status} $message".trim())
    }
}
