package neton.http.h2.hpack

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.refTo
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import neton.http.Method
import neton.http.StatusCode
import neton.http.header.HeaderName
import neton.http.header.HeaderValue
import neton.io.bytes.Buffer
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.getcwd
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

// Ported from h2 0.4.19 `src/hpack/test/fixture.rs`: every story of the hpack-test-case fixtures
// (`src/nativeTest/resources/hpack-test-case/`, see its NOTICE).
//
// For each story, as in the reference: (1) decode every case's `wire` with one decoder, in seqno order, applying
// `header_table_size` through `queueSizeUpdate`, and compare with `headers`; (2) re-encode every case's headers with
// a fresh encoder (applying `header_table_size` to the encoder and a fresh decoder) and check they decode back to the
// same headers. The reference lists the twelve implementation directories (382 stories); `raw-data` (32 stories,
// headers without wire data) only goes through step (2) here, an addition.
//
// Locating the fixtures: the Kotlin/Native test task runs the test binary with the project directory (`http/`) as
// working directory, so `src/nativeTest/resources/hpack-test-case` resolves. Set the environment variable
// HPACK_TEST_CASE_DIR to the directory to run the binary from elsewhere.

private val IMPLEMENTATION_DIRS = listOf(
    "go-hpack",
    "haskell-http2-linear",
    "haskell-http2-linear-huffman",
    "haskell-http2-naive",
    "haskell-http2-naive-huffman",
    "haskell-http2-static",
    "haskell-http2-static-huffman",
    "nghttp2",
    "nghttp2-16384-4096",
    "nghttp2-change-table-size",
    "node-http2-hpack",
    "python-hpack",
)

/** Story files per directory: `story_00.json` .. `story_31.json`, except the two nghttp2 variants that stop at 30. */
private fun storyCount(dir: String): Int =
    if (dir == "nghttp2-16384-4096" || dir == "nghttp2-change-table-size") 31 else 32

