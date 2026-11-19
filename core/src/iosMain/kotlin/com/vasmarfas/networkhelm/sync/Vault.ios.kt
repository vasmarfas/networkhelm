package com.vasmarfas.networkhelm.sync

import platform.Foundation.NSString
import platform.Foundation.precomposedStringWithCanonicalMapping

// a string of Kotlin is an NSString on iOS, which the compiler does not know
@Suppress("CAST_NEVER_SUCCEEDS")
internal actual fun nfc(text: String): String = (text as NSString).precomposedStringWithCanonicalMapping
