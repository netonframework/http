@file:Suppress("UNCHECKED_CAST")

package neton.http.header

import neton.io.core.secureRandomLong

// ===== constants (http 1.5.0 `src/header/map.rs`) =====

/** Hash values are limited to 15 bits: the index table never grows beyond [HeaderMap.MAX_SIZE] slots. */
private const val HASH_MASK: Int = HeaderMap.MAX_SIZE - 1

// Constants related to detecting DOS attacks. Displacement is the number of entries that get shifted when inserting
// a new value; forward shift is how far the entry gets stored from its ideal position.
private const val DISPLACEMENT_THRESHOLD: Int = 128
private const val FORWARD_SHIFT_THRESHOLD: Int = 512

// In the Yellow state the map grows (and goes back to Green) if the load factor is at least 1 / LOAD_FACTOR_THRESHOLD;
// below that, growing would not help against collisions, so it switches to Red (keyed SipHash) instead.
private const val LOAD_FACTOR_THRESHOLD: Int = 5

internal const val DANGER_GREEN: Int = 0
internal const val DANGER_YELLOW: Int = 1
internal const val DANGER_RED: Int = 2

// Result of insert phase one: the probe position in the low 16 bits, the kind and the danger flag above.
private const val PROBE_BITS: Int = 0xFFFF
private const val KIND_MASK: Int = 3 shl 16
private const val KIND_VACANT: Int = 1 shl 16
private const val KIND_OCCUPIED: Int = 2 shl 16
private const val KIND_ROBINHOOD: Int = 3 shl 16
private const val DANGER_FLAG: Int = 1 shl 20

// Iteration cursors (the reference's `Option<Cursor>`): NONE, HEAD, or an index into the extra values (>= 0).
internal const val CURSOR_NONE: Int = -2
internal const val CURSOR_HEAD: Int = -1

private val EMPTY_INTS = IntArray(0)

/** Ints per entry in [HeaderMap.entryMeta]: hash, first extra value, last extra value. */
@PublishedApi internal const val META_STRIDE = 3
private val EMPTY_NAMES = arrayOfNulls<HeaderName>(0)
private val EMPTY_VALUES = arrayOfNulls<Any?>(0)

/** Returned by the internal insert paths instead of throwing, so both the throwing and the `try*` APIs share them. */
private object MaxSizeFailure

private fun usableCapacity(cap: Int): Int = cap - cap / 4

private fun newIndices(size: Int): IntArray = IntArray(size).also { it.fill(-1) }

/**
 * A specialized multimap for header names and values (`http::HeaderMap`).
 *
 * Supports multiple values per header name: [insert] replaces every value of a name, [append] adds one, [get] returns
 * the first value and [getAll] all values of a name in insertion order. [len] counts values, [keysLen] names.
 *
 * ## Iteration order
 * Iterators visit names in an arbitrary but deterministic order (the order of the internal entry list, which
 * `remove` changes by moving the last entry into the hole, as the reference's `swap_remove`); the values of one name
 * are always yielded together, in insertion order. The order does not depend on hashing, so it is the same as the
 * reference's for the same sequence of operations.
 *
 * ## Adaptive hashing
 * Lookups use the precomputed [HeaderName.hash] (state Green). When an insertion has to shift at least 128 entries
 * or lands at least 512 slots away from its ideal position, the map turns Yellow; on the next insertion it either
 * grows (load factor at least 1/5, back to Green) or switches to Red: it rebuilds the table with SipHash-1-3 keyed by
 * two random 64-bit keys from the platform CSPRNG and stays Red until [clear].
 *
 * ## Limits
 * At most [MAX_SIZE] (32768) entries (header names); the throwing APIs throw [MaxSizeReached] beyond that, the `try*`
 * APIs return it as a failed [Result]. The number of values per name is not limited.
 *
 * ## Kotlin shape
 * - The reference's key traits (`AsHeaderName`, `IntoHeaderName`) become overloads taking a [HeaderName] or a
 *   [String]. A string key is matched case-insensitively without allocating; for lookups an invalid name simply does
 *   not match, for insertions it throws [InvalidHeaderName] (the reference panics).
 * - `Index` is `operator fun get` (returns null when absent); [getValue] is the throwing form.
 * - The reference's `&mut T` results become setters: [Entry.Occupied.set] replaces the first value (`get_mut`,
 *   `into_mut`), and the mutable iterators ([iterMut], [valuesMut], [Entry.Occupied.iterMut]) have a `set` that
 *   replaces the value last returned.
 * - Rust's borrow rules become runtime checks: an iterator, [GetAll] view or [Entry] used after the map was
 *   structurally modified by anything else throws [ConcurrentModificationException].
 * - [iter] yields a [Pair] per value; [forEach] is the allocation-free way to visit every value.
 *
 * Lookups ([get], [containsKey], [getAll]'s probe) and insertions into existing capacity do not allocate, apart from
 * the stored values themselves (and the name when a string key creates a new custom name). Not thread-safe.
 */
class HeaderMap<T>() {
    // Index table (`indices: Box<[Pos]>`): -1 for an empty slot, else (hash shl 16) or entryIndex.
    internal var mask: Int = 0
    internal var indices: IntArray = EMPTY_INTS

    // Entries (`entries: Vec<Bucket<T>>`) as parallel arrays: name, first value, hash, and the head / tail of the list
    // of extra values (-1 when the name has a single value).
    @PublishedApi internal var entryCount: Int = 0
    @PublishedApi internal var keys: Array<HeaderName?> = EMPTY_NAMES
    @PublishedApi internal var vals: Array<Any?> = EMPTY_VALUES
    /**
     * Per entry, [META_STRIDE] ints: the name's hash, the first extra value (link next) and the last extra value (link
     * tail). One array instead of three: a map allocates it once when it gets its first names.
     */
    @PublishedApi internal var entryMeta: IntArray = EMPTY_INTS

    // Extra values (`extra_values: Vec<ExtraValue<T>>`), a doubly linked list per name. A link >= 0 is an extra
    // value index (`Link::Extra`), a link < 0 is `inv()` of an entry index (`Link::Entry`).
    @PublishedApi internal var extraCount: Int = 0
    @PublishedApi internal var extraVals: Array<Any?> = EMPTY_VALUES
    internal var extraPrev: IntArray = EMPTY_INTS
    @PublishedApi internal var extraNext: IntArray = EMPTY_INTS

    internal var danger: Int = DANGER_GREEN
    private var sipK0: Long = 0L
    private var sipK1: Long = 0L

    /** Bumped on every structural modification; iterators, views and entries check it. */
    @PublishedApi internal var modCount: Int = 0

    // Out-parameter of removeExtraValue: the (fixed up) `next` link of the removed value.
    private var removedNext: Int = -1

    // ===== size and capacity =====

    /** Returns the number of values stored in the map (`len`); at least [keysLen]. */
    fun len(): Int = entryCount + extraCount

    /** Returns the number of names stored in the map (`keys_len`). */
    fun keysLen(): Int = entryCount

    /** Returns true if the map contains no elements. */
    fun isEmpty(): Boolean = entryCount == 0

    /** Returns the number of names the map can hold without reallocating (an approximation, as in the reference). */
    fun capacity(): Int = usableCapacity(indices.size)

    /** Clears the map, removing all names and values; keeps the allocated memory and resets the hashing to Green. */
    fun clear() {
        keys.fill(null, 0, entryCount)
        vals.fill(null, 0, entryCount)
        extraVals.fill(null, 0, extraCount)
        entryCount = 0
        extraCount = 0
        danger = DANGER_GREEN
        indices.fill(-1)
        modCount++
    }

