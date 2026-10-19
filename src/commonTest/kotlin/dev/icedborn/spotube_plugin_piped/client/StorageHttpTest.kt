package dev.icedborn.spotube_plugin_piped.client

import dev.icedborn.spotube_plugin_piped.core.PipedAccount
import dev.icedborn.spotube_plugin_piped.fakes.FAKE_INSTANCE
import dev.icedborn.spotube_plugin_piped.fakes.FakePiped
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Piped storage routes: stat, get, put with the etag check, and an instance without storage. */
class StorageHttpTest {
    private val account = PipedAccount(FAKE_INSTANCE, "ice", "token-ice")

    @Test
    fun `a put creates a missing file and a get reads it back`() = runTest {
        val piped = FakePiped()
        val http = StorageHttp(piped)
        assertEquals(StorageStat.Missing, http.stat(account, "spdevices"))
        assertEquals(StoragePut.OK, http.put(account, "spdevices", """{"devices":[]}""", etag = null))
        val stat = http.stat(account, "spdevices")
        assertTrue(stat is StorageStat.Exists)
        assertEquals("""{"devices":[]}""", http.get(account, "spdevices"))
    }

    @Test
    fun `a put with a stale etag is a conflict`() = runTest {
        val http = StorageHttp(FakePiped())
        http.put(account, "spa", "1", etag = null)
        val first = http.stat(account, "spa") as StorageStat.Exists
        assertEquals(StoragePut.OK, http.put(account, "spa", "2", first.etag))
        assertEquals(StoragePut.CONFLICT, http.put(account, "spa", "3", first.etag))
        assertEquals(StoragePut.CONFLICT, http.put(account, "spa", "3", etag = null))
        assertEquals("2", http.get(account, "spa"))
    }

    @Test
    fun `an instance without storage reports unsupported`() = runTest {
        val piped = FakePiped().apply { storageEnabled = false }
        val http = StorageHttp(piped)
        assertEquals(false, http.s3Enabled(FAKE_INSTANCE))
        assertEquals(StorageStat.Unsupported, http.stat(account, "spdevices"))
        assertEquals(StoragePut.UNSUPPORTED, http.put(account, "spdevices", "{}", null))
    }

    @Test
    fun `a bad session, a failed fetch and a missing get prove nothing`() = runTest {
        val piped = FakePiped()
        val http = StorageHttp(piped)
        assertNull(http.stat(account.copy(token = "bad"), "spdevices"))
        assertNull(http.get(account, "spnothing"))
        piped.override("/storage/stat", status = 502, body = "")
        assertNull(http.stat(account, "spdevices"))
        assertEquals(true, http.s3Enabled(FAKE_INSTANCE))
    }

    @Test
    fun `an oversized body fails`() = runTest {
        val http = StorageHttp(FakePiped())
        assertEquals(StoragePut.FAILED, http.put(account, "spbig", "x".repeat(600 * 1024), null))
    }

    @Test
    fun `names are lowercase letters and digits up to 32 characters`() {
        assertTrue(isStorageName("spab12cd34lib"))
        assertFalse(isStorageName("sp-ab"))
        assertFalse(isStorageName("Spab"))
        assertFalse(isStorageName("a".repeat(33)))
    }
}
