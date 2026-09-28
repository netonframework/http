package neton.http.h3

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import neton.http.h3.quic.ALPN_H3
import neton.http.h3.quic.Connection
import neton.http.h3.quic.QuicConnection
import neton.http.h3.quic.asH3
import neton.io.net.SocketAddress
import neton.io.net.bindUdp
import neton.quic.Endpoint
import neton.quic.proto.ClientConfig
import neton.quic.proto.ConnectionError
import neton.quic.proto.EndpointConfig
import neton.quic.proto.IdleTimeout
import neton.quic.proto.ServerConfig
import neton.quic.proto.TransportConfig
import neton.quic.proto.VarInt
import neton.quic.proto.default
import neton.quic.proto.withCrypto
import neton.quic.testkit.MockClientCrypto
import neton.quic.testkit.MockHandshakeData
import neton.quic.testkit.MockServerCrypto
import kotlin.test.assertContentEquals
import kotlin.time.Duration

// End-to-end harness (SPEC §5 layer 3): two neton.quic endpoints on loopback UDP, one server and one client, with the
// handshake run by the TLS test double of com.netonstream:quic-testkit (there is no real TLS yet). Both sides offer
// the ALPN "h3" through the double's configuration, as an application will through its TLS configuration.

/** Both endpoints and the two HTTP/3 views of the connection between them. */
class QuicLoopback(
    val serverEndpoint: Endpoint,
    val clientEndpoint: Endpoint,
    val client: QuicConnection,
    val server: QuicConnection,
) {
    /** Closes both endpoints (their connections with code 0) and releases them. */
    fun shutdown() {
        for (e in listOf(clientEndpoint, serverEndpoint)) {
            e.close(VarInt(0), ByteArray(0))
            e.close()
        }
    }
}

/**
 * Connects a client endpoint to a server endpoint on 127.0.0.1 and returns the established connection's two ends.
 * [idleTimeout] null is QUIC's default (30 s); [transport] adjusts both endpoints' transport configuration.
 */
suspend fun CoroutineScope.quicLoopback(
    idleTimeout: Duration? = null,
    transport: TransportConfig.() -> Unit = {},
): QuicLoopback {
    val config = TransportConfig().apply {
        if (idleTimeout != null) maxIdleTimeout(IdleTimeout.of(idleTimeout))
        transport()
    }
    val serverCrypto = MockServerCrypto(alpn = listOf(ALPN_H3))
    val serverEndpoint = Endpoint.create(
        EndpointConfig.default(),
        ServerConfig.withCrypto(serverCrypto).transportConfig(config),
        bindUdp(SocketAddress.IPV4_LOCALHOST_ANY_PORT),
    )
    val clientEndpoint = Endpoint.create(EndpointConfig.default(), null, bindUdp(SocketAddress.IPV4_LOCALHOST_ANY_PORT))
    val clientConfig = ClientConfig(MockClientCrypto(alpn = listOf(ALPN_H3))).transportConfig(config)
    val connecting = async { clientEndpoint.connectWith(clientConfig, serverEndpoint.localAddr(), "localhost").await() }
    val server = checkNotNull(serverEndpoint.accept()) { "server endpoint closed" }.await()
    val client = connecting.await()
    for (c in listOf(client, server)) {
        val data = c.handshakeData() as MockHandshakeData
        assertContentEquals(ALPN_H3, data.protocol, "negotiated ALPN")
    }
    return QuicLoopback(serverEndpoint, clientEndpoint, client.asH3(), server.asH3())
}

/**
 * The thin-interface pair the connection-layer tests run on: client first, server second. [idleTimeout] null: none
 * for the double, QUIC's default for neton.quic. [streamCapacity] null: the default; otherwise the bytes a stream
 * buffers before its writer is held back (the double's buffer; neton.quic's stream receive window).
 */
fun interface QuicPairFactory {
    suspend fun CoroutineScope.pair(idleTimeout: Duration?, streamCapacity: Int?): Pair<Connection, Connection>
}

/** The in-memory QUIC double (layer 2). */
val MEMORY_QUIC = QuicPairFactory { idleTimeout, streamCapacity ->
    memoryQuicPair(this, streamCapacity = streamCapacity ?: (64 * 1024), idleTimeout = idleTimeout)
}

/** neton.quic on loopback UDP (layer 3); the endpoints are cancelled with the test. */
val NETON_QUIC = QuicPairFactory { idleTimeout, streamCapacity ->
    quicLoopback(idleTimeout) { if (streamCapacity != null) streamReceiveWindow(VarInt(streamCapacity.toLong())) }
        .let { it.client to it.server }
}

/**
 * The application error code this end saw the connection closed with, by either side (null: open, or closed
 * otherwise). neton.quic learns of the peer's close asynchronously; callers wait for it.
 */
fun Connection.applicationCloseCode(): Long? = when (this) {
    is MemoryConnection -> link.closeCode
    is QuicConnection -> when (val reason = conn.closeReason()) {
        is ConnectionError.ApplicationClosed -> reason.reason.errorCode.value
        else -> null
    }
    else -> error("unknown QUIC connection $this")
}
