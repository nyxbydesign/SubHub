package com.subhub.app.remote

import android.content.Context
import computer.iroh.BiStream
import computer.iroh.Endpoint
import computer.iroh.EndpointAddr
import computer.iroh.EndpointId
import computer.iroh.EndpointOptions
import computer.iroh.EndpointTicket
import computer.iroh.IrohAndroid
import computer.iroh.RelayMode
import computer.iroh.presetMinimal
import computer.iroh.presetN0
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/** Java-facing streams keep coroutines and native handles inside this adapter. */
class IrohPeerTransport private constructor(private val endpoint: Endpoint, private val relaysEnabled: Boolean) : PeerTransport {
    private val closed = AtomicBoolean()

    override fun localIdentity(): String = endpoint.id().use { hex(it.toBytes()) }

    override fun inviteRoute(): String {
        if (relaysEnabled) {
            try { timed(10_000) { endpoint.online() } }
            catch (timeout: SocketTimeoutException) { /* A direct LAN invitation can still work offline. */ }
        }
        return endpoint.addr().use { address ->
            EndpointTicket.fromAddr(address).use { it.toString() }
        }
    }

    @Throws(IOException::class)
    override fun connect(expectedPeerIdentity: String, route: String, timeoutMillis: Long): PeerTransport.Connection {
        RemoteProtocol.peerId(expectedPeerIdentity)
        if (closed.get() || route.length > 4096) throw IOException("Transport unavailable")
        return timed(timeoutMillis) {
            val address = if (route.isEmpty()) {
                EndpointId.fromBytes(unhex(expectedPeerIdentity)).use { EndpointAddr(it, null, emptyList()) }
            } else {
                EndpointTicket.fromString(route).use { it.endpointAddr() }
            }
            address.use {
                val identity = it.id().use { id -> hex(id.toBytes()) }
                if (identity != expectedPeerIdentity) throw IOException("Invitation identity mismatch")
                val connection = endpoint.connect(it, ALPN)
                try {
                    if (connection.remoteId().use { id -> hex(id.toBytes()) } != expectedPeerIdentity) {
                        throw IOException("Peer identity mismatch")
                    }
                    val stream = connection.openBi()
                    try {
                        stream.send().use { send -> send.writeAll(byteArrayOf(PREFACE)) }
                        IrohConnection(connection, stream)
                    } catch (error: Throwable) {
                        stream.close()
                        throw error
                    }
                } catch (error: Throwable) {
                    connection.close(1, byteArrayOf())
                    connection.close()
                    throw error
                }
            }
        }
    }

    @Throws(IOException::class)
    override fun accept(timeoutMillis: Long): PeerTransport.Connection = timed(timeoutMillis) {
        if (closed.get()) throw IOException("Transport unavailable")
        val incoming = endpoint.acceptNext() ?: throw IOException("Transport closed")
        incoming.use {
            it.accept().use { accepting ->
                if (!accepting.alpn().contentEquals(ALPN)) throw IOException("Unsupported peer protocol")
                val connection = accepting.connect()
                try {
                    val stream = connection.acceptBi()
                    try {
                        val preface = stream.recv().use { recv -> recv.readExact(1u) }
                        if (!preface.contentEquals(byteArrayOf(PREFACE))) throw IOException("Unsupported peer stream")
                        IrohConnection(connection, stream)
                    } catch (error: Throwable) {
                        stream.close()
                        throw error
                    }
                } catch (error: Throwable) {
                    connection.close(1, byteArrayOf())
                    connection.close()
                    throw error
                }
            }
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try { timed(3000) { endpoint.shutdown() } }
            finally { endpoint.close() }
        }
    }

