package dev.astra.ime.oss

/**
 * Fixed-size, read-only views over primitive arrays for loaded resources. Upstream LOUDS and TokenArray columns are
 * typed `MutableList<Char/Short/Int>`, and `toMutableList()` retains one boxed object per entry (millions for a full
 * dictionary); these retain only the array and box transiently in [get].
 *
 * A view owns its array: pass a freshly deserialized array and keep no other reference, so the contents can never
 * change. Every mutator throws [UnsupportedOperationException] (bulk mutators that change nothing, such as clearing an
 * empty list, are no-ops as for [java.util.Arrays.asList]). Out-of-range indices throw [IndexOutOfBoundsException], not
 * the array subclass, so failure names reported by [OssConverter] stay as with `ArrayList`. Equality and hash codes
 * follow the [List] contract, so a view equals a boxed list with the same elements.
 */
internal sealed class LoadedList<E> : AbstractMutableList<E>(), RandomAccess {
    final override fun set(index: Int, element: E): E = throw UnsupportedOperationException(READ_ONLY)
    final override fun add(index: Int, element: E): Unit = throw UnsupportedOperationException(READ_ONLY)
    final override fun removeAt(index: Int): E = throw UnsupportedOperationException(READ_ONLY)

    protected fun checkIndex(index: Int) {
        if (index < 0 || index >= size) throw IndexOutOfBoundsException("Index $index out of bounds for length $size")
    }

    private companion object {
        const val READ_ONLY = "Loaded resource lists are read-only"
    }
}

internal class LoadedCharList(private val values: CharArray) : LoadedList<Char>() {
    override val size: Int get() = values.size
    override fun get(index: Int): Char {
        checkIndex(index)
        return values[index]
    }
}

internal class LoadedShortList(private val values: ShortArray) : LoadedList<Short>() {
    override val size: Int get() = values.size
    override fun get(index: Int): Short {
        checkIndex(index)
        return values[index]
    }
}

internal class LoadedIntList(private val values: IntArray) : LoadedList<Int>() {
    override val size: Int get() = values.size
    override fun get(index: Int): Int {
        checkIndex(index)
        return values[index]
    }
}
