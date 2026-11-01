package top.s0cl.cinny.platform

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import top.s0cl.cinny.matrix.MatrixEngine
import top.s0cl.cinny.matrix.TimelineItem

@Composable
actual fun MediaAttachment(
    engine: MatrixEngine,
    roomId: String,
    item: TimelineItem,
    autoLoad: Boolean,
    modifier: Modifier,
) {
    Text(
        text = "[附件] " + (item.body ?: item.msgType ?: "媒体"),
        modifier = modifier.fillMaxWidth(),
        fontSize = 12.sp,
    )
}
