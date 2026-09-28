package neton.http.h1

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import neton.http.Body
import neton.http.FullBody
import neton.http.HttpError
import neton.http.Response
import neton.io.bytes.Buffer
import neton.io.bytes.Bytes
import neton.io.core.Admission
import neton.io.core.AdmissionTimeoutException
import neton.io.core.IoStream
import neton.http.testStreamPair
import neton.io.net.runReactor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** SPEC §3.10 admission: idle connections hold no permit; a request takes one from its first byte to its response. */
class AdmissionTest {
    private suspend fun IoStream.send(s: String) { write(Buffer().also { it.writeBytes(s.encodeToByteArray()) }) }
    private suspend fun IoStream.readUntil(marker: String): String {
        val acc = Buffer()
        while (!acc.peekAll().decodeToString().contains(marker)) { if (read(acc) < 0) break }
        return acc.readAll().decodeToString()
    }
    private fun cfg(a: Admission) = Http1ServerConfig(headerReadTimeoutMillis = 0, keepAliveIdleTimeoutMillis = 0, autoDateHeader = false, admission = a)
    private fun ok(s: String): Response<Body> = Response(FullBody(Bytes.copyOf(s.encodeToByteArray())))

    @Test
    fun oneRequestAtATimeAcrossConnectionsAndIdleConnectionsHoldNothing() = runReactor {
        val admission = Admission(permits = 1, acquireTimeoutMillis = 10_000)
        val release = CompletableDeferred<Unit>()
        var concurrent = 0; var maxConcurrent = 0
        val service = HttpService { req ->
            concurrent++; maxConcurrent = maxOf(maxConcurrent, concurrent)
            if (req.uri.toString() == "/slow") release.await()
            concurrent--
            ok(req.uri.toString())
        }
        val (s1, c1) = testStreamPair(); val (s2, c2) = testStreamPair(); val (s3, c3) = testStreamPair()
        val servers = listOf(s1, s2, s3).map { s -> async { cfg(admission).serveConnection(s, service).serve() } }
        delay(20)
        assertEquals(0, admission.inUse, "idle connections hold permits")
        c1.send("GET /slow HTTP/1.1\r\n\r\n")
        delay(20)
        assertEquals(1, admission.inUse)
        c2.send("GET /fast HTTP/1.1\r\n\r\n")
        delay(50)
        assertEquals(1, admission.inUse)                     // the second request waits for the permit
        release.complete(Unit)
        assertTrue(c1.readUntil("/slow").endsWith("/slow"))
        assertTrue(c2.readUntil("/fast").endsWith("/fast"))
        assertEquals(1, maxConcurrent)
        delay(20)
        assertEquals(0, admission.inUse, "permits are released after the responses")
        c1.close(); c2.close(); c3.close()
        withTimeout(5_000) { servers.forEach { it.await() } }
        assertEquals(0, admission.inUse)
    }

    @Test
    fun noPermitInTimeFailsOnlyTheWaitingConnection() = runReactor {
        val admission = Admission(permits = 1, acquireTimeoutMillis = 100)
        val release = CompletableDeferred<Unit>()
        val service = HttpService { req -> if (req.uri.toString() == "/slow") release.await(); ok("x") }
        val (s1, c1) = testStreamPair(); val (s2, c2) = testStreamPair()
        val a = async { runCatching { cfg(admission).serveConnection(s1, service).serve() } }
        val b = async { runCatching { cfg(admission).serveConnection(s2, service).serve() } }
        c1.send("GET /slow HTTP/1.1\r\n\r\n")
        delay(20)
        c2.send("GET /other HTTP/1.1\r\n\r\n")
        val e = withTimeout(5_000) { b.await() }.exceptionOrNull()
        assertTrue(e is HttpError && e.cause is AdmissionTimeoutException, "$e")
        assertEquals(1L, admission.timeouts)
        release.complete(Unit)
        assertTrue(c1.readUntil("\r\n\r\nx").endsWith("x"))
        c1.close()
        assertFalse(withTimeout(5_000) { a.await() }.isFailure)
        assertEquals(0, admission.inUse)
        c2.close()
    }
}
