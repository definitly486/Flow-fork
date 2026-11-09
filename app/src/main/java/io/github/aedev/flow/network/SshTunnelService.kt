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
import android.util.Log
import androidx.core.app.NotificationCompat
import com.jcraft.jsch.JSch
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.local.SshTunnelConfigStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean

/** Keeps an SSH local forward alive and routes Flow's configured SOCKS5 proxy through it. */
class SshTunnelService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stopped = AtomicBoolean(false)
    private val connecting = AtomicBoolean(false)
    @Volatile private var session: com.jcraft.jsch.Session? = null

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
        if (!connecting.compareAndSet(false, true)) return START_NOT_STICKY
        scope.launch {
            try {
                establishTunnel()
                updateNotification(getString(R.string.ssh_tunnel_connected))
            } catch (error: Exception) {
                Log.e(TAG, "SSH tunnel connection failed", error)
                showFailureNotification(getString(R.string.ssh_tunnel_failed, error.message ?: error.javaClass.simpleName))
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun establishTunnel() = withContext(Dispatchers.IO) {
        val ssh = SshTunnelConfigStore.load(this@SshTunnelService)
        require(ssh.valid) { getString(R.string.ssh_tunnel_invalid_settings) }

        val proxy = PlayerPreferences(this@SshTunnelService).getProxyConfig()
        require(proxy.enabled && proxy.type == AppProxyType.SOCKS5 && proxy.hasUsableEndpoint()) {
            getString(R.string.ssh_tunnel_requires_socks5)
        }

        val jsch = JSch()
        jsch.setKnownHosts(ByteArrayInputStream(ssh.knownHosts.toByteArray(Charsets.UTF_8)))
        val connected = jsch.getSession(ssh.username, ssh.host.trim(), ssh.port).apply {
            setPassword(ssh.password)
            setConfig("StrictHostKeyChecking", "yes")
            setConfig("PreferredAuthentications", "password,keyboard-interactive")
        }
        session = connected
        try {
            connected.connect(20_000)
            check(!stopped.get()) { "SSH tunnel start was cancelled" }
            connected.setPortForwardingL(LOCAL_HOST, LOCAL_PORT, proxy.host.trim(), proxy.port)
            check(!stopped.get()) { "SSH tunnel start was cancelled" }
            AppProxyManager.setTunnelProxy(
                proxy.copy(host = LOCAL_HOST, port = LOCAL_PORT, type = AppProxyType.SOCKS5, enabled = true),
            )
            // Keep the non-OkHttp YouTube extractor on the same local SOCKS route.
            io.github.aedev.flow.innertube.YouTube.proxy = AppProxyManager.currentProxy()
            io.github.aedev.flow.innertube.YouTube.proxyAuth = null
        } catch (error: Exception) {
            connected.disconnect()
            throw error
        }
    }

    private fun startAsForeground() {
        val notification = buildNotification(getString(R.string.ssh_tunnel_connecting))
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(message: String, ongoing: Boolean = true, showStop: Boolean = true): Notification {
        val stopIntent = Intent(this, SshTunnelService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PendingIntent.getForegroundService(this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            } else {
                PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(getString(R.string.ssh_tunnel_notification_title))
            .setContentText(message)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .apply {
                if (showStop) addAction(0, getString(R.string.ssh_tunnel_stop), stopPendingIntent)
            }
            .build()
    }

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(message))
    }

    /** Keep the actual failure visible after the foreground service has stopped. */
    private fun showFailureNotification(message: String) {
        cleanupTunnel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(false)
        }
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(message, ongoing = false, showStop = false))
        stopSelf()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.ssh_tunnel_notification_title), NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun cleanupTunnel() {
        session?.let { runCatching { it.delPortForwardingL(LOCAL_PORT) }; it.disconnect() }
        session = null
        AppProxyManager.setTunnelProxy(null)
        io.github.aedev.flow.innertube.YouTube.proxy = AppProxyManager.currentProxy()
        io.github.aedev.flow.innertube.YouTube.proxyAuth = AppProxyManager.currentHttpProxyAuthorizationHeader()
    }

    private fun stopTunnel() {
        if (!stopped.compareAndSet(false, true)) return
        cleanupTunnel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopTunnel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "SshTunnelService"
        private const val CHANNEL_ID = "ssh_tunnel"
        private const val NOTIFICATION_ID = 7412
        private const val LOCAL_HOST = "127.0.0.1"
        private const val LOCAL_PORT = 1080
        private const val ACTION_STOP = "io.github.aedev.flow.action.STOP_SSH_TUNNEL"

        fun start(context: android.content.Context) {
            val intent = Intent(context, SshTunnelService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: android.content.Context) {
            context.startService(Intent(context, SshTunnelService::class.java).setAction(ACTION_STOP))
        }
    }
}
