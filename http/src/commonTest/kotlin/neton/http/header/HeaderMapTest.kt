package neton.http.header

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

private fun hv(s: String): HeaderValue = HeaderValue.fromStr(s)
private fun hn(s: String): HeaderName = HeaderName.fromStr(s)

/**
 * Ports of http 1.5.0 `tests/header_map.rs` (35 tests), the two tests inside `src/header/map.rs`, and every doc example
 * of `src/header/map.rs`; followed by tests of the Kotlin-specific shape and of the hash-flooding state machine.
 */
class HeaderMapTest {

    // ===== tests/header_map.rs =====

    @Test
    fun smoke() {
        val headers = HeaderMap.new()
        assertNull(headers["hello"])

        val name = hn("hello")
        when (val e = headers.entry(name)) {
            is Entry.Vacant -> e.insert(hv("world"))
            else -> fail()
        }
        assertTrue(headers["hello"] != null)

        when (val e = headers.entry(name)) {
            is Entry.Occupied -> {
                assertEquals(hv("world"), e.get())
                // Push another value
                e.append(hv("zomg"))
                val i = e.iter()
                assertEquals(hv("world"), i.next())
                assertEquals(hv("zomg"), i.next())
                assertFalse(i.hasNext())
            }
            else -> fail()
        }
    }

    @Test
    fun reserveOverCapacity() {
        // See https://github.com/hyperium/http/issues/352
        val headers = HeaderMap.withCapacity<Int>(32)
        assertFailsWith<MaxSizeReached> { headers.reserve(50_000) } // over MAX_SIZE
    }

    @Test
    fun withCapacityMax() {
        // The largest capacity such that (cap + cap / 3) < MAX_SIZE.
        HeaderMap.withCapacity<Int>(24_576)
    }

    @Test
    fun withCapacityOverflow() {
        assertFailsWith<MaxSizeReached> { HeaderMap.withCapacity<Int>(24_577) }
    }

    @Test
    fun extendSizeHintAboveCapacity() {
        // A map may hold more values than the table can index when many values are appended under one name, so an
        // exact size hint can exceed the largest `reserve` request. Extending must not fail in that case.
        val name = HeaderName.fromStatic("h")
        val value = HeaderValue.fromStatic("0")
        val pairs = List(24_577) { name to value }
        val map = HeaderMap.fromIter(pairs)
        assertEquals(24_577, map.len())
        assertEquals(1, map.keysLen())
    }

    @Test
    fun reserveOverflow() {
        // See https://github.com/hyperium/http/issues/352
        val headers = HeaderMap.withCapacity<Int>(0)
        assertFailsWith<MaxSizeReached> { headers.reserve(Int.MAX_VALUE) } // next power of two overflows
    }

    @Test
    fun reserve() {
        val headers = HeaderMap<Int>()
        assertEquals(0, headers.capacity())

        val requestedCap = 8
        headers.reserve(requestedCap)
        val reservedCap = headers.capacity()
        assertTrue(reservedCap >= requestedCap, "requested $requestedCap capacity, but it reserved only $reservedCap")

        for (i in 0 until requestedCap) headers.insert(hn("h$i"), i)
        assertEquals(reservedCap, headers.capacity(), "unexpected reallocation")
    }

    @Test
    fun drain() {
        val headers = HeaderMap.new()

        // Insert a single value
        headers.insert(hn("hello"), hv("world"))
        run {
            val iter = headers.drain()
            val (name, value) = iter.next()
            assertEquals("hello", name!!.asStr())
            assertEquals(hv("world"), value)
            assertFalse(iter.hasNext())
        }
        assertTrue(headers.isEmpty())

        // Insert two sequential values
        headers.insert(hn("hello"), hv("world"))
        headers.insert(hn("zomg"), hv("bar"))
        headers.append(hn("hello"), hv("world2"))

        // Drain...
        run {
            val iter = headers.drain()
            var (name, value) = iter.next()
            assertEquals("hello", name!!.asStr())
            assertEquals(hv("world"), value)

            iter.next().let { name = it.first; value = it.second }
            assertNull(name)
            assertEquals(hv("world2"), value)

            iter.next().let { name = it.first; value = it.second }
            assertEquals("zomg", name!!.asStr())
            assertEquals(hv("bar"), value)

            assertFalse(iter.hasNext())
        }
    }

    @Test
    fun drainDropImmediately() {
        val headers = HeaderMap.new()
        headers.insert("hello", hv("world"))
        headers.insert("zomg", hv("bar"))
        headers.append("hello", hv("world2"))

        val iter = headers.drain()
        assertEquals(2, iter.sizeHintLower())
        assertEquals(3, iter.sizeHintUpper())
        // not consuming `iter`
    }

    @Test
    fun drainForget() {
        val headers = HeaderMap<HeaderValue>()
        headers.insert("hello", hv("world"))
        headers.insert("zomg", hv("bar"))
        assertEquals(2, headers.len())

        run {
            val iter = headers.drain()
            assertEquals(2, iter.sizeHintLower())
            assertEquals(2, iter.sizeHintUpper())
            iter.next()
            // abandoned, the Kotlin form of mem::forget
        }
        assertEquals(0, headers.len())
    }

    @Test
    fun drainEntry() {
        val headers = HeaderMap.new()
        headers.insert(hn("hello"), hv("world"))
        headers.insert(hn("zomg"), hv("foo"))
        headers.append(hn("hello"), hv("world2"))
        headers.insert(hn("more"), hv("words"))
        headers.append(hn("more"), hv("insertions"))
        assertEquals(5, headers.len())

        // Using insert_mult
        run {
            val e = headers.entry("hello") as? Entry.Occupied ?: fail()
            val vals = e.insertMult(hv("wat")).asSequence().toList()
            assertEquals(2, vals.size)
            assertEquals(hv("world"), vals[0])
            assertEquals(hv("world2"), vals[1])
        }
        assertEquals(5 - 2 + 1, headers.len())
    }

    @Test
    fun eq() {
        val a = HeaderMap.new()
        val b = HeaderMap.new()
        assertEquals(a, b)

        a.insert(hn("hello"), hv("world"))
        assertNotEquals(a, b)

        b.insert(hn("hello"), hv("world"))
        assertEquals(a, b)

        a.insert(hn("foo"), hv("bar"))
        a.append(hn("foo"), hv("baz"))
        assertNotEquals(a, b)

        b.insert(hn("foo"), hv("bar"))
        assertNotEquals(a, b)

        b.append(hn("foo"), hv("baz"))
        assertEquals(a, b)

        a.append(hn("a"), hv("a"))
        a.append(hn("a"), hv("b"))
        b.append(hn("a"), hv("b"))
        b.append(hn("a"), hv("a"))
        assertNotEquals(a, b)
    }

    @Test
    fun intoHeaderName() {
        val m = HeaderMap.new()
        m.insert(HeaderName.HOST, hv("localhost"))
        m.insert(HeaderName.ACCEPT, hv("*/*"))
        m.insert("connection", hv("keep-alive"))

        m.append(HeaderName.LOCATION, hv("/"))
        m.append(HeaderName.VIA, hv("bob"))
        m.append("transfer-encoding", hv("chunked"))

        assertEquals(6, m.len())
    }

