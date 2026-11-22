package com.vasmarfas.networkhelm

internal class GatewayOptions(private val args: Array<String>, private val environment: Map<String, String>) {
    private fun option(name: String): String? {
        val index = args.indexOf(name)
        if (index < 0) return null
        return args.getOrNull(index + 1)?.takeUnless { it.startsWith("--") }
            ?: throw IllegalArgumentException("$name needs a value")
    }

    private fun setting(name: String) = environment["NETWORKHELM_GATEWAY_$name"]?.takeIf { it.isNotBlank() }

    private fun flag(name: String): Boolean = "--${name.lowercase()}" in args || setting(name)?.let {
        it.toBooleanStrictOrNull() ?: throw IllegalArgumentException("NETWORKHELM_GATEWAY_$name must be true or false")
    } ?: false

    val host = option("--host") ?: setting("HOST") ?: "0.0.0.0"
    val port = (option("--port") ?: setting("PORT"))?.let {
        it.toIntOrNull()?.takeIf { value -> value in 1..65535 }
            ?: throw IllegalArgumentException("The gateway port must be between 1 and 65535")
    } ?: 8443
    val public = flag("PUBLIC")
    val code = flag("CODE")
}
