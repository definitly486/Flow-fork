package io.github.aedev.flow.data.local

import io.github.aedev.flow.network.AppProxyConfig
import io.github.aedev.flow.network.AppProxyType
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Portable, password-encrypted backup of SOCKS5/proxy and SSH tunnel settings. */
data class NetworkSettingsBackup(
    val proxy: AppProxyConfig,
    val ssh: SshTunnelConfig,
)

object NetworkSettingsBackupCodec {
    private val MAGIC = byteArrayOf(0x46, 0x4C, 0x4F, 0x57, 0x4E, 0x45, 0x54, 0x31) // FLOWNET1
    private const val SALT_SIZE = 16
    private const val IV_SIZE = 12
    private const val ITERATIONS = 210_000
    private const val KEY_BITS = 256
    private const val MAX_FILE_SIZE = 1_048_576

    fun write(output: OutputStream, backup: NetworkSettingsBackup, password: CharArray) {
        require(password.size >= 8) { "Пароль должен содержать не менее 8 символов" }
        val json = JSONObject()
            .put("version", 1)
            .put("proxy", JSONObject()
                .put("enabled", backup.proxy.enabled)
                .put("type", backup.proxy.type.storageValue)
                .put("host", backup.proxy.host)
                .put("port", backup.proxy.port)
                .put("username", backup.proxy.username)
                .put("password", backup.proxy.password)
                .put("bypassOnVpn", backup.proxy.bypassOnVpn))
            .put("ssh", JSONObject()
                .put("host", backup.ssh.host)
                .put("port", backup.ssh.port)
                .put("username", backup.ssh.username)
                .put("password", backup.ssh.password)
                .put("knownHosts", backup.ssh.knownHosts))
            .toString().toByteArray(Charsets.UTF_8)

        val salt = ByteArray(SALT_SIZE).also(SecureRandom()::nextBytes)
        val iv = ByteArray(IV_SIZE).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(json)
        output.write(MAGIC)
        output.write(salt)
        output.write(iv)
        output.write(encrypted)
        output.flush()
    }

    fun read(input: InputStream, password: CharArray): NetworkSettingsBackup {
        require(password.size >= 8) { "Пароль должен содержать не менее 8 символов" }
        val bytes = input.readBytes()
        require(bytes.size in (MAGIC.size + SALT_SIZE + IV_SIZE + 16)..MAX_FILE_SIZE) { "Некорректный размер файла" }
        require(bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) { "Это не файл резервной копии Flow" }
        var offset = MAGIC.size
        val salt = bytes.copyOfRange(offset, offset + SALT_SIZE).also { offset += SALT_SIZE }
        val iv = bytes.copyOfRange(offset, offset + IV_SIZE).also { offset += IV_SIZE }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(128, iv))
        val json = JSONObject(String(cipher.doFinal(bytes.copyOfRange(offset, bytes.size)), Charsets.UTF_8))
        require(json.getInt("version") == 1) { "Неподдерживаемая версия резервной копии" }
        val p = json.getJSONObject("proxy")
        val s = json.getJSONObject("ssh")
        val proxyPort = p.getInt("port")
        val sshPort = s.getInt("port")
        require(proxyPort in 1..65535 && sshPort in 1..65535) { "Некорректный порт в резервной копии" }
        val type = AppProxyType.fromStorageValue(p.getString("type"))
        return NetworkSettingsBackup(
            proxy = AppProxyConfig(
                enabled = p.getBoolean("enabled"),
                type = type,
                host = p.getString("host"),
                port = proxyPort,
                username = p.getString("username"),
                password = p.getString("password"),
                bypassOnVpn = p.optBoolean("bypassOnVpn", false),
            ),
            ssh = SshTunnelConfig(
                host = s.getString("host"),
                port = sshPort,
                username = s.getString("username"),
                password = s.getString("password"),
                knownHosts = s.getString("knownHosts"),
            ),
        )
    }

    private fun deriveKey(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, ITERATIONS, KEY_BITS)
        return try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }
}