    @Test
    fun asHeaderName() {
        val m = HeaderMap.new()
        val v = hv("localhost")
        m.insert(HeaderName.HOST, v)

        assertEquals(v, m["host"])
        assertEquals(v, m[HeaderName.HOST])

        val s = StringBuilder("ho").append("st").toString()
        assertEquals(v, m[s])
    }

    @Test
    fun insertAllStdHeaders() {
        val m = HeaderMap.new()
        for ((i, hdr) in STD.withIndex()) {
            m.insert(hdr, hv(hdr.asStr()))
            for (j in 0..i) assertEquals(hv(STD[j].asStr()), m.getValue(STD[j]))
            if (i != 0) {
                for (j in i + 1 until STD.size) assertNull(m[STD[j]], "contained ${STD[j]}; j=$j")
            }
        }
    }

    @Test
    fun insert79CustomStdHeaders() {
        val h = HeaderMap.new()
        val hdrs = customStd(79)
        for ((i, hdr) in hdrs.withIndex()) {
            h.insert(hdr, hv(hdr.asStr()))
            for (j in 0..i) assertEquals(hv(hdrs[j].asStr()), h.getValue(hdrs[j]))
            for (j in i + 1 until hdrs.size) assertNull(h[hdrs[j]])
        }
    }

    @Test
    fun appendMultipleValues() {
        val map = HeaderMap.new()
        map.append(HeaderName.CONTENT_TYPE, hv("json"))
        map.append(HeaderName.CONTENT_TYPE, hv("html"))
        map.append(HeaderName.CONTENT_TYPE, hv("xml"))

        val vals = map.getAll(HeaderName.CONTENT_TYPE).iter().asSequence().toList()
        assertEquals(listOf(hv("json"), hv("html"), hv("xml")), vals)
    }

    @Test
    fun getInvalid() {
        val headers = HeaderMap.new()
        headers.insert("foo", hv("bar"))
        assertNull(headers["Evil\r\nKey"])
    }

    @Test
    fun insertInvalid() {
        val headers = HeaderMap.new()
        assertFailsWith<InvalidHeaderName> { headers.insert("evil\r\nfoo", hv("bar")) }
    }

    @Test
    fun valueHtab() {
        // RFC 7230 Section 3.2: field-content = field-vchar [ 1*( SP / HTAB ) field-vchar ]
        HeaderValue.fromStatic("hello\tworld")
        HeaderValue.fromStr("hello\tworld")
    }

    private fun sixHeaders(): HeaderMap<HeaderValue> {
        val headers = HeaderMap.new()
        headers.insert(HeaderName.VIA, hv("1.1 example.com"))
        headers.insert(HeaderName.SET_COOKIE, hv("cookie_1=value 1"))
        headers.append(HeaderName.SET_COOKIE, hv("cookie_2=value 2"))
        headers.append(HeaderName.VIA, hv("1.1 other.com"))
        headers.append(HeaderName.SET_COOKIE, hv("cookie_3=value 3"))
        headers.insert(HeaderName.VARY, hv("*"))
        return headers
    }

    @Test
    fun removeMultipleA() {
        val headers = sixHeaders()
        assertEquals(6, headers.len())

        assertEquals(hv("cookie_1=value 1"), headers.remove(HeaderName.SET_COOKIE))
        assertEquals(3, headers.len())

        assertEquals(hv("1.1 example.com"), headers.remove(HeaderName.VIA))
        assertEquals(1, headers.len())

        assertEquals(hv("*"), headers.remove(HeaderName.VARY))
        assertEquals(0, headers.len())
    }

    @Test
    fun removeMultipleB() {
        val headers = sixHeaders()
        assertEquals(6, headers.len())

        assertEquals(hv("*"), headers.remove(HeaderName.VARY))
        assertEquals(5, headers.len())

        assertEquals(hv("1.1 example.com"), headers.remove(HeaderName.VIA))
        assertEquals(3, headers.len())

        assertEquals(hv("cookie_1=value 1"), headers.remove(HeaderName.SET_COOKIE))
        assertEquals(0, headers.len())
    }

    @Test
    fun removeEntryMulti0() {
        val headers = HeaderMap.new()
        val cookies = removeAllValues(headers, HeaderName.SET_COOKIE)
        assertEquals(0, cookies.size)
        assertEquals(0, headers.len())
    }

    @Test
    fun removeEntryMulti0Others() {
        val headers = HeaderMap.new()
        headers.insert(HeaderName.VIA, hv("1.1 example.com"))
        headers.append(HeaderName.VIA, hv("1.1 other.com"))

        val cookies = removeAllValues(headers, HeaderName.SET_COOKIE)
        assertEquals(0, cookies.size)
        assertEquals(2, headers.len())
    }

    @Test
    fun removeEntryMulti1() {
        val headers = HeaderMap.new()
        headers.insert(HeaderName.SET_COOKIE, hv("cookie_1=value 1"))

        val cookies = removeAllValues(headers, HeaderName.SET_COOKIE)
        assertEquals(1, cookies.size)
        assertEquals(0, headers.len())
    }

    @Test
    fun removeEntryMulti1Other() {
        val headers = HeaderMap.new()
        headers.insert(HeaderName.SET_COOKIE, hv("cookie_1=value 1"))
        headers.insert(HeaderName.VIA, hv("1.1 example.com"))

        val cookies = removeAllValues(headers, HeaderName.SET_COOKIE)
        assertEquals(1, cookies.size)
        assertEquals(1, headers.len())

        val vias = removeAllValues(headers, HeaderName.VIA)
        assertEquals(1, vias.size)
        assertEquals(0, headers.len())
    }

    // For issue hyperium/http#446
    @Test
    fun removeEntryMulti2() {
        val headers = HeaderMap.new()
        headers.insert(HeaderName.SET_COOKIE, hv("cookie_1=value 1"))
        headers.append(HeaderName.SET_COOKIE, hv("cookie_2=value 2"))

        val cookies = removeAllValues(headers, HeaderName.SET_COOKIE)
        assertEquals(2, cookies.size)
        assertEquals(0, headers.len())
    }

    @Test
    fun removeEntryMulti3() {
        val headers = HeaderMap.new()
        headers.insert(HeaderName.SET_COOKIE, hv("cookie_1=value 1"))
        headers.append(HeaderName.SET_COOKIE, hv("cookie_2=value 2"))
        headers.append(HeaderName.SET_COOKIE, hv("cookie_3=value 3"))

        val cookies = removeAllValues(headers, HeaderName.SET_COOKIE)
        assertEquals(3, cookies.size)
        assertEquals(0, headers.len())
    }

    @Test
    fun removeEntryMulti3Others() {
        val headers = sixHeaders()

        val cookies = removeAllValues(headers, HeaderName.SET_COOKIE)
        assertEquals(3, cookies.size)
        assertEquals(3, headers.len())

        val vias = removeAllValues(headers, HeaderName.VIA)
        assertEquals(2, vias.size)
        assertEquals(1, headers.len())

        val varies = removeAllValues(headers, HeaderName.VARY)
        assertEquals(1, varies.size)
        assertEquals(0, headers.len())
    }

    private fun removeAllValues(headers: HeaderMap<HeaderValue>, key: HeaderName): List<HeaderValue> =
        when (val e = headers.entry(key)) {
            is Entry.Occupied -> e.removeEntryMult().second.asSequence().toList()
            is Entry.Vacant -> emptyList()
        }

