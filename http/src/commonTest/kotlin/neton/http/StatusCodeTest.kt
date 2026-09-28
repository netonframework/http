package neton.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StatusCodeTest {
    private fun statusCode(code: Int): StatusCode = StatusCode.fromU16(code)

    // tests/status_code.rs

    @Test fun fromBytes() {
        for (ok in listOf("100", "101", "199", "200", "250", "299", "321", "399", "499", "599", "600", "999")) {
            assertNotNull(StatusCode.tryFromBytes(ok.encodeToByteArray()), ok)
        }
        for (notOk in listOf("0", "00", "10", "40", "99", "000", "010", "099", "1000", "1999")) {
            assertFailsWith<InvalidStatusCode>(notOk) { StatusCode.fromBytes(notOk.encodeToByteArray()) }
        }
    }

    @Test fun equatesWithU16() {
        val status = StatusCode.fromU16(200)
        assertEquals(200, status.asU16())
        assertEquals(status.asU16(), 200)
    }

    @Test fun roundtrip() {
        for (s in 100 until 1000) {
            val sstr = s.toString()
            val status = StatusCode.fromBytes(sstr.encodeToByteArray())
            assertEquals(s, status.asU16())
            assertEquals(sstr, status.asStr())
        }
    }

    @Test fun isInformational() {
        assertTrue(statusCode(100).isInformational())
        assertTrue(statusCode(199).isInformational())

        assertFalse(statusCode(200).isInformational())
    }

    @Test fun isSuccess() {
        assertTrue(statusCode(200).isSuccess())
        assertTrue(statusCode(299).isSuccess())

        assertFalse(statusCode(199).isSuccess())
        assertFalse(statusCode(300).isSuccess())
    }

    @Test fun isRedirection() {
        assertTrue(statusCode(300).isRedirection())
        assertTrue(statusCode(399).isRedirection())

        assertFalse(statusCode(299).isRedirection())
        assertFalse(statusCode(400).isRedirection())
    }

    @Test fun isClientError() {
        assertTrue(statusCode(400).isClientError())
        assertTrue(statusCode(499).isClientError())

        assertFalse(statusCode(399).isClientError())
        assertFalse(statusCode(500).isClientError())
    }

    @Test fun isServerError() {
        assertTrue(statusCode(500).isServerError())
        assertTrue(statusCode(599).isServerError())

        assertFalse(statusCode(499).isServerError())
        assertFalse(statusCode(600).isServerError())
    }

    // status.rs doc examples

    @Test fun moduleDocExample() {
        assertEquals(StatusCode.OK, StatusCode.fromU16(200))
        assertEquals(404, StatusCode.NOT_FOUND.asU16())
        assertTrue(StatusCode.OK.isSuccess())
    }

    @Test fun typeDocExample() {
        assertEquals(StatusCode.OK, StatusCode.fromU16(200))
        assertEquals(404, StatusCode.NOT_FOUND.asU16())
        assertTrue(StatusCode.OK.isSuccess())
    }

    @Test fun fromU16DocExample() {
        assertEquals(StatusCode.OK, StatusCode.fromU16(200))
        assertFailsWith<InvalidStatusCode> { StatusCode.fromU16(99) }
        assertNull(StatusCode.tryFromU16(99))
    }

    @Test fun asU16DocExample() = assertEquals(200, StatusCode.OK.asU16())

    @Test fun asStrDocExample() = assertEquals("200", StatusCode.OK.asStr())

    @Test fun canonicalReasonDocExample() = assertEquals("OK", StatusCode.OK.canonicalReason())

    @Test fun displayDocExample() = assertEquals("200 OK", StatusCode.OK.toString())

    // Beyond the reference tests: SPEC §2 behaviour.

    @Test fun fromU16Range() {
        for (bad in listOf(Int.MIN_VALUE, -1, 0, 99, 1000, 65535, Int.MAX_VALUE)) {
            assertNull(StatusCode.tryFromU16(bad), "$bad")
            assertFailsWith<InvalidStatusCode> { StatusCode.fromU16(bad) }
        }
        for (s in 100..999) assertSame(StatusCode.fromU16(s), StatusCode.tryFromU16(s))
    }

    @Test fun fromBytesRejectsNonDigits() {
        for (bad in listOf("2O0", "20 ", " 20", "+20", "-20", "2a0", "20/", "20:", "abc")) {
            assertNull(StatusCode.tryFromBytes(bad.encodeToByteArray()), bad)
            assertNull(StatusCode.tryFromStr(bad), bad)
        }
        assertNull(StatusCode.tryFromStr("２００"))
        assertFailsWith<InvalidStatusCode> { StatusCode.fromStr("") }
    }

    @Test fun parsingReturnsSharedInstances() {
        val line = "HTTP/1.1 404 Not Found".encodeToByteArray()
        assertSame(StatusCode.NOT_FOUND, StatusCode.fromBytes(line, 9, 3))
        assertSame(StatusCode.OK, StatusCode.fromStr("200"))
        assertSame(StatusCode.fromU16(999), StatusCode.fromStr("999"))
        assertFailsWith<InvalidStatusCode> { StatusCode.fromBytes(line, 9, 4) }
        assertFailsWith<IndexOutOfBoundsException> { StatusCode.fromBytes(line, 21, 3) }
    }

    @Test fun canonicalReasons() {
        var withReason = 0
        for (s in 100..999) if (StatusCode.fromU16(s).canonicalReason() != null) withReason++
        assertEquals(62, withReason)
        assertEquals("Non Authoritative Information", StatusCode.NON_AUTHORITATIVE_INFORMATION.canonicalReason())
        assertEquals("I'm a teapot", StatusCode.IM_A_TEAPOT.canonicalReason())
        assertEquals("Network Authentication Required", StatusCode.fromU16(511).canonicalReason())
        assertNull(StatusCode.fromU16(306).canonicalReason())
        assertEquals("600 <unknown status code>", StatusCode.fromU16(600).toString())
    }

    @Test fun constantsMatchTheirValues() {
        assertEquals(100, StatusCode.CONTINUE.asU16())
        assertEquals(226, StatusCode.IM_USED.asU16())
        assertEquals(431, StatusCode.REQUEST_HEADER_FIELDS_TOO_LARGE.asU16())
        assertEquals(451, StatusCode.UNAVAILABLE_FOR_LEGAL_REASONS.asU16())
        assertEquals(511, StatusCode.NETWORK_AUTHENTICATION_REQUIRED.asU16())
    }

    @Test fun unclassifiedAbove599() {
        val s = StatusCode.fromU16(600)
        assertFalse(s.isInformational() || s.isSuccess() || s.isRedirection() || s.isClientError() || s.isServerError())
    }

    @Test fun defaultAndOrdering() {
        assertSame(StatusCode.OK, StatusCode.DEFAULT)
        assertTrue(StatusCode.OK < StatusCode.NOT_FOUND)
        assertEquals(200, StatusCode.OK.hashCode())
    }
}
