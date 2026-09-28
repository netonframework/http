package neton.http.h2.hpack

import neton.http.Method
import neton.http.header.HeaderName
import neton.http.header.HeaderValue
import neton.io.bytes.Buffer
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Ported from h2 0.4.19 `src/hpack/encoder.rs` (19 tests, one of them `#[ignore]`d there and here).

private fun encode(e: Encoder, hdrs: List<Header>): ByteArray {
    val dst = Buffer(1024)
    e.encode(hdrs, dst)
    return dst.peekAll()
}

private fun method(s: String): Header = Header.Method(Method.fromStr(s))

private fun header(name: String, value: String): Header =
    Header.Field(HeaderName.fromBytes(name.encodeToByteArray()), HeaderValue.fromBytes(value.encodeToByteArray()))

private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

private fun ByteArray.u(i: Int): Int = this[i].toInt() and 0xff

private fun ByteArray.range(from: Int, to: Int = size) = copyOfRange(from, to)

class EncoderTest {
    @Test
    fun testEncodeMethodGet() {
        val encoder = Encoder()
        val res = encode(encoder, listOf(method("GET")))
        assertContentEquals(b(0x80 or 2), res)
        assertEquals(0, encoder.table.len)
    }

    @Test
    fun testEncodeMethodPost() {
        val encoder = Encoder()
        val res = encode(encoder, listOf(method("POST")))
        assertContentEquals(b(0x80 or 3), res)
        assertEquals(0, encoder.table.len)
    }

    @Test
    fun testEncodeMethodPatch() {
        val encoder = Encoder()
        var res = encode(encoder, listOf(method("PATCH")))

        assertEquals(0b01000000 or 2, res.u(0)) // Incremental indexing with the name from the table
        assertEquals(0x80 or 5, res.u(1)) // Huffman-coded value

        assertEquals("PATCH", huffDecode(res, 2, 7))
        assertEquals(1, encoder.table.len)

        res = encode(encoder, listOf(method("PATCH")))

        assertEquals((1 shl 7) or 62, res.u(0))
        assertEquals(1, res.size)
    }

    @Test
    fun testEncodeIndexedNameLiteralValue() {
        val encoder = Encoder()
        var res = encode(encoder, listOf(header("content-language", "foo")))

        assertEquals(0b01000000 or 27, res.u(0)) // Indexed name
        assertEquals(0x80 or 2, res.u(1)) // Huffman-coded value

        assertEquals("foo", huffDecode(res, 2, 4))

        // Same name, new value should still use incremental indexing.
        res = encode(encoder, listOf(header("content-language", "bar")))
        assertEquals(0b01000000 or 27, res.u(0))
        assertEquals(0x80 or 3, res.u(1))
        assertEquals("bar", huffDecode(res, 2, 5))
    }

    @Test
    fun testRepeatedHeadersAreIndexed() {
        val encoder = Encoder()
        var res = encode(encoder, listOf(header("foo", "hello")))

        assertContentEquals(b(0b01000000, 0x80 or 2), res.range(0, 2))
        assertEquals("foo", huffDecode(res, 2, 4))
        assertEquals(0x80 or 4, res.u(4))
        assertEquals("hello", huffDecode(res, 5))
        assertEquals(9, res.size)

        assertEquals(1, encoder.table.len)

        res = encode(encoder, listOf(header("foo", "hello")))
        assertContentEquals(b(0x80 or 62), res)

        assertEquals(1, encoder.table.len)
    }

    @Test
    fun testEvictingHeaders() {
        val encoder = Encoder()

        // Fill the table.
        for (i in 0 until 64) {
            val key = "x-hello-world-${i.toString().padStart(2, '0')}"
            var res = encode(encoder, listOf(header(key, key)))

            assertContentEquals(b(0b01000000, 0x80 or 12), res.range(0, 2))
            assertEquals(key, huffDecode(res, 2, 14))
            assertEquals(0x80 or 12, res.u(14))
            assertEquals(key, huffDecode(res, 15))
            assertEquals(27, res.size)

            // Make sure the header can be found...
            res = encode(encoder, listOf(header(key, key)))

            // Only check that it is found.
            assertEquals(0x80, res.u(0) and 0x80)
        }

        assertEquals(4096, encoder.table.size)
        assertEquals(64, encoder.table.len)

        // Find existing headers.
        for (i in 0 until 64) {
            val key = "x-hello-world-${i.toString().padStart(2, '0')}"
            val res = encode(encoder, listOf(header(key, key)))
            assertEquals(0x80, res.u(0) and 0x80)
        }

        // Insert a new header.
        val key = "x-hello-world-64"
        val res = encode(encoder, listOf(header(key, key)))

        assertContentEquals(b(0b01000000, 0x80 or 12), res.range(0, 2))
        assertEquals(key, huffDecode(res, 2, 14))
        assertEquals(0x80 or 12, res.u(14))
        assertEquals(key, huffDecode(res, 15))
        assertEquals(27, res.size)

        assertEquals(64, encoder.table.len)

        // Now try encoding entries that should exist in the table.
        for (i in 1 until 65) {
            val k = "x-hello-world-${i.toString().padStart(2, '0')}"
            val r = encode(encoder, listOf(header(k, k)))
            assertEquals(0x80 or (61 + (65 - i)), r.u(0))
        }
    }