    /**
     * Reserves capacity for at least [additional] more names (`reserve`), best effort.
     * @throws MaxSizeReached if the new size would exceed [MAX_SIZE].
     */
    fun reserve(additional: Int) {
        if (!reserveCore(additional)) throw MaxSizeReached()
    }

    /** Like [reserve], but returns [MaxSizeReached] as a failed [Result] instead of throwing (`try_reserve`). */
    fun tryReserve(additional: Int): Result<Unit> =
        if (reserveCore(additional)) Result.success(Unit) else Result.failure(MaxSizeReached())

    private fun reserveCore(additional: Int): Boolean {
        require(additional >= 0) { "additional must not be negative: $additional" }
        val cap = entryCount.toLong() + additional
        val rawCap = cap + cap / 3
        if (rawCap > indices.size) {
            val pow2 = nextPowerOfTwo(rawCap)
            if (pow2 > MAX_SIZE) return false
            val raw = pow2.toInt()
            if (entryCount == 0) {
                mask = raw - 1
                indices = newIndices(raw)
                resizeEntries(usableCapacity(raw))
                modCount++
            } else if (!tryGrow(raw)) {
                return false
            }
        }
        return true
    }

    // ===== lookup =====

    /** Returns the first value of [key], or null (`get`; also the reference's `Index`, see [getValue]). */
    operator fun get(key: HeaderName): T? {
        val probe = findProbe(key, null)
        return if (probe < 0) null else vals[indices[probe] and PROBE_BITS] as T
    }

    /** String form of [get]: matched case-insensitively; an invalid name returns null. Does not allocate. */
    operator fun get(key: String): T? {
        val probe = findProbe(null, key)
        return if (probe < 0) null else vals[indices[probe] and PROBE_BITS] as T
    }

    /**
     * Returns the first value of [key] (the reference's `Index`, which panics when the name is absent).
     * @throws NoSuchElementException if the map has no value for [key].
     */
    fun getValue(key: HeaderName): T {
        val probe = findProbe(key, null)
        if (probe < 0) throw NoSuchElementException("no entry found for key \"$key\"")
        return vals[indices[probe] and PROBE_BITS] as T
    }

    /** String form of [getValue]. */
    fun getValue(key: String): T {
        val probe = findProbe(null, key)
        if (probe < 0) throw NoSuchElementException("no entry found for key \"$key\"")
        return vals[indices[probe] and PROBE_BITS] as T
    }

    /** Returns a view of all values of [key] in insertion order (`get_all`); empty if there are none. */
    fun getAll(key: HeaderName): GetAll<T> = GetAll(this, entryIndexOf(findProbe(key, null)))

    /** String form of [getAll]. */
    fun getAll(key: String): GetAll<T> = GetAll(this, entryIndexOf(findProbe(null, key)))

    /** Returns true if the map has a value for [key] (`contains_key`). */
    fun containsKey(key: HeaderName): Boolean = findProbe(key, null) >= 0

    /** String form of [containsKey]. */
    fun containsKey(key: String): Boolean = findProbe(null, key) >= 0

    /** Same as [containsKey], for the `in` operator. */
    operator fun contains(key: HeaderName): Boolean = findProbe(key, null) >= 0

    /** Same as [containsKey], for the `in` operator. */
    operator fun contains(key: String): Boolean = findProbe(null, key) >= 0

    private fun entryIndexOf(probe: Int): Int = if (probe < 0) -1 else indices[probe] and PROBE_BITS

    // ===== insertion =====

    /**
     * Inserts a value for [key] (`insert`): all previous values of the name are removed and the first of them is
     * returned, or null if the name was absent. The stored name is not replaced.
     * @throws MaxSizeReached if the map is full.
     */
    fun insert(key: HeaderName, value: T): T? {
        val r = insertCore(key, null, value)
        if (r === MaxSizeFailure) throw MaxSizeReached()
        return r as T?
    }

    /**
     * String form of [insert]; the name is parsed like [HeaderName.fromStr] (any case).
     * @throws InvalidHeaderName if [key] is not a valid header name (the reference panics).
     * @throws MaxSizeReached if the map is full.
     */
    fun insert(key: String, value: T): T? {
        checkName(key)
        val r = insertCore(null, key, value)
        if (r === MaxSizeFailure) throw MaxSizeReached()
        return r as T?
    }

    /** Like [insert], but returns [MaxSizeReached] as a failed [Result] (`try_insert`). */
    fun tryInsert(key: HeaderName, value: T): Result<T?> {
        val r = insertCore(key, null, value)
        return if (r === MaxSizeFailure) Result.failure(MaxSizeReached()) else Result.success(r as T?)
    }

    /**
     * String form of [tryInsert].
     * @throws InvalidHeaderName if [key] is not a valid header name (the reference panics here too).
     */
    fun tryInsert(key: String, value: T): Result<T?> {
        checkName(key)
        val r = insertCore(null, key, value)
        return if (r === MaxSizeFailure) Result.failure(MaxSizeReached()) else Result.success(r as T?)
    }

    /**
     * Appends a value for [key] (`append`): returns true if the name was already present (the value is added after
     * its existing values), false if it was inserted as a new name.
     * @throws MaxSizeReached if the map is full.
     */
    fun append(key: HeaderName, value: T): Boolean {
        val r = appendCore(key, null, value)
        if (r < 0) throw MaxSizeReached()
        return r == 1
    }

    /**
     * String form of [append].
     * @throws InvalidHeaderName if [key] is not a valid header name.
     * @throws MaxSizeReached if the map is full.
     */
    fun append(key: String, value: T): Boolean {
        checkName(key)
        val r = appendCore(null, key, value)
        if (r < 0) throw MaxSizeReached()
        return r == 1
    }

    /** Like [append], but returns [MaxSizeReached] as a failed [Result] (`try_append`). */
    fun tryAppend(key: HeaderName, value: T): Result<Boolean> {
        val r = appendCore(key, null, value)
        return if (r < 0) Result.failure(MaxSizeReached()) else Result.success(r == 1)
    }

    /**
     * String form of [tryAppend].
     * @throws InvalidHeaderName if [key] is not a valid header name.
     */
    fun tryAppend(key: String, value: T): Result<Boolean> {
        checkName(key)
        val r = appendCore(null, key, value)
        return if (r < 0) Result.failure(MaxSizeReached()) else Result.success(r == 1)
    }

    /**
     * Returns the entry of [key] for in-place manipulation (`entry`). Reserves room for one more name first.
     * @throws MaxSizeReached if the map is full.
     */
    fun entry(key: HeaderName): Entry<T> = entryCore(key, null) ?: throw MaxSizeReached()

    /**
     * String form of [entry].
     * @throws InvalidHeaderName if [key] is not a valid header name.
     * @throws MaxSizeReached if the map is full.
     */
    fun entry(key: String): Entry<T> {
        checkName(key)
        return entryCore(null, key) ?: throw MaxSizeReached()
    }

    /** Like [entry], but returns [MaxSizeReached] as a failed [Result] (`try_entry`). */
    fun tryEntry(key: HeaderName): Result<Entry<T>> {
        val e = entryCore(key, null) ?: return Result.failure(MaxSizeReached())
        return Result.success(e)
    }

    /**
     * String form of [tryEntry] (`try_entry` with a string key): fails with [InvalidHeaderName] if [key] is not a
     * valid header name and with [MaxSizeReached] if the map is full. (The reference reports the latter as
     * `InvalidHeaderName` too, only to keep its signature stable.)
     */
    fun tryEntry(key: String): Result<Entry<T>> {
        if (!isValidName(key)) return Result.failure(InvalidHeaderName())
        val e = entryCore(null, key) ?: return Result.failure(MaxSizeReached())
        return Result.success(e)
    }

