package io.github.aedev.flow.network

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

/**
 * A loopback-only SOCKS5 front end for the SSH-forwarded remote SOCKS5 server.
 *
 * Local app clients use no-auth on 127.0.0.1. This bridge performs RFC 1929
 * username/password authentication on the remote SOCKS5 connection, avoiding
 * platform-dependent java.net.Authenticator behaviour for SOCKS sockets.
 */
internal class Socks5AuthBridge(
    private val listenPort: Int,
    private val upstreamPort: Int,
    username: String,
    password: String,
    private val bindHost: String = "127.0.0.1",
) : Closeable {
    private val usernameBytes = username.toByteArray(Charsets.UTF_8)
    private val passwordBytes = password.toByteArray(Charsets.UTF_8)
    private val executor: ExecutorService =
        Executors.newCachedThreadPool(
            ThreadFactory { task ->
                Thread(task, "Flow-Socks5Bridge").apply { isDaemon = true }
            },
        )
    private val activeSockets = ConcurrentHashMap.newKeySet<Socket>()

    @Volatile private var server: ServerSocket? = null

    init {
        require(usernameBytes.size in 1..255) { "SOCKS5 username must contain 1..255 UTF-8 bytes" }
        require(passwordBytes.size in 1..255) { "SOCKS5 password must contain 1..255 UTF-8 bytes" }
        require(listenPort in 0..65535)
        require(upstreamPort in 1..65535)
    }

    fun start(): Int {
        check(server == null) { "SOCKS5 bridge is already started" }
        val listener = ServerSocket()
        listener.reuseAddress = true
        listener.bind(InetSocketAddress(bindHost, listenPort))
        server = listener
        executor.execute { acceptLoop(listener) }
        Log.i(TAG, "Local SOCKS5 auth bridge listening on $bindHost:${listener.localPort}, upstream=127.0.0.1:$upstreamPort")
        return listener.localPort
    }

    private fun acceptLoop(listener: ServerSocket) {
        while (!listener.isClosed) {
            try {
                val client = listener.accept()
                activeSockets.add(client)
                executor.execute { handleClient(client) }
            } catch (error: SocketException) {
                if (!listener.isClosed) Log.w(TAG, "SOCKS5 bridge accept failed", error)
                return
            } catch (error: IOException) {
                if (!listener.isClosed) Log.w(TAG, "SOCKS5 bridge accept failed", error)
            }
        }
    }

    private fun handleClient(client: Socket) {
        var upstream: Socket? = null
        try {
            client.soTimeout = HANDSHAKE_TIMEOUT_MS
            val clientInput = DataInputStream(client.getInputStream())
            val clientOutput = client.getOutputStream()

            // Negotiate an unauthenticated connection from the app to this loopback bridge.
            if (clientInput.readUnsignedByte() != SOCKS_VERSION) throw IOException("Unsupported local SOCKS version")
            val methodCount = clientInput.readUnsignedByte()
            if (methodCount == 0) throw IOException("Client offered no SOCKS authentication methods")
            val methods = ByteArray(methodCount)
            clientInput.readFully(methods)
            if (NO_AUTH !in methods) {
                clientOutput.write(byteArrayOf(SOCKS_VERSION.toByte(), NO_ACCEPTABLE_METHOD.toByte()))
                clientOutput.flush()
                throw IOException("Local SOCKS client did not offer no-auth")
            }
            clientOutput.write(byteArrayOf(SOCKS_VERSION.toByte(), NO_AUTH.toByte()))
            clientOutput.flush()

            val connectRequest = readConnectRequest(clientInput)
            val remote = Socket()
            upstream = remote
            activeSockets.add(remote)
            remote.connect(InetSocketAddress("127.0.0.1", upstreamPort), CONNECT_TIMEOUT_MS)
            remote.soTimeout = HANDSHAKE_TIMEOUT_MS
            val upstreamInput = DataInputStream(remote.getInputStream())
            val upstreamOutput = remote.getOutputStream()

            // Ask the remote SOCKS5 server specifically for username/password (RFC 1929).
            upstreamOutput.write(byteArrayOf(SOCKS_VERSION.toByte(), 1, USER_PASS.toByte()))
            upstreamOutput.flush()
            val methodReply = ByteArray(2)
            upstreamInput.readFully(methodReply)
            if (methodReply[0].toInt() and 0xff != SOCKS_VERSION || methodReply[1].toInt() and 0xff != USER_PASS) {
                sendConnectFailure(clientOutput)
                throw IOException("Remote SOCKS5 server rejected username/password method")
            }

            upstreamOutput.write(byteArrayOf(1, usernameBytes.size.toByte()))
            upstreamOutput.write(usernameBytes)
            upstreamOutput.write(passwordBytes.size)
            upstreamOutput.write(passwordBytes)
            upstreamOutput.flush()

            val authReply = ByteArray(2)
            upstreamInput.readFully(authReply)
            if (authReply[0].toInt() and 0xff != 1 || authReply[1].toInt() != 0) {
                sendConnectFailure(clientOutput)
                Log.e(TAG, "Remote SOCKS5 username/password authentication rejected (status=${authReply[1].toInt() and 0xff})")
                throw IOException("Remote SOCKS5 username/password authentication failed")
            }
            Log.d(TAG, "Remote SOCKS5 authentication accepted")

            upstreamOutput.write(connectRequest)
            upstreamOutput.flush()
            val connectReply = readSocksReply(upstreamInput)
            clientOutput.write(connectReply)
            clientOutput.flush()
            if (connectReply[1].toInt() != 0) {
                Log.w(TAG, "Remote SOCKS5 CONNECT failed (reply=${connectReply[1].toInt() and 0xff})")
                return
            }

            client.soTimeout = 0
            remote.soTimeout = 0
            relay(client, remote)
        } catch (error: Exception) {
            if (error !is SocketException || !client.isClosed) {
                Log.w(TAG, "SOCKS5 bridge connection failed: ${error.message}")
            }
        } finally {
            activeSockets.remove(client)
            runCatching { client.close() }
            upstream?.let {
                activeSockets.remove(it)
                runCatching { it.close() }
            }
        }
    }

    private fun readConnectRequest(input: DataInputStream): ByteArray {
        val buffer = ByteArrayOutputStream()
        val header = ByteArray(4)
        input.readFully(header)
        if (header[0].toInt() and 0xff != SOCKS_VERSION) throw IOException("Invalid SOCKS request version")
        if (header[1].toInt() and 0xff != CONNECT_COMMAND) throw IOException("Only SOCKS CONNECT is supported")
        buffer.write(header)
        when (header[3].toInt() and 0xff) {
            IPV4 -> buffer.write(ByteArray(4).also(input::readFully))
            DOMAIN -> {
                val length = input.readUnsignedByte()
                buffer.write(length)
                buffer.write(ByteArray(length).also(input::readFully))
            }
            IPV6 -> buffer.write(ByteArray(16).also(input::readFully))
            else -> throw IOException("Unsupported SOCKS address type")
        }
        buffer.write(ByteArray(2).also(input::readFully))
        return buffer.toByteArray()
    }

    private fun readSocksReply(input: DataInputStream): ByteArray {
        val buffer = ByteArrayOutputStream()
        val header = ByteArray(4)
        input.readFully(header)
        if (header[0].toInt() and 0xff != SOCKS_VERSION) throw IOException("Invalid remote SOCKS reply")
        buffer.write(header)
        when (header[3].toInt() and 0xff) {
            IPV4 -> buffer.write(ByteArray(4).also(input::readFully))
            DOMAIN -> {
                val length = input.readUnsignedByte()
                buffer.write(length)
                buffer.write(ByteArray(length).also(input::readFully))
            }
            IPV6 -> buffer.write(ByteArray(16).also(input::readFully))
            else -> throw IOException("Unsupported remote SOCKS reply address type")
        }
        buffer.write(ByteArray(2).also(input::readFully))
        return buffer.toByteArray()
    }

    private fun sendConnectFailure(output: java.io.OutputStream) {
        output.write(byteArrayOf(SOCKS_VERSION.toByte(), GENERAL_FAILURE.toByte(), 0, IPV4.toByte(), 0, 0, 0, 0, 0, 0))
        output.flush()
    }

    private fun relay(client: Socket, upstream: Socket) {
        val finished = CountDownLatch(2)
        executor.execute {
            try {
                client.getInputStream().copyTo(upstream.getOutputStream())
                runCatching { upstream.shutdownOutput() }
            } catch (_: IOException) {
                runCatching { upstream.close() }
            } finally {
                finished.countDown()
            }
        }
        executor.execute {
            try {
                upstream.getInputStream().copyTo(client.getOutputStream())
                runCatching { client.shutdownOutput() }
            } catch (_: IOException) {
                runCatching { client.close() }
            } finally {
                finished.countDown()
            }
        }
        finished.await()
    }

    override fun close() {
        server?.let { runCatching { it.close() } }
        server = null
        activeSockets.forEach { runCatching { it.close() } }
        activeSockets.clear()
        executor.shutdownNow()
    }

    companion object {
        private const val TAG = "Socks5AuthBridge"
        private const val SOCKS_VERSION = 5
        private const val NO_AUTH = 0
        private const val NO_ACCEPTABLE_METHOD = 0xff
        private const val USER_PASS = 2
        private const val CONNECT_COMMAND = 1
        private const val IPV4 = 1
        private const val DOMAIN = 3
        private const val IPV6 = 4
        private const val GENERAL_FAILURE = 1
        private const val HANDSHAKE_TIMEOUT_MS = 15_000
        private const val CONNECT_TIMEOUT_MS = 10_000
    }
}
