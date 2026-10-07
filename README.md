# http

HTTP for Kotlin/Native on top of `com.netonstream:io`. The first version replicates the capabilities of pinned Rust
references: `http` 1.5.0 (common types), `httparse` 1.10.1 and hyper 1.11.1 (HTTP/1.1), `h2` 0.4.19 and hyper's
HTTP/2 wiring, and hyper-util 0.1.20's `server::conn::auto` (HTTP/1 and HTTP/2 on one port). HTTP/3 (`h3` 0.0.8 over
`com.netonstream:quic`) is the separate repository [http3](https://github.com/netonframework/http3)
(`com.netonstream:http3`). Packages: `neton.http` (types), `neton.http.h1`, `neton.http.h2`, `neton.http.auto`.

Specification, every deliberate difference from the references (marked ⚖️), and the implementation record with all
measurements: [SPEC.md](SPEC.md).

## Status

Release coordinate: `com.netonstream:http:0.1.2`, built against `com.netonstream:io:0.3.0`. Kotlin 2.4.0, native
targets only (Linux, macOS, iOS, Android native, Windows mingw).

| Area | State |
|---|---|
| Common types (`Request`, `Response`, `HeaderMap`, `Uri`, `Method`, `StatusCode`, `Version`, `Extensions`) | done; the `http` crate's tests ported |
| HTTP/1.1 server and client (hyper `conn::http1`) | done; hyper's `tests/server.rs`, `tests/client.rs`, `tests/integration.rs` ported |
| HTTP/2 server and client (h2 + hyper `conn::http2`) | done; h2's `tests/h2-tests` and hyper's HTTP/2 tests ported |
| Upgrades, CONNECT and extended CONNECT tunnels | done |
| HTTP/3 | in the separate repository [http3](https://github.com/netonframework/http3) (`com.netonstream:http3`, `neton.http.h3`) |
| HTTP/1 or HTTP/2 on one port (hyper-util `server::conn::auto`) | done; hyper-util's tests ported; ALPN input added |
| Connection pooling and the rest of hyper-util | out of scope for this version |

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

Both on one port (hyper-util `auto::Builder`): `AutoServerConfig(http1Config, http2Config).serveConnection(stream, service)`
reads the HTTP/2 connection preface once per connection to pick the protocol (prior-knowledge h2c). Behind TLS, pass the
negotiated ALPN protocol (`serveConnection(tlsStream, tlsStream.alpn, service)`) and `"h2"` / `"http/1.1"` select the
protocol without reading first. `serveConnectionWithUpgrades` keeps HTTP/1 upgrades on.

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

HTTP/3: see the [http3](https://github.com/netonframework/http3) repository.

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