    /**
     * Removes [key] and returns its first value, or null if absent (`remove`). All values of the name are removed.
     * Moves the last entry into the freed position, which changes the iteration order.
     */
    fun remove(key: HeaderName): T? = removeAt(findProbe(key, null))

    /** String form of [remove]; an invalid name returns null. */
    fun remove(key: String): T? = removeAt(findProbe(null, key))

    private fun removeAt(probe: Int): T? {
        if (probe < 0) return null
        val idx = indices[probe] and PROBE_BITS
        val head = entryMeta[META_STRIDE * (idx) + 1]
        if (head >= 0) removeAllExtraValues(head)
        val value = vals[idx]
        removeFound(probe, idx)
        return value as T
    }

    // ===== iteration =====

    /**
     * Iterates over every (name, value) pair (`iter`); a name with several values is yielded once per value, its
     * values together in insertion order. Allocates one [Pair] per item; see [forEach] for the allocation-free form.
     */
    fun iter(): Iter<T> = Iter(this)

    /** Same as [iter], for `for ((name, value) in map)`. */
    operator fun iterator(): Iter<T> = Iter(this)

    /** Iterates like [iter]; [IterMut.set] replaces the value last returned (`iter_mut`). */
    fun iterMut(): IterMut<T> = IterMut(this)

    /** Visits every (name, value) pair in [iter] order without allocating. The map must not be modified meanwhile. */
    inline fun forEach(action: (name: HeaderName, value: T) -> Unit) {
        val expected = modCount
        var i = 0
        while (i < entryCount) {
            val name = keys[i]!!
            action(name, vals[i] as T)
            if (modCount != expected) throw ConcurrentModificationException()
            var x = entryMeta[META_STRIDE * (i) + 1]
            while (x >= 0) {
                action(name, extraVals[x] as T)
                if (modCount != expected) throw ConcurrentModificationException()
                x = extraNext[x]
            }
            i++
        }
    }

    /** Iterates over the names, each once even if it has several values (`keys`). */
    fun keys(): Keys = Keys(this)

    /** Iterates over every value, in [iter] order (`values`). */
    fun values(): Values<T> = Values(this)

    /** Iterates like [values]; [ValuesMut.set] replaces the value last returned (`values_mut`). */
    fun valuesMut(): ValuesMut<T> = ValuesMut(this)

    /**
     * Removes everything from the map and returns it as an iterator (`drain`). The first value of each name is
     * yielded with the name, the following values of the same name with a null name.
     *
     * Unlike the reference (which empties the map when the iterator is dropped), the map is empty as soon as this
     * returns: the iterator owns the removed storage and the map keeps its index table for reuse.
     */
    fun drain(): Drain<T> {
        val d = Drain<T>(keys, vals, entryMeta, extraVals, extraNext, entryCount, extraCount)
        keys = EMPTY_NAMES
        vals = EMPTY_VALUES
        entryMeta = EMPTY_INTS
        extraVals = EMPTY_VALUES
        extraPrev = EMPTY_INTS
        extraNext = EMPTY_INTS
        entryCount = 0
        extraCount = 0
        indices.fill(-1)
        modCount++
        return d
    }

    /**
     * The reference's consuming `into_iter`: yields the contents in the [drain] form. Kotlin cannot consume the map,
     * so this empties it, exactly like [drain].
     */
    fun intoIter(): Drain<T> = drain()

    // ===== extend =====

    /**
     * Extends this map with the contents of [other] (`Extend<(Option<HeaderName>, T)>` fed by `other.into_iter()`):
     * for every name of [other], the values it has here are replaced by all of its values in [other]; names only
     * present here are kept. [other] is left unchanged (the reference consumes it).
     * @throws MaxSizeReached if the map would exceed [MAX_SIZE] names.
     */
    fun extend(other: HeaderMap<T>) {
        if (other === this) return // replacing every name by its own values is a no-op
        reserveForExtend(other.entryCount)
        val otherKeys = other.keys
        val otherVals = other.vals
        val otherMeta = other.entryMeta
        val otherExtraVals = other.extraVals
        val otherExtraNext = other.extraNext
        for (i in 0 until other.entryCount) {
            val idx = entryForExtend(otherKeys[i]!!, otherVals[i] as T)
            var x = otherMeta[META_STRIDE * (i) + 1]
            while (x >= 0) {
                appendValue(idx, otherExtraVals[x] as T)
                x = otherExtraNext[x]
            }
        }
    }

    /**
     * Extends this map with items in the [drain] form (`Extend<(Option<HeaderName>, T)>`): an item with a name
     * replaces every value of that name, and each following item with a null name is appended to the same name.
     * @throws IllegalArgumentException if the first item has no name.
     * @throws MaxSizeReached if the map would exceed [MAX_SIZE] names.
     */
    fun extendGrouped(items: Iterator<Pair<HeaderName?, T>>) {
        reserveForExtend(if (items is Drain<*>) items.sizeHintLower() else 0)
        if (!items.hasNext()) return
        val first = items.next()
        var idx = entryForExtend(first.first ?: throw IllegalArgumentException("expected a header name, but got None"),
            first.second)
        while (items.hasNext()) {
            val (name, value) = items.next()
            if (name != null) idx = entryForExtend(name, value) else appendValue(idx, value)
        }
    }

    /**
     * Appends every (name, value) pair (`Extend<(HeaderName, T)>`): existing values are kept.
     * @throws MaxSizeReached if the map would exceed [MAX_SIZE] names.
     */
    fun extend(pairs: Iterable<Pair<HeaderName, T>>) {
        reserveForExtend(if (pairs is Collection<*>) pairs.size else 0)
        for ((name, value) in pairs) append(name, value)
    }

    // Keys may already be present or appear several times: reserve the whole hint when empty, else half of it
    // (rounded up), clamped so that an over-estimate cannot make `reserve` fail.
    private fun reserveForExtend(lowerBound: Int) {
        val hint = if (isEmpty()) lowerBound else (lowerBound + 1) / 2
        val maxReserve = maxOf(0, usableCapacity(MAX_SIZE) - entryCount)
        reserve(minOf(hint, maxReserve))
    }

    /** The reference's `try_entry2` followed by `OccupiedEntry::insert` / `VacantEntry::insert_entry`. */
    private fun entryForExtend(name: HeaderName, value: T): Int {
        if (!tryReserveOne()) throw MaxSizeReached()
        val hash = hashKey(name, null)
        val r = phaseOne(hash, name, null)
        val probe = r and PROBE_BITS
        if (r and KIND_MASK == KIND_OCCUPIED) {
            val idx = indices[probe] and PROBE_BITS
            insertOccupied(idx, value)
            return idx
        }
        val idx = insertPhaseTwo(name, value, hash, probe, r and DANGER_FLAG != 0)
        if (idx < 0) throw MaxSizeReached()
        return idx
    }

    // ===== Any =====

    /**
     * Two maps are equal when they have the same number of values and every name has the same values in the same
     * order (`PartialEq`); the iteration order of names does not matter.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HeaderMap<*>) return false
        if (len() != other.len()) return false
        for (i in 0 until entryCount) {
            val probe = other.findProbe(keys[i], null)
            if (probe < 0) return false
            val j = other.indices[probe] and PROBE_BITS
            if (vals[i] != other.vals[j]) return false
            var a = entryMeta[META_STRIDE * (i) + 1]
            var b = other.entryMeta[META_STRIDE * (j) + 1]
            while (a >= 0 && b >= 0) {
                if (extraVals[a] != other.extraVals[b]) return false
                a = extraNext[a]
                b = other.extraNext[b]
            }
            if ((a >= 0) != (b >= 0)) return false
        }
        return true
    }

    /** Consistent with [equals]: independent of the order of names, dependent on the order of each name's values. */
    override fun hashCode(): Int {
        var h = 0
        for (i in 0 until entryCount) {
            var e = 31 * keys[i]!!.hash + vals[i].hashCode()
            var x = entryMeta[META_STRIDE * (i) + 1]
            while (x >= 0) {
                e = 31 * e + extraVals[x].hashCode()
                x = extraNext[x]
            }
            h += e
        }
        return h
    }

