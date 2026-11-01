package top.s0cl.cinny.ui

import androidx.compose.ui.text.AnnotatedString

actual fun htmlToAnnotatedString(html: String): AnnotatedString =
    AnnotatedString(html.replace(Regex("<[^>]+>"), ""))
