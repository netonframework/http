package neton.http.h3.quic

import neton.http.h3.Code
import neton.http.h3.proto.StreamId
import neton.io.bytes.Bytes
import neton.quic.ReadError
import neton.quic.StoppedError
import neton.quic.WriteError
import neton.quic.proto.ClosedStream
import neton.quic.proto.ConnectionError
import neton.quic.proto.VarInt
import neton.quic.Connection as QuinnConnection
import neton.quic.RecvStream as QuinnRecvStream
import neton.quic.SendStream as QuinnSendStream

// The thin QUIC interface (`Quic.kt`) on com.netonstream:quic: the role of h3-quinn (`h3-quinn/src/lib.rs`), which
// adapts quinn's connection and streams to h3's `quic` traits. neton.quic is a port of quinn, so the mapping is the
// one h3-quinn makes, call for call and error for error (SPEC §5, §11 "HTTP/3 阶段 C").
//
// ALPN. An HTTP/3 connection is a QUIC connection whose TLS handshake negotiated the protocol "h3" (RFC 9114 §3.1);
// ALPN is part of the TLS configuration, not of QUIC's: the application sets [ALPN_H3] on the TLS configuration it
// passes to neton.quic's `ServerConfig` / `ClientConfig` (as h3's examples set `alpn_protocols = vec![b"h3"]` on the
// rustls configs they give quinn), and the handshake fails with no_application_protocol when the peers share none.
// This adapter does not check ALPN itself (neither does h3-quinn). See [ALPN_H3] for building both configurations.

/**
 * The ALPN protocol ID of HTTP/3 (RFC 9114 §3.1), to set on the TLS configuration of both endpoints.
 *
 * Building the QUIC configurations for HTTP/3 with neton.quic's TLS 1.3 session (`neton.quic.proto`):
 *
 * ```kotlin
 * // Server: the certificate chain (leaf first, then intermediates) and its private key, both PEM or DER.
 * val serverTls = TlsServerConfig(
 *     certificateChain = Certificates.pem(chainPem),
 *     privateKey = PrivateKey.pem(keyPem),
 *     alpnProtocols = listOf(ALPN_H3),
 * )
 * val endpoint = Endpoint.create(EndpointConfig.default(), ServerConfig.withCrypto(serverTls), bindUdp(address))
 * val conn = endpoint.accept()!!.await()
 * val h3 = neton.http.h3.server.newConnection(conn.asH3())
 *
 * // Client: the trust anchors are explicit (the CA certificates that issued the server's chain, or a self-signed
 * // server certificate); there is no system trust store. The server name passed to connect is checked against the
 * // certificate's DNS names (or IP addresses, when it is one).
 * val clientTls = TlsClientConfig(trustAnchors = Certificates.pem(caPem), alpnProtocols = listOf(ALPN_H3))
 * val client = Endpoint.create(EndpointConfig.default(), null, bindUdp(localAddress))
 * val quic = client.connectWith(ClientConfig(clientTls), serverAddress, "example.com").await()
 * val (driver, sendRequest) = neton.http.h3.client.newClient(quic.asH3())
 * ```
 *
 * A peer that offers no protocol in common fails the handshake with the TLS alert no_application_protocol; a server
 * certificate that does not chain to the client's trust anchors, or does not match the server name, fails it with a
 * certificate alert. `TlsClientConfig.dangerousNoServerVerificationForTestsOnly` exists for local tests only.
 * Configurations are `AutoCloseable`; sessions already started keep what they need.
 */
val ALPN_H3: ByteArray get() = byteArrayOf('h'.code.toByte(), '3'.code.toByte())

/** Wraps a neton.quic connection for HTTP/3 (`h3_quinn::Connection::new`). */
fun QuinnConnection.asH3(): QuicConnection = QuicConnection(this)

/**
 * A neton.quic connection as the thin interface's [Connection] (h3-quinn `Connection`). Stream handles are created per
 * call; the connection itself is not closed by HTTP/3 except through [close].
 */
class QuicConnection(
    /** The underlying neton.quic connection. */
    val conn: QuinnConnection,
) : Connection {
    override suspend fun acceptUni(): RecvStream = try {
        QuicRecvStream(conn.acceptUni())
    } catch (e: ConnectionError) {
        throw convertConnectionError(e)
    }

    override suspend fun acceptBi(): BidiStream = try {
        val (send, recv) = conn.acceptBi()
        QuicBidiStream(QuicSendStream(send, conn), QuicRecvStream(recv))
    } catch (e: ConnectionError) {
        throw convertConnectionError(e)
    }

    override suspend fun openBi(): BidiStream = try {
        val (send, recv) = conn.openBi()
        QuicBidiStream(QuicSendStream(send, conn), QuicRecvStream(recv))
    } catch (e: ConnectionError) {
        throw StreamErrorIncoming.ConnectionLost(convertConnectionError(e))
    }

    override suspend fun openUni(): SendStream = try {
        QuicSendStream(conn.openUni(), conn)
    } catch (e: ConnectionError) {
        throw StreamErrorIncoming.ConnectionLost(convertConnectionError(e))
    }

    /** `close`: CONNECTION_CLOSE of type 0x1d with the HTTP/3 [code]. Idempotent (neton.quic ignores a second close). */
    override fun close(code: Code, reason: ByteArray) {
        conn.close(VarInt.fromLong(code.value), reason)
    }

    /** h3-quinn's `opener` clones the connection handle; the handle here is stateless, so it is this object. */
    override fun opener(): OpenStreams = this

    override fun toString(): String = "QuicConnection(${conn.side()})"
}