    /** The reference's `Debug` form: `{"name": value, ...}`, one pair per value. */
    override fun toString(): String {
        val sb = StringBuilder()
        sb.append('{')
        var first = true
        forEach { name, value ->
            if (!first) sb.append(", ")
            first = false
            sb.append('"').append(name.asStr()).append("\": ").append(value)
        }
        sb.append('}')
        return sb.toString()
    }

    /** Returns a copy of this map (`Clone`); the values themselves are shared, not copied. */
    fun clone(): HeaderMap<T> {
        val m = HeaderMap<T>()
        m.mask = mask
        m.indices = indices.copyOf()
        m.entryCount = entryCount
        m.keys = keys.copyOf()
        m.vals = vals.copyOf()
        m.entryMeta = entryMeta.copyOf()
        m.extraCount = extraCount
        m.extraVals = extraVals.copyOf()
        m.extraPrev = extraPrev.copyOf()
        m.extraNext = extraNext.copyOf()
        m.danger = danger
        m.sipK0 = sipK0
        m.sipK1 = sipK1
        return m
    }

    // ===== internals: hashing =====

    /** Hash of a key in the current danger state; exactly one of [name] and [str] is non-null. */
    private fun hashKey(name: HeaderName?, str: String?): Int {
        if (danger == DANGER_RED) {
            val k0 = sipK0
            val k1 = sipK1
            val h = if (name != null) {
                val b = name.bytes
                sipHash13(k0, k1, b.size) { b[it].toInt() and 0xff }
            } else {
                val s = str!!
                sipHash13(k0, k1, s.length) { HEADER_CHARS[charToByte(s[it].code)].toInt() }
            }
            return h.toInt() and HASH_MASK
        }
        return (name?.hash ?: HeaderName.hashOf(str!!)) and HASH_MASK
    }

    private fun matches(stored: HeaderName, name: HeaderName?, str: String?): Boolean =
        if (name != null) stored == name else stored.equalsIgnoreCase(str!!)

    // ===== internals: probing =====

    /** Returns the index-table position holding [name] / [str], or -1 (`find`). */
    private fun findProbe(name: HeaderName?, str: String?): Int {
        if (entryCount == 0) return -1
        val hash = hashKey(name, str)
        val idx = indices
        val m = mask
        var probe = hash and m
        var dist = 0
        while (true) {
            val p = idx[probe]
            if (p < 0) return -1
            val entryHash = p ushr 16
            // Give up when the probe distance is longer than the resident's.
            if (dist > ((probe - entryHash) and m)) return -1
            if (entryHash == hash && matches(keys[p and PROBE_BITS]!!, name, str)) return probe
            dist++
            probe = (probe + 1) and m
        }
    }

    /**
     * First part of the Robin Hood insertion (`insert_phase_one!`): from the ideal position, find either the slot
     * holding the key, the first empty slot, or the first slot whose resident is closer to its own ideal position.
     */
    private fun phaseOne(hash: Int, name: HeaderName?, str: String?): Int {
        val idx = indices
        val m = mask
        val notRed = danger != DANGER_RED
        var probe = hash and m
        var dist = 0
        while (true) {
            val p = idx[probe]
            if (p < 0) {
                return probe or KIND_VACANT or
                    (if (dist >= FORWARD_SHIFT_THRESHOLD && notRed) DANGER_FLAG else 0)
            }
            val entryHash = p ushr 16
            if (((probe - entryHash) and m) < dist) {
                return probe or KIND_ROBINHOOD or
                    (if (dist >= FORWARD_SHIFT_THRESHOLD && notRed) DANGER_FLAG else 0)
            }
            if (entryHash == hash && matches(keys[p and PROBE_BITS]!!, name, str)) return probe or KIND_OCCUPIED
            dist++
            probe = (probe + 1) and m
        }
    }

    /** `try_insert2`: returns the previous first value, null, or [MaxSizeFailure]. */
    private fun insertCore(name: HeaderName?, str: String?, value: T): Any? {
        if (!tryReserveOne()) return MaxSizeFailure
        val hash = hashKey(name, str)
        val r = phaseOne(hash, name, str)
        val probe = r and PROBE_BITS
        return when (r and KIND_MASK) {
            KIND_VACANT -> {
                // As in the reference, the danger flag is not acted upon for a vacant slot here.
                if (entryCount >= MAX_SIZE) return MaxSizeFailure
                val index = entryCount
                pushEntry(hash, name ?: HeaderName.fromStr(str!!), value)
                indices[probe] = (hash shl 16) or index
                modCount++
                null
            }
            KIND_OCCUPIED -> insertOccupied(indices[probe] and PROBE_BITS, value)
            else -> {
                if (insertPhaseTwo(name ?: HeaderName.fromStr(str!!), value, hash, probe, r and DANGER_FLAG != 0) < 0) {
                    MaxSizeFailure
                } else {
                    null
                }
            }
        }
    }

    /** `try_append2`: 1 if the name existed, 0 if inserted, -1 on [MaxSizeReached]. */
    private fun appendCore(name: HeaderName?, str: String?, value: T): Int {
        if (!tryReserveOne()) return -1
        val hash = hashKey(name, str)
        val r = phaseOne(hash, name, str)
        val probe = r and PROBE_BITS
        return when (r and KIND_MASK) {
            KIND_VACANT -> {
                if (entryCount >= MAX_SIZE) return -1
                val index = entryCount
                pushEntry(hash, name ?: HeaderName.fromStr(str!!), value)
                indices[probe] = (hash shl 16) or index
                modCount++
                0
            }
            KIND_OCCUPIED -> {
                appendValue(indices[probe] and PROBE_BITS, value)
                1
            }
            else -> if (insertPhaseTwo(name ?: HeaderName.fromStr(str!!), value, hash, probe,
                    r and DANGER_FLAG != 0) < 0) -1 else 0
        }
    }

    /** `try_entry2`: null on [MaxSizeReached]. */
    private fun entryCore(name: HeaderName?, str: String?): Entry<T>? {
        if (!tryReserveOne()) return null
        val hash = hashKey(name, str)
        val r = phaseOne(hash, name, str)
        val probe = r and PROBE_BITS
        return if (r and KIND_MASK == KIND_OCCUPIED) {
            Entry.Occupied(this, probe, indices[probe] and PROBE_BITS)
        } else {
            Entry.Vacant(this, name ?: HeaderName.fromStr(str!!), hash, probe, r and DANGER_FLAG != 0)
        }
    }

    /**
     * Phase two (`try_insert_phase_two`): push the entry and forward-shift the index table from [probe]. Raises the
     * danger level when [dangerous] or when too many slots were shifted. Returns the entry index, or -1 when full.
     */
    internal fun insertPhaseTwo(key: HeaderName, value: T, hash: Int, probe: Int, dangerous: Boolean): Int {
        if (entryCount >= MAX_SIZE) return -1
        val index = entryCount
        pushEntry(hash, key, value)
        val numDisplaced = doInsertPhaseTwo(probe, (hash shl 16) or index)
        if (dangerous || numDisplaced >= DISPLACEMENT_THRESHOLD) {
            if (danger == DANGER_GREEN) danger = DANGER_YELLOW // set_yellow
        }
        modCount++
        return index
    }

