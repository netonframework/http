package neton.http.h2.proto

import neton.http.h2.frame.StreamId

/**
 * Storage for the streams of a connection (`Store`, `src/proto/streams/store.rs`): the stream ID → stream map.
 *
 * The reference pairs a slab (the streams) with an `IndexMap` (the IDs, iterated by position, `swap_remove` on
 * unlink). Here streams are plain objects and the map is an insertion-ordered array with an open-addressing index
 * keyed by the raw ID, keeping the reference's iteration order (including what `swap_remove` does to it) without
 * boxing keys.
 *
 * - [unlink] removes the ID association (the stream is gone as far as the protocol is concerned);
 * - [remove] is the reference's slab removal: the stream object itself is simply dropped, only [wiredCount] tracks it.
 */
internal class Store {
    private var streams = arrayOfNulls<Stream>(16)
    private var count = 0

    // Open addressing: slot → position in [streams] + 1 (0 = empty). Keys are the stream IDs of those positions.
    private var index = IntArray(32)
    private var mask = 31

    /** Streams linked or still referenced (`num_wired_streams`). */
    var wiredCount = 0
        private set

    /** Streams with an ID association (`num_active_streams`). */
    val activeCount: Int get() = count

    fun find(id: StreamId): Stream? {
        val pos = position(id.value)
        return if (pos < 0) null else streams[pos]
    }

    /** Inserts a new stream (`insert` / `VacantEntry::insert`); its ID must not be present. */
    fun insert(stream: Stream): Stream {
        check(position(stream.id.value) < 0) { "stream ${stream.id.value} already in the store" }
        if (count == streams.size) streams = streams.copyOf(count * 2)
        streams[count] = stream
        count++
        if (count * 2 > mask + 1) rehash((mask + 1) * 2) else putIndex(stream.id.value, count - 1)
        wiredCount++
        return stream
    }

    /** Removes the ID → stream association (`Ptr::unlink`); the last stream takes its place in iteration order. */
    fun unlink(stream: Stream) {
        val id = stream.id.value
        val slot = slotOf(id)
        if (slot < 0) return
        val pos = index[slot] - 1
        deleteSlot(slot)
        val last = count - 1
        if (pos != last) {
            val moved = streams[last]!!
            streams[pos] = moved
            index[slotOf(moved.id.value)] = pos + 1
        }
        streams[last] = null
        count--
    }

    /** The stream is dropped (`Ptr::remove`); it must have been unlinked. */
    fun remove(stream: Stream) {
        check(find(stream.id) !== stream) { "removing a linked stream" }
        if (stream.removed) return
        stream.removed = true
        wiredCount--
    }

    /** Whether [stream] is still linked under its ID. */
    fun isLinked(stream: Stream): Boolean = find(stream.id) === stream

    /**
     * Calls [f] on every linked stream (`for_each` / `try_for_each`). [f] may unlink the stream it is given (and only
     * that one): the stream moved into its place is visited next.
     */
    inline fun forEach(f: (Stream) -> Unit) {
        var len = activeCount
        var i = 0
        while (i < len) {
            f(streamAt(i))
            val newLen = activeCount
            if (newLen < len) {
                check(newLen == len - 1)
                len--
            } else {
                i++
            }
        }
    }

    fun streamAt(i: Int): Stream = streams[i]!!

    private fun position(id: Int): Int {
        val slot = slotOf(id)
        return if (slot < 0) -1 else index[slot] - 1
    }

    private fun hash(id: Int): Int {
        // Stream IDs are sequential (odd or even): spread them over the table.
        val h = id * -0x61c88647
        return h xor (h ushr 16)
    }

    private fun slotOf(id: Int): Int {
        var i = hash(id) and mask
        while (true) {
            val e = index[i]
            if (e == 0) return -1
            if (streams[e - 1]!!.id.value == id) return i
            i = (i + 1) and mask
        }
    }

    private fun putIndex(id: Int, pos: Int) {
        var i = hash(id) and mask
        while (index[i] != 0) i = (i + 1) and mask
        index[i] = pos + 1
    }

    /** Backward-shift deletion for linear probing. */
    private fun deleteSlot(slot: Int) {
        var hole = slot
        var i = (slot + 1) and mask
        while (true) {
            val e = index[i]
            if (e == 0) break
            val home = hash(streams[e - 1]!!.id.value) and mask
            // Move the entry into the hole when its home is not in (hole, i].
            val between = if (hole <= i) home in (hole + 1)..i else home > hole || home <= i
            if (!between) {
                index[hole] = e
                hole = i
            }
            i = (i + 1) and mask
        }
        index[hole] = 0
    }

    private fun rehash(capacity: Int) {
        index = IntArray(capacity)
        mask = capacity - 1
        for (p in 0 until count) putIndex(streams[p]!!.id.value, p)
    }
}
