package io.github.aedev.flow.data.local

import android.content.Context

/** SSH endpoint credentials and pinned OpenSSH known_hosts entries. */
data class SshTunnelConfig(
    val host: String = "",
    val port: Int = 22,
    val username: String = "",
    val password: String = "",
    val knownHosts: String = "",
) {
    val valid: Boolean
        get() = host.isNotBlank() && port in 1..65535 && username.isNotBlank() &&
            password.isNotEmpty() && knownHosts.isNotBlank()
}

/**
 * Separate from proxy preferences: the SOCKS5 endpoint and credentials remain owned by
 * PlayerPreferences. Only SSH connection parameters are stored here.
 */
object SshTunnelConfigStore {
    private const val PREFS = "ssh_tunnel"
    private const val KEY_HOST = "host"
    private const val KEY_PORT = "port"
    private const val KEY_USERNAME = "username"
    private const val KEY_PASSWORD = "password"
    private const val KEY_KNOWN_HOSTS = "known_hosts"

    fun load(context: Context): SshTunnelConfig {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return SshTunnelConfig(
            host = prefs.getString(KEY_HOST, "").orEmpty(),
            port = prefs.getInt(KEY_PORT, 22),
            username = prefs.getString(KEY_USERNAME, "").orEmpty(),
            password = KeystoreSecretBox.open(prefs.getString(KEY_PASSWORD, null)),
            knownHosts = prefs.getString(KEY_KNOWN_HOSTS, "").orEmpty(),
        )
    }

    fun save(context: Context, config: SshTunnelConfig) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val editor = prefs.edit()
            .putString(KEY_HOST, config.host.trim())
            .putInt(KEY_PORT, config.port)
            .putString(KEY_USERNAME, config.username.trim())
            .putString(KEY_KNOWN_HOSTS, config.knownHosts.trim())
        if (config.password.isEmpty()) editor.remove(KEY_PASSWORD)
        else editor.putString(KEY_PASSWORD, KeystoreSecretBox.seal(config.password))
        check(editor.commit()) { "Could not save SSH settings" }
    }
}
