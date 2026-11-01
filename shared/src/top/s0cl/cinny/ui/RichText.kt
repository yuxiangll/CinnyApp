package top.s0cl.cinny.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

/**
 * M2：Matrix `formatted_body`（受限 HTML / 子集 Markdown）渲染。
 *
 * 支持：`b/strong`、`i/em`、`u`、`s/del`、`code`、`pre`、`a`、`span data-mx-spoiler`、
 * `br/p`、`ul/ol/li`（列表符号）、`blockquote`（▎前缀）、`h1…h6`（加粗 + 换行）。
 * - matrix.to 用户链接按 mention 高亮；
 * - 剧透默认遮挡，点击整条消息展开（由调用方控制 spoilerRevealed）。
 */
fun renderMatrixHtml(
    html: String,
    spoilerRevealed: Boolean,
    textColor: Color,
    linkColor: Color,
    codeBackground: Color,
    spoilerBackground: Color,
): AnnotatedString = buildAnnotatedString {
    var bold = 0
    var italic = 0
    var underline = 0
    var strike = 0
    var code = 0
    var spoiler = 0
    var link: String? = null
    var orderedList = false
    var orderedIndex = 0
    val newline = "\n"
    val bullet = "\u2022 "
    val quoteBar = "\u258E "

    fun push(text: String) {
        if (text.isEmpty()) return
        val start = length
        if (spoiler > 0 && !spoilerRevealed) {
            append("\u2588".repeat(text.length.coerceAtMost(24)))
        } else {
            append(text)
        }
        addStyle(
            SpanStyle(
                color = if (link != null) linkColor else textColor,
                fontWeight = if (bold > 0) FontWeight.Bold else null,
                fontStyle = if (italic > 0) FontStyle.Italic else null,
                textDecoration = decoration(underline, strike),
                fontFamily = if (code > 0) FontFamily.Monospace else null,
                background = when {
                    code > 0 -> codeBackground
                    spoiler > 0 -> spoilerBackground
                    else -> Color.Transparent
                },
            ),
            start,
            length,
        )
    }

    val token = Regex("<[^>]+>")
    var cursor = 0
    while (cursor < html.length) {
        val match = token.find(html, cursor)
        val textEnd = match?.range?.first ?: html.length
        if (textEnd > cursor) push(decodeEntities(html.substring(cursor, textEnd)))
        if (match == null) break
        val raw = match.value.lowercase()
        val closing = raw.startsWith("</")
        when {
            raw.startsWith("<blockquote") -> if (closing) push(newline) else push(quoteBar)
            raw.startsWith("<ul") -> Unit
            raw.startsWith("<ol") -> {
                orderedList = !closing
                if (!closing) orderedIndex = 0
            }
            raw.startsWith("<li") -> {
                if (!closing) {
                    if (orderedList) {
                        orderedIndex += 1
                        push("$orderedIndex. ")
                    } else {
                        push(bullet)
                    }
                }
            }
            raw.startsWith("<h1") || raw.startsWith("<h2") || raw.startsWith("<h3") ||
                raw.startsWith("<h4") || raw.startsWith("<h5") || raw.startsWith("<h6") -> {
                if (closing) {
                    bold -= 1
                    push(newline)
                } else {
                    bold += 1
                    push(newline)
                }
            }
            // "<span"/"<strong" 也以 "<s" 开头，必须先判断更长的标签。
            raw.startsWith("<span") -> if (raw.contains("data-mx-spoiler")) spoiler += if (closing) -1 else 1
            raw.startsWith("<strong") || raw.startsWith("<b") -> bold += if (closing) -1 else 1
            raw.startsWith("<i") || raw.startsWith("<em") -> italic += if (closing) -1 else 1
            raw.startsWith("<u") -> underline += if (closing) -1 else 1
            raw.startsWith("<del") || raw.startsWith("<s") -> strike += if (closing) -1 else 1
            raw.startsWith("<code") || raw.startsWith("<pre") -> code += if (closing) -1 else 1
            raw.startsWith("<a ") -> link = if (closing) null else hrefOf(match.value)
            raw == "<br>" || raw == "<br/>" || raw == "<br />" -> push(newline)
            raw.startsWith("<p") && closing -> push(newline)
        }
        if (bold < 0) bold = 0
        if (italic < 0) italic = 0
        if (underline < 0) underline = 0
        if (strike < 0) strike = 0
        if (code < 0) code = 0
        if (spoiler < 0) spoiler = 0
        cursor = match.range.last + 1
    }
}

private fun decoration(underline: Int, strike: Int): TextDecoration? = when {
    underline > 0 && strike > 0 -> TextDecoration.combine(
        listOf(TextDecoration.Underline, TextDecoration.LineThrough),
    )
    underline > 0 -> TextDecoration.Underline
    strike > 0 -> TextDecoration.LineThrough
    else -> null
}

private fun hrefOf(tag: String): String? {
    val key = "href="
    val index = tag.indexOf(key, ignoreCase = true)
    if (index < 0) return null
    var i = index + key.length
    while (i < tag.length && tag[i].isWhitespace()) i++
    if (i >= tag.length) return null
    val quoteCode = tag[i].code
    if (quoteCode != 34 && quoteCode != 39) return null
    val quote = tag[i]
    val endQuote = tag.indexOf(quote, i + 1)
    if (endQuote < 0) return null
    return tag.substring(i + 1, endQuote)
}

private fun decodeEntities(text: String): String = text
    .replace("&amp;", "&")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace("&#39;", "'")
    .replace("&nbsp;", " ")
