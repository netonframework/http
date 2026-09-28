package neton.http.h2.hpack

import neton.http.header.HeaderName

/**
 * The HPACK encoder's dynamic table (`h2::hpack::table::Table`, `src/hpack/table.rs`).
 *
 * Entries live in a deque ([slots], newest first) and are found through a Robin Hood hash index ([indices]) keyed
 * by the header *name*; entries with the same name form a linked list through [Slot.next], newest first, so a lookup
 * finds a name once and then walks its values. Positions stored in the index are "slot index minus [inserted]"
 * (wrapping), so pushing a new entry at the front never rewrites the index; the real slot is `pos + inserted`. All
 * of this is the reference's design, including its wrapping arithmetic (here modulo 2^32 instead of 2^64, which is
 * equivalent because only differences of small indices are ever observed).
 *
 * [index] reports its result through [resKind], [resIdx] and [resSlot] instead of returning an `Index` value, so
 * encoding a header allocates nothing unless the header is inserted into the table.
 */
internal class EncoderTable(maxSize: Int, capacity: Int) {
    private var mask = 0

    // Robin Hood index: position (wrapping slot offset) and hash; hash EMPTY marks a vacant bucket (`None`).
    private var idxPos = IntArray(0)
    private var idxHash = IntArray(0)

    // The slot deque (a power-of-two ring): header, name hash, entry size and the `next` link of each slot.
    private var slotHeader = arrayOfNulls<Header>(0)
    private var slotHash = IntArray(0)
    private var slotSize = IntArray(0)
    private var slotNext = IntArray(0)
    private var slotHasNext = BooleanArray(0)
    private var slotHead = 0
    private var slotCount = 0

    private var inserted = 0

    /** Size of the entries in bytes. */
    var size: Int = 0
        private set

    var maxSize: Int = maxSize
        private set

    // Result of the last [index] call (the reference's `Index`).
    var resKind: Int = 0
        private set
    var resIdx: Int = 0
        private set
    var resSlot: Int = 0
        private set

    init {
        if (capacity != 0) {
            val raw = maxOf(nextPowerOfTwo(capacity + capacity / 3), 8)
            mask = raw - 1
            idxPos = IntArray(raw)
            idxHash = IntArray(raw) { EMPTY }
            allocSlots(nextPowerOfTwo(usableCapacity(raw)))
        }
    }

    /** Number of entries (`len`). */
    val len: Int get() = slotCount

    private fun usableCapacity(): Int = usableCapacity(idxPos.size)

    // ---- the slot deque ----

    private fun allocSlots(cap: Int) {
        slotHeader = arrayOfNulls(cap)
        slotHash = IntArray(cap)
        slotSize = IntArray(cap)
        slotNext = IntArray(cap)
        slotHasNext = BooleanArray(cap)
        slotHead = 0
    }

    private fun phys(i: Int): Int = (slotHead + i) and (slotHeader.size - 1)

    /** Header of the slot at real index [i] (0 = newest). */
    fun slotHeaderAt(i: Int): Header = slotHeader[phys(i)]!!

    private fun pushFront(header: Header, hash: Int, len: Int) {
        if (slotCount == slotHeader.size) {
            val oldCap = slotHeader.size
            val newCap = if (oldCap == 0) 8 else oldCap * 2
            val h = arrayOfNulls<Header>(newCap)
            val hs = IntArray(newCap)
            val sz = IntArray(newCap)
            val nx = IntArray(newCap)
            val hn = BooleanArray(newCap)
            for (i in 0 until slotCount) {
                val p = (slotHead + i) and (oldCap - 1)
                h[i] = slotHeader[p]; hs[i] = slotHash[p]; sz[i] = slotSize[p]; nx[i] = slotNext[p]; hn[i] = slotHasNext[p]
            }
            slotHeader = h; slotHash = hs; slotSize = sz; slotNext = nx; slotHasNext = hn
            slotHead = 0
        }
        slotHead = (slotHead - 1) and (slotHeader.size - 1)
        slotHeader[slotHead] = header
        slotHash[slotHead] = hash
        slotSize[slotHead] = len
        slotHasNext[slotHead] = false
        slotCount++
    }

