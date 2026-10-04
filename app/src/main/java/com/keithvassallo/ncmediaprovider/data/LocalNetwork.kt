package com.keithvassallo.ncmediaprovider.data

import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Whether [address] is on a local network: private IPv4 ranges, loopback, link-local, IPv6
 * unique-local, and 100.64.0.0/10, which Tailscale and similar VPNs use. Android 17 asks for
 * ACCESS_LOCAL_NETWORK before an app may reach such an address (Phase 1.5), and plain HTTP is
 * allowed only to them (#27).
 */
internal fun isLocalNetworkAddress(address: InetAddress): Boolean {
    if (address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress) return true
    val bytes = address.address
    return when (address) {
        is Inet4Address -> (bytes[0].toInt() and 0xFF) == 100 && (bytes[1].toInt() and 0xC0) == 64
        is Inet6Address -> (bytes[0].toInt() and 0xFE) == 0xFC
        else -> false
    }
}

/** Plain HTTP to a server outside the local network: refused before anything is sent. */
class PlainHttpNotAllowedException : IOException("Plain HTTP is allowed only for servers on your local network")