    private class IrohConnection(private val native: computer.iroh.Connection, private val stream: BiStream) : PeerTransport.Connection {
        private val closed = AtomicBoolean()
        @Volatile private var deadlineNanos = System.nanoTime() + 10_000_000_000L
        private val receiver = stream.recv()
        private val sender = stream.send()
        private val input = BufferedInputStream(object : InputStream() {
            override fun read(): Int {
                val data = ByteArray(1)
                return if (read(data, 0, 1) == -1) -1 else data[0].toInt() and 255
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (offset < 0 || length < 0 || offset > buffer.size - length) throw IndexOutOfBoundsException()
                if (length == 0) return 0
                if (closed.get()) throw IOException("Connection closed")
                try {
                    val bytes = timed(remainingMillis()) { receiver.read(length.coerceAtMost(64 * 1024).toUInt()) }
                    if (bytes.isEmpty()) return -1
                    bytes.copyInto(buffer, offset)
                    return bytes.size
                } catch (error: IOException) { close(); throw error }
            }
        }, 64 * 1024)
        private val output = BufferedOutputStream(object : OutputStream() {
            override fun write(value: Int) { write(byteArrayOf(value.toByte()), 0, 1) }
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                if (offset < 0 || length < 0 || offset > buffer.size - length) throw IndexOutOfBoundsException()
                if (closed.get()) throw IOException("Connection closed")
                try { timed(remainingMillis()) { sender.writeAll(buffer.copyOfRange(offset, offset + length)) } }
                catch (error: IOException) { close(); throw error }
            }
        }, 64 * 1024)

        override fun authenticatedPeerIdentity(): String = native.remoteId().use { hex(it.toBytes()) }
        override fun input(): InputStream = input
        override fun output(): OutputStream = output
        override fun setReadTimeoutMillis(timeoutMillis: Long) {
            require(timeoutMillis in 1..60_000)
            deadlineNanos = System.nanoTime() + timeoutMillis * 1_000_000
        }
        private fun remainingMillis(): Long {
            val remaining = deadlineNanos - System.nanoTime()
            if (remaining <= 0) throw SocketTimeoutException("Peer operation timed out")
            return ((remaining + 999_999) / 1_000_000).coerceAtMost(60_000)
        }
        override fun isDirect(): Boolean = native.paths().any { it.isSelected && it.isIp }
        override fun close() {
            if (closed.compareAndSet(false, true)) {
                try { native.close(0, byteArrayOf()) }
                finally { receiver.close(); sender.close(); stream.close(); native.close() }
            }
        }
    }

    companion object {
        private val ALPN = RemoteProtocol.ALPN.toByteArray(StandardCharsets.US_ASCII)
        private const val PREFACE: Byte = 0x53
        @Volatile private var contextInstalled = false

        @JvmStatic
        @Throws(IOException::class)
        fun open(context: Context, seed: ByteArray, relayUrls: Array<String>?, timeoutMillis: Long): IrohPeerTransport {
            require(seed.size == 32)
            synchronized(this) {
                if (!contextInstalled) {
                    IrohAndroid.installAndroidContext(context.applicationContext)
                    contextInstalled = true
                }
            }
            val secret = seed.copyOf()
            try {
                val relayMode = when {
                    relayUrls == null -> null
                    relayUrls.isEmpty() -> RelayMode.disabled()
                    else -> {
                        relayUrls.forEach {
                            val uri = URI(it)
                            require(uri.scheme == "https" && uri.host != null && uri.userInfo == null && uri.fragment == null)
                        }
                        RelayMode.customFromUrls(relayUrls.toList())
                    }
                }
                val options = EndpointOptions(
                    preset = if (relayUrls?.isEmpty() == true) presetMinimal() else presetN0(),
                    secretKey = secret, alpns = listOf(ALPN), relayMode = relayMode
                )
                try { return IrohPeerTransport(timed(timeoutMillis) { Endpoint.bind(options) }, relayUrls?.isEmpty() != true) }
                finally { options.destroy() }
            } finally { secret.fill(0) }
        }

        private fun <T> timed(timeoutMillis: Long, block: suspend () -> T): T {
            require(timeoutMillis in 1..60_000)
            try { return runBlocking { withTimeout(timeoutMillis) { block() } } }
            catch (timeout: TimeoutCancellationException) { throw SocketTimeoutException("Peer operation timed out") }
            catch (error: IOException) { throw error }
            catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Peer operation interrupted")
            }
            catch (error: Exception) { throw IOException("Peer transport operation failed", error) }
        }

        private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
        private fun unhex(value: String): ByteArray = ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
}