@OptIn(ExperimentalForeignApi::class)
private fun readFile(path: String): ByteArray? {
    val f = fopen(path, "rb") ?: return null
    try {
        fseek(f, 0.convert(), SEEK_END)
        val size: Int = ftell(f).convert()
        fseek(f, 0.convert(), SEEK_SET)
        val out = ByteArray(size)
        if (size > 0) {
            val n = out.usePinned { fread(it.addressOf(0), 1u, size.convert(), f) }
            if (n.toInt() != size) return null
        }
        return out
    } finally {
        fclose(f)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun cwd(): String {
    val buf = ByteArray(4096)
    return getcwd(buf.refTo(0), buf.size.convert())?.toKString() ?: "?"
}

@OptIn(ExperimentalForeignApi::class)
private fun fixtureRoot(): String {
    val candidates = listOfNotNull(
        getenv("HPACK_TEST_CASE_DIR")?.toKString(),
        "src/nativeTest/resources/hpack-test-case",
        "http/src/nativeTest/resources/hpack-test-case",
    )
    for (c in candidates) if (readFile("$c/README.md") != null) return c
    fail("hpack-test-case fixtures not found from ${cwd()}; tried $candidates (set HPACK_TEST_CASE_DIR)")
}

// ---- a minimal JSON reader (objects, arrays, strings, numbers, literals) ----

private class Json(private val s: String) {
    private var i = 0

    fun parse(): Any? {
        val v = value()
        ws()
        check(i == s.length) { "trailing data at $i" }
        return v
    }

    private fun ws() {
        while (i < s.length && s[i].isWhitespace()) i++
    }

    private fun value(): Any? {
        ws()
        return when (val c = s[i]) {
            '{' -> obj()
            '[' -> arr()
            '"' -> str()
            't' -> lit("true", true)
            'f' -> lit("false", false)
            'n' -> lit("null", null)
            else -> if (c == '-' || c.isDigit()) num() else error("unexpected '$c' at $i")
        }
    }

    private fun lit(word: String, v: Any?): Any? {
        check(s.startsWith(word, i)) { "bad literal at $i" }
        i += word.length
        return v
    }

    private fun num(): Long {
        val start = i
        if (s[i] == '-') i++
        while (i < s.length && s[i].isDigit()) i++
        check(i == s.length || s[i] !in ".eE") { "only integers are supported (at $start)" }
        return s.substring(start, i).toLong()
    }

    private fun str(): String {
        i++ // opening quote
        val sb = StringBuilder()
        while (true) {
            val c = s[i++]
            when (c) {
                '"' -> return sb.toString()
                '\\' -> when (val e = s[i++]) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000c')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                    else -> error("bad escape '\\$e' at $i")
                }
                else -> sb.append(c)
            }
        }
    }

    /** Keeps member order (the headers arrays hold one-member objects). */
    private fun obj(): List<Pair<String, Any?>> {
        i++
        val out = ArrayList<Pair<String, Any?>>()
        ws()
        if (s[i] == '}') { i++; return out }
        while (true) {
            ws()
            val k = str()
            ws()
            check(s[i++] == ':') { "expected ':' at $i" }
            out.add(k to value())
            ws()
            when (s[i++]) {
                ',' -> continue
                '}' -> return out
                else -> error("expected ',' or '}' at $i")
            }
        }
    }

    private fun arr(): List<Any?> {
        i++
        val out = ArrayList<Any?>()
        ws()
        if (s[i] == ']') { i++; return out }
        while (true) {
            out.add(value())
            ws()
            when (s[i++]) {
                ',' -> continue
                ']' -> return out
                else -> error("expected ',' or ']' at $i")
            }
        }
    }
}

@Suppress("UNCHECKED_CAST")
private fun List<Pair<String, Any?>>.field(name: String): Any? = firstOrNull { it.first == name }?.second

private class Case(
    val seqno: Long,
    val wire: ByteArray?,
    val expect: List<Pair<String, String>>,
    val headerTableSize: Int?,
)

private fun hex(s: String): ByteArray {
    check(s.length % 2 == 0)
    return ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
}

@Suppress("UNCHECKED_CAST")
private fun loadCases(json: String): List<Case> {
    val story = Json(json).parse() as List<Pair<String, Any?>>
    val cases = story.field("cases") as List<Any?>
    return cases.mapIndexed { index, c ->
        val case = c as List<Pair<String, Any?>>
        val headers = (case.field("headers") as List<Any?>).map { h ->
            val member = (h as List<Pair<String, Any?>>).single()
            member.first to (member.second as String)
        }
        Case(
            seqno = (case.field("seqno") as Long?) ?: index.toLong(),
            wire = (case.field("wire") as String?)?.let(::hex),
            expect = headers,
            headerTableSize = (case.field("header_table_size") as Long?)?.toInt(),
        )
    }.sortedBy { it.seqno }
}

/** `key_str`. */
private fun keyStr(h: Header): String = when (h) {
    is Header.Field -> h.name.asStr()
    is Header.Authority -> ":authority"
    is Header.Method -> ":method"
    is Header.Scheme -> ":scheme"
    is Header.Path -> ":path"
    is Header.Protocol -> ":protocol"
    is Header.Status -> ":status"
    is Header.Value -> error("the decoder never yields a nameless header")
}

/** `value_str`. */
private fun valueStr(h: Header): String = when (h) {
    is Header.Field -> h.value.toStr()
    is Header.Authority -> h.value
    is Header.Method -> h.value.asStr()
    is Header.Scheme -> h.value
    is Header.Path -> h.value
    is Header.Protocol -> h.value
    is Header.Status -> h.value.asStr()
    is Header.Value -> error("the decoder never yields a nameless header")
}

/** `Header::new(name, value).unwrap()` for the fixture's strings. */
private fun newHeader(name: String, value: String): Header = when {
    name == ":authority" -> Header.Authority(value)
    name == ":method" -> Header.Method(Method.fromStr(value))
    name == ":scheme" -> Header.Scheme(value)
    name == ":path" -> Header.Path(value)
    name == ":protocol" -> Header.Protocol(value)
    name == ":status" -> Header.Status(StatusCode.fromStr(value))
    name.startsWith(":") -> error("invalid pseudo-header $name")
    else -> Header.Field(HeaderName.fromLowercase(name.encodeToByteArray()), HeaderValue.fromBytes(value.encodeToByteArray()))
}

private class Totals {
    var stories = 0
    var decodedCases = 0
    var encodedCases = 0
    var headers = 0
}

private fun testStory(json: String, totals: Totals, where: String) {
    val cases = loadCases(json)

    // First, check decoding against the fixtures.
    if (cases.all { it.wire != null }) {
        val decoder = Decoder()
        for (case in cases) {
            val expect = ArrayDeque(case.expect)
            case.headerTableSize?.let { decoder.queueSizeUpdate(it) }
            val wire = case.wire!!
            val err = decoder.decode(wire, 0, wire.size) { e ->
                val (name, value) = expect.removeFirst()
                // Build the failure message only on a mismatch: this runs for ~470k headers.
                if (name != keyStr(e) || value != valueStr(e)) {
                    fail("$where seqno ${case.seqno}: expected $name: $value, got ${keyStr(e)}: ${valueStr(e)}")
                }
                true
            }
            assertNull(err, "$where seqno ${case.seqno}")
            assertEquals(0, expect.size, "$where seqno ${case.seqno}")
            totals.decodedCases++
            totals.headers += case.expect.size
        }
    } else {
        assertTrue(cases.none { it.wire != null }, "$where: mixed cases with and without wire")
    }

    // Now, encode the headers and decode them back.
    val encoder = Encoder()
    val decoder = Decoder()
    for (case in cases) {
        val buf = Buffer(64 * 1024)
        case.headerTableSize?.let {
            encoder.updateMaxSize(it)
            decoder.queueSizeUpdate(it)
        }
        val input = ArrayDeque(case.expect.map { (n, v) -> newHeader(n, v) })
        encoder.encode(input.toList(), buf)
        val wire = buf.peekAll()
        val err = decoder.decode(wire, 0, wire.size) { e ->
            val expected = input.removeFirst()
            if (expected != e) fail("$where seqno ${case.seqno} (re-encoded): expected $expected, got $e")
            true
        }
        assertNull(err, "$where seqno ${case.seqno} (re-encoded)")
        assertEquals(0, input.size)
        totals.encodedCases++
    }
    totals.stories++
}

class FixtureTest {
    private fun runDirs(dirs: List<String>): Totals {
        val root = fixtureRoot()
        val totals = Totals()
        for (dir in dirs) {
            for (n in 0 until storyCount(dir)) {
                val file = "story_${n.toString().padStart(2, '0')}.json"
                val data = readFile("$root/$dir/$file") ?: fail("missing $root/$dir/$file")
                testStory(data.decodeToString(), totals, "$dir/$file")
            }
        }
        return totals
    }

    /** The reference's fixture tests: all twelve implementation directories. */
    @Test
    fun implementationStories() {
        val t = runDirs(IMPLEMENTATION_DIRS)
        println(
            "hpack fixtures: ${t.stories} stories, ${t.decodedCases} cases decoded (${t.headers} headers), " +
                "${t.encodedCases} cases re-encoded",
        )
        assertEquals(382, t.stories)
    }

    /** Addition: the raw header sets, encoded and decoded back. */
    @Test
    fun rawDataStories() {
        val t = runDirs(listOf("raw-data"))
        println("hpack raw-data: ${t.stories} stories, ${t.encodedCases} cases re-encoded")
        assertEquals(32, t.stories)
        assertEquals(0, t.decodedCases)
    }
}
