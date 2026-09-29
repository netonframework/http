# http

HTTP for Kotlin/Native on top of `com.netonstream:io`. The first version replicates the capabilities of pinned Rust
references: `http` 1.5.0 (common types), `httparse` 1.10.1 and hyper 1.11.1 (HTTP/1.1), `h2` 0.4.19 and hyper's
HTTP/2 wiring, and `h3` 0.0.8 (HTTP/3 over `com.netonstream:quic`, in the separate artifact `com.netonstream:http3`).
Packages: `neton.http` (types), `neton.http.h1`, `neton.http.h2`, `neton.http.h3`.

Specification, every deliberate difference from the references (marked ⚖️), and the implementation record with all
measurements: [SPEC.md](SPEC.md).

## Status

Not published yet; built against `com.netonstream:io:0.2.0-SNAPSHOT` from `mavenLocal`. Kotlin 2.4.0, native
targets only (Linux, macOS, iOS, Android native, Windows mingw).

| Area | State |
|---|---|
| Common types (`Request`, `Response`, `HeaderMap`, `Uri`, `Method`, `StatusCode`, `Version`, `Extensions`) | done; the `http` crate's tests ported |
| HTTP/1.1 server and client (hyper `conn::http1`) | done; hyper's `tests/server.rs`, `tests/client.rs`, `tests/integration.rs` ported |
| HTTP/2 server and client (h2 + hyper `conn::http2`) | done; h2's `tests/h2-tests` and hyper's HTTP/2 tests ported |
| Upgrades, CONNECT and extended CONNECT tunnels | done |
| HTTP/3 (h3 0.0.8 over `com.netonstream:quic`, artifact `com.netonstream:http3`) | implemented; h3's connection and request tests ported and run in memory, over neton.quic with the TLS test double and over neton.quic with real TLS 1.3; interop with external implementations: see SPEC §11 |
| Connection pooling, protocol auto-detection (hyper-util) | out of scope for this version |

## Conformance and tests

- About 1,200 tests. The suite runs over in-memory streams and over real loopback TCP
  (`NETON_HTTP_TEST_TRANSPORT=tcp`), with the epoll and io_uring drivers on Linux. The exception is h2's mock-based
  tests, which stay in memory (SPEC §11).
- h2spec 2.1.1: 145 / 145 against the HTTP/2 server.
- curl interop, HTTP/1.x and h2c: `http-bench/curl-interop.sh`.
- Fuzz targets of `httparse`, `http` and `h2` ported as seeded tests with coverage floors.
- End-to-end acceptance: every split point of pipelined requests, request-smuggling vectors, and a 100 MB streamed
  upload in bounded memory.

## Security defaults that differ from hyper

These are stricter than hyper, and each one is configurable:
- Transfer-Encoding together with Content-Length is rejected (400).
- The request line is limited to 8 KiB and the header section to 64 KiB.
- Request bodies are limited to 10 MiB (413).
- Header read timeout 10 s, keep-alive idle timeout 60 s.

The full table is SPEC §3.9.

## Usage

HTTP/1 server:

```kotlin
val hello = Bytes.copyOf("Hello, World!".encodeToByteArray())
val config = Http1ServerConfig()
serveTcp("127.0.0.1", 3000, reactors = 1, shutdownOnSignals = true) { stream ->
    runCatching { config.serveConnection(stream) { request -> Response<Body>(FullBody(hello)) }.serve() }
}
```

HTTP/2 over cleartext: the same service, with `Http2ServerConfig().serveConnection(stream, service).serve()`.

HTTP/1 client (hyper `client::conn::http1`):

```kotlin
runReactor {
    val stream = connect("127.0.0.1", 3000)
    val (sender, connection) = http1Handshake(stream)
    launch { connection.run() }
    sender.ready()
    val response = sender.sendRequest(Request.builder().uri("/").header("host", "127.0.0.1").body(EmptyBody as Body))
    while (response.body.nextFrame() != null) {}
}
```

HTTP/2 client: `neton.http.h2.http2Handshake(stream)` with an absolute URI. `SendRequest.clone()` gives one sender per
concurrent stream.

HTTP/3 (`neton.http.h3`) runs on a `neton.quic` connection whose TLS handshake negotiated ALPN "h3". The TLS
configuration is `neton.quic.proto`'s TLS 1.3 session (OpenSSL): the server gives its certificate chain and key, the
client gives its trust anchors explicitly (there is no system trust store), and both offer `ALPN_H3`:

```kotlin
// Server
val tls = TlsServerConfig(Certificates.pem(chainPem), PrivateKey.pem(keyPem), alpnProtocols = listOf(ALPN_H3))
val endpoint = Endpoint.create(EndpointConfig.default(), ServerConfig.withCrypto(tls), bindUdp(address))
while (true) {
    val quic = endpoint.accept()?.await() ?: break
    launch {
        val conn = neton.http.h3.server.newConnection(quic.asH3())
        while (true) {
            val (request, stream) = conn.accept()?.resolveRequest() ?: break
            stream.sendResponse(Response.builder().status(200).body(Unit))
            stream.sendData(Bytes.copyOf("hello".encodeToByteArray()))
            stream.finish()
        }
    }
}

// Client
val tls = TlsClientConfig(trustAnchors = Certificates.pem(caPem), alpnProtocols = listOf(ALPN_H3))
val quic = clientEndpoint.connectWith(ClientConfig(tls), serverAddress, "example.com").await()
val (driver, sender) = neton.http.h3.client.newClient(quic.asH3())
launch { driver.run() }
val stream = sender.sendRequest(Request.get("https://example.com/").body(Unit))
stream.finish()
val response = stream.recvResponse()
while (true) stream.recvData() ?: break
```

A server certificate that does not chain to the client's trust anchors or does not match the server name fails the
handshake, as does a peer without "h3". The KDoc of `neton.http.h3.quic.ALPN_H3` has the details.

Complete programs: `http-bench/src/nativeMain/kotlin/neton/http/bench/` (`HelloServer.kt`, `EchoServer.kt`,
`HelloClient.kt`).

## Performance

These figures were measured on a 4-vCPU Linux VM with one pinned core, against the same program written with hyper.
They are instructions per request from cachegrind, which is the method used for every change (SPEC §11). They apply
only to these hello-world shapes: they are not a claim about production maturity or about other workloads.

| Scenario | neton | hyper |
|---|---|---|
| HTTP/1 server, hello | ~11,500 | 6,549 |
| HTTP/1 server, browser-like request (9 headers) | ~25,600 | 13,173 |
| HTTP/2 server, hello (h2c, 10 × 10 streams) | ~37,200 | 27,070 |
| HTTP/1 client | ~35,050 | 17,260 |
| HTTP/2 client | ~39,540 | 28,115 |

Most of the remaining gap is Kotlin/Native runtime cost: GC sweeping, stack-frame zeroing, and one heap object per
protocol value. The details and the measured history of each optimisation are in SPEC §11.

On a single pinned core, the GC thread's time-to-safepoint spin dominates p99. Applications can lower its priority
with neton-io's `GcTuning.lowerGcThreadPriority` / `NETON_IO_GC_THREAD_NICE`.

## Building and testing

```
./gradlew :http:macosArm64Test                                   # or linuxX64Test (NETON_IO_DRIVER=epoll|iouring)
NETON_HTTP_TEST_TRANSPORT=tcp ./gradlew :http:macosArm64Test --rerun-tasks
./gradlew :http-bench:linkHelloServerReleaseExecutableLinuxX64   # bench programs
```
