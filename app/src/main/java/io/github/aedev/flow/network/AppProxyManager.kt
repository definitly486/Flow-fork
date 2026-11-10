package io.github.aedev.flow.network

import android.util.Base64
import android.util.Log
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.util.concurrent.CopyOnWriteArrayList

enum class AppProxyType(val storageValue: String) {
    HTTP("http"),
    SOCKS5("socks5");

    companion object {
        fun fromStorageValue(value: String?): AppProxyType =
            when (value?.lowercase()) {
                SOCKS5.storageValue -> SOCKS5
                else -> HTTP
            }
    }
}

data class AppProxyConfig(
    val enabled: Boolean = false,
    val type: AppProxyType = AppProxyType.HTTP,
    val host: String = "",
    val port: Int = 8080,
    val username: String = "",
    val password: String = "",
    val bypassOnVpn: Boolean = false,
) {
    fun normalized(): AppProxyConfig = copy(host = host.trim(), username = username.trim())
    fun effective(vpnActive: Boolean): AppProxyConfig = if (bypassOnVpn && vpnActive) copy(enabled = false) else this
    fun watchesVpn(): Boolean = bypassOnVpn && hasUsableEndpoint()
    fun hasUsableEndpoint(): Boolean = enabled && host.isNotBlank() && port in 1..65535

    fun toProxy(): Proxy? {
        if (!hasUsableEndpoint()) return null
        val proxyType = when (type) {
            AppProxyType.HTTP -> Proxy.Type.HTTP
            AppProxyType.SOCKS5 -> Proxy.Type.SOCKS
        }
        return Proxy(proxyType, InetSocketAddress.createUnresolved(host, port))
    }

    fun hasCredentials(): Boolean = username.isNotBlank()

    fun httpProxyAuthorizationHeader(): String? {
        if (!hasUsableEndpoint() || type != AppProxyType.HTTP || !hasCredentials()) return null
        val credentials = "$username:$password"
        val encoded = Base64.encodeToString(credentials.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return "Basic $encoded"
    }

    fun signature(): String = listOf(enabled, type.storageValue, host, port, username, password).joinToString("|")
}

/**
 * Android's SOCKS implementation may report a loopback proxy as either 127.0.0.1,
 * localhost or ::1 to the process-wide Authenticator. Treat those as the same endpoint,
 * but never send saved credentials to a different host or port.
 */
internal fun matchesProxyAuthEndpoint(
    configuredHost: String,
    configuredPort: Int,
    requestingHost: String?,
    requestingPort: Int,
): Boolean {
    val configured = configuredHost.trim().lowercase().removePrefix("[").removeSuffix("]")
    val requesting = requestingHost.orEmpty().trim().lowercase().removePrefix("[").removeSuffix("]")
    val portMatches = requestingPort <= 0 || requestingPort == configuredPort
    val hostMatches = configured == requesting ||
        (configured.isLoopbackProxyHost() && requesting.isLoopbackProxyHost())
    return portMatches && hostMatches
}

private fun String.isLoopbackProxyHost(): Boolean =
    this == "localhost" || this == "::1" || this.startsWith("127.")

object AppProxyManager {
    private const val TAG = "AppProxyManager"

    @Volatile private var config: AppProxyConfig = AppProxyConfig()
    @Volatile private var baseConfig: AppProxyConfig = AppProxyConfig()
    @Volatile private var tunnelOverride: AppProxyConfig? = null
    private val lock = Any()
    private val livePools = CopyOnWriteArrayList<ConnectionPool>()

    fun update(newConfig: AppProxyConfig, vpnActive: Boolean = false) {
        val changed = synchronized(lock) {
            val previous = config.signature()
            baseConfig = newConfig.normalized().effective(vpnActive)
            config = tunnelOverride?.effective(vpnActive) ?: baseConfig
            installJvmAuthenticatorLocked()
            config.signature() != previous
        }
        if (changed) livePools.forEach { it.evictAll() }
    }

    fun setTunnelProxy(override: AppProxyConfig?) {
        val changed = synchronized(lock) {
            val previous = config.signature()
            tunnelOverride = override?.normalized()
            config = tunnelOverride?.let { tunnel ->
                if (tunnel.bypassOnVpn && !baseConfig.enabled) tunnel.copy(enabled = false) else tunnel
            } ?: baseConfig
            installJvmAuthenticatorLocked()
            config.signature() != previous
        }
        if (changed) livePools.forEach { it.evictAll() }
    }

    fun currentConfig(): AppProxyConfig = config
    fun currentProxy(): Proxy? = config.toProxy()
    fun currentHttpProxyAuthorizationHeader(): String? = config.httpProxyAuthorizationHeader()
    fun currentSignature(): String = config.signature()

    fun applyTo(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        val activeConfig = config
        val proxy = activeConfig.toProxy() ?: return builder
        builder.proxy(proxy)
        if (activeConfig.type == AppProxyType.HTTP) {
            val authHeader = activeConfig.httpProxyAuthorizationHeader()
            if (authHeader != null) {
                builder.proxyAuthenticator { _, response ->
                    response.request.newBuilder().header("Proxy-Authorization", authHeader).build()
                }
            }
        }
        return builder
    }

    fun buildLive(builder: OkHttpClient.Builder): OkHttpClient =
        builder.proxySelector(AppProxySelector)
            .proxyAuthenticator(AppProxyAuthenticator)
            .build()
            .also { livePools.addIfAbsent(it.connectionPool) }

    private fun installJvmAuthenticatorLocked() {
        val activeConfig = config
        val shouldInstallProxyAuthenticator = activeConfig.hasUsableEndpoint() &&
            activeConfig.type == AppProxyType.SOCKS5 && activeConfig.hasCredentials()

        if (!shouldInstallProxyAuthenticator) {
            Authenticator.setDefault(null)
            return
        }

        val proxyHost = activeConfig.host
        val proxyPort = activeConfig.port
        val username = activeConfig.username
        val passwordChars = activeConfig.password.toCharArray()

        Authenticator.setDefault(object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication? {
                if (requestorType != RequestorType.PROXY) return null

                val requestHost = requestingHost
                val requestPort = requestingPort
                val matches = matchesProxyAuthEndpoint(proxyHost, proxyPort, requestHost, requestPort)
                Log.d(TAG, "SOCKS auth request: host=${requestHost ?: "<null>"}, port=$requestPort, protocol=$requestingProtocol, endpointMatch=$matches")
                if (!matches) return null

                return PasswordAuthentication(username, passwordChars.copyOf())
            }
        })
    }
}