    /** Forward-shifts positions starting at [start] until an empty slot; returns the number displaced. */
    private fun doInsertPhaseTwo(start: Int, pos: Int): Int {
        val idx = indices
        val m = mask
        var probe = start
        var old = pos
        var numDisplaced = 0
        while (true) {
            val p = idx[probe]
            idx[probe] = old
            if (p < 0) break
            numDisplaced++
            old = p
            probe = (probe + 1) and m
        }
        return numDisplaced
    }

    /** Replaces the first value of entry [index] and removes the others (`insert_occupied`); returns the old one. */
    internal fun insertOccupied(index: Int, value: T): T {
        val head = entryMeta[META_STRIDE * (index) + 1]
        if (head >= 0) removeAllExtraValues(head)
        val old = vals[index]
        vals[index] = value
        modCount++
        return old as T
    }

    /** `insert_occupied_mult`: like [insertOccupied] but returns every previous value. */
    internal fun insertOccupiedMult(index: Int, value: T): ValueDrain<T> {
        val old = vals[index]
        vals[index] = value
        val extras = drainExtraValues(index)
        modCount++
        return ValueDrain(old as T, extras)
    }

    /**
     * Removes and returns every extra value of entry [index], in order. (The reference's `insert_mult` panics here
     * when the name has three or more values, because it clears the entry's links before unlinking; this unlinks
     * first.)
     */
    internal fun drainExtraValues(index: Int): Array<Any?>? {
        var head = entryMeta[META_STRIDE * (index) + 1]
        if (head < 0) return null
        var n = 0
        var x = head
        while (x >= 0) { n++; x = extraNext[x] }
        val out = arrayOfNulls<Any?>(n)
        var i = 0
        while (true) {
            out[i++] = removeExtraValue(head)
            if (removedNext >= 0) head = removedNext else break
        }
        return out
    }

    /** Appends [value] to the list of entry [entryIdx] (`append_value`). */
    internal fun appendValue(entryIdx: Int, value: T) {
        if (extraCount == extraVals.size) growExtras()
        val idx = extraCount++
        extraVals[idx] = value
        val tail = entryMeta[META_STRIDE * (entryIdx) + 2]
        if (entryMeta[META_STRIDE * (entryIdx) + 1] >= 0) {
            extraPrev[idx] = tail
            extraNext[idx] = entryIdx.inv()
            extraNext[tail] = idx
            entryMeta[META_STRIDE * (entryIdx) + 2] = idx
        } else {
            extraPrev[idx] = entryIdx.inv()
            extraNext[idx] = entryIdx.inv()
            entryMeta[META_STRIDE * (entryIdx) + 1] = idx
            entryMeta[META_STRIDE * (entryIdx) + 2] = idx
        }
        modCount++
    }

    private fun pushEntry(hash: Int, key: HeaderName, value: T) {
        if (entryCount == keys.size) resizeEntries(maxOf(capacity(), entryCount * 2, 8))
        val i = entryCount++
        keys[i] = key
        vals[i] = value
        entryMeta[META_STRIDE * (i)] = hash
        entryMeta[META_STRIDE * (i) + 1] = -1
        entryMeta[META_STRIDE * (i) + 2] = -1
    }

    private fun resizeEntries(size: Int) {
        if (keys.size >= size) return
        keys = keys.copyOf(size)
        vals = vals.copyOf(size)
        entryMeta = entryMeta.copyOf(size * META_STRIDE)
    }

    private fun growExtras() {
        val size = maxOf(4, extraVals.size * 2)
        extraVals = extraVals.copyOf(size)
        extraPrev = extraPrev.copyOf(size)
        extraNext = extraNext.copyOf(size)
    }

    // ===== internals: removal =====

    /**
     * Removes entry [found] held at index-table position [probe] (`remove_found`). Its extra values must already be
     * removed. The last entry moves into [found] (swap-remove) and the table is backward-shifted.
     */
    internal fun removeFound(probe: Int, found: Int) {
        val idx = indices
        val m = mask
        idx[probe] = -1
        val last = entryCount - 1
        if (found != last) {
            keys[found] = keys[last]
            vals[found] = vals[last]
            entryMeta[META_STRIDE * (found)] = entryMeta[META_STRIDE * (last)]
            entryMeta[META_STRIDE * (found) + 1] = entryMeta[META_STRIDE * (last) + 1]
            entryMeta[META_STRIDE * (found) + 2] = entryMeta[META_STRIDE * (last) + 2]
        }
        keys[last] = null
        vals[last] = null
        entryCount = last

        if (found < entryCount) {
            // Fix the position that points to the moved entry (its old index is `entryCount` now).
            val h = entryMeta[META_STRIDE * (found)]
            var p = h and m
            while (true) {
                val pos = idx[p]
                if (pos >= 0 && (pos and PROBE_BITS) >= entryCount) {
                    idx[p] = (h shl 16) or found
                    break
                }
                p = (p + 1) and m
            }
            val head = entryMeta[META_STRIDE * (found) + 1]
            if (head >= 0) {
                extraPrev[head] = found.inv()
                extraNext[entryMeta[META_STRIDE * (found) + 2]] = found.inv()
            }
        }

        // Backward-shift deletion: move following non-ideally placed positions back by one.
        if (entryCount > 0) {
            var lastProbe = probe
            var p = (probe + 1) and m
            while (true) {
                val pos = idx[p]
                if (pos < 0 || ((p - (pos ushr 16)) and m) == 0) break
                idx[lastProbe] = pos
                idx[p] = -1
                lastProbe = p
                p = (p + 1) and m
            }
        }
        modCount++
    }

    /** Removes extra value [idx] (`remove_extra_value`); sets [removedNext] to its fixed-up next link. */
    private fun removeExtraValue(idx: Int): Any? {
        val prev = extraPrev[idx]
        val next = extraNext[idx]

        // Unlink.
        if (prev < 0 && next < 0) {
            val e = prev.inv()
            entryMeta[META_STRIDE * (e) + 1] = -1
            entryMeta[META_STRIDE * (e) + 2] = -1
        } else if (prev < 0) {
            entryMeta[META_STRIDE * (prev.inv()) + 1] = next
            extraPrev[next] = prev
        } else if (next < 0) {
            entryMeta[META_STRIDE * (next.inv()) + 2] = prev
            extraNext[prev] = next
        } else {
            extraNext[prev] = next
            extraPrev[next] = prev
        }

        // Swap-remove.
        val value = extraVals[idx]
        val oldIdx = extraCount - 1
        if (idx != oldIdx) {
            extraVals[idx] = extraVals[oldIdx]
            extraPrev[idx] = extraPrev[oldIdx]
            extraNext[idx] = extraNext[oldIdx]
        }
        extraVals[oldIdx] = null
        extraCount = oldIdx

        var fixedNext = next
        if (fixedNext == oldIdx) fixedNext = idx

        // If another value moved into `idx`, fix the links pointing to it.
        if (idx != oldIdx) {
            val mp = extraPrev[idx]
            val mn = extraNext[idx]
            if (mp < 0) entryMeta[META_STRIDE * (mp.inv()) + 1] = idx else extraNext[mp] = idx
            if (mn < 0) entryMeta[META_STRIDE * (mn.inv()) + 2] = idx else extraPrev[mn] = idx
        }
        removedNext = fixedNext
        modCount++
        return value
    }

