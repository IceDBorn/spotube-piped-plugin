package dev.icedborn.spotube_plugin_piped_metadata

import dev.icedborn.spotube_plugin_piped_metadata.fakes.FakeHttp
import dev.icedborn.spotube_plugin_piped_metadata.fakes.FakeStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import net.swiftzer.semver.SemVer

private const val ASSET =
    """{"name":"spotube-plugin-piped.smplug","browser_download_url":"https://example/plugin.smplug"}"""

/** Update checks read GitHub releases; a failure must be a null, never an exception. */
class UpdateCheckerTest {

    private val current = SemVer.parse("0.0.1")

    private fun release(tag: String, notes: String = "notes", assets: String = ASSET) = """
        {"tag_name":"$tag","body":"$notes","assets":[$assets]}
    """.trimIndent()

    /** A nightly release has the fixed tag "nightly", so the version sits in the title (`name`). */
    private fun nightlyRelease(version: String, notes: String = "nightly notes") = """
        {"tag_name":"nightly","name":"$version","body":"$notes","assets":[$ASSET]}
    """.trimIndent()

    @Test
    fun `a newer release is offered with its asset url and notes`() = runTest {
        val http = FakeHttp().apply { onPath("/releases/latest", body = release("0.1.0", "what changed")) }
        val info = UpdateChecker(http).check(current)
        assertEquals("0.1.0", info?.latestVersion)
        assertEquals("https://example/plugin.smplug", info?.directDownloadUrl)
        assertEquals("what changed", info?.releaseNotes)
    }

    @Test
    fun `the same or an older release offers nothing`() = runTest {
        val same = FakeHttp().apply { onPath("/releases/latest", body = release("0.0.1")) }
        assertNull(UpdateChecker(same).check(current))
        val older = FakeHttp().apply { onPath("/releases/latest", body = release("0.0.0")) }
        assertNull(UpdateChecker(older).check(current))
    }

    @Test
    fun `a v prefixed tag is parsed`() = runTest {
        val http = FakeHttp().apply { onPath("/releases/latest", body = release("v0.2.0")) }
        assertEquals("0.2.0", UpdateChecker(http).check(current)?.latestVersion)
    }

    @Test
    fun `a release without a plugin asset offers nothing`() = runTest {
        val http = FakeHttp().apply {
            onPath("/releases/latest", body = release("0.1.0", assets = """{"name":"source.zip","browser_download_url":"https://example/s.zip"}"""))
        }
        assertNull(UpdateChecker(http).check(current))
    }

    @Test
    fun `a non 2xx response offers nothing instead of throwing`() = runTest {
        val http = FakeHttp().apply { onPath("/releases/latest", status = 403, body = """{"message":"rate limit"}""") }
        assertNull(UpdateChecker(http).check(current))
    }

    @Test
    fun `a blank body or an unparseable body offers nothing`() = runTest {
        assertNull(UpdateChecker(FakeHttp().apply { onPath("/releases/latest", body = "  ") }).check(current))
        assertNull(UpdateChecker(FakeHttp().apply { onPath("/releases/latest", body = "not json") }).check(current))
    }

    @Test
    fun `a tag that is not a version offers nothing`() = runTest {
        val http = FakeHttp().apply { onPath("/releases/latest", body = release("nightly")) }
        assertNull(UpdateChecker(http).check(current))
    }

    @Test
    fun `a second call inside the cache window makes no request`() = runTest {
        val http = FakeHttp().apply { onPath("/releases/latest", body = release("0.1.0")) }
        val checker = UpdateChecker(http)
        checker.check(current)
        checker.check(current)
        assertEquals(1, http.requests.size)
    }

    @Test
    fun `a failed check is cached too`() = runTest {
        val http = FakeHttp().apply { onPath("/releases/latest", status = 500, body = "boom") }
        val checker = UpdateChecker(http)
        assertNull(checker.check(current))
        assertNull(checker.check(current))
        assertEquals(1, http.requests.size)
    }

    // ── update channels ────────────────────────────────────────────────────

    private val nightly3 = SemVer.parse("0.0.2-nightly.3")

    private suspend fun setting(channel: UpdateChannel): UpdateChannelSetting =
        UpdateChannelSetting(EntityStore(FakeStorage())).apply { set(channel) }