    @Test
    fun removeEntry3OthersA() {
        val headers = sixHeaders()
        assertEquals(6, headers.len())

        assertEquals(hv("cookie_1=value 1"), removeValues(headers, HeaderName.SET_COOKIE))
        assertEquals(3, headers.len())

        assertEquals(hv("1.1 example.com"), removeValues(headers, HeaderName.VIA))
        assertEquals(1, headers.len())

        assertEquals(hv("*"), removeValues(headers, HeaderName.VARY))
        assertEquals(0, headers.len())
    }

    @Test
    fun removeEntry3OthersB() {
        val headers = sixHeaders()
        assertEquals(6, headers.len())

        assertEquals(hv("*"), removeValues(headers, HeaderName.VARY))
        assertEquals(5, headers.len())

        assertEquals(hv("1.1 example.com"), removeValues(headers, HeaderName.VIA))
        assertEquals(3, headers.len())

        assertEquals(hv("cookie_1=value 1"), removeValues(headers, HeaderName.SET_COOKIE))
        assertEquals(0, headers.len())
    }

    private fun removeValues(headers: HeaderMap<HeaderValue>, key: HeaderName): HeaderValue? =
        when (val e = headers.entry(key)) {
            is Entry.Occupied -> e.removeEntry().second
            is Entry.Vacant -> null
        }

    @Test
    fun ensureMiriSharedreadonlyNotViolated() {
        val headers = HeaderMap.new()
        headers.insert(HeaderName.fromStatic("chunky-trailer"), HeaderValue.fromStatic("header data"))
        val foo = headers.iter().next()
        assertEquals("chunky-trailer", foo.first.asStr())
    }

    @Test
    fun ensureMiriItermutNotViolated() {
        val headers = HeaderMap<Int>()
        headers.insert(HeaderName.fromStatic("hello"), 1)
        headers.insert(HeaderName.fromStatic("zomg"), 2)

        val iter = headers.iterMut()
        val (_, first) = iter.next()
        iter.set(first + 10)
        val (_, second) = iter.next()
        iter.set(second + 20)

        assertEquals(11, headers["hello"])
        assertEquals(22, headers["zomg"])
    }

    @Test
    fun ensureMiriValueitermutNotViolated() {
        val headers = HeaderMap<Int>()
        headers.insert(HeaderName.fromStatic("hello"), 1)
        headers.append(HeaderName.fromStatic("hello"), 2)
        headers.append(HeaderName.fromStatic("hello"), 3)

        val entry = headers.entry(HeaderName.fromStatic("hello")) as? Entry.Occupied ?: fail()
        val iter = entry.iterMut()
        val first = iter.next()
        iter.set(first + 10)
        val second = iter.next()
        iter.set(second + 20)

        assertEquals(listOf(11, 22, 3), headers.getAll("hello").toList())
    }

    @Test
    fun intoIterDropPanicAfterYieldingExtraValueDoubleDrops() {
        // The reference test guards against a double free when a value's destructor panics while IntoIter drains
        // itself on drop. Kotlin has no destructors; what remains portable is the observable behaviour: yielding the
        // first entry and one extra value, then abandoning the iterator, is safe and leaves the map empty.
        val map = HeaderMap<Int>()
        map.append("x-first", 1)
        map.append("x-first", 2)
        map.insert("x-second", 3)

        val iter = map.intoIter()
        assertEquals(Pair<HeaderName?, Int>(hn("x-first"), 1), iter.next())
        assertEquals(Pair<HeaderName?, Int>(null, 2), iter.next())
        // abandon `iter`
        assertTrue(map.isEmpty())
        assertEquals(Pair<HeaderName?, Int>(hn("x-second"), 3), iter.next())
        assertFalse(iter.hasNext())
    }

    // ===== src/header/map.rs #[test]s =====

    // `test_bounds` checks Send + Sync auto-trait bounds at compile time; Kotlin has no equivalent (not portable).

    @Test
    fun skipDuplicatesDuringKeyIteration() {
        val map = HeaderMap.new()
        map.tryAppend("a", HeaderValue.fromStatic("a")).getOrThrow()
        map.tryAppend("a", HeaderValue.fromStatic("b")).getOrThrow()
        assertEquals(map.keysLen(), map.keys().asSequence().count())
    }

    // ===== doc examples of src/header/map.rs =====

    @Test
    fun docHeaderMap() {
        val headers = HeaderMap.new()
        headers.insert(HeaderName.HOST, hv("example.com"))
        headers.insert(HeaderName.CONTENT_LENGTH, hv("123"))

        assertTrue(headers.containsKey(HeaderName.HOST))
        assertFalse(headers.containsKey(HeaderName.LOCATION))
        assertEquals(hv("example.com"), headers.getValue(HeaderName.HOST))

        headers.remove(HeaderName.HOST)
        assertFalse(headers.containsKey(HeaderName.HOST))
    }

    @Test
    fun docNew() {
        val map = HeaderMap.new()
        assertTrue(map.isEmpty())
        assertEquals(0, map.capacity())
    }

    @Test
    fun docWithCapacity() {
        val map = HeaderMap.withCapacity<Int>(10)
        assertTrue(map.isEmpty())
        assertEquals(12, map.capacity())
    }

    @Test
    fun docTryWithCapacity() {
        val map = HeaderMap.tryWithCapacity<Int>(10).getOrThrow()
        assertTrue(map.isEmpty())
        assertEquals(12, map.capacity())
    }

    @Test
    fun docLen() {
        val map = HeaderMap.new()
        assertEquals(0, map.len())
        map.insert(HeaderName.ACCEPT, hv("text/plain"))
        map.insert(HeaderName.HOST, hv("localhost"))
        assertEquals(2, map.len())
        map.append(HeaderName.ACCEPT, hv("text/html"))
        assertEquals(3, map.len())
    }

    @Test
    fun docKeysLen() {
        val map = HeaderMap.new()
        assertEquals(0, map.keysLen())
        map.insert(HeaderName.ACCEPT, hv("text/plain"))
        map.insert(HeaderName.HOST, hv("localhost"))
        assertEquals(2, map.keysLen())
        map.insert(HeaderName.ACCEPT, hv("text/html"))
        assertEquals(2, map.keysLen())
    }

    @Test
    fun docIsEmpty() {
        val map = HeaderMap.new()
        assertTrue(map.isEmpty())
        map.insert(HeaderName.HOST, hv("hello.world"))
        assertFalse(map.isEmpty())
    }

    @Test
    fun docClear() {
        val map = HeaderMap.new()
        map.insert(HeaderName.HOST, hv("hello.world"))
        map.clear()
        assertTrue(map.isEmpty())
        assertTrue(map.capacity() > 0)
    }

    @Test
    fun docCapacity() {
        val map = HeaderMap.new()
        assertEquals(0, map.capacity())
        map.insert(HeaderName.HOST, hv("hello.world"))
        assertEquals(6, map.capacity())
    }

    @Test
    fun docReserve() {
        val map = HeaderMap.new()
        map.reserve(10)
        map.insert(HeaderName.HOST, hv("bar"))
        assertEquals(12, map.capacity())
    }

    @Test
    fun docTryReserve() {
        val map = HeaderMap.new()
        map.tryReserve(10).getOrThrow()
        map.tryInsert(HeaderName.HOST, hv("bar")).getOrThrow()
        assertEquals(12, map.capacity())
    }