    /** `remove_all_extra_values`. */
    internal fun removeAllExtraValues(head0: Int) {
        var head = head0
        while (true) {
            removeExtraValue(head)
            if (removedNext >= 0) head = removedNext else break
        }
    }

    // ===== internals: growth and the danger state machine =====

    /** `try_reserve_one`: makes room for one more name, or runs the Yellow transition. False on [MaxSizeReached]. */
    private fun tryReserveOne(): Boolean {
        val len = entryCount
        if (danger == DANGER_YELLOW) {
            if (len * LOAD_FACTOR_THRESHOLD >= indices.size) {
                // Transition back to Green and double the capacity.
                danger = DANGER_GREEN
                return tryGrow(indices.size * 2)
            } else {
                becomeRed()
            }
        } else if (len == capacity()) {
            if (len == 0) {
                mask = 8 - 1
                indices = newIndices(8)
                resizeEntries(usableCapacity(8))
                modCount++
            } else {
                return tryGrow(indices.size shl 1)
            }
        }
        return true
    }

    /** Yellow -> Red (`set_red` + rebuild): new random SipHash keys, then rehash every entry. */
    private fun becomeRed() {
        danger = DANGER_RED
        sipK0 = secureRandomLong()
        sipK1 = secureRandomLong()
        indices.fill(-1)
        rebuild()
        modCount++
    }

    /** Test hook: forces the Yellow -> Red transition, as the next insertion would after detecting an attack. */
    internal fun forceRedForTest() {
        danger = DANGER_YELLOW
        becomeRed()
    }

    /** Re-inserts every entry into the (cleared) index table using the current hash (`rebuild`). */
    private fun rebuild() {
        val idx = indices
        val m = mask
        outer@ for (index in 0 until entryCount) {
            val hash = hashKey(keys[index], null)
            entryMeta[META_STRIDE * (index)] = hash
            var probe = hash and m
            var dist = 0
            while (true) {
                val p = idx[probe]
                if (p < 0) {
                    idx[probe] = (hash shl 16) or index
                    continue@outer
                }
                // If the existing element probed less than us, take its slot (Robin Hood).
                if (((probe - (p ushr 16)) and m) < dist) break
                dist++
                probe = (probe + 1) and m
            }
            doInsertPhaseTwo(probe, (hash shl 16) or index)
        }
    }

    /** `try_grow`: rebuilds the index table with [newRawCap] slots, no Robin Hood stealing needed. */
    private fun tryGrow(newRawCap: Int): Boolean {
        if (newRawCap > MAX_SIZE) return false
        val old = indices
        val oldMask = mask
        // Find the first ideally placed element: the start of a cluster.
        var firstIdeal = 0
        for (i in old.indices) {
            val p = old[i]
            if (p >= 0 && ((i - (p ushr 16)) and oldMask) == 0) {
                firstIdeal = i
                break
            }
        }
        indices = newIndices(newRawCap)
        mask = newRawCap - 1
        for (i in firstIdeal until old.size) reinsertEntryInOrder(old[i])
        for (i in 0 until firstIdeal) reinsertEntryInOrder(old[i])
        resizeEntries(capacity())
        modCount++
        return true
    }

    private fun reinsertEntryInOrder(pos: Int) {
        if (pos < 0) return
        val idx = indices
        val m = mask
        var probe = (pos ushr 16) and m
        while (idx[probe] >= 0) probe = (probe + 1) and m
        idx[probe] = pos
    }

    // ===== nested types =====

    /** Iterator over (name, value) pairs; see [HeaderMap.iter]. */
    open class Iter<T> internal constructor(internal val map: HeaderMap<T>) :
        Iterator<Pair<HeaderName, T>> {
        private val expected = map.modCount
        private var entry = 0
        private var cursor = if (map.entryCount > 0) CURSOR_HEAD else CURSOR_NONE
        /** Entry and extra-value index (-1 for the first value) of the item last returned. */
        internal var lastEntry = -1
        internal var lastExtra = -1

        override fun hasNext(): Boolean {
            if (map.modCount != expected) throw ConcurrentModificationException()
            return cursor != CURSOR_NONE || entry + 1 < map.entryCount
        }

        /** Moves to the next value and records it in [lastEntry] / [lastExtra]. */
        internal fun advance() {
            if (map.modCount != expected) throw ConcurrentModificationException()
            if (cursor == CURSOR_NONE) {
                if (entry + 1 >= map.entryCount) throw NoSuchElementException()
                entry++
                cursor = CURSOR_HEAD
            }
            lastEntry = entry
            if (cursor == CURSOR_HEAD) {
                lastExtra = -1
                val next = map.entryMeta[META_STRIDE * (entry) + 1]
                cursor = if (next >= 0) next else CURSOR_NONE
            } else {
                lastExtra = cursor
                val next = map.extraNext[cursor]
                cursor = if (next >= 0) next else CURSOR_NONE
            }
        }

        internal fun lastValue(): T =
            (if (lastExtra < 0) map.vals[lastEntry] else map.extraVals[lastExtra]) as T

        internal fun setLast(value: T) {
            if (map.modCount != expected) throw ConcurrentModificationException()
            check(lastEntry >= 0) { "next() has not been called" }
            if (lastExtra < 0) map.vals[lastEntry] = value else map.extraVals[lastExtra] = value
        }

        override fun next(): Pair<HeaderName, T> {
            advance()
            return Pair(map.keys[lastEntry]!!, lastValue())
        }
    }

    /** Iterator over (name, value) pairs whose values can be replaced; see [HeaderMap.iterMut]. */
    class IterMut<T> internal constructor(map: HeaderMap<T>) : Iter<T>(map) {
        /** Replaces the value last returned by [next] (the reference yields `&mut T`). */
        fun set(value: T) = setLast(value)
    }

    /** Iterator over values; see [HeaderMap.values]. */
    open class Values<T> internal constructor(map: HeaderMap<T>) : Iterator<T> {
        internal val inner = Iter(map)
        override fun hasNext(): Boolean = inner.hasNext()
        override fun next(): T {
            inner.advance()
            return inner.lastValue()
        }
    }

    /** Iterator over values that can be replaced; see [HeaderMap.valuesMut]. */
    class ValuesMut<T> internal constructor(map: HeaderMap<T>) : Values<T>(map) {
        /** Replaces the value last returned by [next]. */
        fun set(value: T) = inner.setLast(value)
    }

    /** Iterator over names, each once; see [HeaderMap.keys]. */
    class Keys internal constructor(private val map: HeaderMap<*>) : Iterator<HeaderName> {
        private val expected = map.modCount
        private var i = 0

        override fun hasNext(): Boolean {
            if (map.modCount != expected) throw ConcurrentModificationException()
            return i < map.entryCount
        }

        override fun next(): HeaderName {
            if (!hasNext()) throw NoSuchElementException()
            return map.keys[i++]!!
        }
    }

    /**
     * The contents removed by [HeaderMap.drain] (also [HeaderMap.intoIter]): each name with its first value, then
     * its other values with a null name. Owns the removed storage, so it stays valid whatever happens to the map.
     */
    class Drain<T> internal constructor(
        private val keys: Array<HeaderName?>,
        private val vals: Array<Any?>,
        private val entryMeta: IntArray,
        private val extraVals: Array<Any?>,
        private val extraNext: IntArray,
        private val len: Int,
        private var extraRemaining: Int,
    ) : Iterator<Pair<HeaderName?, T>> {
        private var idx = 0
        private var nextExtra = -1

        /** Lower bound of the remaining items: the remaining names (`size_hint().0`). */
        fun sizeHintLower(): Int = len - idx

        /** Upper bound of the remaining items: remaining names plus remaining extra values (`size_hint().1`). */
        fun sizeHintUpper(): Int = len - idx + extraRemaining

        override fun hasNext(): Boolean = nextExtra >= 0 || idx < len

        override fun next(): Pair<HeaderName?, T> {
            val x = nextExtra
            if (x >= 0) {
                val v = extraVals[x]
                extraVals[x] = null
                nextExtra = extraNext[x]
                extraRemaining--
                return Pair(null, v as T)
            }
            if (idx == len) throw NoSuchElementException()
            val i = idx++
            val key = keys[i]
            val v = vals[i]
            keys[i] = null
            vals[i] = null
            nextExtra = entryMeta[META_STRIDE * (i) + 1]
            return Pair(key, v as T)
        }
    }

