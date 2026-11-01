package top.s0cl.cinny.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text as M3Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import top.s0cl.cinny.core.design.CinnyRadii
import top.s0cl.cinny.core.design.CinnySpacing
import top.s0cl.cinny.core.design.LocalCinnyColors
import top.s0cl.cinny.matrix.MatrixEngine
import top.s0cl.cinny.matrix.RoomSummary
import top.s0cl.cinny.matrix.SpaceSummary
import top.s0cl.cinny.platform.PlatformBackHandler
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import kotlin.math.roundToInt

/**
 * M2：空间列表。
 *
 * - 支持长按 `≡` 拖拽排序（松手时写入 `in.cinny.spaces` 账户数据）；
 * - 嵌套空间可折叠展开；
 * - 进入空间 Lobby 后可以把已加入的房间挂到空间下。
 */
@Composable
fun SpacesScreen(engine: MatrixEngine, onOpenRoom: (String) -> Unit) {
    val colors = LocalCinnyColors.current
    val scope = rememberCoroutineScope()
    val spaces by engine.spaces().collectAsState(initial = emptyList())
    var selected by remember { mutableStateOf<SpaceSummary?>(null) }
    var newName by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    if (selected != null) {
        SpaceLobbyScreen(engine, selected!!, onBack = { selected = null }, onOpenRoom = onOpenRoom)
        return
    }

    val spaceIds = remember(spaces) { spaces.map { it.spaceId }.toSet() }
    val parentOf: Map<String, String> = remember(spaces) {
        buildMap {
            spaces.forEach { space ->
                space.childRoomIds.filter { it in spaceIds }.forEach { child ->
                    putIfAbsent(child, space.spaceId)
                }
            }
        }
    }
    val topLevel = remember(spaces, parentOf) {
        spaces.filter { parentOf[it.spaceId] == null }
    }
    var order by remember(spaces) { mutableStateOf(topLevel.map { it.spaceId }) }
    var collapsed by remember { mutableStateOf(setOf<String>()) }
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableStateOf(0f) }
    val rowHeightPx = with(LocalDensity.current) { 88.dp.toPx() }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    ) {
        SmallTitle("空间")
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = CinnySpacing.S300),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CinnySpacing.S200),
        ) {
            TextField(
                value = newName,
                onValueChange = { newName = it },
                label = "新空间名称",
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            Button(onClick = {
                val name = newName.trim()
                if (name.isNotEmpty()) {
                    scope.launch {
                        engine.createSpace(name)
                            .onSuccess { newName = ""; error = null }
                            .onFailure { error = it.message }
                    }
                }
            }) { Text("创建") }
        }
        error?.let { Text(it, color = colors.critical.main, fontSize = 12.sp) }
        if (spaces.isEmpty()) {
            Box(Modifier.fillMaxHeight().padding(top = 80.dp), contentAlignment = Alignment.Center) {
                Text("暂无空间", color = colors.background.onContainer.copy(alpha = 0.7f))
            }
        } else {
            Column(Modifier.fillMaxWidth().padding(vertical = CinnySpacing.S200)) {
                order.forEachIndexed { index, id ->
                    SpaceRow(
                        engine = engine,
                        space = spaces.firstOrNull { it.spaceId == id } ?: return@forEachIndexed,
                        index = index,
                        order = order,
                        onOrderChange = { order = it },
                        nested = spaces.filter { child -> child.spaceId in
                            (spaces.firstOrNull { it.spaceId == id }?.childRoomIds ?: emptyList())
                        },
                        collapsed = collapsed.contains(id),
                        onToggleCollapse = {
                            collapsed = if (collapsed.contains(id)) collapsed - id else collapsed + id
                        },
                        onOpen = { selected = it },
                        rowHeightPx = rowHeightPx,
                        dragging = draggingId == id,
                        onDraggingChange = { dragging -> draggingId = if (dragging) id else null },
                        dragOffset = if (draggingId == id) dragOffset else 0f,
                        onDragOffsetChange = { dragOffset = it },
                        scope = scope,
                    )
                }
            }
        }
    }
}

