package neton.http.h1

import kotlin.test.Test

/**
 * hyper 1.11.1 `tests/integration.rs`: each `t!` case runs the HTTP/1 client against the HTTP/1 server over loopback
 * TCP, directly and through the naive proxy (`tests/support/mod.rs`). The HTTP/2 runs of each case and the HTTP/2-only
 * `http2_parallel_10` are not ported.
 */
class HyperIntegrationTest {

    private fun t(client: List<Pair<CReq, CRes>>, server: List<Pair<SReq, SRes>>) {
        hyperTest { runT(client, server, proxy = false) }
        hyperTest { runT(client, server, proxy = true) }
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
        client = listOf(CReq(uri = "/") to CRes(status = 200, body = "hello world")),
        server = listOf(SReq(uri = "/") to SRes(headers = listOf("keep-alive" to "timeout=5, max=1000"), body = "hello world")),
    )

    @Test
    fun getStripUpgradeHeader() = t(
        client = listOf(CReq(uri = "/") to CRes(status = 200, body = "hello world")),
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
}
