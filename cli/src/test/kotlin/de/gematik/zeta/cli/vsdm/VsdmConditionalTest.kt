package de.gematik.zeta.cli.vsdm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The transparency rule: what a caller asked for decides what it gets back. These cases are the
 * specification — a client that asked a conditional question always gets its own question asked
 * upstream and its own answer returned.
 */
class VsdmConditionalTest {

    private val stored = "\"a1b2c3\""

    @Test
    fun `caching off passes the client's condition through untouched`() {
        val absent = cacheDecision(null, cachedEtag = stored, cacheControl = null, cacheEnabled = false)
        assertEquals(CacheIntent.OFF, absent.intent)
        assertNull(absent.ifNoneMatch, "without a cache we must not invent a header — the 428 stays")

        val supplied = cacheDecision("\"x\"", cachedEtag = stored, cacheControl = null, cacheEnabled = false)
        assertEquals(CacheIntent.OFF, supplied.intent)
        assertEquals("\"x\"", supplied.ifNoneMatch)
    }

    @Test
    fun `no client condition and a stored version asks for that version`() {
        val d = cacheDecision(null, cachedEtag = stored, cacheControl = null, cacheEnabled = true)
        assertEquals(CacheIntent.SERVE_FROM_CACHE, d.intent)
        assertEquals(stored, d.ifNoneMatch)
    }

    @Test
    fun `no client condition and nothing stored sends the sentinel`() {
        val d = cacheDecision(null, cachedEtag = null, cacheControl = null, cacheEnabled = true)
        assertEquals(CacheIntent.FILL, d.intent)
        assertEquals(NO_KNOWN_VERSION_ETAG, d.ifNoneMatch)
    }

    @Test
    fun `a client naming the stored version revalidates on its own behalf`() {
        val d = cacheDecision(stored, cachedEtag = stored, cacheControl = null, cacheEnabled = true)
        assertEquals(CacheIntent.CLIENT_REVALIDATE, d.intent)
        assertEquals(stored, d.ifNoneMatch)
    }

    @Test
    fun `a client naming another version is passed through, sentinel included`() {
        val other = cacheDecision("\"zzz\"", cachedEtag = stored, cacheControl = null, cacheEnabled = true)
        assertEquals(CacheIntent.BYPASS, other.intent)
        assertEquals("\"zzz\"", other.ifNoneMatch)

        val sentinel = cacheDecision(NO_KNOWN_VERSION_ETAG, cachedEtag = stored, cacheControl = null, cacheEnabled = true)
        assertEquals(CacheIntent.BYPASS, sentinel.intent)
        assertEquals(NO_KNOWN_VERSION_ETAG, sentinel.ifNoneMatch, "the all-zero etag stays the escape hatch")
    }

    @Test
    fun `no-cache and no-store bypass the stored version`() {
        listOf("no-cache", "No-Store", "max-age=0, no-cache").forEach { directive ->
            val d = cacheDecision(null, cachedEtag = stored, cacheControl = directive, cacheEnabled = true)
            assertEquals(CacheIntent.BYPASS, d.intent, directive)
            assertEquals(NO_KNOWN_VERSION_ETAG, d.ifNoneMatch, directive)
        }
    }

    @Test
    fun `no-store also forbids writing the answer back, no-cache does not`() {
        listOf("no-store", "No-Store", "max-age=0, no-store").forEach { directive ->
            val d = cacheDecision(null, cachedEtag = stored, cacheControl = directive, cacheEnabled = true)
            assertFalse(d.store, directive)
        }
        assertTrue(cacheDecision(null, cachedEtag = stored, cacheControl = "no-cache", cacheEnabled = true).store)
        assertTrue(cacheDecision(null, cachedEtag = null, cacheControl = null, cacheEnabled = true).store)
    }

    @Test
    fun `outcome names what happened, and only a confirmed stored version is served`() {
        assertEquals("hit", cacheOutcome(CacheIntent.SERVE_FROM_CACHE, 304))
        assertEquals("miss", cacheOutcome(CacheIntent.SERVE_FROM_CACHE, 200))
        assertEquals("miss", cacheOutcome(CacheIntent.FILL, 200))
        assertEquals("revalidated", cacheOutcome(CacheIntent.CLIENT_REVALIDATE, 304))
        assertEquals("bypass", cacheOutcome(CacheIntent.BYPASS, 304))
        assertEquals("off", cacheOutcome(CacheIntent.OFF, 200))

        assertTrue(servesFromCache(CacheIntent.SERVE_FROM_CACHE, 304))
        assertFalse(servesFromCache(CacheIntent.SERVE_FROM_CACHE, 200))
        assertFalse(servesFromCache(CacheIntent.CLIENT_REVALIDATE, 304), "the client asked, so the 304 is its answer")
        assertFalse(servesFromCache(CacheIntent.BYPASS, 304))
    }
}
