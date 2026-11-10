package io.github.aedev.flow.network

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Proxy

class AppProxyConfigTest {
    private fun config(
        type: AppProxyType = AppProxyType.HTTP,
        host: String = "proxy.example.test",
        port: Int = 8080,
    ) = AppProxyConfig(enabled = true, type = type, host = host, port = port)

    @Test
    fun `http proxy endpoint is left unresolved so no lookup happens on the calling thread`() {
        val proxy = config().toProxy()
        assertThat(proxy).isNotNull()
        assertThat(proxy!!.type()).isEqualTo(Proxy.Type.HTTP)
        val address = proxy.address() as InetSocketAddress
        assertThat(address.isUnresolved).isTrue()
        assertThat(address.hostString).isEqualTo("proxy.example.test")
        assertThat(address.port).isEqualTo(8080)
    }

    @Test
    fun `socks5 proxy endpoint is left unresolved too`() {
        val proxy = config(type = AppProxyType.SOCKS5, port = 1080).toProxy()
        assertThat(proxy).isNotNull()
        assertThat(proxy!!.type()).isEqualTo(Proxy.Type.SOCKS)
        val address = proxy.address() as InetSocketAddress
        assertThat(address.isUnresolved).isTrue()
        assertThat(address.port).isEqualTo(1080)
    }

    @Test
    fun `SOCKS credentials match equivalent loopback host names`() {
        assertThat(matchesProxyAuthEndpoint("127.0.0.1", 1080, "localhost", 1080)).isTrue()
        assertThat(matchesProxyAuthEndpoint("localhost", 1080, "::1", 1080)).isTrue()
        assertThat(matchesProxyAuthEndpoint("127.0.0.1", 1080, "127.0.0.2", 1080)).isTrue()
    }

    @Test
    fun `SOCKS credentials do not match a different endpoint`() {
        assertThat(matchesProxyAuthEndpoint("127.0.0.1", 1080, "192.168.1.2", 1080)).isFalse()
        assertThat(matchesProxyAuthEndpoint("127.0.0.1", 1080, "localhost", 1081)).isFalse()
        assertThat(matchesProxyAuthEndpoint("proxy.example.test", 1080, "localhost", 1080)).isFalse()
    }

    @Test
    fun `an unspecified requesting port is accepted when host matches`() {
        assertThat(matchesProxyAuthEndpoint("127.0.0.1", 1080, "localhost", -1)).isTrue()
    }

    @Test
    fun `an unusable endpoint yields no proxy`() {
        assertThat(config().copy(enabled = false).toProxy()).isNull()
        assertThat(config(host = "   ").toProxy()).isNull()
        assertThat(config(port = 0).toProxy()).isNull()
        assertThat(config(port = 70_000).toProxy()).isNull()
    }

    @Test
    fun `a vpn pauses the proxy only when the bypass is on`() {
        val bypass = config().copy(bypassOnVpn = true)
        assertThat(bypass.effective(vpnActive = true).toProxy()).isNull()
        assertThat(bypass.effective(vpnActive = false).toProxy()).isNotNull()
        assertThat(config().effective(vpnActive = true).toProxy()).isNotNull()
        assertThat(config().effective(vpnActive = false).toProxy()).isNotNull()
    }

    @Test
    fun `the network is watched for a vpn only while it could change the proxy`() {
        assertThat(config().copy(bypassOnVpn = true).watchesVpn()).isTrue()
        assertThat(config().watchesVpn()).isFalse()
        assertThat(config().copy(enabled = false, bypassOnVpn = true).watchesVpn()).isFalse()
        assertThat(config(host = " ").copy(bypassOnVpn = true).watchesVpn()).isFalse()
    }

    @Test
    fun `the bypass switch alone does not change the signature clients are keyed by`() {
        assertThat(config().copy(bypassOnVpn = true).signature()).isEqualTo(config().signature())
    }
}
