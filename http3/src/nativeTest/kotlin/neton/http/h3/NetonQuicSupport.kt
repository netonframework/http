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
import neton.quic.proto.CryptoClientConfig
import neton.quic.proto.CryptoServerConfig
import neton.quic.proto.ServerConfig
import neton.quic.proto.TlsClientConfig
import neton.quic.proto.TlsHandshakeData
import neton.quic.proto.TlsServerConfig
import neton.quic.proto.TransportConfig
import neton.quic.proto.VarInt
import neton.quic.proto.default
import neton.quic.proto.withCrypto
import neton.quic.testkit.MockClientCrypto
import neton.quic.testkit.MockHandshakeData
import neton.quic.testkit.MockServerCrypto
import neton.quic.testkit.TestCa
import neton.quic.testkit.TestIdentity
import kotlin.test.assertContentEquals
import kotlin.time.Duration

// End-to-end harness (SPEC §5 layers 3 and 4): two neton.quic endpoints on loopback UDP, one server and one client.
// The handshake runs either on the real TLS 1.3 session of com.netonstream:quic (OpenSSL; the server presents a
// certificate issued by a test CA from quic-testkit's TestPki, the client trusts exactly that CA) or on the TLS test
// double of quic-testkit (MockTls). Both sides offer the ALPN "h3" through their TLS configuration, as an application
// does (see `neton.http.h3.quic.ALPN_H3`).

/** The TLS layer of a loopback connection. */
enum class TestTls {
    /** The real TLS 1.3 session (OpenSSL), with test certificates. */
    REAL,

    /** quic-testkit's TLS test double. */
    MOCK,
}

/**
 * Test certificates, generated once per test process: a CA and a server certificate it issued for `localhost` and
 * 127.0.0.1. The client's only trust anchor is the CA.
 */
object TestCerts {
    val ca: TestCa by lazy { TestCa.create("neton http3 test CA") }
    val server: TestIdentity by lazy { ca.issue("localhost", listOf("localhost"), listOf("127.0.0.1", "::1")) }

    /** The server side of HTTP/3 over real TLS: the server's chain (leaf, then CA) and key, ALPN [alpn]. */
    fun serverConfig(alpn: List<ByteArray> = listOf(ALPN_H3)): TlsServerConfig =
        TlsServerConfig(server.chainWith(ca.identity), server.privateKey, alpnProtocols = alpn)

    /** The client side of HTTP/3 over real TLS: trusts the test CA only, offers [alpn]. */
    fun clientConfig(alpn: List<ByteArray> = listOf(ALPN_H3)): TlsClientConfig =
        TlsClientConfig(trustAnchors = ca.trustAnchors, alpnProtocols = alpn)
}

/** The server crypto of [tls] offering [alpn]. */
fun serverCrypto(tls: TestTls, alpn: List<ByteArray> = listOf(ALPN_H3)): CryptoServerConfig = when (tls) {
    TestTls.REAL -> TestCerts.serverConfig(alpn)
    TestTls.MOCK -> MockServerCrypto(alpn = alpn)
}

/** The client crypto of [tls] offering [alpn]. */
fun clientCrypto(tls: TestTls, alpn: List<ByteArray> = listOf(ALPN_H3)): CryptoClientConfig = when (tls) {
    TestTls.REAL -> TestCerts.clientConfig(alpn)
    TestTls.MOCK -> MockClientCrypto(alpn = alpn)
}

/** The application protocol the handshake of [c] negotiated. */
fun negotiatedAlpn(c: neton.quic.Connection): ByteArray? = when (val data = c.handshakeData()) {
    is TlsHandshakeData -> data.protocol
    is MockHandshakeData -> data.protocol
    else -> error("unknown handshake data $data")
}

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
 * Connects a client endpoint to a server endpoint on 127.0.0.1 over [tls] and returns the established connection's
 * two ends; the client connects to the server name `localhost`, which the real server certificate carries.
 * [idleTimeout] null is QUIC's default (30 s); [transport] adjusts both endpoints' transport configuration.
 */
suspend fun CoroutineScope.quicLoopback(
    tls: TestTls,
    idleTimeout: Duration? = null,
    transport: TransportConfig.() -> Unit = {},
): QuicLoopback {
    val config = TransportConfig().apply {
        if (idleTimeout != null) maxIdleTimeout(IdleTimeout.of(idleTimeout))
        transport()
    }
    val serverEndpoint = Endpoint.create(
        EndpointConfig.default(),
        ServerConfig.withCrypto(serverCrypto(tls)).transportConfig(config),
        bindUdp(SocketAddress.IPV4_LOCALHOST_ANY_PORT),
    )
    val clientEndpoint = Endpoint.create(EndpointConfig.default(), null, bindUdp(SocketAddress.IPV4_LOCALHOST_ANY_PORT))
    val clientConfig = ClientConfig(clientCrypto(tls)).transportConfig(config)
    val connecting = async { clientEndpoint.connectWith(clientConfig, serverEndpoint.localAddr(), "localhost").await() }
    val server = checkNotNull(serverEndpoint.accept()) { "server endpoint closed" }.await()
    val client = connecting.await()
    for (c in listOf(client, server)) assertContentEquals(ALPN_H3, negotiatedAlpn(c), "negotiated ALPN")
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

/** neton.quic on loopback UDP over [tls]; the endpoints are cancelled with the test. */
fun netonQuic(tls: TestTls) = QuicPairFactory { idleTimeout, streamCapacity ->
    quicLoopback(tls, idleTimeout) { if (streamCapacity != null) streamReceiveWindow(VarInt(streamCapacity.toLong())) }
        .let { it.client to it.server }
}

/** neton.quic on loopback UDP with the TLS test double (layer 3). */
val NETON_QUIC = netonQuic(TestTls.MOCK)

/** neton.quic on loopback UDP with the real TLS session (layer 4's transport). */
val NETON_QUIC_TLS = netonQuic(TestTls.REAL)

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
