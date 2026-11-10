package io.github.aedev.flow.network

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.local.SshTunnelConfigStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.security.Provider
import java.security.Security
import java.security.Signature
import org.bouncycastle.jce.provider.BouncyCastleProvider

/**
 * SSH local-forward service:
 * app -> 127.0.0.1:1080 (no-auth bridge) -> 127.0.0.1:1081 -> SSH
 * -> the authenticated SOCKS5 listener on the SSH server.
 *
 * Flow's saved SOCKS5 endpoint remains the source of port and credentials; SSH settings
 * are stored separately and never overwrite the saved proxy.
 */
class SshTunnelService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var running = false
    @Volatile private var session: Session? = null
    @Volatile private var tunnelProxy: AppProxyConfig? = null
    @Volatile private var authBridge: Socks5AuthBridge? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTunnel()
            return START_NOT_STICKY
        }
        startAsForeground()
        if (!running) {
            _status.value = TunnelStatus(TunnelPhase.CONNECTING)
            running = true
            scope.launch { tunnelLoop() }
        }
        return START_STICKY
    }

    private suspend fun tunnelLoop() {
        while (running && scope.isActive) {
            try {
                establishTunnel()
                _status.value = TunnelStatus(TunnelPhase.CONNECTED)
                updateNotification(getString(R.string.ssh_tunnel_connected))
                val active = session
                while (running && active != null && active.isConnected) delay(1000)
                if (running) Log.w(TAG, "SSH connection was closed; reconnecting")
            } catch (error: Exception) {
                if (running) {
                    Log.e(TAG, "SSH tunnel connection failed", error)
                    _status.value = TunnelStatus(TunnelPhase.ERROR, error.localizedMessage ?: error.javaClass.simpleName)
                }
            } finally {
                disconnect()
                clearTunnelProxy()
            }
            if (running) {
                _status.value = TunnelStatus(TunnelPhase.RECONNECTING)
                updateNotification(getString(R.string.ssh_tunnel_reconnecting))
                delay(RECONNECT_DELAY_MS)
            }
        }
    }

    private suspend fun establishTunnel() {
        val ssh = SshTunnelConfigStore.load(this)
        require(ssh.valid) { getString(R.string.ssh_tunnel_invalid_settings) }

        val proxy = PlayerPreferences(this).getProxyConfig()
        require(proxy.enabled && proxy.type == AppProxyType.SOCKS5 && proxy.hasUsableEndpoint()) {
            getString(R.string.ssh_tunnel_requires_socks5)
        }

        // JSch's known_hosts parser expects a real file. This mirrors Telegram-X and avoids
        // provider/parser differences seen when passing an in-memory stream.
        val knownHostsFile = File(filesDir, "ssh_tunnel_known_hosts")
        knownHostsFile.writeText(ssh.knownHosts.trim() + "\n", Charsets.UTF_8)

        ensureEd25519Support()
        val jsch = JSch()
        jsch.setKnownHosts(knownHostsFile.absolutePath)
        val repository = jsch.hostKeyRepository
        val expected = repository.getHostKey(ssh.host.trim(), null)
        Log.i(TAG, "Known-host check: host=${ssh.host.trim()}, port=${ssh.port}, fileExists=${knownHostsFile.isFile}, fileLength=${knownHostsFile.length()}, loadedEntries=${expected?.size ?: 0}")
        expected?.forEach { key ->
            Log.i(TAG, "Expected host key: host=${key.host}, type=${key.type}, fingerprint=${fingerprint(Base64.decode(key.key, Base64.DEFAULT))}")
        }

        val connected = jsch.getSession(ssh.username, ssh.host.trim(), ssh.port).apply {
            setPassword(ssh.password)
            setConfig("StrictHostKeyChecking", "yes")
            // Match the pinned key used by Telegram-X instead of letting JSch negotiate
            // a different host-key algorithm than the one the user pinned.
            setConfig("server_host_key", "ssh-ed25519")
            setConfig("PreferredAuthentications", "password")
            setServerAliveInterval(10_000)
            setServerAliveCountMax(3)
        }
        session = connected
        try {
            connected.connect(15_000)
            check(running) { "SSH tunnel start was cancelled" }
            // Forward a private second port to the remote SOCKS5 server. The local
            // bridge on LOCAL_PORT performs RFC 1929 authentication explicitly.
            connected.setPortForwardingL(LOCAL_HOST, FORWARD_PORT, "127.0.0.1", proxy.port)
            check(running) { "SSH tunnel start was cancelled" }

            val bridge = Socks5AuthBridge(
                listenPort = LOCAL_PORT,
                upstreamPort = FORWARD_PORT,
                username = proxy.username,
                password = proxy.password,
            )
            bridge.start()
            authBridge = bridge
            check(running) { "SSH tunnel start was cancelled" }

            // The app connects to the loopback bridge without credentials; the bridge
            // authenticates to the remote SOCKS5 endpoint using the saved credentials.
            val localProxy = proxy.copy(
                host = LOCAL_HOST,
                port = LOCAL_PORT,
                type = AppProxyType.SOCKS5,
                username = "",
                password = "",
                enabled = true,
            )
            tunnelProxy = localProxy
            AppProxyManager.setTunnelProxy(localProxy)
            io.github.aedev.flow.innertube.YouTube.proxy = AppProxyManager.currentProxy()
            // No proxy credentials are sent by app clients to the local bridge.
            io.github.aedev.flow.innertube.YouTube.proxyAuth = null
            Log.i(TAG, "SSH tunnel established: 127.0.0.1:$LOCAL_PORT -> auth bridge -> 127.0.0.1:$FORWARD_PORT -> SSH -> 127.0.0.1:${proxy.port}")
        } catch (error: Exception) {
            connected.disconnect()
            session = null
            throw error
        }
    }

    private fun ensureEd25519Support() {
        try {
            val signature = Signature.getInstance("Ed25519")
            Log.i(TAG, "Ed25519 signature available from ${signature.provider.name}")
            return
        } catch (_: NoSuchAlgorithmException) {
            // Android builds may not expose the JCA Ed25519 algorithm by default.
        }

        val current = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)
        if (current != null) {
            try {
                Signature.getInstance("Ed25519", current)
                Security.insertProviderAt(current, 1)
                Log.i(TAG, "Ed25519 signature available from existing ${current.name} provider")
                return
            } catch (_: NoSuchAlgorithmException) {
                Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            }
        }
        Security.insertProviderAt(BouncyCastleProvider(), 1)
        val signature = Signature.getInstance("Ed25519")
        Log.i(TAG, "Ed25519 signature available from ${signature.provider.name} provider")
    }

    private fun fingerprint(key: ByteArray): String =
        try {
            val digest = MessageDigest.getInstance("SHA-256").digest(key)
            "SHA256:" + Base64.encodeToString(digest, Base64.NO_WRAP or Base64.NO_PADDING)
        } catch (error: Exception) {
            "unavailable:${error.javaClass.simpleName}"
        }

    private fun clearTunnelProxy() {
        tunnelProxy = null
        AppProxyManager.setTunnelProxy(null)
        io.github.aedev.flow.innertube.YouTube.proxy = AppProxyManager.currentProxy()
        io.github.aedev.flow.innertube.YouTube.proxyAuth = AppProxyManager.currentHttpProxyAuthorizationHeader()
    }

    private fun disconnect() {
        authBridge?.let { runCatching { it.close() } }
        authBridge = null
        val active = session
        session = null
        if (active != null) runCatching { active.disconnect() }
    }

    private fun startAsForeground() {
        val notification = buildNotification("Connecting to SSH server")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(message: String): Notification {
        val stopIntent = Intent(this, SshTunnelService::class.java).setAction(ACTION_STOP)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val stopPendingIntent =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PendingIntent.getForegroundService(this, 1, stopIntent, flags)
            } else {
                PendingIntent.getService(this, 1, stopIntent, flags)
            }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(getString(R.string.ssh_tunnel_notification_title))
            .setContentText(message)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.ssh_tunnel_stop), stopPendingIntent)
            .build()
    }

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(message))
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.ssh_tunnel_notification_title), NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun stopTunnel() {
        running = false
        _status.value = TunnelStatus(TunnelPhase.STOPPED)
        disconnect()
        clearTunnelProxy()
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        _status.value = TunnelStatus(TunnelPhase.STOPPED)
        disconnect()
        clearTunnelProxy()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private val _status = kotlinx.coroutines.flow.MutableStateFlow(TunnelStatus(TunnelPhase.STOPPED))
        val status: kotlinx.coroutines.flow.StateFlow<TunnelStatus> = _status

        private const val TAG = "SshTunnelService"
        private const val CHANNEL_ID = "ssh_tunnel"
        private const val NOTIFICATION_ID = 7412
        private const val LOCAL_HOST = "127.0.0.1"
        private const val LOCAL_PORT = 1080
        private const val FORWARD_PORT = 1081
        private const val RECONNECT_DELAY_MS = 5_000L
        private const val ACTION_STOP = "io.github.aedev.flow.action.STOP_SSH_TUNNEL"

        fun start(context: android.content.Context) {
            _status.value = TunnelStatus(TunnelPhase.CONNECTING)
            androidx.core.content.ContextCompat.startForegroundService(
                context,
                Intent(context, SshTunnelService::class.java),
            )
        }

        fun stop(context: android.content.Context) {
            context.startService(Intent(context, SshTunnelService::class.java).setAction(ACTION_STOP))
        }
    }
}

data class TunnelStatus(val phase: TunnelPhase, val detail: String? = null)

enum class TunnelPhase { STOPPED, CONNECTING, CONNECTED, RECONNECTING, ERROR }
