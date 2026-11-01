package top.s0cl.cinny.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml

actual fun htmlToAnnotatedString(html: String): AnnotatedString =
    AnnotatedString.fromHtml(html)