    /**
     * A view of all values of one name (`GetAll`), returned by [HeaderMap.getAll]. Empty when the name is absent.
     * Iterating it (or [iter]) visits the values in insertion order.
     */
    class GetAll<T> internal constructor(private val map: HeaderMap<T>, private val index: Int) : Iterable<T> {
        private val expected = map.modCount

        /** Returns an iterator over the values, in insertion order (`iter`). */
        fun iter(): ValueIter<T> {
            if (map.modCount != expected) throw ConcurrentModificationException()
            return ValueIter(map, index)
        }

        override fun iterator(): ValueIter<T> = iter()

        /** Equal when both views yield equal values in the same order (`PartialEq`). */
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is GetAll<*>) return false
            val a = iter()
            val b = other.iter()
            while (a.hasNext() && b.hasNext()) if (a.next() != b.next()) return false
            return !a.hasNext() && !b.hasNext()
        }

        override fun hashCode(): Int {
            var h = 1
            for (v in iter()) h = 31 * h + v.hashCode()
            return h
        }

        override fun toString(): String = iter().asSequence().joinToString(", ", "[", "]")
    }

    /**
     * Double-ended iterator over the values of one name (`ValueIter`); [next] walks from the first value, [nextBack]
     * from the last, and they stop when they meet.
     */
    open class ValueIter<T> internal constructor(private val map: HeaderMap<T>, private val index: Int) :
        Iterator<T> {
        private val expected = map.modCount
        private var front: Int
        private var back: Int
        private var lastSlot = CURSOR_NONE

        init {
            if (index >= 0) {
                front = CURSOR_HEAD
                val tail = map.entryMeta[META_STRIDE * (index) + 1]
                back = if (tail >= 0) map.entryMeta[META_STRIDE * (index) + 2] else CURSOR_HEAD
            } else {
                front = CURSOR_NONE
                back = CURSOR_NONE
            }
        }

        override fun hasNext(): Boolean {
            if (map.modCount != expected) throw ConcurrentModificationException()
            return front != CURSOR_NONE
        }

        override fun next(): T {
            if (map.modCount != expected) throw ConcurrentModificationException()
            val f = front
            when {
                f == CURSOR_NONE -> throw NoSuchElementException()
                f == CURSOR_HEAD -> {
                    if (back == CURSOR_HEAD) {
                        front = CURSOR_NONE
                        back = CURSOR_NONE
                    } else {
                        front = map.entryMeta[META_STRIDE * (index) + 1]
                    }
                }
                else -> {
                    if (front == back) {
                        front = CURSOR_NONE
                        back = CURSOR_NONE
                    } else {
                        val n = map.extraNext[f]
                        front = if (n >= 0) n else CURSOR_NONE
                    }
                }
            }
            lastSlot = f
            return slotValue(f)
        }

        /**
         * Returns the last value not yet returned (`next_back`).
         * @throws NoSuchElementException if every value has been returned.
         */
        fun nextBack(): T {
            if (map.modCount != expected) throw ConcurrentModificationException()
            val b = back
            when {
                b == CURSOR_NONE -> throw NoSuchElementException()
                b == CURSOR_HEAD -> {
                    front = CURSOR_NONE
                    back = CURSOR_NONE
                }
                else -> {
                    if (front == back) {
                        front = CURSOR_NONE
                        back = CURSOR_NONE
                    } else {
                        val p = map.extraPrev[b]
                        back = if (p >= 0) p else CURSOR_HEAD
                    }
                }
            }
            lastSlot = b
            return slotValue(b)
        }

        private fun slotValue(slot: Int): T =
            (if (slot == CURSOR_HEAD) map.vals[index] else map.extraVals[slot]) as T

        internal fun setLast(value: T) {
            if (map.modCount != expected) throw ConcurrentModificationException()
            val s = lastSlot
            check(s != CURSOR_NONE) { "next() has not been called" }
            if (s == CURSOR_HEAD) map.vals[index] = value else map.extraVals[s] = value
        }
    }

    /** [ValueIter] whose values can be replaced (`ValueIterMut`); see [Entry.Occupied.iterMut]. */
    class ValueIterMut<T> internal constructor(map: HeaderMap<T>, index: Int) : ValueIter<T>(map, index) {
        /** Replaces the value last returned by [next] or [nextBack]. */
        fun set(value: T) = setLast(value)
    }

    /** The values removed from one name (`ValueDrain`), first value first. */
    class ValueDrain<T> internal constructor(first: T, private val extras: Array<Any?>?) : Iterator<T> {
        private var first: Any? = first
        private var hasFirst = true
        private var i = 0

        override fun hasNext(): Boolean = hasFirst || (extras != null && i < extras.size)

        override fun next(): T {
            if (hasFirst) {
                hasFirst = false
                val v = first
                first = null
                return v as T
            }
            if (extras == null || i >= extras.size) throw NoSuchElementException()
            val v = extras[i]
            extras[i++] = null
            return v as T
        }
    }

    companion object {
        /** Maximum number of entries (names) in a map (`MAX_SIZE`, 2^15). */
        const val MAX_SIZE: Int = 1 shl 15

        /** Creates an empty `HeaderMap<HeaderValue>` without allocating (`HeaderMap::new`). */
        fun new(): HeaderMap<HeaderValue> = HeaderMap()

        /**
         * Creates an empty map able to hold about [capacity] names without reallocating (`with_capacity`); more
         * capacity than requested may be allocated.
         * @throws MaxSizeReached if [capacity] exceeds the maximum (24576).
         */
        fun <T> withCapacity(capacity: Int): HeaderMap<T> = tryWithCapacity<T>(capacity).getOrThrow()

        /** Like [withCapacity], but returns [MaxSizeReached] as a failed [Result] (`try_with_capacity`). */
        fun <T> tryWithCapacity(capacity: Int): Result<HeaderMap<T>> {
            require(capacity >= 0) { "capacity must not be negative: $capacity" }
            val m = HeaderMap<T>()
            if (capacity == 0) return Result.success(m)
            val rawCap = capacity.toLong() + capacity / 3
            val pow2 = nextPowerOfTwo(rawCap)
            if (pow2 > MAX_SIZE) return Result.failure(MaxSizeReached())
            val raw = pow2.toInt()
            m.mask = raw - 1
            m.indices = newIndices(raw)
            m.resizeEntries(usableCapacity(raw))
            return Result.success(m)
        }

        /** Builds a map from (name, value) pairs, appending values of repeated names (`FromIterator`). */
        fun <T> fromIter(pairs: Iterable<Pair<HeaderName, T>>): HeaderMap<T> = HeaderMap<T>().also { it.extend(pairs) }

        /**
         * Converts a string map to a header map (`TryFrom<&HashMap<K, V>>`).
         * @throws InvalidHeaderName if a key is not a valid header name.
         * @throws InvalidHeaderValue if a value is not a valid header value.
         */
        fun fromMap(map: Map<String, String>): HeaderMap<HeaderValue> {
            val m = HeaderMap<HeaderValue>()
            m.reserveForExtend(map.size)
            for ((k, v) in map) m.append(HeaderName.fromStr(k), HeaderValue.fromStr(v))
            return m
        }

        /** Like [fromMap], but returns null if a key or a value is invalid. */
        fun tryFromMap(map: Map<String, String>): HeaderMap<HeaderValue>? {
            val m = HeaderMap<HeaderValue>()
            m.reserveForExtend(map.size)
            for ((k, v) in map) {
                val name = HeaderName.tryFromStr(k) ?: return null
                val value = HeaderValue.tryFromStr(v) ?: return null
                m.append(name, value)
            }
            return m
        }
    }
}

