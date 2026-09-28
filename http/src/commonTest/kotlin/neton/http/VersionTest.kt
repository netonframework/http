package neton.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class VersionTest {
    // version.rs module doc example
    @Test fun moduleDocExample() {
        val http11 = Version.HTTP_11
        val http2 = Version.HTTP_2
        assertNotEquals(http11, http2)
        assertEquals("HTTP/2.0", http2.toString())
    }

    // Beyond the reference tests: SPEC §2 behaviour.

    @Test fun defaultIsHttp11() = assertSame(Version.HTTP_11, Version.DEFAULT)

    @Test fun debugForms() {
        assertEquals(
            listOf("HTTP/0.9", "HTTP/1.0", "HTTP/1.1", "HTTP/2.0", "HTTP/3.0"),
            Version.entries.map { it.toString() },
        )
    }

    @Test fun ordering() {
        assertTrue(Version.HTTP_09 < Version.HTTP_10)
        assertTrue(Version.HTTP_10 < Version.HTTP_11)
        assertTrue(Version.HTTP_11 < Version.HTTP_2)
        assertTrue(Version.HTTP_2 < Version.HTTP_3)
    }
}