    @Test
    fun docGet() {
        val map = HeaderMap.new()
        assertNull(map["host"])

        map.insert(HeaderName.HOST, hv("hello"))
        assertEquals(hv("hello"), map[HeaderName.HOST])
        assertEquals(hv("hello"), map["host"])

        map.append(HeaderName.HOST, hv("world"))
        assertEquals(hv("hello"), map["host"])
    }

    @Test
    fun docGetMut() {
        // `get_mut(..).push_str(..)`: with immutable values, the first value is replaced through the entry.
        val map = HeaderMap<String>()
        map.insert(HeaderName.HOST, "hello")
        (map.entry("host") as Entry.Occupied).let { it.set(it.get() + "-world") }
        assertEquals("hello-world", map[HeaderName.HOST])
    }

    @Test
    fun docGetAll() {
        val map = HeaderMap.new()
        map.insert(HeaderName.HOST, hv("hello"))
        map.append(HeaderName.HOST, hv("goodbye"))

        val view = map.getAll("host")
        val iter = view.iter()
        assertEquals(hv("hello"), iter.next())
        assertEquals(hv("goodbye"), iter.next())
        assertFalse(iter.hasNext())
    }

    @Test
    fun docContainsKey() {
        val map = HeaderMap.new()
        assertFalse(map.containsKey(HeaderName.HOST))
        map.insert(HeaderName.HOST, hv("world"))
        assertTrue(map.containsKey("host"))
        assertTrue("host" in map)
    }

    @Test
    fun docIter() {
        val map = HeaderMap.new()
        map.insert(HeaderName.HOST, hv("hello"))
        map.append(HeaderName.HOST, hv("goodbye"))
        map.insert(HeaderName.CONTENT_LENGTH, hv("123"))

        val seen = ArrayList<Pair<HeaderName, HeaderValue>>()
        for ((key, value) in map) seen.add(key to value)
        assertEquals(
            listOf(HeaderName.HOST to hv("hello"), HeaderName.HOST to hv("goodbye"), HeaderName.CONTENT_LENGTH to hv("123")),
            seen,
        )
    }

    @Test
    fun docIterMut() {
        val map = HeaderMap<String>()
        map.insert(HeaderName.HOST, "hello")
        map.append(HeaderName.HOST, "goodbye")
        map.insert(HeaderName.CONTENT_LENGTH, "123")

        val it = map.iterMut()
        while (it.hasNext()) {
            val (_, value) = it.next()
            it.set("$value-boop")
        }
        assertEquals(listOf("hello-boop", "goodbye-boop"), map.getAll(HeaderName.HOST).toList())
        assertEquals("123-boop", map[HeaderName.CONTENT_LENGTH])
    }

    @Test
    fun docKeys() {
        val map = HeaderMap.new()
        map.insert(HeaderName.HOST, hv("hello"))
        map.append(HeaderName.HOST, hv("goodbye"))
        map.insert(HeaderName.CONTENT_LENGTH, hv("123"))
        assertEquals(listOf(HeaderName.HOST, HeaderName.CONTENT_LENGTH), map.keys().asSequence().toList())
    }

    @Test
    fun docValues() {
        val map = HeaderMap.new()
        map.insert(HeaderName.HOST, hv("hello"))
        map.append(HeaderName.HOST, hv("goodbye"))
        map.insert(HeaderName.CONTENT_LENGTH, hv("123"))
        assertEquals(listOf(hv("hello"), hv("goodbye"), hv("123")), map.values().asSequence().toList())
    }

    @Test
    fun docValuesMut() {
        val map = HeaderMap<String>()
        map.insert(HeaderName.HOST, "hello")
        map.append(HeaderName.HOST, "goodbye")
        map.insert(HeaderName.CONTENT_LENGTH, "123")

        val it = map.valuesMut()
        while (it.hasNext()) it.set(it.next() + "-boop")
        assertEquals(listOf("hello-boop", "goodbye-boop", "123-boop"), map.values().asSequence().toList())
    }

    @Test
    fun docDrain() {
        val map = HeaderMap.new()
        map.insert(HeaderName.HOST, hv("hello"))
        map.append(HeaderName.HOST, hv("goodbye"))
        map.insert(HeaderName.CONTENT_LENGTH, hv("123"))

        val drain = map.drain()
        assertEquals(Pair<HeaderName?, HeaderValue>(HeaderName.HOST, hv("hello")), drain.next())
        assertEquals(Pair<HeaderName?, HeaderValue>(null, hv("goodbye")), drain.next())
        assertEquals(Pair<HeaderName?, HeaderValue>(HeaderName.CONTENT_LENGTH, hv("123")), drain.next())
        assertFalse(drain.hasNext())
    }

    private val counterHeaders = listOf("content-length", "x-hello", "Content-Length", "x-world")

    @Test
    fun docEntry() {
        val map = HeaderMap<Int>()
        for (header in counterHeaders) {
            // `*map.entry(header).or_insert(0) += 1`
            val counter = map.entry(header).orInsert(0)
            (map.entry(header) as Entry.Occupied).set(counter + 1)
        }
        assertEquals(2, map["content-length"])
        assertEquals(1, map["x-hello"])
    }

    @Test
    fun docInsert() {
        val map = HeaderMap.new()
        assertNull(map.insert(HeaderName.HOST, hv("world")))
        assertFalse(map.isEmpty())

        val prev = map.insert(HeaderName.HOST, hv("earth"))
        assertEquals(hv("world"), prev)
    }

    @Test
    fun docTryInsert() {
        val map = HeaderMap.new()
        assertNull(map.tryInsert(HeaderName.HOST, hv("world")).getOrThrow())
        assertFalse(map.isEmpty())

        val prev = map.tryInsert(HeaderName.HOST, hv("earth")).getOrThrow()
        assertEquals(hv("world"), prev)
    }

    @Test
    fun docAppend() {
        val map = HeaderMap.new()
        assertNull(map.insert(HeaderName.HOST, hv("world")))
        assertFalse(map.isEmpty())

        map.append(HeaderName.HOST, hv("earth"))

        val i = map.getAll("host").iter()
        assertEquals(hv("world"), i.next())
        assertEquals(hv("earth"), i.next())
    }

    @Test
    fun docTryAppend() {
        val map = HeaderMap.new()
        assertNull(map.tryInsert(HeaderName.HOST, hv("world")).getOrThrow())
        assertFalse(map.isEmpty())

        map.tryAppend(HeaderName.HOST, hv("earth")).getOrThrow()

        val i = map.getAll("host").iter()
        assertEquals(hv("world"), i.next())
        assertEquals(hv("earth"), i.next())
    }

    @Test
    fun docRemove() {
        val map = HeaderMap.new()
        map.insert(HeaderName.HOST, hv("hello.world"))

        val prev = map.remove(HeaderName.HOST)
        assertEquals(hv("hello.world"), prev)
        assertNull(map.remove(HeaderName.HOST))
    }

    @Test
    fun docIntoIter() {
        val map = HeaderMap.new()
        map.insert(HeaderName.CONTENT_LENGTH, hv("123"))
        map.insert(HeaderName.CONTENT_TYPE, hv("json"))

        val iter = map.intoIter()
        assertEquals(Pair<HeaderName?, HeaderValue>(HeaderName.CONTENT_LENGTH, hv("123")), iter.next())
        assertEquals(Pair<HeaderName?, HeaderValue>(HeaderName.CONTENT_TYPE, hv("json")), iter.next())
        assertFalse(iter.hasNext())
    }