/**
 * A view into a single name of a [HeaderMap], which is either [Vacant] or [Occupied] (`http::header::Entry`).
 *
 * An entry is only valid until the map is structurally modified by something other than the entry itself; using it
 * afterwards throws [ConcurrentModificationException]. Operations that consume the entry in the reference
 * ([Vacant.insert], [Occupied.remove], ...) make it unusable ([IllegalStateException]).
 */
sealed class Entry<T> {
    /** Returns the entry's name (`key`). */
    abstract fun key(): HeaderName

    /**
     * Inserts [default] if the entry is vacant; returns the first value of the entry (`or_insert`; the reference
     * returns `&mut T`, see [Occupied.set]).
     * @throws MaxSizeReached if the map is full.
     */
    fun orInsert(default: T): T = orTryInsert(default).getOrThrow()

    /** Like [orInsert], but returns [MaxSizeReached] as a failed [Result] (`or_try_insert`). */
    fun orTryInsert(default: T): Result<T> = when (this) {
        is Occupied -> Result.success(get())
        is Vacant -> tryInsert(default)
    }

    /** Like [orInsert], but [default] is only called if the entry is vacant (`or_insert_with`). */
    inline fun orInsertWith(default: () -> T): T = orTryInsertWith(default).getOrThrow()

    /** Like [orInsertWith], but returns [MaxSizeReached] as a failed [Result] (`or_try_insert_with`). */
    inline fun orTryInsertWith(default: () -> T): Result<T> = when (this) {
        is Occupied -> Result.success(get())
        is Vacant -> tryInsert(default())
    }

    /** A vacant entry (`VacantEntry`): the name has no value in the map. */
    class Vacant<T> internal constructor(
        private val map: HeaderMap<T>,
        private val key: HeaderName,
        private val hash: Int,
        private val probe: Int,
        private val danger: Boolean,
    ) : Entry<T>() {
        private val expected = map.modCount
        private var consumed = false

        override fun key(): HeaderName = key

        /** Returns the name, consuming nothing (`into_key`). */
        fun intoKey(): HeaderName = key

        /**
         * Inserts [value] for this entry's name and returns it (`insert`; the reference returns `&mut T`).
         * @throws MaxSizeReached if the map is full.
         */
        fun insert(value: T): T = tryInsert(value).getOrThrow()

        /** Like [insert], but returns [MaxSizeReached] as a failed [Result] (`try_insert`). */
        fun tryInsert(value: T): Result<T> = tryInsertEntry(value).map { value }

        /**
         * Inserts [value] and returns the now occupied entry for further changes (`insert_entry`).
         * @throws MaxSizeReached if the map is full.
         */
        fun insertEntry(value: T): Occupied<T> = tryInsertEntry(value).getOrThrow()

        /** Like [insertEntry], but returns [MaxSizeReached] as a failed [Result] (`try_insert_entry`). */
        fun tryInsertEntry(value: T): Result<Occupied<T>> {
            check(!consumed) { "entry already used" }
            if (map.modCount != expected) throw ConcurrentModificationException()
            val index = map.insertPhaseTwo(key, value, hash, probe, danger)
            if (index < 0) return Result.failure(MaxSizeReached())
            consumed = true
            return Result.success(Occupied(map, probe, index))
        }
    }

    /** An occupied entry (`OccupiedEntry`): the name has at least one value. */
    class Occupied<T> internal constructor(
        private val map: HeaderMap<T>,
        private val probe: Int,
        private val index: Int,
    ) : Entry<T>() {
        private var expected = map.modCount
        private var consumed = false

        private fun checkValid() {
            check(!consumed) { "entry already removed" }
            if (map.modCount != expected) throw ConcurrentModificationException()
        }

        override fun key(): HeaderName {
            checkValid()
            return map.keys[index]!!
        }

        /** Returns the first value (`get`). */
        fun get(): T {
            checkValid()
            return map.vals[index] as T
        }

        /**
         * Replaces the first value only, keeping the others (the reference's `get_mut` / `into_mut`, which hand out
         * `&mut T`). Unlike [insert], this does not remove the other values.
         */
        fun set(value: T) {
            checkValid()
            map.vals[index] = value
        }

        /** Replaces all values with [value]; returns the previous first value (`insert`). */
        fun insert(value: T): T {
            checkValid()
            val old = map.insertOccupied(index, value)
            expected = map.modCount
            return old
        }

        /** Replaces all values with [value]; returns every previous value in order (`insert_mult`). */
        fun insertMult(value: T): HeaderMap.ValueDrain<T> {
            checkValid()
            val drain = map.insertOccupiedMult(index, value)
            expected = map.modCount
            return drain
        }

        /** Appends [value] after the existing values (`append`). */
        fun append(value: T) {
            checkValid()
            map.appendValue(index, value)
            expected = map.modCount
        }

        /** Removes the name and all its values; returns the first value (`remove`). */
        fun remove(): T = removeEntry().second

        /** Removes the name and all its values; returns the name and the first value (`remove_entry`). */
        fun removeEntry(): Pair<HeaderName, T> {
            checkValid()
            val head = map.entryMeta[META_STRIDE * (index) + 1]
            if (head >= 0) map.removeAllExtraValues(head)
            val key = map.keys[index]!!
            val value = map.vals[index] as T
            map.removeFound(probe, index)
            consumed = true
            return Pair(key, value)
        }

        /** Removes the name and all its values; returns the name and every value in order (`remove_entry_mult`). */
        fun removeEntryMult(): Pair<HeaderName, HeaderMap.ValueDrain<T>> {
            checkValid()
            val extras = map.drainExtraValues(index)
            val key = map.keys[index]!!
            val value = map.vals[index] as T
            map.removeFound(probe, index)
            consumed = true
            return Pair(key, HeaderMap.ValueDrain(value, extras))
        }

        /** Iterates over the values in insertion order (`iter`). */
        fun iter(): HeaderMap.ValueIter<T> {
            checkValid()
            return HeaderMap.ValueIter(map, index)
        }

        /** Iterates over the values; the iterator's `set` replaces the value last returned (`iter_mut`). */
        fun iterMut(): HeaderMap.ValueIterMut<T> {
            checkValid()
            return HeaderMap.ValueIterMut(map, index)
        }

        /** Same as [iterMut] (`IntoIterator for OccupiedEntry`). */
        operator fun iterator(): HeaderMap.ValueIterMut<T> = iterMut()
    }
}

private fun nextPowerOfTwo(n: Long): Long {
    var p = 1L
    while (p < n) p = p shl 1
    return p
}

private fun isValidName(s: String): Boolean {
    val len = s.length
    if (len == 0 || len > MAX_HEADER_NAME_LEN) return false
    for (i in 0 until len) if (HEADER_CHARS[charToByte(s[i].code)].toInt() == 0) return false
    return true
}

private fun checkName(s: String) {
    if (!isValidName(s)) throw InvalidHeaderName()
}
