package com.vasmarfas.networkhelm.ui.editor

enum class Tone { Comment, String, Keyword, Name, Number, Variable, Path, Error, Warning }

class Span(val start: Int, val end: Int, val tone: Tone)

enum class Language { Plain, RouterOs, Json, Yaml, Config, Shell, Lua, Log }

fun languageOf(name: String): Language {
    val file = name.substringAfterLast('/').lowercase()
    return when (file.substringAfterLast('.', file)) {
        "rsc" -> Language.RouterOs
        "json" -> Language.Json
        "yaml", "yml" -> Language.Yaml
        "conf", "cfg", "ini", "config", "properties", "toml", "network", "wireless", "firewall", "dhcp", "system", "uhttpd", "dropbear" -> Language.Config
        "sh", "ash", "bash", "local", "profile" -> Language.Shell
        "lua" -> Language.Lua
        "log" -> Language.Log
        else -> Language.Plain
    }
}

const val HIGHLIGHT_LIMIT = 400_000

fun highlight(text: CharSequence, language: Language): List<Span> {
    if (language == Language.Plain || text.length > HIGHLIGHT_LIMIT) return emptyList()
    val spans = ArrayList<Span>()
    Scan(text, spans, language).run()
    return spans
}

private val routerOsVerbs = setOf(
    "add", "set", "remove", "print", "get", "find", "enable", "disable", "export", "import", "move", "edit", "comment", "reset",
    "run", "monitor", "unset", "set", "clear", "flush", "release", "renew", "disconnect", "start", "stop", "restart", "check",
)

private val shellWords = setOf(
    "if", "then", "else", "elif", "fi", "for", "while", "until", "do", "done", "case", "esac", "in", "function", "return",
    "exit", "local", "export", "echo", "exec", "set", "unset", "shift", "break", "continue", "source", "trap", "eval", "test",
)

private val luaWords = setOf(
    "function", "end", "if", "then", "else", "elseif", "for", "while", "do", "return", "local", "nil", "true", "false", "and",
    "or", "not", "in", "repeat", "until", "break",
)

private val configWords = setOf("config", "option", "list", "package")

private val badWords = Regex("\\b(error|fail(ed|ure)?|critical|fatal|panic|denied|refused)\\b", RegexOption.IGNORE_CASE)
private val warnWords = Regex("\\b(warn(ing)?|timeout|retry|deprecated)\\b", RegexOption.IGNORE_CASE)
private val stamp = Regex("^\\s*(\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}|[A-Z][a-z]{2} +\\d+ \\d{2}:\\d{2}:\\d{2}|[a-z]{3}/\\d{2}/\\d{4} \\d{2}:\\d{2}:\\d{2})")

private class Scan(private val text: CharSequence, private val spans: MutableList<Span>, private val language: Language) {
    private var at = 0

    fun run() {
        if (language == Language.Log) return log()
        var lineStart = true
        while (at < text.length) {
            val c = text[at]
            when {
                c == '\n' -> {
                    at++
                    lineStart = true
                    continue
                }
                c == ' ' || c == '\t' || c == '\r' -> {
                    at++
                    continue
                }
                comment(c, lineStart) -> {}
                c == '"' || c == '\'' && language != Language.RouterOs -> string(c)
                c == '$' && language in setOf(Language.RouterOs, Language.Shell) -> variable()
                c.isDigit() -> number()
                c == '/' && language == Language.RouterOs && lineStart -> path()
                c == ':' && language == Language.RouterOs && at + 1 < text.length && text[at + 1].isLetter() -> word(Tone.Keyword, 1)
                c == '[' && language == Language.Config && lineStart -> section()
                c.isLetter() || c == '_' || c == '-' && language == Language.RouterOs -> identifier(lineStart)
                else -> at++
            }
            lineStart = false
        }
    }

    private fun comment(c: Char, lineStart: Boolean): Boolean {
        val marker = when (language) {
            Language.Lua -> c == '-' && at + 1 < text.length && text[at + 1] == '-'
            Language.Config -> (c == '#' || c == ';') && lineStart
            Language.Json -> false
            else -> c == '#' && (lineStart || at > 0 && text[at - 1] in " \t;{")
        }
        if (!marker) return false
        val end = lineEnd(at)
        spans += Span(at, end, Tone.Comment)
        at = end
        return true
    }

    private fun lineEnd(from: Int): Int {
        var i = from
        while (i < text.length && text[i] != '\n') i++
        return i
    }

    private fun string(quote: Char) {
        val start = at
        at++
        val broken = language !in setOf(Language.RouterOs, Language.Shell, Language.Lua)
        while (at < text.length && text[at] != quote && !(broken && text[at] == '\n')) {
            if (text[at] == '\\') at++
            at++
        }
        at = minOf(at + if (at < text.length && text[at] == quote) 1 else 0, text.length)
        val key = language == Language.Json && followedByColon(at)
        spans += Span(start, at, if (key) Tone.Name else Tone.String)
    }