    // ---- lookups ----

    /**
     * Returns the HPACK index to use for a name-indexed reference to the last result (`resolve_idx`). Not valid for
     * [NOT_INDEXED].
     */
    fun resolveIdx(kind: Int, idx: Int, slot: Int): Int = when (kind) {
        INDEXED, NAME -> idx
        INSERTED -> idx + DYN_OFFSET
        INSERTED_VALUE -> slot + DYN_OFFSET
        else -> throw IllegalStateException("cannot resolve index")
    }

    /**
     * Indexes the header (kind, [name], [value]) in the HPACK table (`Table::index`). [header] is the same header
     * as an object, if the caller has one; otherwise one is created when the header is inserted.
     */
    fun index(kind: Int, name: HeaderName?, value: Any, header: Header?, len: Int, sensitive: Boolean) {
        // Check the static table.
        val statik = indexStatic(kind, name, value)

        // Don't index certain headers. This logic is borrowed from nghttp2. Their names are always in the static
        // table.
        if (skipValueIndex(kind, name)) return indexNew(statik)

        // If the header is already indexed by the static table, return that.
        if (statik != STATIC_NONE && statik and STATIC_VALUE_MATCH != 0) return result(INDEXED, statik and 0xff, 0)

        // Don't index large headers.
        if (len.toLong() * 4 > maxSize.toLong() * 3) return indexNew(statik)

        indexDynamic(kind, name, value, header, len, sensitive, statik)
    }

    private fun result(kind: Int, idx: Int, slot: Int) {
        resKind = kind; resIdx = idx; resSlot = slot
    }

    /** `Index::new`. */
    private fun indexNew(statik: Int) {
        when {
            statik == STATIC_NONE -> result(NOT_INDEXED, 0, 0)
            statik and STATIC_VALUE_MATCH != 0 -> result(INDEXED, statik and 0xff, 0)
            else -> result(NAME, statik, 0)
        }
    }

    private fun indexDynamic(kind: Int, name: HeaderName?, value: Any, header: Header?, len: Int, sensitive: Boolean, statik: Int) {
        if (len.toLong() + size < maxSize || !sensitive) {
            // Only grow internal storage if needed.
            reserveOne()
        }
        if (idxPos.isEmpty()) {
            // If the index is not empty, it can never be full, so only the empty case needs checking.
            return indexNew(statik)
        }

        val hash = hashHeader(kind, name)
        var probe = hash and mask
        var dist = 0
        val n = idxPos.size
        while (true) {
            if (probe < n) {
                if (idxHash[probe] != EMPTY) {
                    // The bucket is occupied; check whether it has a lower displacement.
                    val theirDist = probeDistance(idxHash[probe], probe)
                    if (theirDist < dist) {
                        // Robin Hood.
                        return indexVacant(kind, name, value, header, len, sensitive, hash, dist, probe, statik)
                    } else if (idxHash[probe] == hash && nameEq(slotHeaderAt(idxPos[probe] + inserted), kind, name)) {
                        // Matching name, check the values.
                        return indexOccupied(kind, name, value, header, len, sensitive, hash, idxPos[probe], statik)
                    }
                } else {
                    return indexVacant(kind, name, value, header, len, sensitive, hash, dist, probe, statik)
                }
                dist++
                probe++
            } else {
                probe = 0
            }
        }
    }