@Composable
private fun SpaceRow(
    engine: MatrixEngine,
    space: SpaceSummary,
    index: Int,
    order: List<String>,
    onOrderChange: (List<String>) -> Unit,
    nested: List<SpaceSummary>,
    collapsed: Boolean,
    onToggleCollapse: () -> Unit,
    onOpen: (SpaceSummary) -> Unit,
    rowHeightPx: Float,
    dragging: Boolean,
    onDraggingChange: (Boolean) -> Unit,
    dragOffset: Float,
    onDragOffsetChange: (Float) -> Unit,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val colors = LocalCinnyColors.current
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(0, dragOffset.roundToInt()) }
                .background(if (dragging) colors.background.containerActive else colors.background.container),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "≡",
                color = colors.background.onContainer.copy(alpha = 0.6f),
                fontSize = 18.sp,
                modifier = Modifier
                    .padding(start = CinnySpacing.S300)
                    .pointerInput(space.spaceId, order) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                onDraggingChange(true)
                                onDragOffsetChange(0f)
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                onDragOffsetChange(dragOffset + amount.y)
                            },
                            onDragEnd = {
                                val steps = (dragOffset / rowHeightPx).roundToInt()
                                if (steps != 0) {
                                    val target = (index + steps).coerceIn(0, order.lastIndex)
                                    val mutable = order.toMutableList()
                                    val moved = mutable.removeAt(index)
                                    mutable.add(target, moved)
                                    onOrderChange(mutable)
                                    scope.launch { engine.reorderSpaces(mutable) }
                                }
                                onDraggingChange(false)
                                onDragOffsetChange(0f)
                            },
                            onDragCancel = {
                                onDraggingChange(false)
                                onDragOffsetChange(0f)
                            },
                        )
                    },
            )
            Column(Modifier.weight(1f)) {
                BasicComponent(
                    title = space.name,
                    summary = space.topic ?: "${space.childRoomIds.size} 个子房间",
                    onClick = { onOpen(space) },
                )
            }
        }
        HorizontalDivider()
        if (nested.isNotEmpty()) {
            if (!collapsed) {
                nested.forEach { child ->
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .background(colors.background.container)
                            .clickable { onOpen(child) }
                            .padding(start = CinnySpacing.S500, top = CinnySpacing.S200, bottom = CinnySpacing.S200),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("↳ ", color = colors.primary.main, fontSize = 12.sp)
                        Text(child.name, color = colors.background.onContainer, fontSize = 14.sp)
                    }
                }
            }
            Text(
                text = if (collapsed) "展开嵌套（${nested.size}）" else "折叠嵌套",
                color = colors.primary.main,
                fontSize = 11.sp,
                modifier = Modifier.padding(start = CinnySpacing.S500, bottom = CinnySpacing.S200)
                    .clickable(onClick = onToggleCollapse),
            )
        }
    }
}

/** M2：空间 Lobby —— 子房间列表 + 添加已加入房间。 */
@Composable
private fun SpaceLobbyScreen(
    engine: MatrixEngine,
    space: SpaceSummary,
    onBack: () -> Unit,
    onOpenRoom: (String) -> Unit,
) {
    val colors = LocalCinnyColors.current
    val scope = rememberCoroutineScope()
    PlatformBackHandler(onBack = onBack)
    val rooms by engine.rooms().collectAsState(initial = emptyList())
    val spaces by engine.spaces().collectAsState(initial = emptyList())
    val current = spaces.firstOrNull { it.spaceId == space.spaceId } ?: space
    var showAdd by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    val children = remember(rooms, current) {
        if (current.childRoomIds.isEmpty()) {
            rooms
        } else {
            rooms.filter { it.roomId in current.childRoomIds }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(colors.background.container)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
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
                text = current.name,
                modifier = Modifier.weight(1f).padding(start = CinnySpacing.S200),
                color = colors.background.onContainer,
                fontSize = 16.sp,
                maxLines = 1,
            )
            Text(
                text = "添加房间",
                color = colors.primary.main,
                fontSize = 13.sp,
                modifier = Modifier.clickable { showAdd = true }.padding(CinnySpacing.S200),
            )
        }
        HorizontalDivider()
        SmallTitle("Lobby")
        status?.let {
            Text(it, color = colors.primary.main, fontSize = 12.sp, modifier = Modifier.padding(CinnySpacing.S300))
        }
        if (children.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("该 Space 暂无已加入子房间", color = colors.background.onContainer.copy(alpha = 0.7f))
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(children, key = { it.roomId }) { room ->
                    BasicComponent(
                        title = room.name,
                        summary = room.topic ?: room.roomId,
                        onClick = { onOpenRoom(room.roomId) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    if (showAdd) {
        val candidates = rooms.filter { it.roomId !in current.childRoomIds }
        AddRoomDialog(
            rooms = candidates,
            onDismiss = { showAdd = false },
            onPick = { room ->
                showAdd = false
                scope.launch {
                    engine.addRoomToSpace(current.spaceId, room.roomId)
                        .onSuccess { status = "已添加：${room.name}" }
                        .onFailure { status = "添加失败：${it.message}" }
                }
            },
        )
    }
}

@Composable
private fun AddRoomDialog(
    rooms: List<RoomSummary>,
    onDismiss: () -> Unit,
    onPick: (RoomSummary) -> Unit,
) {
    val colors = LocalCinnyColors.current
    AlertDialog(
        containerColor = colors.background.container,
        titleContentColor = colors.background.onContainer,
        textContentColor = colors.background.onContainer,
        onDismissRequest = onDismiss,
        title = { M3Text("添加已加入的房间") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                if (rooms.isEmpty()) {
                    M3Text("没有可添加的房间", color = colors.background.onContainer.copy(alpha = 0.7f))
                }
                rooms.forEach { room ->
                    Text(
                        text = room.name,
                        color = colors.background.onContainer,
                        fontSize = 14.sp,
                        modifier = Modifier.fillMaxWidth()
                            .clickable { onPick(room) }
                            .padding(vertical = CinnySpacing.S200),
                    )
                    HorizontalDivider()
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { M3Text("取消") }
        },
    )
}