    private fun bothReleases(
        http: FakeHttp,
        nightly: String? = nightlyRelease("0.0.2-nightly.5"),
        stable: String? = release("0.0.1"),
    ): FakeHttp = http.apply {
        if (nightly != null) onPath("/releases/tags/nightly", body = nightly)
        if (stable != null) onPath("/releases/latest", body = stable)
    }

    @Test
    fun `the stable channel never asks for the nightly release`() = runTest {
        val http = bothReleases(FakeHttp(), stable = release("0.0.2"))
        val info = UpdateChecker(http, setting(UpdateChannel.STABLE)).check(current)
        assertEquals("0.0.2", info?.latestVersion)
        assertEquals(0, http.countMatching("/releases/tags/nightly"))
    }

    @Test
    fun `auto follows the installed build`() = runTest {
        val auto = setting(UpdateChannel.AUTO)
        assertEquals(UpdateChannel.STABLE, auto.resolve(current))
        assertEquals(UpdateChannel.NIGHTLY, auto.resolve(nightly3))
    }

    @Test
    fun `auto on a stable build reads the stable release`() = runTest {
        val http = bothReleases(FakeHttp(), stable = release("0.1.0"))
        assertEquals("0.1.0", UpdateChecker(http, setting(UpdateChannel.AUTO)).check(current)?.latestVersion)
    }

    @Test
    fun `auto on a nightly build reads the nightly release`() = runTest {
        // Stable is older than the install, so only the nightly endpoint can produce an offer.
        val http = bothReleases(FakeHttp(), stable = release("0.0.1"))
        val info = UpdateChecker(http, setting(UpdateChannel.AUTO)).check(nightly3)
        assertEquals("0.0.2-nightly.5", info?.latestVersion)
        assertEquals(1, http.countMatching("/releases/tags/nightly"))
    }

    @Test
    fun `nightly offers a newer nightly build`() = runTest {
        val http = bothReleases(FakeHttp())
        val info = UpdateChecker(http, setting(UpdateChannel.NIGHTLY)).check(nightly3)
        assertEquals("0.0.2-nightly.5", info?.latestVersion)
    }

    @Test
    fun `nightly offers a stable release newer than the last nightly`() = runTest {
        val http = bothReleases(FakeHttp(), stable = release("0.0.3"))
        val info = UpdateChecker(http, setting(UpdateChannel.NIGHTLY)).check(SemVer.parse("0.0.2-nightly.5"))
        assertEquals("0.0.3", info?.latestVersion)
    }

    @Test
    fun `nightly offers nothing when both are older than the install`() = runTest {
        val http = bothReleases(FakeHttp(), nightly = nightlyRelease("0.0.2-nightly.3"), stable = release("0.0.1"))
        assertNull(UpdateChecker(http, setting(UpdateChannel.NIGHTLY)).check(nightly3))
    }

    @Test
    fun `a failed nightly request still offers a newer stable release`() = runTest {
        val http = FakeHttp().apply { onPath("/releases/latest", body = release("0.0.3")) }
        val info = UpdateChecker(http, setting(UpdateChannel.NIGHTLY)).check(nightly3)
        assertEquals("0.0.3", info?.latestVersion)
    }

    @Test
    fun `a failed stable request still offers a newer nightly build`() = runTest {
        val http = FakeHttp().apply { onPath("/releases/tags/nightly", body = nightlyRelease("0.0.2-nightly.9")) }
        val info = UpdateChecker(http, setting(UpdateChannel.NIGHTLY)).check(nightly3)
        assertEquals("0.0.2-nightly.9", info?.latestVersion)
    }

    @Test
    fun `a thrown nightly request still offers a newer stable release`() = runTest {
        val http = FakeHttp().apply {
            onPathThrow("/releases/tags/nightly", IllegalStateException("timeout"))
            onPath("/releases/latest", body = release("0.0.3"))
        }
        val info = UpdateChecker(http, setting(UpdateChannel.NIGHTLY)).check(nightly3)
        assertEquals("0.0.3", info?.latestVersion)
    }

    @Test
    fun `a thrown stable request still offers a newer nightly build`() = runTest {
        val http = FakeHttp().apply {
            onPath("/releases/tags/nightly", body = nightlyRelease("0.0.2-nightly.9"))
            onPathThrow("/releases/latest", IllegalStateException("timeout"))
        }
        val info = UpdateChecker(http, setting(UpdateChannel.NIGHTLY)).check(nightly3)
        assertEquals("0.0.2-nightly.9", info?.latestVersion)
    }

