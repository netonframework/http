package neton.http.h2

import neton.http.h2.codec.UserError
import neton.http.h2.frame.Reason
import neton.http.h2.frame.StreamId
import neton.http.h2.proto.Initiator
import neton.http.h2.proto.IoErrorKind
import neton.http.h2.proto.ProtoError
import neton.io.bytes.Bytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The in-module test of h2 0.4.19 `src/error.rs` (1), and the `Display` forms of `h2::Error` the integration tests
// compare against.

class ErrorTest {
    @Test
    fun errorFromReason() {
        val err = H2Error.fromReason(Reason.HTTP_1_1_REQUIRED)
        assertEquals(Reason.HTTP_1_1_REQUIRED, err.reason())
    }

    @Test
    fun displayForms() {
        assertEquals(
            "stream error sent by user: stream no longer needed",
            H2Error.from(ProtoError.Reset(StreamId(1), Reason.CANCEL, Initiator.User)).message,
        )
        assertEquals(
            "stream error detected: unspecific protocol error detected",
            H2Error.from(ProtoError.libraryReset(StreamId(1), Reason.PROTOCOL_ERROR)).message,
        )
        assertEquals(
            "stream error received: refused stream before processing any application logic",
            H2Error.from(ProtoError.remoteReset(StreamId(1), Reason.REFUSED_STREAM)).message,
        )
        assertEquals(
            "connection error detected: detected excessive load generating behavior (b\"too_many_resets\")",
            H2Error.from(ProtoError.libraryGoAwayData(Reason.ENHANCE_YOUR_CALM, "too_many_resets")).message,
        )
        assertEquals(
            "connection error received: not a result of an error",
            H2Error.from(ProtoError.remoteGoAway(Bytes.EMPTY, Reason.NO_ERROR)).message,
        )
        assertEquals("protocol error: unspecific protocol error detected", H2Error.fromReason(Reason.PROTOCOL_ERROR).message)
        assertEquals("user error: stream ID overflowed", H2Error.fromUser(UserError.OverflowedStreamId).message)
        assertEquals("broken pipe", H2Error.fromIo(IoErrorKind.BrokenPipe).message)
        assertEquals("connection closed", H2Error.fromIo(IoErrorKind.Other, "connection closed").message)
    }

    @Test
    fun predicates() {
        val remoteGoAway = H2Error.from(ProtoError.remoteGoAway(Bytes.EMPTY, Reason.NO_ERROR))
        assertTrue(remoteGoAway.isGoAway)
        assertTrue(remoteGoAway.isRemote)
        assertFalse(remoteGoAway.isLibrary)
        val libraryReset = H2Error.from(ProtoError.libraryReset(StreamId(3), Reason.CANCEL))
        assertTrue(libraryReset.isReset)
        assertTrue(libraryReset.isLibrary)
        assertEquals(StreamId(3), libraryReset.streamId)
        val io = H2Error.fromIo(IoErrorKind.UnexpectedEof)
        assertTrue(io.isIo)
        assertNull(io.reason())
        assertEquals(IoErrorKind.UnexpectedEof, io.ioKind)
    }
}
