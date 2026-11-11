// This whole file is for Android older than 6.0, where the deprecated
// network APIs (NetworkInfo, activeNetworkInfo, TYPE_*) are the only ones.
@file:Suppress("DEPRECATION")

package com.kaydn.fastfetchandroid

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface
import java.net.Socket

// ==================================================
// LEGACY NETWORK
// ==================================================
//
// The active connection below Android 6.0, where ConnectivityManager has
// no activeNetwork (API 23). Only DeviceInfo.getNetworkLines() calls this,
// and only below 6.0, so the normal app never runs it.
//
//  - 5.1 (API 22): a VPN this app's traffic really goes through, if any
//    (activeNetworkInfo can report the network under the VPN instead).
//  - 5.0-5.1 (API 21-22): else the connected Network that matches
//    activeNetworkInfo, then its LinkProperties, like 6.0+.
//  - 4.1-4.4: activeNetworkInfo for the type, and the interface list for
//    the addresses. No DNS line: Android doesn't say which DNS servers
//    the active connection uses there.

internal object LegacyNetwork {

    fun read(context: Context): DeviceInfo.NetworkSnapshot? {
        val connectivityManager =
            context.getSystemService(
                Context.CONNECTIVITY_SERVICE
            ) as ConnectivityManager

        if (Build.VERSION.SDK_INT >= 22) {
            usableVpn(connectivityManager)?.let { vpn ->
                readNetwork(
                    connectivityManager = connectivityManager,
                    network = vpn,
                    fallbackType = "VPN"
                )?.let {
                    return it
                }
            }
        }

        val activeInfo =
            connectivityManager.activeNetworkInfo
                ?: return null

        if (!activeInfo.isConnected) {
            return null
        }

        if (Build.VERSION.SDK_INT >= 21) {
            matchingNetwork(
                connectivityManager = connectivityManager,
                activeInfo = activeInfo
            )?.let { network ->
                readNetwork(
                    connectivityManager = connectivityManager,
                    network = network,
                    fallbackType = typeName(activeInfo.type)
                )?.let {
                    return it
                }
            }
        }

        return readInterfaces(activeInfo)
    }

    // ==================================================
    // ANDROID 5.0-5.1
    // ==================================================

    /**
     * A connected VPN with internet access that this app can use. A VPN
     * can belong to another user or leave this app out, so it only counts
     * if a socket can be bound to it (the same check Chromium uses).
     * Network.bindSocket(Socket) is 5.1+.
     */
    @RequiresApi(22)
    private fun usableVpn(connectivityManager: ConnectivityManager): Network? {
        return connectivityManager.allNetworks.firstOrNull { network ->
            val capabilities = connectivityManager.getNetworkCapabilities(network)

            capabilities != null &&
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                connectivityManager.getNetworkInfo(network)?.isConnected == true &&
                canBindSocket(network)
        }
    }

    @RequiresApi(22)
    private fun canBindSocket(network: Network): Boolean {
        val socket = Socket()

        return try {
            network.bindSocket(socket)
            true
        } catch (_: IOException) {
            false
        } finally {
            try {
                socket.close()
            } catch (_: IOException) {
                // Nothing to clean up.
            }
        }
    }

    @RequiresApi(21)
    private fun matchingNetwork(
        connectivityManager: ConnectivityManager,
        activeInfo: NetworkInfo
    ): Network? {
        return connectivityManager.allNetworks.firstOrNull { network ->
            val info = connectivityManager.getNetworkInfo(network)

            info != null &&
                info.isConnected &&
                info.type == activeInfo.type
        }
    }

    @RequiresApi(21)
    private fun readNetwork(
        connectivityManager: ConnectivityManager,
        network: Network,
        fallbackType: String
    ): DeviceInfo.NetworkSnapshot? {
        val properties =
            connectivityManager
                .getLinkProperties(network)
                ?: return null

        val capabilities =
            connectivityManager
                .getNetworkCapabilities(network)

        return DeviceInfo.NetworkSnapshot(
            connectionType =
                if (capabilities != null) {
                    DeviceInfo.transportName(capabilities)
                } else {
                    fallbackType
                },
            interfaceName = properties.interfaceName,
            addresses = properties.linkAddresses.map { linkAddress ->
                DeviceInfo.NetworkAddress(
                    address = linkAddress.address,
                    prefixLength = linkAddress.prefixLength
                )
            },
            dnsServers = properties.dnsServers
        )
    }

    // ==================================================
    // ANDROID 4.1-4.4
    // ==================================================

    private fun readInterfaces(activeInfo: NetworkInfo): DeviceInfo.NetworkSnapshot {
        val chosen = pickInterface(activeInfo.type)

        return DeviceInfo.NetworkSnapshot(
            connectionType = typeName(activeInfo.type),
            interfaceName = chosen?.first,
            addresses = chosen?.second ?: emptyList(),
            dnsServers = emptyList()
        )
    }

    /**
     * Which interface carries the active connection. Below 5.0 Android
     * doesn't say, so pick it by name (wlan* for Wi-Fi, eth* for Ethernet),
     * or take it if it's the only interface with a usable address. In any
     * other case it would be a guess, so no interface or addresses are
     * shown.
     */
    private fun pickInterface(activeType: Int): Pair<String, List<DeviceInfo.NetworkAddress>>? {
        val candidates =
            try {
                NetworkInterface.getNetworkInterfaces()
                    ?.toList()
                    .orEmpty()
                    .filter { it.isUp && !it.isLoopback }
                    .map { networkInterface ->
                        networkInterface.name to
                            networkInterface.interfaceAddresses.mapNotNull { interfaceAddress ->
                                interfaceAddress.address?.let {
                                    DeviceInfo.NetworkAddress(
                                        address = it,
                                        prefixLength = interfaceAddress.networkPrefixLength.toInt()
                                    )
                                }
                            }
                    }
                    .filter { (_, addresses) ->
                        addresses.any { isUsable(it) }
                    }
            } catch (_: Exception) {
                emptyList()
            }

        val namePrefix =
            when (activeType) {
                ConnectivityManager.TYPE_WIFI -> "wlan"
                ConnectivityManager.TYPE_ETHERNET -> "eth"
                else -> null
            }

        return candidates.firstOrNull { (name, _) ->
            namePrefix != null && name.startsWith(namePrefix)
        }
            ?: candidates.singleOrNull()
    }

    /** An address worth showing: IPv4, or IPv6 that isn't link-local. */
    private fun isUsable(networkAddress: DeviceInfo.NetworkAddress): Boolean {
        val address = networkAddress.address

        return !address.isLoopbackAddress &&
            (
                address is Inet4Address ||
                    (address is Inet6Address && !address.isLinkLocalAddress)
                )
    }

    // ==================================================
    // TYPE NAMES
    // ==================================================

    // TYPE_VPN is API 21, but it's a constant compiled into the app, so
    // on older Android the type just never matches.
    @SuppressLint("InlinedApi")
    private fun typeName(type: Int): String {
        return when (type) {
            ConnectivityManager.TYPE_VPN -> "VPN"
            ConnectivityManager.TYPE_WIFI -> "Wi-Fi"
            ConnectivityManager.TYPE_MOBILE -> "Cellular"
            ConnectivityManager.TYPE_ETHERNET -> "Ethernet"
            else -> "Network"
        }
    }
}