    private fun followedByColon(from: Int): Boolean {
        var i = from
        while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
        return i < text.length && text[i] == ':'
    }

    private fun variable() {
        val start = at
        at++
        if (at < text.length && text[at] == '{') {
            while (at < text.length && text[at] != '}' && text[at] != '\n') at++
            at = minOf(at + 1, text.length)
        } else if (at < text.length && text[at] == '"') {
            at++
            while (at < text.length && text[at] != '"' && text[at] != '\n') at++
            at = minOf(at + 1, text.length)
        } else {
            while (at < text.length && (text[at].isLetterOrDigit() || text[at] == '_' || text[at] == '-' && language == Language.RouterOs)) at++
        }
        if (at > start + 1) spans += Span(start, at, Tone.Variable)
    }

    private fun number() {
        if (at > 0 && (text[at - 1].isLetterOrDigit() || text[at - 1] == '_')) {
            at++
            return
        }
        val start = at
        while (at < text.length && (text[at].isLetterOrDigit() || text[at] in ".:/%")) at++
        spans += Span(start, at, Tone.Number)
    }

    private fun word(tone: Tone, skip: Int = 0) {
        val start = at
        at += skip
        while (at < text.length && (text[at].isLetterOrDigit() || text[at] == '-' || text[at] == '_')) at++
        spans += Span(start, at, tone)
    }

    private fun path() {
        val start = at
        at++
        var end = at
        while (at < text.length && text[at] != '\n') {
            val wordStart = at
            while (at < text.length && (text[at].isLetterOrDigit() || text[at] == '-' || text[at] == '/')) at++
            val word = text.subSequence(wordStart, at).toString()
            val spaced = at < text.length && text[at] == ' '
            if (word.isEmpty() || at < text.length && text[at] == '=' || word.substringAfterLast('/') in routerOsVerbs && wordStart > start + 1) {
                at = wordStart
                break
            }
            end = at
            if (!spaced) break
            at++
        }
        spans += Span(start, end, Tone.Path)
        at = end
        while (at < text.length && text[at] == ' ') at++
        if (at < text.length && text[at].isLetter()) {
            val verbStart = at
            while (at < text.length && (text[at].isLetter() || text[at] == '-')) at++
            if (text.subSequence(verbStart, at).toString() in routerOsVerbs) spans += Span(verbStart, at, Tone.Keyword)
        }
    }

    private fun section() {
        val start = at
        while (at < text.length && text[at] != '\n' && text[at] != ']') at++
        at = minOf(at + 1, text.length)
        spans += Span(start, at, Tone.Keyword)
    }

    private fun identifier(lineStart: Boolean) {
        val start = at
        while (at < text.length && (text[at].isLetterOrDigit() || text[at] == '-' || text[at] == '_' || text[at] == '.' && language == Language.Config)) at++
        val word = text.subSequence(start, at).toString()
        val next = if (at < text.length) text[at] else '\n'
        val tone = when {
            language == Language.RouterOs && next == '=' -> Tone.Name
            language == Language.RouterOs && lineStart && word in routerOsVerbs -> Tone.Keyword
            language == Language.Shell && word in shellWords -> Tone.Keyword
            language == Language.Lua && word in luaWords -> Tone.Keyword
            language == Language.Config && lineStart && word in configWords -> Tone.Keyword
            language == Language.Config && (next == '=' || next == ':' && lineStart) -> Tone.Name
            language == Language.Shell && next == '=' && lineStart -> Tone.Name
            language == Language.Yaml && next == ':' && (at + 1 >= text.length || text[at + 1] == ' ' || text[at + 1] == '\n') -> Tone.Name
            language == Language.Json && word in setOf("true", "false", "null") -> Tone.Keyword
            language == Language.Yaml && word in setOf("true", "false", "null", "yes", "no") -> Tone.Keyword
            else -> null
        }
        if (tone != null) spans += Span(start, at, tone)
    }

    private fun log() {
        var from = 0
        while (from < text.length) {
            val end = lineEnd(from)
            val line = text.subSequence(from, end).toString()
            stamp.find(line)?.let { spans += Span(from + it.range.first, from + it.range.last + 1, Tone.Number) }
            val covered = stamp.find(line)?.range?.last?.plus(1) ?: 0
            val bad = badWords.find(line, covered)
            val warn = warnWords.find(line, covered)
            when {
                bad != null -> spans += Span(from + bad.range.first, from + bad.range.last + 1, Tone.Error)
                warn != null -> spans += Span(from + warn.range.first, from + warn.range.last + 1, Tone.Warning)
            }
            from = end + 1
        }
    }
}