    @Test
    fun docIntoIterMultipleValues() {
        val map = HeaderMap.new()
        map.append(HeaderName.CONTENT_LENGTH, hv("123"))
        map.append(HeaderName.CONTENT_LENGTH, hv("456"))
        map.append(HeaderName.CONTENT_TYPE, hv("json"))
        map.append(HeaderName.CONTENT_TYPE, hv("html"))
        map.append(HeaderName.CONTENT_TYPE, hv("xml"))

        val iter = map.intoIter()
        assertEquals(Pair<HeaderName?, HeaderValue>(HeaderName.CONTENT_LENGTH, hv("123")), iter.next())
        assertEquals(Pair<HeaderName?, HeaderValue>(null, hv("456")), iter.next())
        assertEquals(Pair<HeaderName?, HeaderValue>(HeaderName.CONTENT_TYPE, hv("json")), iter.next())
        assertEquals(Pair<HeaderName?, HeaderValue>(null, hv("html")), iter.next())
        assertEquals(Pair<HeaderName?, HeaderValue>(null, hv("xml")), iter.next())
        assertFalse(iter.hasNext())
    }

    @Test
    fun docTryFromHashMap() {
        val map = HashMap<String, String>()
        map["X-Custom-Header"] = "my value"

        val headers = HeaderMap.fromMap(map)
        assertEquals(hv("my value"), headers.getValue("X-Custom-Header"))

        assertNull(HeaderMap.tryFromMap(mapOf("bad name" to "v")))
        assertNull(HeaderMap.tryFromMap(mapOf("good" to "bad\u0000value")))
        assertFailsWith<InvalidHeaderName> { HeaderMap.fromMap(mapOf("bad name" to "v")) }
    }

    @Test
    fun docExtend() {
        val map = HeaderMap.new()
        map.insert(HeaderName.ACCEPT, hv("text/plain"))
        map.insert(HeaderName.HOST, hv("hello.world"))

        val extra = HeaderMap.new()
        extra.insert(HeaderName.HOST, hv("foo.bar"))
        extra.insert(HeaderName.COOKIE, hv("hello"))
        extra.append(HeaderName.COOKIE, hv("world"))

        map.extend(extra)

        assertEquals(hv("foo.bar"), map.getValue("host"))
        assertEquals(hv("text/plain"), map.getValue("accept"))
        assertEquals(hv("hello"), map.getValue("cookie"))

        assertEquals(1, map.getAll("host").iter().asSequence().count())
        assertEquals(2, map.getAll("cookie").iter().asSequence().count())
    }

    @Test
    fun docEntryOrInsert() {
        val map = HeaderMap<Int>()
        for (header in counterHeaders) {
            val counter = map.entry(header).orInsert(0)
            (map.entry(header) as Entry.Occupied).set(counter + 1)
        }
        assertEquals(2, map.getValue("content-length"))
        assertEquals(1, map.getValue("x-hello"))
    }

    @Test
    fun docEntryOrTryInsert() {
        val map = HeaderMap<Int>()
        for (header in counterHeaders) {
            val counter = map.entry(header).orTryInsert(0).getOrThrow()
            (map.entry(header) as Entry.Occupied).set(counter + 1)
        }
        assertEquals(2, map.getValue("content-length"))
        assertEquals(1, map.getValue("x-hello"))
    }

    @Test
    fun docEntryOrInsertWith() {
        val map = HeaderMap.new()
        val res = map.entry("x-hello").orInsertWith { hv("world") }
        assertEquals(hv("world"), res)
    }

    @Test
    fun docEntryOrInsertWithNotCalled() {
        val map = HeaderMap.new()
        map.tryInsert(HeaderName.HOST, hv("world")).getOrThrow()

        val res = map.tryEntry("host").getOrThrow().orTryInsertWith { fail("unreachable") }.getOrThrow()
        assertEquals(hv("world"), res)
    }

    @Test
    fun docEntryOrTryInsertWith() {
        val map = HeaderMap.new()
        val res = map.entry("x-hello").orTryInsertWith { hv("world") }.getOrThrow()
        assertEquals(hv("world"), res)
        assertEquals(hv("world"), map.entry("x-hello").orInsertWith { fail("unreachable") })
    }

    @Test
    fun docEntryKey() {
        val map = HeaderMap.new()
        assertTrue(map.entry("x-hello").key().equalsIgnoreCase("x-hello"))
    }

    @Test
    fun docVacantEntryKey() {
        val map = HeaderMap.new()
        assertEquals("x-hello", map.entry("x-hello").key().asStr())
    }

    @Test
    fun docVacantEntryIntoKey() {
        val map = HeaderMap.new()
        val v = map.entry("x-hello")
        assertIs<Entry.Vacant<HeaderValue>>(v)
        assertEquals("x-hello", v.intoKey().asStr())
    }

    @Test
    fun docVacantEntryInsert() {
        val map = HeaderMap.new()
        val v = map.entry("x-hello")
        if (v is Entry.Vacant) v.insert(hv("world"))
        assertEquals(hv("world"), map.getValue("x-hello"))
    }

    @Test
    fun docVacantEntryTryInsert() {
        val map = HeaderMap.new()
        val v = map.entry("x-hello")
        if (v is Entry.Vacant) v.tryInsert(hv("world")).getOrThrow()
        assertEquals(hv("world"), map.getValue("x-hello"))
    }

    @Test
    fun docVacantEntryInsertEntry() {
        val map = HeaderMap.new()
        val v = map.tryEntry("x-hello").getOrThrow()
        if (v is Entry.Vacant) {
            val e = v.insertEntry(hv("world"))
            e.insert(hv("world2"))
        }
        assertEquals(hv("world2"), map.getValue("x-hello"))
    }

    @Test
    fun docVacantEntryTryInsertEntry() {
        val map = HeaderMap.new()
        val v = map.tryEntry("x-hello").getOrThrow()
        if (v is Entry.Vacant) {
            val e = v.tryInsertEntry(hv("world")).getOrThrow()
            e.insert(hv("world2"))
        }
        assertEquals(hv("world2"), map.getValue("x-hello"))
    }

    @Test
    fun docGetAllIter() {
        val map = HeaderMap.new()
        map.insert(HeaderName.HOST, hv("hello.world"))
        map.append(HeaderName.HOST, hv("hello.earth"))

        val iter = map.getAll("host").iter()
        assertEquals(hv("hello.world"), iter.next())
        assertEquals(hv("hello.earth"), iter.next())
        assertFalse(iter.hasNext())
    }

    @Test
    fun docOccupiedEntryKey() {
        val map = HeaderMap.new()
        map.insert(HeaderName.HOST, hv("world"))
        val e = map.entry("host")
        assertIs<Entry.Occupied<HeaderValue>>(e)
        assertEquals("host", e.key().asStr())
    }

    @Test
    fun docOccupiedEntryGet() {
        val map = HeaderMap.new()
        map.insert(HeaderName.HOST, hv("hello.world"))
        val e = map.entry("host")
        assertIs<Entry.Occupied<HeaderValue>>(e)
        assertEquals(hv("hello.world"), e.get())
        e.append(hv("hello.earth"))
        assertEquals(hv("hello.world"), e.get())
    }

