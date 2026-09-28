package neton.http

import kotlin.reflect.KClass

/**
 * A type-keyed map of protocol extensions, mirroring `http::Extensions`.
 *
 * `Request` and `Response` use it to carry extra data derived from the underlying protocol. At most one
 * value is stored per key type. The backing map is allocated only on the first insertion, so an unused
 * `Extensions` costs a single null field.
 *
 * Differences forced by Kotlin, behaviour otherwise identical:
 * - The key is a [KClass] where the reference uses `TypeId`. Generic type arguments are erased, so
 *   `List<Int>` and `List<String>` share one key.
 * - Values are held by reference. [getMut] is therefore the same as [get]; mutate through the returned
 *   object. [copy] (the reference's `Clone`) copies the map but shares the values.
 * - `get_or_insert_default` has no counterpart (Kotlin has no `Default` trait); use [getOrInsertWith].
 */
class Extensions {
    private var map: HashMap<KClass<*>, Any>? = null

    /**
     * Stores [value] under [type], returning the value previously stored under that type (now replaced),
     * or null.
     */
    fun <T : Any> insert(type: KClass<T>, value: T): T? {
        val m = map ?: HashMap<KClass<*>, Any>().also { map = it }
        @Suppress("UNCHECKED_CAST")
        return m.put(type, value) as T?
    }

    /** Stores [value] under its static type [T]; see the [KClass] overload. */
    inline fun <reified T : Any> insert(value: T): T? = insert(T::class, value)

    /** Returns the value stored under [type], or null. */
    fun <T : Any> get(type: KClass<T>): T? {
        @Suppress("UNCHECKED_CAST")
        return map?.get(type) as T?
    }

    /** Returns the value stored under [T], or null. */
    inline fun <reified T : Any> get(): T? = get(T::class)

    /** Same as [get]: values are held by reference, so the result can be mutated in place. */
    fun <T : Any> getMut(type: KClass<T>): T? = get(type)

    /** Same as [get]: values are held by reference, so the result can be mutated in place. */
    inline fun <reified T : Any> getMut(): T? = get(T::class)

    /** Returns the value stored under [type], storing [value] first if there is none. */
    fun <T : Any> getOrInsert(type: KClass<T>, value: T): T = get(type) ?: value.also { insert(type, it) }

    /** Returns the value stored under [T], storing [value] first if there is none. */
    inline fun <reified T : Any> getOrInsert(value: T): T = getOrInsert(T::class, value)

    /** Returns the value stored under [type], storing the result of [f] first if there is none. */
    inline fun <T : Any> getOrInsertWith(type: KClass<T>, f: () -> T): T = get(type) ?: f().also { insert(type, it) }

    /** Returns the value stored under [T], storing the result of [f] first if there is none. */
    inline fun <reified T : Any> getOrInsertWith(f: () -> T): T = getOrInsertWith(T::class, f)

    /** Removes and returns the value stored under [type], or null. */
    fun <T : Any> remove(type: KClass<T>): T? {
        @Suppress("UNCHECKED_CAST")
        return map?.remove(type) as T?
    }

    /** Removes and returns the value stored under [T], or null. */
    inline fun <reified T : Any> remove(): T? = remove(T::class)

    /** Removes every value. Keeps the allocated map for reuse, as the reference does. */
    fun clear() {
        map?.clear()
    }

    /** Whether no value is stored. */
    fun isEmpty(): Boolean = map?.isEmpty() ?: true

    /** The number of stored values. */
    fun len(): Int = map?.size ?: 0

    /**
     * Moves every value of [other] into this map; where both hold a value of the same type, the one from
     * [other] wins. The reference consumes `other`; here [other] is left empty.
     */
    fun extend(other: Extensions) {
        val theirs = other.map ?: return
        other.map = null
        val mine = map
        if (mine == null) map = theirs else mine.putAll(theirs)
    }

    /** A new `Extensions` holding the same values (the reference's `Clone`; values are shared, not cloned). */
    fun copy(): Extensions {
        val result = Extensions()
        map?.let { result.map = HashMap(it) }
        return result
    }

    /** The set of stored type names, as the reference's `Debug` (for example `{kotlin.Int}`). */
    override fun toString(): String {
        val m = map ?: return "{}"
        return m.keys.joinToString(", ", "{", "}") { it.qualifiedName ?: it.simpleName ?: "<anonymous>" }
    }
}
