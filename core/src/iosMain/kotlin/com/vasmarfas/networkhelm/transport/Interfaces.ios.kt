@file:OptIn(ExperimentalForeignApi::class)

package com.vasmarfas.networkhelm.transport

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocPointerTo
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.darwin.freeifaddrs
import platform.darwin.getifaddrs
import platform.darwin.ifaddrs
import platform.posix.AF_INET
import platform.posix.IFF_BROADCAST
import platform.posix.IFF_LOOPBACK
import platform.posix.IFF_UP
import platform.posix.sockaddr
import platform.posix.sockaddr_in

class Ipv4Interface(val name: String, val address: String, val prefix: Int, val broadcast: String?)

// the IPv4 addresses of the interfaces that are up, the loopback left out
fun ipv4Interfaces(): List<Ipv4Interface> = memScoped {
    val list = allocPointerTo<ifaddrs>()
    if (getifaddrs(list.ptr) != 0) return emptyList()
    try {
        val found = ArrayList<Ipv4Interface>()
        var entry = list.value
        while (entry != null) {
            val item = entry.pointed
            entry = item.ifa_next
            val flags = item.ifa_flags.toInt()
            val address = item.ifa_addr ?: continue
            val name = item.ifa_name?.toKString() ?: continue
            if (address.pointed.sa_family.toInt() != AF_INET || flags and IFF_UP == 0 || flags and IFF_LOOPBACK != 0) continue
            val broadcast = if (flags and IFF_BROADCAST != 0) item.ifa_dstaddr?.let(::textOf) else null
            found += Ipv4Interface(name, textOf(address), item.ifa_netmask?.let(::prefixOf) ?: 32, broadcast)
        }
        found
    } finally {
        freeifaddrs(list.value)
    }
}

private fun prefixOf(mask: CPointer<sockaddr>): Int = mask.reinterpret<sockaddr_in>().pointed.sin_addr.s_addr.countOneBits()