    @Test
    fun docOccupiedEntryGetMut() {
        val map = HeaderMap<String>()
        map.insert(HeaderName.HOST, "hello.world")
        val e = map.entry("host")
        assertIs<Entry.Occupied<String>>(e)
        e.set(e.get() + "-2")
        assertEquals("hello.world-2", e.get())
    }

    @Test
    fun docOccupiedEntryIntoMut() {
        val map = HeaderMap<String>()
        map.insert(HeaderName.HOST, "hello.world")
        map.append(HeaderName.HOST, "hello.earth")
        val e = map.entry("host")
        if (e is Entry.Occupied) e.set(e.get() + "-2")
        assertEquals("hello.world-2", map.getValue("host"))
        assertEquals(listOf("hello.world-2", "hello.earth"), map.getAll("host").toList())
    }

    @Test
    fun docOccupiedEntryInsert() {
        val map = HeaderMap.new()
        map.insert(HeaderName.HOST, hv("hello.world"))
        val e = map.entry("host")
        if (e is Entry.Occupied) {
            val prev = e.insert(hv("earth"))
            assertEquals(hv("hello.world"), prev)
        }
        assertEquals(hv("earth"), map.getValue("host"))
    }

    @Test
    fun docOccupiedEntryInsertMult() {
        val map = HeaderMap.new()
        map.insert(HeaderName.HOST, hv("world"))
        map.append(HeaderName.HOST, hv("world2"))
        val e = map.entry("host")
        if (e is Entry.Occupied) {
            val prev = e.insertMult(hv("earth"))
            assertEquals(hv("world"), prev.next())
            assertEquals(hv("world2"), prev.next())
            assertFalse(prev.hasNext())
        }
        assertEquals(hv("earth"), map.getValue("host"))
    }

    @Test
    fun docOccupiedEntryAppend() {
        val map = HeaderMap.new()
        map.insert(HeaderName.HOST, hv("world"))
        val e = map.entry("host")
        if (e is Entry.Occupied) e.append(hv("earth"))

        val i = map.getAll("host").iter()
        assertEquals(hv("world"), i.next())
        assertEquals(hv("earth"), i.next())
    }

    @Test
    fun docOccupiedEntryRemove() {
        val map = HeaderMap.new()
        map.insert(HeaderName.HOST, hv("world"))
        val e = map.entry("host")
        if (e is Entry.Occupied) assertEquals(hv("world"), e.remove())
        assertFalse(map.containsKey("host"))
    }

    @Test
    fun docOccupiedEntryRemoveEntry() {
        val map = HeaderMap.new()
        map.insert(HeaderName.HOST, hv("world"))
        val e = map.entry("host")
        if (e is Entry.Occupied) {
            val (key, prev) = e.removeEntry()
            assertEquals("host", key.asStr())
            assertEquals(hv("world"), prev)
        }
        assertFalse(map.containsKey("host"))
    }

    @Test
    fun docOccupiedEntryIter() {
        val map = HeaderMap.new()
        map.insert(HeaderName.HOST, hv("world"))
        map.append(HeaderName.HOST, hv("earth"))
        val e = map.entry("host")
        if (e is Entry.Occupied) {
            val iter = e.iter()
            assertEquals(hv("world"), iter.next())
            assertEquals(hv("earth"), iter.next())
            assertFalse(iter.hasNext())
        }
    }

    @Test
    fun docOccupiedEntryIterMut() {
        val map = HeaderMap<String>()
        map.insert(HeaderName.HOST, "world")
        map.append(HeaderName.HOST, "earth")
        val e = map.entry("host")
        if (e is Entry.Occupied) {
            val it = e.iterMut()
            while (it.hasNext()) it.set(it.next() + "-boop")
        }
        val i = map.getAll("host").iter()
        assertEquals("world-boop", i.next())
        assertEquals("earth-boop", i.next())
    }

    // ===== Kotlin shape and behaviour beyond the reference tests =====

    /** The reference panics here (it clears the entry's links before unlinking the extra values); we don't. */
    @Test
    fun insertMultThreeOrMoreValues() {
        val map = HeaderMap<Int>()
        map.insert(HeaderName.HOST, 1)
        for (v in 2..5) map.append(HeaderName.HOST, v)
        map.append(HeaderName.VIA, 10)
        map.append(HeaderName.VIA, 11)
        val e = map.entry(HeaderName.HOST) as Entry.Occupied
        assertEquals(listOf(1, 2, 3, 4, 5), e.insertMult(9).asSequence().toList())
        assertEquals(listOf(9), map.getAll(HeaderName.HOST).toList())
        assertEquals(listOf(10, 11), map.getAll(HeaderName.VIA).toList())
        assertEquals(3, map.len())
    }

    /**
     * Iteration order depends only on the sequence of operations (entries are swap-removed), not on hashing, so it
     * must equal the reference's. Expected values were produced by http 1.5.0 running the same LCG-driven sequence.
     */
    @Test
    fun iterationOrderMatchesReference() {
        fun run(nNames: Int, steps: Int, seed: Int): Pair<String, Long> {
            val map = HeaderMap<Int>()
            val names = List(nNames) { hn("n$it") }
            var s = seed
            for (step in 0 until steps) {
                s = s * 1103515245 + 12345
                val r = (s ushr 16) and 0x7fff
                val name = names[r % nNames]
                when ((r / nNames) % 4) {
                    0 -> map.insert(name, step)
                    1, 2 -> map.append(name, step)
                    else -> map.remove(name)
                }
            }
            val sb = StringBuilder()
            map.forEach { k, v -> sb.append(k.asStr()).append('=').append(v).append(',') }
            val out = sb.toString()
            var h = 0xcbf29ce484222325uL.toLong() // FNV-1a 64 offset basis
            for (b in out.encodeToByteArray()) h = (h xor (b.toLong() and 0xff)) * 0x100000001b3L
            return out to h
        }
        val (out, h) = run(12, 200, 12345)
        assertEquals("n2=191,n2=197,n9=183,n10=173,n11=186,n7=175,n1=190,n1=198,n4=194,n5=189,n5=193,n0=199,", out)
        assertEquals(0x74bce2de7d0abac9uL.toLong(), h)
        assertEquals(0x8ccbd523ee34dd13uL.toLong(), run(40, 1000, 7).second)
        assertEquals(0x3d4693b72d1b3507uL.toLong(), run(300, 5000, 99).second)
    }

    @Test
    fun stringKeysAreCaseInsensitiveAndDoNotMatchInvalidNames() {
        val map = HeaderMap<Int>()
        map.insert("X-Custom", 1)
        assertEquals(1, map["x-custom"])
        assertEquals(1, map["X-CUSTOM"])
        assertTrue(map.append("x-CUSTOM", 2))
        assertEquals(1, map.keysLen())
        assertEquals("x-custom", map.keys().next().asStr())
        assertNull(map[""])
        assertNull(map["x-custom "])
        assertNull(map["x-custöm"])
        assertFalse(map.containsKey("x custom"))
        assertNull(map.remove("bad name"))
        assertEquals(listOf<Int>(), map.getAll("nope").toList())
        assertTrue(map.tryEntry("bad name").exceptionOrNull() is InvalidHeaderName)
        assertFailsWith<InvalidHeaderName> { map.entry("") }
        assertFailsWith<InvalidHeaderName> { map.append("a b", 3) }
        assertFailsWith<InvalidHeaderName> { map.tryInsert("a b", 3) }
        assertEquals(2, map.len())
    }