    private fun indexOccupied(
        kind: Int, name: HeaderName?, value: Any, header: Header?, len: Int, sensitive: Boolean,
        hash: Int, startIndex: Int, statik: Int,
    ) {
        // There already is an entry with this name. Check whether a value matches; the header is only inserted if
        // the table is not at capacity.
        var index = startIndex
        while (true) {
            val realIdx = index + inserted
            val p = phys(realIdx)
            if (slotHeader[p]!!.valueObj == value) {
                // A full match.
                return result(INDEXED, realIdx + DYN_OFFSET, 0)
            }
            if (slotHasNext[p]) {
                index = slotNext[p]
                continue
            }
            if (sensitive) return result(NAME, realIdx + DYN_OFFSET, 0)

            updateSize(len, index, true)

            // Insert the new header.
            insert(header ?: makeHeader(kind, name, value), hash, len)

            // The previous node of the list may have been evicted while making room.
            val newRealIdx = index + inserted
            if (newRealIdx.toUInt() < slotCount.toUInt()) {
                val q = phys(newRealIdx)
                slotNext[q] = -inserted
                slotHasNext[q] = true
            }

            // Even if the previous entry was evicted, it can still be referenced by the new one.
            return if (statik != STATIC_NONE) result(INSERTED_VALUE, statik and 0xff, 0)
            else result(INSERTED_VALUE, realIdx + DYN_OFFSET, 0)
        }
    }

    private fun indexVacant(
        kind: Int, name: HeaderName?, value: Any, header: Header?, len: Int, sensitive: Boolean,
        hash: Int, dist0: Int, probe0: Int, statik: Int,
    ) {
        if (sensitive) return indexNew(statik)
        var dist = dist0
        var probe = probe0

        if (updateSize(len, 0, false)) {
            // Entries were evicted: the bucket may now sit closer to the ideal position.
            while (dist != 0) {
                val back = (probe - 1) and mask
                if (idxHash[back] != EMPTY) {
                    val theirDist = probeDistance(idxHash[back], back)
                    if (theirDist < dist - 1) {
                        probe = back
                        dist--
                    } else {
                        break
                    }
                } else {
                    probe = back
                    dist--
                }
            }
        }

        insert(header ?: makeHeader(kind, name, value), hash, len)

        var prevPos = idxPos[probe]
        var prevHash = idxHash[probe]
        idxPos[probe] = -inserted
        idxHash[probe] = hash

        if (prevHash != EMPTY) {
            // Shift the following buckets forward.
            var p = probe + 1
            val n = idxPos.size
            while (true) {
                if (p < n) {
                    val curPos = idxPos[p]
                    val curHash = idxHash[p]
                    idxPos[p] = prevPos
                    idxHash[p] = prevHash
                    if (curHash == EMPTY) break
                    prevPos = curPos
                    prevHash = curHash
                    p++
                } else {
                    p = 0
                }
            }
        }

        if (statik != STATIC_NONE) result(INSERTED_VALUE, statik and 0xff, 0) else result(INSERTED, 0, 0)
    }

    private fun insert(header: Header, hash: Int, len: Int) {
        inserted++
        pushFront(header, hash, len)
    }

    /** Sets the maximum size, evicting as needed (`Table::resize`). */
    fun resize(newSize: Int) {
        maxSize = newSize
        if (newSize == 0) {
            size = 0
            idxHash.fill(EMPTY)
            slotHeader.fill(null)
            slotCount = 0
            slotHead = 0
            inserted = 0
        } else {
            converge(0, false)
        }
    }

    private fun updateSize(len: Int, prevIdx: Int, hasPrev: Boolean): Boolean {
        size += len
        return converge(prevIdx, hasPrev)
    }

    private fun converge(prevIdx: Int, hasPrev: Boolean): Boolean {
        var ret = false
        while (size > maxSize) {
            ret = true
            evict(prevIdx, hasPrev)
        }
        return ret
    }

    private fun evict(prevIdx: Int, hasPrev: Boolean) {
        val posIdx = (slotCount - 1) - inserted

        // Remove the oldest entry.
        val p = phys(slotCount - 1)
        val hash = slotHash[p]
        val hasNext = slotHasNext[p]
        val next = slotNext[p]
        size -= slotSize[p]
        slotHeader[p] = null
        slotCount--

        // Find its bucket.
        var probe = hash and mask
        val n = idxPos.size
        while (true) {
            if (probe < n) {
                if (idxHash[probe] != EMPTY && idxPos[probe] == posIdx) {
                    if (hasNext) {
                        idxPos[probe] = next
                    } else if (hasPrev && idxPos[probe] == prevIdx) {
                        // Still referenced by the entry being inserted: point at it (it becomes slot 0).
                        idxPos[probe] = -(inserted + 1)
                    } else {
                        idxHash[probe] = EMPTY
                        removePhaseTwo(probe)
                    }
                    break
                }
                probe++
            } else {
                probe = 0
            }
        }
    }