    @Test
    fun testLargeHeadersAreNotIndexed() {
        val encoder = Encoder(128, 0)
        val key = "hello-world-hello-world-HELLO-zzz"

        val res = encode(encoder, listOf(header(key, key)))

        assertContentEquals(b(0, 0x80 or 25), res.range(0, 2))

        assertEquals(0, encoder.table.len)
        assertEquals(0, encoder.table.size)
    }

    @Test
    fun testSensitiveHeadersAreNeverIndexed() {
        var value = HeaderValue.fromBytes("12345".encodeToByteArray())
        value.isSensitive = true
        var hdr: Header = Header.Field(HeaderName.fromStr("my-password"), value)

        // Now, try to encode the sensitive header.
        var encoder = Encoder()
        var res = encode(encoder, listOf(hdr))

        assertContentEquals(b(0b10000, 0x80 or 8), res.range(0, 2))
        assertEquals("my-password", huffDecode(res, 2, 10))
        assertEquals(0x80 or 4, res.u(10))
        assertEquals("12345", huffDecode(res, 11))

        // Now, try to encode a sensitive header with a name in the static table.
        value = HeaderValue.fromBytes("12345".encodeToByteArray())
        value.isSensitive = true
        hdr = Header.Field(HeaderName.fromStr("authorization"), value)

        encoder = Encoder()
        res = encode(encoder, listOf(hdr))

        assertContentEquals(b(0b11111, 8), res.range(0, 2))
        assertEquals(0x80 or 4, res.u(2))
        assertEquals("12345", huffDecode(res, 3))

        // Using the name of a previously indexed header (without the sensitive flag).
        encode(encoder, listOf(header("my-password", "not-so-secret")))

        value = HeaderValue.fromBytes("12345".encodeToByteArray())
        value.isSensitive = true
        hdr = Header.Field(HeaderName.fromStr("my-password"), value)
        res = encode(encoder, listOf(hdr))

        assertContentEquals(b(0b11111, 47), res.range(0, 2))
        assertEquals(0x80 or 4, res.u(2))
        assertEquals("12345", huffDecode(res, 3))
    }

    @Test
    fun testContentLengthValueNotIndexed() {
        val encoder = Encoder()
        val res = encode(encoder, listOf(header("content-length", "1234")))

        assertContentEquals(b(15, 13, 0x80 or 3), res.range(0, 3))
        assertEquals("1234", huffDecode(res, 3))
        assertEquals(6, res.size)
    }

    @Test
    fun testEncodingHeadersWithSameName() {
        val encoder = Encoder()
        val name = "hello"

        // Encode the first one.
        encode(encoder, listOf(header(name, "one")))

        // Encode the second one.
        var res = encode(encoder, listOf(header(name, "two")))
        assertContentEquals(b(0x40 or 62, 0x80 or 3), res.range(0, 2))
        assertEquals("two", huffDecode(res, 2))
        assertEquals(5, res.size)

        // Encode the first one again.
        res = encode(encoder, listOf(header(name, "one")))
        assertContentEquals(b(0x80 or 63), res)

        // Now the second one.
        res = encode(encoder, listOf(header(name, "two")))
        assertContentEquals(b(0x80 or 62), res)
    }

    @Test
    fun testEvictingHeadersWhenMultipleOfSameNameAreInTable() {
        // The encoder only has space for 2 headers.
        val encoder = Encoder(76, 0)

        encode(encoder, listOf(header("foo", "bar")))
        assertEquals(1, encoder.table.len)

        encode(encoder, listOf(header("bar", "foo")))
        assertEquals(2, encoder.table.len)

        // This evicts the first header, while still referencing the header name.
        var res = encode(encoder, listOf(header("foo", "baz")))
        assertContentEquals(b(0x40 or 63, 0, 0x80 or 3), res.range(0, 3))
        assertEquals(2, encoder.table.len)

        // Try adding the same header again.
        res = encode(encoder, listOf(header("foo", "baz")))
        assertContentEquals(b(0x80 or 62), res)
        assertEquals(2, encoder.table.len)
    }

    @Test
    fun testMaxSizeZero() {
        // Static table only.
        val encoder = Encoder(0, 0)
        var res = encode(encoder, listOf(method("GET")))
        assertContentEquals(b(0x80 or 2), res)
        assertEquals(0, encoder.table.len)

        res = encode(encoder, listOf(header("foo", "bar")))
        assertContentEquals(b(0, 0x80 or 2), res.range(0, 2))
        assertEquals("foo", huffDecode(res, 2, 4))
        assertEquals(0x80 or 3, res.u(4))
        assertEquals("bar", huffDecode(res, 5, 8))
        assertEquals(0, encoder.table.len)

        // Encode a custom value.
        res = encode(encoder, listOf(header("transfer-encoding", "chunked")))
        assertContentEquals(b(15, 42, 0x80 or 6), res.range(0, 3))
        assertEquals("chunked", huffDecode(res, 3))
    }