    @Test
    fun getValueThrowsWhenAbsent() {
        val map = HeaderMap.new()
        assertFailsWith<NoSuchElementException> { map.getValue(HeaderName.HOST) }
        assertFailsWith<NoSuchElementException> { map.getValue("host") }
    }

    @Test
    fun maxSizeReachedIsReportedByBothForms() {
        val map = HeaderMap<Int>()
        // 24576 names fill the largest table; the next new name needs a table beyond MAX_SIZE.
        for (i in 0 until 24_576) map.insert(hn("h$i"), i)
        assertEquals(24_576, map.capacity())
        assertFailsWith<MaxSizeReached> { map.insert(hn("one-more"), 0) }
        assertFailsWith<MaxSizeReached> { map.append("one-more", 0) }
        assertFailsWith<MaxSizeReached> { map.entry(hn("one-more")) }
        assertTrue(map.tryInsert(hn("one-more"), 0).exceptionOrNull() is MaxSizeReached)
        assertTrue(map.tryAppend(hn("one-more"), 0).exceptionOrNull() is MaxSizeReached)
        assertTrue(map.tryEntry(hn("one-more")).exceptionOrNull() is MaxSizeReached)
        assertTrue(map.tryEntry("one-more").exceptionOrNull() is MaxSizeReached)
        assertTrue(map.tryReserve(1).exceptionOrNull() is MaxSizeReached)
        assertTrue(HeaderMap.tryWithCapacity<Int>(24_577).exceptionOrNull() is MaxSizeReached)
        assertEquals(24_576, map.len())
        assertEquals(7, map[hn("h7")])
        // MaxSizeReached is an HttpException.
        assertIs<neton.http.HttpException>(MaxSizeReached())
    }

    @Test
    fun staleIteratorsAndEntriesAreDetected() {
        val map = HeaderMap<Int>()
        map.insert("a", 1)
        map.insert("b", 2)
        val it = map.iter()
        it.next()
        map.append("a", 3)
        assertFailsWith<ConcurrentModificationException> { it.next() }

        val view = map.getAll("a")
        map.remove("b")
        assertFailsWith<ConcurrentModificationException> { view.iter() }

        val e = map.entry("a") as Entry.Occupied
        e.append(4) // the entry's own changes keep it valid
        assertEquals(listOf(1, 3, 4), e.iter().asSequence().toList())
        map.insert("c", 5)
        assertFailsWith<ConcurrentModificationException> { e.get() }

        val v = map.entry("d") as Entry.Vacant
        v.insert(6)
        assertFailsWith<IllegalStateException> { v.insert(7) }
        assertEquals(6, map["d"])

        assertFailsWith<ConcurrentModificationException> {
            map.forEach { _, _ -> map.remove("c") }
        }
    }

    @Test
    fun valueIterIsDoubleEnded() {
        val map = HeaderMap<Int>()
        for (v in 1..5) map.append("x", v)
        val it = map.getAll("x").iter()
        assertEquals(1, it.next())
        assertEquals(5, it.nextBack())
        assertEquals(4, it.nextBack())
        assertEquals(2, it.next())
        assertEquals(3, it.nextBack())
        assertFalse(it.hasNext())
        assertFailsWith<NoSuchElementException> { it.next() }
        assertFailsWith<NoSuchElementException> { it.nextBack() }

        val single = HeaderMap<Int>().also { it.insert("y", 1) }.getAll("y").iter()
        assertEquals(1, single.nextBack())
        assertFalse(single.hasNext())
    }

    @Test
    fun extendWithPairsAppendsAndExtendGroupedReplaces() {
        val map = HeaderMap<Int>()
        map.insert("a", 1)
        map.extend(listOf(hn("a") to 2, hn("b") to 3))
        assertEquals(listOf(1, 2), map.getAll("a").toList())

        val source = HeaderMap<Int>()
        source.append("a", 7)
        source.append("a", 8)
        map.extendGrouped(source.drain())
        assertEquals(listOf(7, 8), map.getAll("a").toList())
        assertEquals(listOf(3), map.getAll("b").toList())
        assertTrue(source.isEmpty())

        assertFailsWith<IllegalArgumentException> { map.extendGrouped(listOf<Pair<HeaderName?, Int>>(null to 1).iterator()) }
    }

    @Test
    fun cloneEqualsAndHashCode() {
        val a = HeaderMap<Int>()
        a.insert("a", 1)
        a.append("a", 2)
        a.insert("b", 3)
        val b = a.clone()
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        b.append("b", 4)
        assertNotEquals(a, b)
        assertEquals(listOf(3), a.getAll("b").toList())

        // Same content inserted in another order: equal, with the same hash code.
        val c = HeaderMap<Int>()
        c.insert("b", 3)
        c.insert("a", 1)
        c.append("a", 2)
        assertEquals(a, c)
        assertEquals(a.hashCode(), c.hashCode())
        assertEquals("{\"a\": 1, \"a\": 2, \"b\": 3}", a.toString())
    }

    @Test
    fun reuseAfterDrainAndClear() {
        val map = HeaderMap<Int>()
        for (i in 0 until 20) map.append(hn("h${i % 7}"), i)
        val cap = map.capacity()
        assertEquals(20, map.drain().asSequence().count())
        assertEquals(cap, map.capacity())
        for (i in 0 until 20) map.append(hn("h${i % 7}"), i)
        assertEquals(20, map.len())
        assertEquals(listOf(0, 7, 14), map.getAll("h0").toList())
        map.clear()
        assertTrue(map.isEmpty())
        assertNull(map["h0"])
        map.insert("h0", 1)
        assertEquals(1, map.len())
    }

    // ===== hash-flooding protection (Danger: Green -> Yellow -> Red / back to Green) =====

    /** Finds [count] names whose hash has low bits [slot] under [mask] (a collision attack on the Green hash). */
    private fun collidingNames(prefix: String, mask: Int, slot: Int, count: Int): List<HeaderName> {
        val out = ArrayList<HeaderName>(count)
        var i = 0
        while (out.size < count) {
            val s = "$prefix$i"
            if ((HeaderName.hashOf(s) and mask) == slot) out.add(hn(s))
            i++
        }
        return out
    }

    /**
     * Builds a table of 2048 slots where one insertion displaces 200 entries (>= DISPLACEMENT_THRESHOLD):
     * one name at ideal slot 100, 200 names at ideal slot 101 (forming a run), then a second name for slot 100 steals
     * slot 101 and shifts the whole run.
     */
    private fun displacementAttack(map: HeaderMap<Int>, mask: Int): List<HeaderName> {
        val atSlot = collidingNames("a", mask, 100, 2)
        val run = collidingNames("b", mask, 101, 200)
        val names = ArrayList<HeaderName>()
        map.insert(atSlot[0], 0)
        names.add(atSlot[0])
        for ((i, n) in run.withIndex()) {
            map.insert(n, i + 1)
            names.add(n)
        }
        assertEquals(DANGER_GREEN, map.danger)
        map.append(atSlot[1], 1000)
        names.add(atSlot[1])
        return names
    }

