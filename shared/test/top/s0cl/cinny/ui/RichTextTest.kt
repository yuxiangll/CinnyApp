package top.s0cl.cinny.ui

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RichTextTest {

    private fun render(html: String, spoiler: Boolean = false) = renderMatrixHtml(
        html = html,
        spoilerRevealed = spoiler,
        textColor = Color.Black,
        linkColor = Color.Blue,
        codeBackground = Color.LightGray,
        spoilerBackground = Color.DarkGray,
    )

    @Test
    fun rendersBoldItalicAndEntities() {
        val result = render("<b>bold</b> &amp; <i>it</i>")
        assertEquals("bold & it", result.text)
        assertTrue(result.spanStyles.any { it.item.fontWeight != null })
        assertTrue(result.spanStyles.any { it.item.fontStyle != null })
    }

    @Test
    fun rendersUnorderedAndOrderedLists() {
        val unordered = render("<ul><li>a</li><li>b</li></ul>")
        assertTrue(unordered.text.contains("\u2022 a"))
        assertTrue(unordered.text.contains("\u2022 b"))

        val ordered = render("<ol><li>first</li><li>second</li></ol>")
        assertTrue(ordered.text.contains("1. first"))
        assertTrue(ordered.text.contains("2. second"))
    }

    @Test
    fun rendersBlockquoteAndHeadings() {
        val quote = render("<blockquote>quoted</blockquote>")
        assertTrue(quote.text.contains("\u258E quoted"))

        val heading = render("<h3>title</h3>body")
        assertTrue(heading.text.contains("title"))
        assertTrue(heading.text.startsWith("\n"))
        assertTrue(heading.spanStyles.any { it.item.fontWeight != null })
    }

    @Test
    fun rendersCodeWithMonospace() {
        val result = render("<pre><code>f()</code></pre>")
        assertEquals("f()", result.text)
        assertTrue(result.spanStyles.any { it.item.fontFamily != null })
    }

    @Test
    fun spoilerIsMaskedUntilRevealed() {
        val masked = render("<span data-mx-spoiler>secret</span>", spoiler = false)
        assertEquals("\u2588".repeat(6), masked.text)

        val revealed = render("<span data-mx-spoiler>secret</span>", spoiler = true)
        assertEquals("secret", revealed.text)
    }

    @Test
    fun mentionsUseLinkColor() {
        val result = render("<a href=\"https://matrix.to/#/@test:s0cl.top\">@test</a>")
        assertEquals("@test", result.text)
        assertTrue(result.spanStyles.any { it.item.color == Color.Blue })
    }
}
