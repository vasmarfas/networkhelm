package com.vasmarfas.networkhelm.model

private val syslog = Regex("""^(\w{3} [ \d]\d \d{2}:\d{2}:\d{2}) \S+ ([^\[:]+)(?:\[\d+\])?: (.*)$""")

internal fun syslogLine(text: String): Map<String, String>? {
    val (time, program, message) = syslog.matchEntire(text)?.destructured ?: return null
    return mapOf("time" to time, "program" to program, "message" to message)
}