    @Test
    fun dangerYellowThenRedAtLowLoad() {
        val map = HeaderMap<Int>()
        map.reserve(1500) // 2048 slots, capacity 1536: no growth during the attack
        assertEquals(2048, map.capacity() * 4 / 3)
        val names = displacementAttack(map, 2047)
        assertEquals(DANGER_YELLOW, map.danger)

        // Next insertion: 202 * 5 < 2048, so growing would not help: switch to Red and rebuild with SipHash.
        map.insert("after-attack", -1)
        assertEquals(DANGER_RED, map.danger)
        assertEquals(2048, map.capacity() * 4 / 3)

        // Everything is still found, by name and by string.
        assertEquals(0, map[names[0]])
        for (i in 1..200) assertEquals(i, map[names[i]])
        assertEquals(1000, map[names[201].asStr().uppercase()])
        assertEquals(-1, map["after-attack"])
        assertEquals(203, map.keysLen())

        // Red stays Red; keep working against a model.
        val model = LinkedHashMap<HeaderName, MutableList<Int>>()
        map.forEach { k, v -> model.getOrPut(k) { ArrayList() }.add(v) }
        for (i in 0 until 3000) {
            val n = hn("r${i % 700}")
            when (i % 5) {
                0, 1 -> { map.append(n, i); model.getOrPut(n) { ArrayList() }.add(i) }
                2 -> { map.insert(n, i); model[n] = mutableListOf(i) }
                3 -> assertEquals(model.remove(n)?.first(), map.remove(n))
                else -> assertEquals(model[names[i % names.size]]?.first(), map[names[i % names.size]])
            }
        }
        assertEquals(DANGER_RED, map.danger)
        assertModel(model, map)

        // clear() resets to Green.
        map.clear()
        assertEquals(DANGER_GREEN, map.danger)
    }

    @Test
    fun dangerYellowThenGreenAtHighLoad() {
        val map = HeaderMap<Int>()
        map.reserve(300) // 512 slots, capacity 384
        assertEquals(384, map.capacity())
        val names = displacementAttack(map, 511)
        assertEquals(DANGER_YELLOW, map.danger)

        // 202 * 5 >= 512: grow (double) and go back to Green.
        map.insert("after-attack", -1)
        assertEquals(DANGER_GREEN, map.danger)
        assertEquals(768, map.capacity())
        for (i in 1..200) assertEquals(i, map[names[i]])
        assertEquals(1000, map[names[201]])
    }

    @Test
    fun dangerForwardShiftThresholdViaVacantEntry() {
        // 513 names with the same ideal slot: the last one lands 512 slots away. As in the reference, `insert` ignores
        // the danger flag for a vacant slot, while the entry API passes it to phase two.
        val mask = 4095
        val map = HeaderMap<Int>()
        map.reserve(3000) // 4096 slots
        val names = collidingNames("f", mask, 7, 514)
        for (i in 0 until 513) map.insert(names[i], i)
        assertEquals(DANGER_GREEN, map.danger) // insert(): vacant slot at distance 512, flag ignored

        val e = map.entry(names[513])
        assertIs<Entry.Vacant<Int>>(e)
        e.insert(513) // distance 513 >= 512 through the entry API: Yellow
        assertEquals(DANGER_YELLOW, map.danger)
        map.insert("next", -1) // 515 * 5 < 4096: Red
        assertEquals(DANGER_RED, map.danger)
        for (i in 0 until 514) assertEquals(i, map[names[i]])
    }

    @Test
    fun redStateStringLookupsAndRemovals() {
        val map = HeaderMap<Int>()
        for (i in 0 until 50) map.append("X-Name-$i", i)
        map.append("x-name-3", 100)
        map.forceRedForTest()
        assertEquals(DANGER_RED, map.danger)
        for (i in 0 until 50) {
            assertEquals(i, map["x-name-$i"])
            assertEquals(i, map[hn("X-NAME-$i")])
        }
        assertEquals(listOf(3, 100), map.getAll("X-Name-3").toList())
        assertEquals(3, map.remove("X-NAME-3"))
        assertNull(map["x-name-3"])
        assertNull(map["x name"])
        for (i in 50 until 400) map.insert(hn("x-name-$i"), i) // grows while Red
        assertEquals(DANGER_RED, map.danger)
        assertEquals(399, map.keysLen())
        for (i in 0 until 400) if (i != 3) assertEquals(i, map["x-name-$i"])
        // Red keys are drawn per map: another map gets different keys (collision with probability ~2^-128).
        val other = HeaderMap<Int>().also { it.insert("a", 1); it.forceRedForTest() }
        assertEquals(1, other["a"])
    }

    private fun assertModel(model: Map<HeaderName, List<Int>>, map: HeaderMap<Int>) {
        assertEquals(model.size, map.keysLen())
        assertEquals(model.values.sumOf { it.size }, map.len())
        for ((k, v) in model) {
            assertEquals(v.first(), map[k])
            assertEquals(v, map.getAll(k).toList())
        }
    }

    companion object {
        private fun customStd(n: Int): List<HeaderName> = List(n) { i -> hn("${STD[i % STD.size].asStr()}-$i") }

        private val STD: List<HeaderName> = with(HeaderName) {
            listOf(
                ACCEPT, ACCEPT_CHARSET, ACCEPT_ENCODING, ACCEPT_LANGUAGE, ACCEPT_RANGES,
                ACCESS_CONTROL_ALLOW_CREDENTIALS, ACCESS_CONTROL_ALLOW_HEADERS, ACCESS_CONTROL_ALLOW_METHODS,
                ACCESS_CONTROL_ALLOW_ORIGIN, ACCESS_CONTROL_EXPOSE_HEADERS, ACCESS_CONTROL_MAX_AGE,
                ACCESS_CONTROL_REQUEST_HEADERS, ACCESS_CONTROL_REQUEST_METHOD, AGE, ALLOW, ALT_SVC, AUTHORIZATION,
                CACHE_CONTROL, CACHE_STATUS, CDN_CACHE_CONTROL, CONNECTION, CONTENT_DISPOSITION, CONTENT_ENCODING,
                CONTENT_LANGUAGE, CONTENT_LENGTH, CONTENT_LOCATION, CONTENT_RANGE, CONTENT_SECURITY_POLICY,
                CONTENT_SECURITY_POLICY_REPORT_ONLY, CONTENT_TYPE, COOKIE, DNT, DATE, ETAG, EXPECT, EXPIRES,
                FORWARDED, FROM, HOST, IF_MATCH, IF_MODIFIED_SINCE, IF_NONE_MATCH, IF_RANGE, IF_UNMODIFIED_SINCE,
                LAST_MODIFIED, LINK, LOCATION, MAX_FORWARDS, ORIGIN, PRAGMA, PROXY_AUTHENTICATE, PROXY_AUTHORIZATION,
                PUBLIC_KEY_PINS, PUBLIC_KEY_PINS_REPORT_ONLY, RANGE, REFERER, REFERRER_POLICY, RETRY_AFTER, SERVER,
                SET_COOKIE, STRICT_TRANSPORT_SECURITY, TE, TRAILER, TRANSFER_ENCODING, USER_AGENT, UPGRADE,
                UPGRADE_INSECURE_REQUESTS, VARY, VIA, WARNING, WWW_AUTHENTICATE, X_CONTENT_TYPE_OPTIONS,
                X_DNS_PREFETCH_CONTROL, X_FRAME_OPTIONS, X_XSS_PROTECTION,
            )
        }
    }
}
