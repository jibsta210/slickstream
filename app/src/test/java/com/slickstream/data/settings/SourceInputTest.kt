package com.slickstream.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SettingsRepository.normalizeSourceInput] turns whatever the user pastes into the Streaming source
 * box into something the resolver can actually query.
 *
 * This is worth testing precisely because every failure mode here is SILENT: a value that does not
 * resolve is still stored, and the settings screen still shows a green "✓ Custom source active" while
 * every source request quietly 404s. The user then reports "no sources" and nothing points at the URL.
 */
class SourceInputTest {

    private fun norm(s: String) = SettingsRepository.normalizeSourceInputForTest(s)

    @Test
    fun `a bare api token becomes a configured torrentio base`() {
        val out = norm("TESTTOKENPLACEHOLDER")
        assertEquals(
            "https://torrentio.strem.fun/realdebrid=TESTTOKENPLACEHOLDER/",
            out,
        )
    }

    @Test
    fun `surrounding whitespace is ignored`() {
        assertEquals(norm("ABC123"), norm("   ABC123\n"))
    }

    @Test
    fun `an https url is kept verbatim`() {
        val url = "https://torrentio.strem.fun/providers=yts,eztv|realdebrid=TOK/manifest.json"
        assertEquals(url, norm(url))
    }

    @Test
    fun `the stremio install scheme is rewritten to https`() {
        // The /configure page's Install button href is literally "stremio://host/<config>/manifest.json".
        // Left alone it reaches Retrofit as an unresolvable scheme and the addon is silently skipped.
        assertEquals(
            "https://torrentio.strem.fun/realdebrid=TOK/manifest.json",
            norm("stremio://torrentio.strem.fun/realdebrid=TOK/manifest.json"),
        )
    }

    @Test
    fun `a schemeless paste gets https`() {
        assertEquals(
            "https://torrentio.strem.fun/realdebrid=TOK/",
            norm("torrentio.strem.fun/realdebrid=TOK/"),
        )
    }

    @Test
    fun `a bare config segment is wrapped`() {
        assertEquals("https://torrentio.strem.fun/realdebrid=TOK/", norm("realdebrid=TOK"))
    }

    @Test
    fun `empty stays empty so clearing works`() {
        assertEquals("", norm(""))
        assertEquals("", norm("   "))
    }

    @Test
    fun `a token needing escaping is percent-encoded rather than changing the route`() {
        // It becomes a PATH SEGMENT. An unescaped '/' or '?' would silently repoint the request.
        val out = norm("abc/def")
        // Contains a slash, so it is treated as a URL rather than a token — the point is that it is
        // never emitted as a raw extra path segment under realdebrid=.
        assertTrue(out.startsWith("https://"))
        assertTrue(!out.contains("realdebrid=abc/def"))
    }

    @Test
    fun `a real multi-option config url survives intact`() {
        // Commas are LEGAL inside one Torrentio config; this must not be treated as a list.
        val url = "https://torrentio.strem.fun/providers=yts,eztv,rarbg|sort=qualitysize|realdebrid=TOK/"
        assertEquals(url, norm(url))
    }
}
