package top.s0cl.cinny

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 一个用于验证 Miuix 组件接入是否正常的简易测试页：
 * 开关、输入框、滑块、按钮，并把当前状态回显出来。
 */
@Composable
fun MiuixTestScreen() {
    var notificationEnabled by remember { mutableStateOf(true) }
    var nickname by remember { mutableStateOf("") }
    var volume by remember { mutableFloatStateOf(0.6f) }
    var lastResult by remember { mutableStateOf("还没有提交") }

    MiuixTheme {
        Scaffold(
            topBar = { TopAppBar(title = "Miuix UI 测试") },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SmallTitle("基础组件")
                Card {
                    BasicComponent(
                        title = "接收通知",
                        summary = if (notificationEnabled) "已开启" else "已关闭",
                        endActions = {
                            Switch(
                                checked = notificationEnabled,
                                onCheckedChange = { notificationEnabled = it },
                            )
                        },
                    )
                    HorizontalDivider()
                    BasicComponent(
                        title = "昵称",
                        summary = nickname.ifBlank { "未填写" },
                    )
                    TextField(
                        value = nickname,
                        onValueChange = { nickname = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = "请输入昵称",
                    )
                    Spacer(Modifier.height(12.dp))
                }

                SmallTitle("滑块")
                Card {
                    Text(
                        text = "当前音量：${(volume * 100).toInt()}%",
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(8.dp))
                    Slider(
                        value = volume,
                        onValueChange = { volume = it },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                SmallTitle("操作")
                Card {
                    Button(
                        onClick = {
                            lastResult = buildString {
                                append("通知=")
                                append(if (notificationEnabled) "开" else "关")
                                append("，昵称=")
                                append(nickname.ifBlank { "匿名" })
                                append("，音量=")
                                append((volume * 100).toInt())
                                append("%")
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("提交测试")
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = lastResult,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Preview
@Composable
private fun MiuixTestScreenPreview() {
    MiuixTestScreen()
}
