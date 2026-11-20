package com.vasmarfas.networkhelm.model

// a bare key is too common a name to count, the variables of a container have one
fun sensitive(property: String, type: ValueType? = null): Boolean {
    if (secret(type)) return true
    val name = property.split('.', ' ').last().replace('_', '-')
    return secretWords.any { name == it || name.endsWith("-$it") } || name.matches(wepKey)
}

// a field with choices refers to an element instead of holding a key, as auth-key of the NTP server does
fun Argument.sensitive() = secret(type) || choices.isEmpty() && sensitive(name, type)

// lines of secrets, as the secret variables of a container, are as secret as one
private fun secret(type: ValueType?) = type == ValueType.Secret || (type as? ValueType.Many)?.element == ValueType.Secret

private val secretWords =listOf("password", "passphrase", "secret", "psk", "private-key", "preshared-key", "pre-shared-key", "auth-key", "authentication-key", "md5-key", "enc-key")

private val wepKey = Regex("static-key-[0-3]")