    @Test
    fun testUpdateMaxSizeCombos() {
        var encoder = Encoder()
        assertNull(encoder.sizeUpdate)
        assertEquals(4096, encoder.table.maxSize)

        encoder.updateMaxSize(4096) // Default size
        assertNull(encoder.sizeUpdate)

        encoder.updateMaxSize(0)
        assertEquals(Encoder.SizeUpdate.One(0), encoder.sizeUpdate)

        encoder.updateMaxSize(100)
        assertEquals(Encoder.SizeUpdate.Two(0, 100), encoder.sizeUpdate)

        encoder = Encoder()
        encoder.setMaxAllowedSize(8000)
        encoder.updateMaxSize(8000)
        assertEquals(Encoder.SizeUpdate.One(8000), encoder.sizeUpdate)

        encoder.updateMaxSize(100)
        assertEquals(Encoder.SizeUpdate.One(100), encoder.sizeUpdate)

        encoder.updateMaxSize(8000)
        assertEquals(Encoder.SizeUpdate.Two(100, 8000), encoder.sizeUpdate)

        encoder.updateMaxSize(4000)
        assertEquals(Encoder.SizeUpdate.Two(100, 4000), encoder.sizeUpdate)

        encoder.updateMaxSize(50)
        assertEquals(Encoder.SizeUpdate.One(50), encoder.sizeUpdate)
    }

    @Test
    fun testResizingTable() {
        val encoder = Encoder()

        // Add a header.
        encode(encoder, listOf(header("foo", "bar")))

        encoder.updateMaxSize(1)
        assertEquals(1, encoder.table.len)

        var res = encode(encoder, listOf(method("GET")))
        assertContentEquals(b(32 or 1, 0x80 or 2), res)
        assertEquals(0, encoder.table.len)

        res = encode(encoder, listOf(header("foo", "bar")))
        assertEquals(0, res.u(0))

        encoder.updateMaxSize(100)
        res = encode(encoder, listOf(header("foo", "bar")))
        assertContentEquals(b(32 or 31, 69, 64), res.range(0, 3))

        encoder.updateMaxSize(0)
        res = encode(encoder, listOf(header("foo", "bar")))
        assertContentEquals(b(32, 0), res.range(0, 2))
    }

    @Test
    fun testDecreasingTableSizeWithoutEviction() {
        val encoder = Encoder()

        // Add a header.
        encode(encoder, listOf(header("foo", "bar")))

        encoder.updateMaxSize(100)
        assertEquals(1, encoder.table.len)

        val res = encode(encoder, listOf(header("foo", "bar")))
        assertContentEquals(b(32 or 31, 69, 0x80 or 62), res)
    }

    @Test
    fun testNamelessHeader() {
        val encoder = Encoder()

        val res = encode(
            encoder,
            listOf(
                Header.Field(HeaderName.fromStr("hello"), HeaderValue.fromBytes("world".encodeToByteArray())),
                Header.Value(HeaderValue.fromBytes("zomg".encodeToByteArray())),
            ),
        )

        assertContentEquals(b(0x40, 0x80 or 4), res.range(0, 2))
        assertEquals("hello", huffDecode(res, 2, 6))
        assertEquals(0x80 or 4, res.u(6))
        assertEquals("world", huffDecode(res, 7, 11))

        // Next is not indexed.
        assertContentEquals(b(15, 47, 0x80 or 3), res.range(11, 14))
        assertEquals("zomg", huffDecode(res, 14))
    }

    @Test
    fun testLargeSizeUpdate() {
        val encoder = Encoder()
        encoder.setMaxAllowedSize(Int.MAX_VALUE) // usize::MAX in the reference

        encoder.updateMaxSize(1912930560)
        assertEquals(Encoder.SizeUpdate.One(1912930560), encoder.sizeUpdate)

        val dst = Buffer(6)
        encoder.beginBlock(dst)
        assertContentEquals(b(63, 225, 129, 148, 144, 7), dst.peekAll())
    }

    @Test
    fun testLargeSizeUpdateIsCapped() {
        val encoder = Encoder(0, 0)

        encoder.updateMaxSize(1912930560)
        assertEquals(Encoder.SizeUpdate.One(Encoder.DEFAULT_MAX_ALLOWED_SIZE), encoder.sizeUpdate)

        val dst = Buffer(3)
        encoder.beginBlock(dst)
        assertContentEquals(b(63, 225, 31), dst.peekAll())
        assertEquals(Encoder.DEFAULT_MAX_ALLOWED_SIZE, encoder.table.maxSize)
    }

    @Ignore
    @Test
    fun testEvictedOverflow() {
        // Not sure what the best way to do this is (empty and ignored in the reference as well).
    }
}
