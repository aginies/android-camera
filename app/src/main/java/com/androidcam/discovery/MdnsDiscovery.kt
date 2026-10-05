package com.androidcam.discovery

import timber.log.Timber
import java.net.InetAddress
import java.util.concurrent.Executors
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/**
 * mDNS/Bonjour service discovery.
 *
 * Advertises the app on the local network so remote clients can find it.
 * The advertisement includes the auth [token] so a discovered device can be
 * connected to without manual token entry.
 *
 * All JmDNS calls run on a dedicated background thread: JmDNS performs
 * network I/O (address resolution, registration) which is not allowed on
 * the main thread.
 */
class MdnsDiscovery {
    companion object {
        private const val SERVICE_TYPE = "_androidcam._tcp.local."
    }

    private val executor =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "mdns-discovery").apply { isDaemon = true }
        }

    @Volatile
    private var jmDNS: JmDNS? = null

    @Volatile
    private var serviceInfo: ServiceInfo? = null

    /** Start advertising this device on the local network (background thread). */
    fun start(
        hostName: String,
        port: Int,
        ip: InetAddress,
        token: String,
    ) {
        executor.execute {
            try {
                val props =
                    mapOf(
                        "model" to "android-cam",
                        "version" to "0.1.0",
                        "token" to token,
                        "stream_url" to "http://$ip:$port/?token=$token",
                        "control_url" to "ws://$ip:$port/ws/control?token=$token",
                    )
                serviceInfo = ServiceInfo.create(SERVICE_TYPE, hostName, port, 0, 0, props)
                // Pass the hostname explicitly: JmDNS would otherwise do a
                // reverse DNS lookup (InetAddress.getHostName) which is slow
                // and can fail on LAN-only networks.
                jmDNS =
                    JmDNS.create(ip, hostName).also {
                        it.registerService(serviceInfo)
                    }
                Timber.i("mDNS service advertised: $hostName on port $port")
            } catch (e: Exception) {
                Timber.e(e, "mDNS advertising failed")
                stopInternal()
            }
        }
    }

    /** Stop advertising and release resources (background thread). */
    fun stop() {
        executor.execute { stopInternal() }
    }

    private fun stopInternal() {
        try {
            serviceInfo?.let { jmDNS?.unregisterService(it) }
        } catch (e: Exception) {
            Timber.w(e, "Error unregistering mDNS service")
        }
        try {
            jmDNS?.close()
        } catch (e: Exception) {
            Timber.w(e, "Error closing JmDNS")
        }
        jmDNS = null
        serviceInfo = null
    }
}