    /** Shifts back the buckets displaced by the one just removed. */
    private fun removePhaseTwo(start: Int) {
        var lastProbe = start
        var probe = start + 1
        val n = idxPos.size
        while (true) {
            if (probe < n) {
                if (idxHash[probe] != EMPTY && probeDistance(idxHash[probe], probe) > 0) {
                    idxPos[lastProbe] = idxPos[probe]
                    idxHash[lastProbe] = idxHash[probe]
                    idxHash[probe] = EMPTY
                } else {
                    break
                }
                lastProbe = probe
                probe++
            } else {
                probe = 0
            }
        }
    }

    private fun reserveOne() {
        val len = slotCount
        if (len == usableCapacity()) {
            if (len == 0) {
                mask = 8 - 1
                idxPos = IntArray(8)
                idxHash = IntArray(8) { EMPTY }
            } else {
                grow(idxPos.size shl 1)
            }
        }
    }

    private fun grow(newRawCap: Int) {
        // Find the first ideally placed bucket: the start of a cluster.
        var firstIdeal = 0
        for (i in idxPos.indices) {
            if (idxHash[i] != EMPTY && probeDistance(idxHash[i], i) == 0) {
                firstIdeal = i
                break
            }
        }

        // Visit the buckets in an order where they can simply be reinserted without stealing.
        val oldPos = idxPos
        val oldHash = idxHash
        idxPos = IntArray(newRawCap)
        idxHash = IntArray(newRawCap) { EMPTY }
        mask = newRawCap - 1
        for (i in firstIdeal until oldPos.size) reinsertInOrder(oldPos[i], oldHash[i])
        for (i in 0 until firstIdeal) reinsertInOrder(oldPos[i], oldHash[i])
    }

    private fun reinsertInOrder(pos: Int, hash: Int) {
        if (hash == EMPTY) return
        var probe = hash and mask
        val n = idxPos.size
        while (true) {
            if (probe < n) {
                if (idxHash[probe] == EMPTY) {
                    idxPos[probe] = pos
                    idxHash[probe] = hash
                    return
                }
                probe++
            } else {
                probe = 0
            }
        }
    }

    private fun probeDistance(hash: Int, current: Int): Int = (current - (hash and mask)) and mask

    companion object {
        // `Index` variants.
        const val INDEXED = 0
        const val NAME = 1
        const val INSERTED = 2
        const val INSERTED_VALUE = 3
        const val NOT_INDEXED = 4

        /** The first dynamic index (`DYN_OFFSET`). */
        const val DYN_OFFSET = STATIC_TABLE_LEN + 1

        private const val EMPTY = -1

        /** The reference masks name hashes to 16 bits (`MAX_SIZE`). */
        private const val HASH_MASK = (1 shl 16) - 1

        private fun usableCapacity(cap: Int): Int = cap - cap / 4

        private fun nextPowerOfTwo(n: Int): Int {
            var c = 1
            while (c < n) c = c shl 1
            return c
        }

        /**
         * Hash of a header name (`hash_header`): the name's precomputed hash for a regular field, a fixed value per
         * pseudo-header. The reference feeds the name to FNV; any stable hash gives the same table behaviour.
         */
        private fun hashHeader(kind: Int, name: HeaderName?): Int =
            (if (kind == K_FIELD) name!!.hash else (kind * -0x61c88647) xor (kind ushr 3)) and HASH_MASK

        /** `Name` equality: same pseudo-header, or equal field names. */
        private fun nameEq(h: Header, kind: Int, name: HeaderName?): Boolean =
            h.kind == kind && (kind != K_FIELD || (h as Header.Field).name == name)
    }
}
