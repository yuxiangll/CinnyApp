package top.s0cl.cinny.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text as M3Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import top.s0cl.cinny.core.design.CinnySpacing
import top.s0cl.cinny.core.design.LocalCinnyColors
import top.s0cl.cinny.matrix.CryptoStatus
import top.s0cl.cinny.core.settings.AppSettings
import top.s0cl.cinny.matrix.MatrixEngine
import top.s0cl.cinny.matrix.PusherInfo
import top.s0cl.cinny.matrix.TimelineItem
import top.s0cl.cinny.matrix.VerificationUiState
import top.s0cl.cinny.platform.PlatformBackHandler
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField

@Composable
fun M3BackRow(title: String, onBack: () -> Unit) {
    val colors = LocalCinnyColors.current
    Column(Modifier.fillMaxWidth().background(colors.background.container)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(CinnySpacing.S300),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "‹ 返回",
                modifier = Modifier.clickable(onClick = onBack).padding(CinnySpacing.S200),
                color = colors.primary.main,
                fontSize = 14.sp,
            )
            Text(
                text = title,
                modifier = Modifier.weight(1f).padding(start = CinnySpacing.S200),
                color = colors.background.onContainer,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        HorizontalDivider()
    }
}

@Composable
fun EncryptionScreen(engine: MatrixEngine, onBack: () -> Unit) {
    val colors = LocalCinnyColors.current
    PlatformBackHandler(onBack = onBack)
    val scope = rememberCoroutineScope()
    val status by engine.cryptoStatus().collectAsState()
    val verification by engine.verificationState().collectAsState()
    var recoveryInput by rememberSaveable { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background.container)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState()),
    ) {
        M3BackRow("加密与安全", onBack)
        Column(
            modifier = Modifier.fillMaxWidth().padding(CinnySpacing.S400),
            verticalArrangement = Arrangement.spacedBy(CinnySpacing.S300),
        ) {
            SmallTitle("E2EE 状态")
            Card {
                BasicComponent(title = "阶段", summary = status.phase)
                HorizontalDivider()
                BasicComponent(title = "本机中继端口", summary = status.relayPort.toString())
                HorizontalDivider()
                BasicComponent(title = "设备 ID", summary = status.deviceId ?: "-")
                HorizontalDivider()
                BasicComponent(title = "设备验证", summary = status.verificationState)
                HorizontalDivider()
                BasicComponent(title = "密钥备份", summary = status.backupState)
                HorizontalDivider()
                BasicComponent(title = "恢复状态", summary = status.recoveryState)
            }
            Card {
                BasicComponent(title = "Ed25519 指纹", summary = status.identityEd25519 ?: "-")
                HorizontalDivider()
                BasicComponent(title = "Curve25519 指纹", summary = status.identityCurve25519 ?: "-")
            }
            if (status.recoveryKey != null) {
                SmallTitle("恢复密钥（请妥善保存）")
                Card {
                    BasicComponent(title = "恢复密钥", summary = status.recoveryKey!!)
                }
            }
            status.error?.let {
                M3Text(text = it, color = colors.critical.main, fontSize = 12.sp)
            }
            message?.let {
                M3Text(text = it, color = colors.primary.main, fontSize = 12.sp)
            }
            SmallTitle("密钥备份")
            Button(
                onClick = {
                    scope.launch {
                        engine.enableKeyBackup()
                            .onSuccess { message = "已启用密钥备份，恢复密钥见上方。" }
                            .onFailure { message = "启用失败：${it.message}" }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("启用密钥备份（Secret Storage）") }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(CinnySpacing.S200),
            ) {
                TextField(
                    value = recoveryInput,
                    onValueChange = { recoveryInput = it },
                    label = "恢复密钥",
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                Button(
                    onClick = {
                        scope.launch {
                            engine.restoreKeyBackup(recoveryInput)
                                .onSuccess { message = "已从恢复密钥恢复备份" }
                                .onFailure { message = "恢复失败：${it.message}" }
                        }
                    },
                ) { Text("恢复") }
            }
            VerificationSection(engine, verification)
        }
    }
}

@Composable
fun VerificationSection(engine: MatrixEngine, verification: VerificationUiState) {
    val colors = LocalCinnyColors.current
    val scope = rememberCoroutineScope()
    SmallTitle("SAS 设备验证")
    Card {
        BasicComponent(title = "状态", summary = verification.stage)
        if (verification.requestFrom != null) {
            HorizontalDivider()
            BasicComponent(title = "来自", summary = verification.requestFrom ?: "")
        }
        if (verification.emojis.isNotEmpty()) {
            HorizontalDivider()
            BasicComponent(title = "表情比对", summary = verification.emojis.joinToString("  "))
        }
        if (verification.decimals.isNotEmpty()) {
            HorizontalDivider()
            BasicComponent(
                title = "数字比对",
                summary = verification.decimals.joinToString("  "),
            )
        }
        verification.error?.let {
            HorizontalDivider()
            BasicComponent(title = "错误", summary = it)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(CinnySpacing.S200)) {
        Button(
            onClick = {
                scope.launch { engine.requestVerification().onFailure { } }
            },
            modifier = Modifier.weight(1f),
        ) { Text("发起验证") }
        if (verification.canAccept) {
            Button(
                onClick = {
                    scope.launch {
                        engine.acceptVerification()
                        engine.startSasVerification()
                    }
                },
                modifier = Modifier.weight(1f),
            ) { Text("接受并开始 SAS") }
        }
    }
    if (verification.canApprove) {
        Row(horizontalArrangement = Arrangement.spacedBy(CinnySpacing.S200)) {
            Button(
                onClick = { scope.launch { engine.approveSasVerification() } },
                modifier = Modifier.weight(1f),
            ) { Text("表情一致，确认") }
            Button(
                onClick = { scope.launch { engine.declineVerification() } },
                modifier = Modifier.weight(1f),
            ) { Text("不一致") }
        }
    }
    M3Text(
        text = "验证需要同一账号的第二台设备配合，双方比对表情后完成交叉签名。",
        color = colors.background.onContainer.copy(alpha = 0.7f),
        fontSize = 12.sp,
    )
}

@Composable
fun DevToolsScreen(engine: MatrixEngine, onBack: () -> Unit) {
    val colors = LocalCinnyColors.current
    PlatformBackHandler(onBack = onBack)
    val scope = rememberCoroutineScope()
    var roomId by rememberSaveable { mutableStateOf("") }
    var eventType by rememberSaveable { mutableStateOf("com.example.custom") }
    var stateKey by rememberSaveable { mutableStateOf("") }
    var content by rememberSaveable { mutableStateOf("{\"hello\":\"m3\"}") }
    var accountType by rememberSaveable { mutableStateOf("in.cinny.dev") }
    var output by remember { mutableStateOf<String?>(null) }
    val status by engine.cryptoStatus().collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background.container)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        M3BackRow("开发者工具", onBack)
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                .padding(CinnySpacing.S400),
            verticalArrangement = Arrangement.spacedBy(CinnySpacing.S200),
        ) {
            SmallTitle("目标")
            Button(
                onClick = {
                    roomId = TEST_ROOM_ID
                    eventType = "m.room.message"
                    content = "{\"msgtype\":\"m.text\",\"body\":\"m3_e2ee_roundtrip\"}"
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("填入测试参数（yes 房间）") }
            Button(
                onClick = {
                    roomId = TEST_ROOM_ID
                    eventType = "m.room.message"
                    val formatted = "<h3>标题</h3><ul><li>第一项</li><li><strong>加粗项</strong></li></ul>" +
                        "<blockquote>引用文本</blockquote><pre><code>code_block()</code></pre>"
                    content = org.json.JSONObject()
                        .put("msgtype", "m.text")
                        .put("body", "富文本测试 标题 第一项 加粗项 引用文本 code_block()")
                        .put("format", "org.matrix.custom.html")
                        .put("formatted_body", formatted)
                        .toString()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("填入富文本测试参数") }
            TextField(
                value = roomId,
                onValueChange = { roomId = it },
                label = "房间 ID",
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            TextField(
                value = eventType,
                onValueChange = { eventType = it },
                label = "事件类型",
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            TextField(
                value = stateKey,
                onValueChange = { stateKey = it },
                label = "状态事件 state_key（可空）",
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            TextField(
                value = content,
                onValueChange = { content = it },
                label = "事件内容 JSON",
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(CinnySpacing.S200)) {
                Button(
                    onClick = {
                        scope.launch {
                            engine.sendCustomEvent(roomId.trim(), eventType.trim(), content)
                                .onSuccess { output = "消息事件已发送：" + (it.eventId ?: "-") }
                                .onFailure { output = "发送失败：" + it.message }
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("发送事件") }
                Button(
                    onClick = {
                        scope.launch {
                            engine.sendStateEvent(roomId.trim(), eventType.trim(), stateKey.trim(), content)
                                .onSuccess { output = "状态事件已发送：" + (it.eventId ?: "-") }
                                .onFailure { output = "状态事件失败：" + it.message }
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("发送状态事件") }
            }
            TextField(
                value = accountType,
                onValueChange = { accountType = it },
                label = "账号数据类型",
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(CinnySpacing.S200)) {
                Button(
                    onClick = {
                        scope.launch {
                            engine.setAccountData(accountType.trim(), content)
                                .onSuccess { output = "账号数据已写入 " + accountType }
                                .onFailure { output = "写入失败：" + it.message }
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("写账号数据") }
                Button(
                    onClick = {
                        scope.launch {
                            engine.getAccountData(accountType.trim())
                                .onSuccess { output = "账号数据：" + it }
                                .onFailure { output = "读取失败：" + it.message }
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("读账号数据") }
            }
            SmallTitle("加密层")
            M3Text(
                text = "rust-sdk：" + status.phase + "（relay=" + status.relayPort + "）",
                color = colors.background.onContainer.copy(alpha = 0.8f),
                fontSize = 12.sp,
            )
            Button(
                onClick = {
                    scope.launch {
                        engine.sendRawThroughCrypto(roomId.trim(), eventType.trim(), content)
                            .onSuccess { output = "rust-sdk 发送成功：" + (it.eventId ?: "-") }
                            .onFailure { output = "rust-sdk 发送失败：" + it.message }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("通过 rust-sdk 发送（加密路径）") }
            output?.let {
                Card {
                    M3Text(text = it, color = colors.background.onContainer, fontSize = 12.sp)
                }
            }
            ConversationTestSection(engine, roomId.trim()) { roomId = it }
        }
    }
}

@Composable
fun EncryptedMessageBody(item: TimelineItem, color: Color) {
    val colors = LocalCinnyColors.current
    val text = item.decryptedBody
    if (text != null) {
        M3Text(text = text, color = color, fontSize = 14.sp)
        M3Text(
            text = "🔒 已解密" + if (item.isEdited) "（已编辑）" else "",
            color = colors.primary.main,
            fontSize = 11.sp,
        )
    } else {
        M3Text(
            text = "🔒 " + (item.decryptionError ?: "加密消息"),
            color = colors.background.onContainer.copy(alpha = 0.7f),
            fontSize = 13.sp,
        )
    }
}

/** 验收用测试房间（s0cl.top 上的加密房间 yes）。 */
private const val TEST_ROOM_ID = "!vWU4mutXaaQIGfWdn86eL9blNQFi_ukvIkwqwwKKGgY:s0cl.top"

@Composable
fun PushSection(
    engine: MatrixEngine,
    settings: AppSettings,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
) {
    val colors = LocalCinnyColors.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var pushers by remember { mutableStateOf<List<PusherInfo>>(emptyList()) }
    var topic by remember(settings.pushTopic) { mutableStateOf(settings.pushTopic) }
    var gateway by remember(settings.pushGateway) { mutableStateOf(settings.pushGateway) }

    LaunchedEffect(Unit) {
        engine.listPushers().onSuccess { pushers = it }
    }

    Card {
        BasicComponent(
            title = "推送开关",
            summary = if (settings.pushEnabled) "已开启（HTTP pusher）" else "已关闭",
            onClick = { onUpdate { it.copy(pushEnabled = !it.pushEnabled) } },
        )
        HorizontalDivider()
        BasicComponent(title = "已注册推送", summary = "${pushers.size} 个")
    }
    TextField(
        value = topic,
        onValueChange = {
            topic = it
            onUpdate { s -> s.copy(pushTopic = it) }
        },
        label = "推送主题（UnifiedPush topic）",
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    TextField(
        value = gateway,
        onValueChange = {
            gateway = it
            onUpdate { s -> s.copy(pushGateway = it) }
        },
        label = "推送网关（HTTP pusher URL 前缀）",
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(CinnySpacing.S200)) {
        Button(
            onClick = {
                val result = if (topic.isBlank()) {
                    // 没有 topic 时生成一个随机 UnifiedPush 主题
                    val generated = "cinny-" + (0 until 12)
                        .map { "abcdefghijklmnopqrstuvwxyz0123456789".random() }
                        .joinToString("")
                    topic = generated
                    onUpdate { it.copy(pushTopic = generated) }
                    generated
                } else {
                    topic.trim()
                }
                scope.launch {
                    engine.registerHttpPusher(result, gateway.trim())
                        .onSuccess {
                            status = "已注册推送：${gateway.trimEnd('/')}/$result"
                            engine.listPushers().onSuccess { pushers = it }
                        }
                        .onFailure { status = "注册失败：${it.message}" }
                }
            },
            modifier = Modifier.weight(1f),
        ) { Text("注册推送") }
        Button(
            onClick = {
                scope.launch {
                    engine.unregisterPusher(PUSH_APP_ID, topic.trim())
                        .onSuccess {
                            status = "已取消推送"
                            engine.listPushers().onSuccess { pushers = it }
                        }
                        .onFailure { status = "取消失败：${it.message}" }
                }
            },
            modifier = Modifier.weight(1f),
        ) { Text("取消注册") }
    }
    Button(
        onClick = {
            scope.launch {
                engine.listPushers()
                    .onSuccess { pushers = it; status = "服务器返回 ${it.size} 个推送器" }
                    .onFailure { status = "读取失败：${it.message}" }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("刷新推送列表") }
    status?.let {
        M3Text(text = it, color = colors.primary.main, fontSize = 12.sp)
    }
    if (pushers.isNotEmpty()) {
        Card {
            pushers.forEachIndexed { index, pusher ->
                if (index > 0) HorizontalDivider()
                BasicComponent(
                    title = pusher.appId,
                    summary = (pusher.url ?: pusher.pushkey) + " · " + (pusher.kind ?: "-"),
                )
            }
        }
    }
}

/** 与 Android 侧 WebViewMatrixEngine.PUSH_APP_ID 保持一致。 */
private const val PUSH_APP_ID = "top.s0cl.cinny"

@Composable
private fun ConversationTestSection(engine: MatrixEngine, roomId: String, onRoomId: (String) -> Unit) {
    val colors = LocalCinnyColors.current
    val scope = rememberCoroutineScope()
    var altUser by rememberSaveable { mutableStateOf("test2") }
    var altPassword by rememberSaveable { mutableStateOf("test123") }
    var message by rememberSaveable { mutableStateOf("hello_from_test2") }
    var result by remember { mutableStateOf<String?>(null) }
    var altLoggedIn by remember { mutableStateOf(engine.altUserId()) }
    var busy by remember { mutableStateOf(false) }

    SmallTitle("双账号对话测试（E2EE）")
    M3Text(
        text = "第二账号在本进程内运行独立的 rust-sdk 加密客户端（独立设备、独立存储）。",
        color = colors.background.onContainer.copy(alpha = 0.7f),
        fontSize = 12.sp,
    )
    Button(
        onClick = {
            if (busy) return@Button
            busy = true
            result = "正在运行双账号对话测试…"
            scope.launch {
                engine.runConversationTest(altUser.trim(), altPassword)
                    .onSuccess { result = it; altLoggedIn = engine.altUserId() }
                    .onFailure { result = "对话测试失败：" + it.message }
                busy = false
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text(if (busy) "运行中…" else "★ 一键双账号对话测试") }
    TextField(
        value = altUser,
        onValueChange = { altUser = it },
        label = "第二账号",
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    TextField(
        value = altPassword,
        onValueChange = { altPassword = it },
        label = "第二账号密码",
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    Button(
        onClick = {
            if (busy) return@Button
            busy = true
            scope.launch {
                engine.altLogin(altUser.trim(), altPassword)
                    .onSuccess {
                        altLoggedIn = it
                        result = "第二账号已登录并启动加密客户端：$it"
                    }
                    .onFailure { result = "登录失败：${it.message}" }
                busy = false
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text(if (altLoggedIn == null) "① 登录第二账号" else "① 已登录：$altLoggedIn") }

    Row(horizontalArrangement = Arrangement.spacedBy(CinnySpacing.S200)) {
        Button(
            onClick = {
                scope.launch {
                    engine.altCreateEncryptedRoom("M3 E2EE 对话测试")
                        .onSuccess {
                            onRoomId(it)
                            result = "已创建加密房间并邀请第二账号：$it"
                        }
                        .onFailure { result = "创建失败：${it.message}" }
                }
            },
            modifier = Modifier.weight(1f),
        ) { Text("② 建加密房间") }
        Button(
            onClick = {
                scope.launch {
                    engine.altJoinRoom(roomId.trim())
                        .onSuccess { result = "第二账号已加入房间" }
                        .onFailure { result = "加入失败：${it.message}" }
                }
            },
            modifier = Modifier.weight(1f),
        ) { Text("③ 第二账号加入") }
    }
    TextField(
        value = message,
        onValueChange = { message = it },
        label = "对话内容",
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(CinnySpacing.S200)) {
        Button(
            onClick = {
                scope.launch {
                    engine.altSendEncrypted(roomId.trim(), message)
                        .onSuccess { result = "第二账号已加密发送：$it" }
                        .onFailure { result = "发送失败：${it.message}" }
                }
            },
            modifier = Modifier.weight(1f),
        ) { Text("④ 第二账号加密发送") }
        Button(
            onClick = {
                scope.launch {
                    engine.altDecryptLatest(roomId.trim())
                        .onSuccess { result = it }
                        .onFailure { result = "解密失败：${it.message}" }
                }
            },
            modifier = Modifier.weight(1f),
        ) { Text("⑤ 第二账号解最新") }
    }
    Button(
        onClick = {
            scope.launch {
                engine.sendEncryptedText(roomId.trim(), message)
                    .onSuccess { result = "本账号（test）已加密发送：" + it.value }
                    .onFailure { result = "本账号发送失败：" + it.message }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("⑥ 本账号(test) 加密发送") }
    result?.let {
        Card {
            M3Text(text = it, color = colors.background.onContainer, fontSize = 12.sp)
        }
    }
}