    @Test
    fun `both requests throwing offers nothing instead of throwing`() = runTest {
        val http = FakeHttp().apply {
            onPathThrow("/releases/tags/nightly", IllegalStateException("timeout"))
            onPathThrow("/releases/latest", IllegalStateException("timeout"))
        }
        assertNull(UpdateChecker(http, setting(UpdateChannel.NIGHTLY)).check(nightly3))
    }

    @Test
    fun `a nightly title that is not a version is skipped`() = runTest {
        val http = bothReleases(FakeHttp(), nightly = nightlyRelease("nightly build"), stable = release("0.0.3"))
        val info = UpdateChecker(http, setting(UpdateChannel.NIGHTLY)).check(nightly3)
        assertEquals("0.0.3", info?.latestVersion)
    }

    @Test
    fun `both releases failing offers nothing instead of throwing`() = runTest {
        val http = FakeHttp().apply {
            onPath("/releases/tags/nightly", status = 500, body = "boom")
            onPath("/releases/latest", status = 503, body = "boom")
        }
        assertNull(UpdateChecker(http, setting(UpdateChannel.NIGHTLY)).check(nightly3))
    }

    @Test
    fun `a channel change inside the cache window makes a new request`() = runTest {
        val http = bothReleases(FakeHttp(), nightly = nightlyRelease("0.0.3-nightly.1"), stable = release("0.0.2"))
        val channel = setting(UpdateChannel.AUTO)
        val checker = UpdateChecker(http, channel)
        assertEquals("0.0.2", checker.check(current)?.latestVersion)
        // Same checker, but the stored channel now resolves to NIGHTLY.
        channel.set(UpdateChannel.NIGHTLY)
        assertEquals("0.0.3-nightly.1", checker.check(current)?.latestVersion)
        // One stable read for the first check, then the nightly check reads both endpoints.
        assertEquals(2, http.countMatching("/releases/latest"))
        assertEquals(1, http.countMatching("/releases/tags/nightly"))
    }

    @Test
    fun `clearing the cache makes a new request`() = runTest {
        val http = bothReleases(FakeHttp())
        val checker = UpdateChecker(http, setting(UpdateChannel.STABLE))
        checker.check(current)
        checker.clearCache()
        checker.check(current)
        assertEquals(2, http.countMatching("/releases/latest"))
    }

    @Test
    fun `an unknown stored channel reads as auto`() = runTest {
        val storage = FakeStorage().apply { values["piped.update.channel"] = "\"BOGUS\"" }
        val setting = UpdateChannelSetting(EntityStore(storage))
        assertEquals(UpdateChannel.AUTO, setting.stored())
        assertEquals(UpdateChannel.STABLE, setting.resolve(current))
    }

    @Test
    fun `an unset channel reads as auto`() = runTest {
        assertEquals(UpdateChannel.AUTO, UpdateChannelSetting(EntityStore(FakeStorage())).stored())
    }

    @Test
    fun `nightly versions order the way the update check needs`() {
        val stable = SemVer.parse("0.0.1")
        val nightly = SemVer.parse("0.0.2-nightly.1")
        // A nightly outranks the release it was built on, and loses to the next stable.
        assertTrue(nightly > stable)
        assertTrue(SemVer.parse("0.0.2") > SemVer.parse("0.0.2-nightly.9"))
        // Run numbers compare as numbers, so a later nightly is newer.
        assertTrue(SemVer.parse("0.0.2-nightly.10") > SemVer.parse("0.0.2-nightly.9"))
        assertNull(SemVer.parse("0.0.1").preRelease)
        assertEquals("nightly.3", SemVer.parse("0.0.2-nightly.3").preRelease)
    }

    @Test
    fun `a storage failure while reading the channel falls back to stable`() = runTest {
        val storage = object : FakeStorage() {
            override suspend fun getString(key: String): String? = throw IllegalStateException("storage down")
        }
        val http = FakeHttp().apply { onPath("/releases/latest", body = release("0.1.0")) }
        val info = UpdateChecker(http, UpdateChannelSetting(EntityStore(storage))).check(current)
        // Falls back to the stable channel, which still answers.
        assertEquals("0.1.0", info?.latestVersion)
    }
}
