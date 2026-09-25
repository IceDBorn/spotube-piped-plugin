package dev.icedborn.spotube_plugin_piped_metadata

import dev.icedborn.spotube_plugin_piped_metadata.fakes.FakeHttp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import net.swiftzer.semver.SemVer

private const val ASSET =
    """{"name":"spotube-plugin-piped.smplug","browser_download_url":"https://example/plugin.smplug"}"""

/** Update checks read the latest GitHub release; a failure must be a null, never an exception. */
class UpdateCheckerTest {

    private val current = SemVer.parse("0.0.1")

    private fun release(tag: String, notes: String = "notes", assets: String = ASSET) = """
        {"tag_name":"$tag","body":"$notes","assets":[$assets]}
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
}
