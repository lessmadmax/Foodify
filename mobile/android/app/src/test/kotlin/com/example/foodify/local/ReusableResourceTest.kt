package com.example.foodify.local

import org.junit.Assert.*
import org.junit.Test

class ReusableResourceTest {
    @Test fun repeatedInferenceKeepsEngineUntilExplicitRelease() {
        var created = 0
        var closed = 0
        val cache = ReusableResource<Any> { closed++ }
        val first = cache.acquire { created++; Any() }
        assertSame(first, cache.acquire { created++; Any() })
        assertEquals(1, created)
        assertEquals(0, closed)
        cache.release()
        cache.release()
        assertEquals(1, closed)
        assertFalse(cache.isLoaded)
        assertNotSame(first, cache.acquire { created++; Any() })
        assertEquals(2, created)
    }
    @Test fun initializationFailureCanRetry() {
        val cache = ReusableResource<Any> { }
        try { cache.acquire { error("initialization failed") }; fail() }
        catch (_: IllegalStateException) { }
        assertFalse(cache.isLoaded)
        assertNotNull(cache.acquire { Any() })
    }
    @Test fun closeFailureStillRemovesOldEngine() {
        val cache = ReusableResource<Any> { error("close failed") }
        val first = cache.acquire { Any() }
        try { cache.release(); fail() } catch (_: IllegalStateException) { }
        assertFalse(cache.isLoaded)
        assertNotSame(first, cache.acquire { Any() })
    }
}
