package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.openwrt.quote

// the users of a server of pppd as lines of /etc/ppp/chap-secrets under the name of the server, where the lines of other servers and
// those an administrator commented out stay as they are. A password goes in as the input of the command and never comes back; a user
// turned off stands behind #[disabled]. pppd reads the file as a client logs in, so a change ends no session
internal class ChapSecrets(private val root: Root, private val server: String) {
    suspend fun add(name: String, password: String, disabled: Boolean) {
        root.masked(
            "umask 077; f=$CHAP_FILE; touch \"\$f\" && { [ -z \"\$(tail -c 1 \"\$f\")\" ] || echo >> \"\$f\"; } && cat >> \"\$f\"",
            (if (disabled) CHAP_OFF else "") + line(name, password) + "\n",
        )
    }

    // a new password takes the place of the old line, which stays off where it was
    suspend fun set(name: String, password: String?, disabled: Boolean?) =
        edit(listOf(name), password?.ifEmpty { null }?.let { line(name, it) }, if (disabled == null) "keep" else if (disabled) "yes" else "no")

    suspend fun remove(names: List<String>) = edit(names, null, "gone")

    // the lines of the users given turned off or on, given a new line, which comes as the input, or gone
    private suspend fun edit(names: List<String>, line: String?, off: String) {
        root.masked(
            "f=$CHAP_FILE; [ -f \"\$f\" ] || exit 0; cp -p \"\$f\" \"\$f.networkhelm-new\" && awk -v s=${quote(server)} -v list=${quote(names.joinToString(" "))} -v off=$off " +
                "'$CHAP_EDIT' - \"\$f\" > \"\$f.networkhelm-new\" && mv -f \"\$f.networkhelm-new\" \"\$f\"",
            "${line.orEmpty()}\n",
        )
    }

    // a line for a user from any address, in quotes as pppd reads them
    private fun line(name: String, password: String): String {
        if (password.isEmpty()) throw DeviceException(ErrorCategory.ArgumentValue, "password: a value is needed")
        if (password.any { it == '\n' || it == '\r' }) throw DeviceException(ErrorCategory.ArgumentValue, "the password may hold no line break")
        return "\"$name\" $server \"${password.replace("\\", "\\\\").replace("\"", "\\\"")}\" *"
    }
}

private const val CHAP_FILE = "/etc/ppp/chap-secrets"
private const val CHAP_OFF = "#[disabled] "

// the lines whose user is in list and whose server is s: off yes or no turns them off or on, keep leaves that, gone takes them out; the
// first line of the input, where it is not empty, takes their place
private val CHAP_EDIT = $$"""BEGIN { n = split(list, k, " "); for (i = 1; i <= n; i++) names[k[i]] = 1 }
NR == FNR { line = $0; next }
{ l = $0; was = sub(/^#\[disabled\] /, "", l); split(l, w, /[ \t]+/); u = w[1]; gsub(/"/, "", u) }
w[2] == s && (u in names) { if (off == "gone") next; if (line != "") l = line; p = (off == "yes" || (off == "keep" && was)) ? "#[disabled] " : ""; print p l; next }
{ print }"""
