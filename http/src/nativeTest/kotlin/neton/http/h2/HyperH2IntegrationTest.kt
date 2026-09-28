package neton.http.h2

import neton.http.h1.CReq
import neton.http.h1.CRes
import neton.http.h1.SOME
import neton.http.h1.SReq
import neton.http.h1.SRes
import neton.http.h1.hyperTest
import kotlin.test.Test

/**
 * hyper 1.11.1 `tests/integration.rs`, the HTTP/2 runs: each `t!` case runs the HTTP/2 client against the HTTP/2
 * server over loopback TCP, directly and through the naive proxy (`client_version: 2`, `tests/support/mod.rs`), and
 * `http2_parallel_10` sends ten 8 KiB requests at once. The HTTP/1 runs are in `neton.http.h1.HyperIntegrationTest`.
 */
class HyperH2IntegrationTest {

    private fun t(client: List<Pair<CReq, CRes>>, server: List<Pair<SReq, SRes>>) {
        hyperTest { runT2(client, server, proxy = false) }
        hyperTest { runT2(client, server, proxy = true) }
    }

    @Test
    fun get1() = t(
        client = listOf(CReq(uri = "/") to CRes(status = 200, headers = listOf("date" to SOME))),
        server = listOf(SReq(uri = "/") to SRes()),
    )

    @Test
    fun getImplicitPath() = t(
        client = listOf(CReq(uri = "") to CRes(status = 200)),
        server = listOf(SReq(uri = "/") to SRes()),
    )

    @Test
    fun dateIsntOverwritten() = t(
        client = listOf(CReq() to CRes(status = 200, headers = listOf("date" to "let me through"))),
        server = listOf(SReq() to SRes(headers = listOf("date" to "let me through"))),
    )

    @Test
    fun getBody() = t(
        client = listOf(CReq(uri = "/") to CRes(status = 200, headers = listOf("content-length" to "11"), body = "hello world")),
        server = listOf(SReq(uri = "/") to SRes(headers = listOf("content-length" to "11"), body = "hello world")),
    )

    @Test
    fun getBody2KeepsAlive() = t(
        client = List(2) { CReq(uri = "/") to CRes(status = 200, headers = listOf("content-length" to "11"), body = "hello world") },
        server = List(2) { SReq(uri = "/") to SRes(headers = listOf("content-length" to "11"), body = "hello world") },
    )

    @Test
    fun getStripConnectionHeader() = t(
        // h2 doesn't actually receive the connection header
        client = listOf(CReq(uri = "/") to CRes(status = 200, body = "hello world")),
        // http2 should strip this header
        server = listOf(SReq(uri = "/") to SRes(headers = listOf("connection" to "close"), body = "hello world")),
    )

    @Test
    fun getStripKeepAliveHeader() = t(
        // h2 doesn't actually receive the keep-alive header
        client = listOf(CReq(uri = "/") to CRes(status = 200, body = "hello world")),
        // http2 should strip this header
        server = listOf(SReq(uri = "/") to SRes(headers = listOf("keep-alive" to "timeout=5, max=1000"), body = "hello world")),
    )

    @Test
    fun getStripUpgradeHeader() = t(
        // h2 doesn't actually receive the upgrade header
        client = listOf(CReq(uri = "/") to CRes(status = 200, body = "hello world")),
        // http2 should strip this header
        server = listOf(SReq(uri = "/") to SRes(headers = listOf("upgrade" to "h2c"), body = "hello world")),
    )

    @Test
    fun getAllowTeTrailersHeader() = t(
        // http2 strips connection headers other than TE "trailers"
        client = listOf(CReq(uri = "/", headers = listOf("te" to "trailers")) to CRes(status = 200)),
        server = listOf(SReq(uri = "/", headers = listOf("te" to "trailers")) to SRes()),
    )

    @Test
    fun getBodyChunked() = t(
        // h2 doesn't actually receive the transfer-encoding header
        client = listOf(CReq(uri = "/") to CRes(status = 200, body = "hello world")),
        // http2 should strip this header
        server = listOf(SReq(uri = "/") to SRes(headers = listOf("transfer-encoding" to "chunked"), body = "hello world")),
    )

    @Test
    fun postOutgoingLength() = t(
        client = listOf(CReq(method = "POST", uri = "/hello", body = "hello, world!") to CRes()),
        server = listOf(SReq(method = "POST", uri = "/hello", headers = listOf("content-length" to "13"), body = "hello, world!") to SRes()),
    )

    @Test
    fun postChunked() = t(
        // http2 should strip this header
        client = listOf(CReq(method = "POST", uri = "/post_chunked", headers = listOf("transfer-encoding" to "chunked"), body = "hello world") to CRes()),
        server = listOf(SReq(method = "POST", uri = "/post_chunked", body = "hello world") to SRes()),
    )

    @Test
    fun get2() = t(
        client = listOf(CReq(uri = "/1") to CRes(status = 200), CReq(uri = "/2") to CRes(status = 200)),
        server = listOf(SReq(uri = "/1") to SRes(), SReq(uri = "/2") to SRes()),
    )

    @Test
    fun http2Parallel10() {
        val body = "x".repeat(8192)
        val c = List(10) { CReq(uri = "/", body = body) to CRes(body = body) }
        val s = List(10) { SReq(uri = "/", body = body) to SRes(body = body) }
        hyperTest { runT2(c, s, proxy = false, parallel = true) }
        hyperTest { runT2(c, s, proxy = true, parallel = true) }
    }
}