/** The sending side of a neton.quic stream (h3-quinn `SendStream`). */
class QuicSendStream(
    /** The underlying neton.quic stream. */
    val stream: QuinnSendStream,
    private val conn: QuinnConnection,
) : SendStream {
    override val sendId: StreamId = StreamId(stream.id.value)

    /**
     * h3-quinn `send_data` + `poll_ready`: writes all of [data] without copying, suspending while flow or congestion
     * control blocks the stream (neton.quic parks the writer until the peer grants credit), so a slow reader holds
     * the writer back.
     */
    override suspend fun write(data: Bytes) {
        if (data.size == 0) return // h3-quinn's `poll_ready` loop does not touch the stream for an empty buffer
        try {
            stream.writeChunk(data)
        } catch (e: WriteError) {
            throw convertWriteError(e)
        }
    }

    /**
     * h3-quinn `poll_finish`: FIN. neton.quic (like quinn) treats a stream the peer stopped as finished; a stream
     * already finished or reset is [StreamErrorIncoming.Unknown] (h3-quinn maps every `finish` error so).
     * ⚖️ A closed connection is reported as [StreamErrorIncoming.ConnectionLost] rather than `Unknown`, as the other
     * operations do, so that HTTP/3 reports the connection's error (neton.quic's `finish` does not check it).
     */
    override suspend fun finish() {
        conn.closeReason()?.let { throw StreamErrorIncoming.ConnectionLost(convertConnectionError(it)) }
        try {
            stream.finish()
        } catch (e: ClosedStream) {
            throw StreamErrorIncoming.Unknown(e)
        }
    }

    /** h3-quinn `reset`: RESET_STREAM; errors (the stream already finished or reset) are ignored. */
    override fun reset(errorCode: Long) {
        try {
            stream.reset(VarInt.fromLongOrNull(errorCode) ?: VarInt.MAX)
        } catch (_: ClosedStream) {
        }
    }

    /** neton.quic `stopped` (not in h3's trait; see [SendStream.stopped]). */
    override suspend fun stopped(): Long? = try {
        stream.stopped()?.value
    } catch (e: StoppedError) {
        throw when (e) {
            is StoppedError.ConnectionLost -> StreamErrorIncoming.ConnectionLost(convertConnectionError(e.error))
            is StoppedError.ZeroRttRejected -> StreamErrorIncoming.Unknown(e)
        }
    }

    override fun toString(): String = "QuicSendStream(${stream.id})"
}

/** The receiving side of a neton.quic stream (h3-quinn `RecvStream`). */
class QuicRecvStream(
    /** The underlying neton.quic stream. */
    val stream: QuinnRecvStream,
) : RecvStream {
    override val recvId: StreamId = StreamId(stream.id.value)

    /** h3-quinn `poll_data`: the next chunk in order, without copying (`read_chunk(usize::MAX, true)`); null at the end. */
    override suspend fun read(): Bytes? = try {
        stream.readChunk(Int.MAX_VALUE, true)?.bytes
    } catch (e: ReadError) {
        throw convertReadError(e)
    }

    /** h3-quinn `stop_sending`: STOP_SENDING; errors (already stopped, finished or reset) are ignored (`.ok()`). */
    override fun stopSending(errorCode: Long) {
        try {
            stream.stop(VarInt.fromLongOrNull(errorCode) ?: VarInt.MAX)
        } catch (_: ClosedStream) {
        }
    }

    override fun toString(): String = "QuicRecvStream(${stream.id})"
}

/** Both sides of a bidirectional neton.quic stream (h3-quinn `BidiStream`). */
class QuicBidiStream(
    private val send: QuicSendStream,
    private val recv: QuicRecvStream,
) : BidiStream, SendStream by send, RecvStream by recv {
    override fun split(): Pair<SendStream, RecvStream> = send to recv

    override fun toString(): String = "QuicBidiStream($sendId)"
}

/**
 * h3-quinn `convert_connection_error`: the peer's application close keeps its code, the idle timeout is a timeout, and
 * everything else (a transport error, a reset, a local close, ...) is not HTTP/3's business.
 */
fun convertConnectionError(e: ConnectionError): ConnectionErrorIncoming = when (e) {
    is ConnectionError.ApplicationClosed -> ConnectionErrorIncoming.ApplicationClose(e.reason.errorCode.value)
    ConnectionError.TimedOut -> ConnectionErrorIncoming.Timeout()
    else -> ConnectionErrorIncoming.Undefined(e)
}

/** h3-quinn `convert_read_error_to_stream_error`. */
private fun convertReadError(e: ReadError): StreamErrorIncoming = when (e) {
    is ReadError.Reset -> StreamErrorIncoming.StreamTerminated(e.errorCode.value)
    is ReadError.ConnectionLost -> StreamErrorIncoming.ConnectionLost(convertConnectionError(e.error))
    // h3-quinn panics on IllegalOrderedRead ("h3-quinn only performs ordered reads"); this adapter only reads in order
    is ReadError.ClosedStream, is ReadError.IllegalOrderedRead, is ReadError.ZeroRttRejected -> StreamErrorIncoming.Unknown(e)
}

/** h3-quinn `convert_write_error_to_stream_error`. */
private fun convertWriteError(e: WriteError): StreamErrorIncoming = when (e) {
    is WriteError.Stopped -> StreamErrorIncoming.StreamTerminated(e.errorCode.value)
    is WriteError.ConnectionLost -> StreamErrorIncoming.ConnectionLost(convertConnectionError(e.error))
    is WriteError.ClosedStream, is WriteError.ZeroRttRejected -> StreamErrorIncoming.Unknown(e)
}
