package com.example.foodify.local

/** Access only from the owning serial worker; failed factories are never cached. */
internal class ReusableResource<T : Any>(private val close: (T) -> Unit) {
    private var value: T? = null
    val isLoaded: Boolean get() = value != null
    fun acquire(factory: () -> T): T = value ?: factory().also { value = it }
    fun release() {
        val previous = value
        value = null
        if (previous != null) close(previous)
    }
}
