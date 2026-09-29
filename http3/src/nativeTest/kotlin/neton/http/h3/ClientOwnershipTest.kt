package neton.http.h3

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import neton.http.Request
import neton.http.h3.client.SendRequest
import neton.http.h3.client.SenderCount
import neton.http.h3.proto.StreamId
import neton.http.h3.quic.*
import neton.io.bytes.Bytes
import kotlin.test.Test
import kotlin.test.assertEquals

class ClientOwnershipTest {
    private class Stream : BidiStream {
        override val sendId = StreamId(0)
        override val recvId = StreamId(0)
        var resets = 0
        var stops = 0
        var writes = 0
        val writing = CompletableDeferred<Unit>()
        override suspend fun write(data: Bytes) {
            writes++
            writing.complete(Unit)
            awaitCancellation()
        }
        override suspend fun finish() = Unit
        override fun reset(errorCode: Long) { resets++ }
        override fun stopSending(errorCode: Long) { stops++ }
        override suspend fun stopped(): Long? = null
        override suspend fun read(): Bytes? = null
        override fun split(): Pair<SendStream, RecvStream> = this to this
    }

    private fun sender(stream: Stream, state: SharedState, opening: suspend () -> Unit = {}): SendRequest {
        val open = object : OpenStreams {
            override suspend fun openBi(): BidiStream { opening(); return stream }
            override suspend fun openUni(): SendStream = error("unused")
            override fun close(code: Code, reason: ByteArray) = Unit
        }
        return SendRequest(open, state, Config(), SenderCount(), false)
    }

    @Test
    fun cancelledHeadersReleaseBothStreamHalves() = h3Test {
        val stream = Stream()
        val send = sender(stream, SharedState { _, _ -> })
        val job = launch { send.sendRequest(Request.get("https://localhost/").body(Unit)) }
        stream.writing.await()
        job.cancel()
        job.join()
        assertEquals(1, stream.resets)
        assertEquals(1, stream.stops)
    }

    @Test
    fun goawayDuringOpenDoesNotSendNewRequest() = h3Test {
        val stream = Stream()
        val state = SharedState { _, _ -> }
        val opening = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val send = sender(stream, state) { opening.complete(Unit); release.await() }
        val job = launch {
            failsWith<StreamError.RemoteClosing> { send.sendRequest(Request.get("https://localhost/").body(Unit)) }
        }
        opening.await()
        state.setClosing()
        release.complete(Unit)
        job.join()
        assertEquals(0, stream.writes)
        assertEquals(1, stream.resets)
        assertEquals(1, stream.stops)
    }
}
