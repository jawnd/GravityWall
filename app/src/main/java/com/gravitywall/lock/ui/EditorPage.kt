package com.gravitywall.lock.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import com.gravitywall.lock.AppController
import com.gravitywall.lock.data.Project
import com.gravitywall.lock.data.ProjectLayer
import com.gravitywall.lock.data.RuntimeConfig
import com.gravitywall.lock.render.StageLayer
import kotlinx.coroutines.launch

/**
 * 编辑器页（v0.6 定稿）：
 * 预览 + 选中层数值条 → 图层清单（两行系统时钟把清单切成三条带）→ 保存动作。
 *
 * 清单自上而下就是压叠顺序：按住手柄拖动排序，跨过时钟行即换带；两行时钟是固定分界线，
 * 不可拖动、没有菜单。隐藏的图层：名字与缩略图变浅。
 */

private val ROW_H = 54.dp

@Composable
internal fun EditorTab(ctrl: AppController) {
    val context = LocalContext.current
    val dm = context.resources.displayMetrics
    val project = ctrl.project
    val base = (dm.widthPixels * ctrl.config.amplitude).coerceAtLeast(1f)

    var showCanvas by remember { mutableStateOf(false) }
    var showGlobals by remember { mutableStateOf(false) }
    var importMenu by remember { mutableStateOf(false) }
    var saveMenu by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }
    var saveAsOpen by remember { mutableStateOf(false) }
    var replaceFor by remember { mutableStateOf<String?>(null) }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { ctrl.addImageLayer(it) }
    }
    val pickMask = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { ctrl.addMaskImageLayer(it) }
    }
    val pickReplace = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val id = replaceFor
        if (uri != null && id != null) ctrl.replaceImage(id, uri)
        replaceFor = null
    }

    if (project == null) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            Text(
                "还没有打开项目。到「作品」页新建一个，或把海报包导入为项目。",
                fontSize = 13.sp,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        }
        return
    }
    val layers = ctrl.summary?.layers ?: emptyList()

    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        // ---- 顶栏：左 = 全局参数（项目级），右 = 撤销 / 重做 ----
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { showGlobals = true }) { Text("☰ 全局", fontSize = 13.sp) }
            Text(
                project.name,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            TextButton(onClick = { ctrl.undo() }, enabled = ctrl.canUndo) { Text("撤销", fontSize = 12.sp) }
            TextButton(onClick = { ctrl.redo() }, enabled = ctrl.canRedo) { Text("重做", fontSize = 12.sp) }
        }

        // ---- 预览 + 选中层数值 ----
        // 预览是一块固定面板（大小、位置都不变，也不能关闭）——外框把"窗口"和
        // "里面被选中的图层"分开，否则满屏图层的选中框看起来就像预览本身能拖能关。
        val density = LocalDensity.current
        val (screenW, screenH) = realScreen(context)
        val previewWpx = (screenW * 0.46f).toInt()
        // 宽度固定，高度按设备真实屏幕比例算——预览就是这块屏幕的等比缩小
        val panelHpx = (previewWpx.toFloat() * screenH / screenW).toInt()
        Row(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.width(with(density) { previewWpx.toDp() })) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "预览（随倾斜实时动）",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { showCanvas = true },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp),
                    ) { Text("全屏", fontSize = 10.sp) }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(with(density) { panelHpx.toDp() })
                        .background(Color.Black),
                ) {
                    EditorPreview(ctrl, layers, screenW, screenH, Modifier.fillMaxSize(), editable = false, revision = ctrl.revision)
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                val sel = ctrl.selectedLayer
                if (sel == null) {
                    Text(
                        "未选中图层：点清单里的行，或点预览里的图层",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        NumField("X", sel.x, Modifier.weight(1f)) { v ->
                            ctrl.beginEdit(); ctrl.updateSelected { it.x = v }
                        }
                        NumField("Y", sel.y, Modifier.weight(1f)) { v ->
                            ctrl.beginEdit(); ctrl.updateSelected { it.y = v }
                        }
                    }
                    Text(
                        sel.name,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Text(
                        "视差 ${motionText(sel, ctrl.config, base)}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "${sel.w.toInt()}×${sel.h.toInt()}　${sel.rotation.toInt()}°　透明度 ${sel.alpha * 100 / 255}%",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("启用", fontSize = 12.sp)
                        Switch(
                            checked = sel.visible,
                            onCheckedChange = { ctrl.toggleVisible(sel.id) },
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box {
                            OutlinedButton(
                                onClick = { importMenu = true },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp),
                                modifier = Modifier.height(36.dp),
                            ) { Text("导入 ▾", fontSize = 12.sp) }
                            DropdownMenu(expanded = importMenu, onDismissRequest = { importMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("导入图片", fontSize = 13.sp) },
                                    onClick = { importMenu = false; pickImage.launch("image/*") },
                                )
                                DropdownMenuItem(
                                    text = { Text("纯色填充", fontSize = 13.sp) },
                                    onClick = { importMenu = false; ctrl.addColorLayer(0xFF3D8BFF.toInt()) },
                                )
                                DropdownMenuItem(
                                    text = { Text("导入遮罩", fontSize = 13.sp) },
                                    onClick = { importMenu = false; ctrl.addMaskLayer("rect") },
                                )
                                DropdownMenuItem(
                                    text = { Text("导入遮罩（自定义图片）", fontSize = 13.sp) },
                                    onClick = { importMenu = false; pickMask.launch("image/*") },
                                )
                                DropdownMenuItem(
                                    text = { Text("导入视频（后续版本）", fontSize = 13.sp) },
                                    onClick = { importMenu = false },
                                    enabled = false,
                                )
                                DropdownMenuItem(
                                    text = { Text("序列帧 / GIF（后续版本）", fontSize = 13.sp) },
                                    onClick = { importMenu = false },
                                    enabled = false,
                                )
                            }
                        }
                        OutlinedButton(
                            onClick = { showCanvas = true },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp),
                            modifier = Modifier.height(36.dp),
                        ) { Text("更多设置", fontSize = 12.sp) }
                    }
                }
            }
        }

        // ---- 图层清单 ----
        Text(
            "图层清单（自上而下 = 压叠自上而下；按住 ☰ 拖动排序）",
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
        )
        LayerList(
            ctrl = ctrl,
            project = project,
            base = base,
            onRename = { l ->
                renameTarget = l.id
                renameText = l.name
            },
        )

        // ---- 底部动作 ----
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                Button(onClick = { saveMenu = true }) { Text("保存 ▾") }
                DropdownMenu(expanded = saveMenu, onDismissRequest = { saveMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("保存", fontSize = 13.sp) },
                        onClick = { saveMenu = false; ctrl.saveNow() },
                    )
                    DropdownMenuItem(
                        text = { Text("另存为新项目", fontSize = 13.sp) },
                        onClick = {
                            saveMenu = false
                            renameText = "${project.name} 副本"
                            saveAsOpen = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("导出海报包（iOS 兼容 .tendies）", fontSize = 13.sp) },
                        onClick = { saveMenu = false; ctrl.exportProject(apply = false) },
                    )
                    DropdownMenuItem(
                        text = { Text("导出作品包（.gwp，本机搬运/备份）", fontSize = 13.sp) },
                        onClick = { saveMenu = false; ctrl.exportProjectArchive() },
                    )
                    DropdownMenuItem(
                        text = { Text("应用到锁屏", fontSize = 13.sp) },
                        onClick = { saveMenu = false; ctrl.applyProject() },
                    )
                }
            }
            OutlinedButton(onClick = { ctrl.applyProject() }) { Text("应用到锁屏") }
        }
        Box(Modifier.height(20.dp))
    }

    // ---- 弹层 ----
    if (showGlobals) {
        GlobalsSheet(
            ctrl = ctrl,
            project = project,
            onDismiss = { showGlobals = false },
            onSaveAs = {
                showGlobals = false
                renameText = "${project.name} 副本"
                saveAsOpen = true
            },
        )
    }
    if (showCanvas) {
        CanvasScreen(
            ctrl = ctrl,
            layers = layers,
            dm = dm,
            onPickImage = { id ->
                replaceFor = id
                pickReplace.launch("image/*")
            },
            onClose = { showCanvas = false },
        )
    }
    renameTarget?.let { id ->
        NameDialog(
            title = "重命名图层",
            label = "图层名",
            initial = renameText,
            confirmText = "改名",
            onDismiss = { renameTarget = null },
            onConfirm = { v ->
                ctrl.renameLayer(id, v)
                renameTarget = null
            },
        )
    }
    if (saveAsOpen) {
        NameDialog(
            title = "另存为新项目",
            label = "项目名",
            initial = renameText,
            confirmText = "另存",
            onDismiss = { saveAsOpen = false },
            onConfirm = { v ->
                ctrl.saveAs(v)
                saveAsOpen = false
            },
        )
    }
}

/** 设备真实屏幕尺寸（含系统栏），图层坐标就在这个空间里——和模块在锁屏里用的是同一套。 */
internal fun realScreen(context: android.content.Context): Pair<Int, Int> = runCatching {
    val wm = context.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager
    val b = wm.maximumWindowMetrics.bounds
    if (b.width() > 0 && b.height() > 0) b.width() to b.height() else fallbackScreen(context)
}.getOrElse { fallbackScreen(context) }

private fun fallbackScreen(context: android.content.Context): Pair<Int, Int> {
    val dm = context.resources.displayMetrics
    return dm.widthPixels to dm.heightPixels
}

/** 宽高链条图标：点亮＝等比缩放，划斜线＝自由拉伸。 */
@Composable
private fun ChainIcon(linked: Boolean) {
    val tint = if (linked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(18.dp)) {
        val w = size.width
        val h = size.height
        val sw = 1.8.dp.toPx()
        // 两个环
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.08f, h * 0.34f),
            size = Size(w * 0.40f, h * 0.32f),
            cornerRadius = CornerRadius(h * 0.16f),
            style = Stroke(sw),
        )
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.52f, h * 0.34f),
            size = Size(w * 0.40f, h * 0.32f),
            cornerRadius = CornerRadius(h * 0.16f),
            style = Stroke(sw),
        )
        if (linked) {
            drawLine(tint, Offset(w * 0.44f, h * 0.5f), Offset(w * 0.56f, h * 0.5f), sw)
        } else {
            drawLine(
                tint,
                Offset(w * 0.16f, h * 0.84f),
                Offset(w * 0.84f, h * 0.16f),
                sw,
                StrokeCap.Round,
            )
        }
    }
}

/** 选中层的运动摘要：系数模式显示强度，像素模式显示 px 幅度。 */
private fun motionText(l: ProjectLayer, cfg: RuntimeConfig, base: Float): String {
    val m = l.resolvedMotion(base)
    return if (cfg.paramMode == "coefficient") {
        val cx = l.coefX ?: (maxOf(kotlin.math.abs(m[0]), kotlin.math.abs(m[1])) / base)
        val cy = l.coefY ?: (maxOf(kotlin.math.abs(m[2]), kotlin.math.abs(m[3])) / base)
        "X %+.2f　Y %+.2f".format(cx, cy)
    } else {
        val ax = maxOf(kotlin.math.abs(m[0]), kotlin.math.abs(m[1]))
        val ay = maxOf(kotlin.math.abs(m[2]), kotlin.math.abs(m[3]))
        "±${ax.toInt()} / ±${ay.toInt()} px"
    }
}

/** 行内副标题：视差强度 + 不透明度（一眼看出每层动多少）。 */
private fun subtitleOf(l: ProjectLayer, cfg: RuntimeConfig, base: Float): String {
    val m = l.resolvedMotion(base)
    val motion = if (cfg.paramMode == "coefficient") {
        val cx = l.coefX ?: (maxOf(kotlin.math.abs(m[0]), kotlin.math.abs(m[1])) / base)
        val cy = l.coefY ?: (maxOf(kotlin.math.abs(m[2]), kotlin.math.abs(m[3])) / base)
        "视差 ×%+.2f/×%+.2f".format(cx, cy)
    } else {
        val ax = maxOf(kotlin.math.abs(m[0]), kotlin.math.abs(m[1]))
        "视差 ±${ax.toInt()}px"
    }
    val vis = if (l.visible) "" else " · 已隐藏"
    if (l.maskShape != null) {
        val shape = when (l.maskShape) {
            "round" -> "圆形"
            "image" -> "图片"
            else -> "方形"
        }
        return "遮罩（$shape） · 不透明 ${l.alpha * 100 / 255}%$vis"
    }
    return "$motion · 不透明 ${l.alpha * 100 / 255}%$vis"
}

// ------------------------------------------------------------------ 图层清单

@Composable
private fun LayerList(
    ctrl: AppController,
    project: Project,
    base: Float,
    onRename: (ProjectLayer) -> Unit,
) {
    ctrl.revision // 图层是就地改的，读一下版本号让清单整体跟着重画
    val rows = ctrl.rows()
    val rowPx = with(LocalDensity.current) { ROW_H.toPx() }
    var dragId by remember { mutableStateOf<String?>(null) }
    var dragDy by remember { mutableStateOf(0f) }
    var menuId by remember { mutableStateOf<String?>(null) }

    val fromIdx = rows.indexOf(dragId)
    val shift = if (dragId != null && fromIdx >= 0) Math.round(dragDy / rowPx) else 0
    val toIdx = if (fromIdx < 0) -1 else (fromIdx + shift).coerceIn(0, rows.size - 1)

    Column(modifier = Modifier.fillMaxWidth()) {
        rows.forEachIndexed { i, rowId ->
            val offsetY = when {
                dragId == null -> 0f
                rowId == dragId -> dragDy
                fromIdx in 0 until toIdx && i in (fromIdx + 1)..toIdx -> -rowPx
                toIdx in 0 until fromIdx && i in toIdx until fromIdx -> rowPx
                else -> 0f
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ROW_H)
                    .offset { IntOffset(0, offsetY.toInt()) }
                    .zIndex(if (rowId == dragId) 2f else 0f),
            ) {
                when (rowId) {
                    AppController.MARK_MINUTE -> ClockRow("分钟（系统时钟 · 不可编辑）")
                    AppController.MARK_HOUR -> ClockRow("小时（系统时钟 · 不可编辑）")
                    else -> {
                        val layer = project.layers.firstOrNull { it.id == rowId }
                        if (layer != null) {
                            // 手柄：按住即拖。用 startDragImmediately 抢先声明手势，
                            // 否则页面滚动会跟它抢（合成手势尤其明显）。
                            val handleMod = Modifier.draggable(
                                orientation = Orientation.Vertical,
                                startDragImmediately = true,
                                state = rememberDraggableState { delta -> dragDy += delta },
                                onDragStarted = {
                                    dragId = rowId
                                    dragDy = 0f
                                },
                                onDragStopped = { _ ->
                                    val from = ctrl.rows().indexOf(rowId)
                                    val d = Math.round(dragDy / rowPx)
                                    dragId = null
                                    dragDy = 0f
                                    if (from >= 0 && d != 0) ctrl.moveRow(rowId, d)
                                },
                            )
                            LayerRowItem(
                                ctrl = ctrl,
                                project = project,
                                layer = layer,
                                base = base,
                                tick = ctrl.revision,
                                selected = ctrl.selectedLayerId == layer.id,
                                dragging = dragId == layer.id,
                                menuOpen = menuId == layer.id,
                                handleMod = handleMod,
                                onMenuOpen = { menuId = layer.id },
                                onMenuClose = { menuId = null },
                                onRename = { onRename(layer) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 清单里的系统时钟行（固定分界线，不可拖动、没有菜单）。 */
@Composable
private fun ClockRow(label: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
        Text(
            "  $label  ",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Box(Modifier.weight(1f).height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
    }
}

@Composable
private fun LayerRowItem(
    ctrl: AppController,
    project: Project,
    layer: ProjectLayer,
    base: Float,
    /** 版本号：只是用来让这一行在图层数据变化时重画 */
    tick: Int,
    selected: Boolean,
    dragging: Boolean,
    menuOpen: Boolean,
    handleMod: Modifier,
    onMenuOpen: () -> Unit,
    onMenuClose: () -> Unit,
    onRename: () -> Unit,
) {
    if (tick < 0) return
    val bg = when {
        dragging -> MaterialTheme.colorScheme.surfaceContainerHighest
        selected -> MaterialTheme.colorScheme.secondaryContainer
        else -> Color.Transparent
    }
    val nameColor = if (layer.visible) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .combinedClickable(onClick = { ctrl.selectLayer(layer.id) }, onLongClick = onMenuOpen)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(handleMod.size(36.dp), contentAlignment = Alignment.Center) {
            DragHandleArt(Modifier.alpha(if (layer.visible) 1f else 0.4f))
        }
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            val image = layer.image
            val color = layer.color
            if (image != null) {
                val bmp = remember(image) { thumbOf(project, image) }
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().alpha(if (layer.visible) 1f else 0.35f),
                    )
                }
            } else if (color != null) {
                val c = Color(color)
                Box(Modifier.fillMaxSize().background(if (layer.visible) c else c.copy(alpha = 0.3f)))
            } else if (layer.maskShape != null) {
                // 形状遮罩：缩略图直接画形状本身（方形 / 圆形）
                val tint = MaterialTheme.colorScheme.onSurfaceVariant
                Canvas(Modifier.fillMaxSize()) {
                    val pad = size.minDimension * 0.18f
                    val r = if (layer.maskShape == "round") {
                        (size.minDimension - pad * 2f) / 2f
                    } else {
                        size.minDimension * 0.12f
                    }
                    drawRoundRect(
                        color = tint.copy(alpha = if (layer.visible) 0.75f else 0.35f),
                        topLeft = Offset(pad, pad),
                        size = Size(size.width - pad * 2f, size.height - pad * 2f),
                        cornerRadius = CornerRadius(r),
                    )
                }
            }
        }
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(
                layer.name,
                fontSize = 14.sp,
                maxLines = 1,
                color = nameColor,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
            Text(
                subtitleOf(layer, ctrl.config, base),
                fontSize = 11.sp,
                maxLines = 1,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (layer.visible) 1f else 0.55f),
            )
        }
        Box(
            modifier = Modifier.size(36.dp).clickable { ctrl.toggleVisible(layer.id) },
            contentAlignment = Alignment.Center,
        ) {
            EyeIcon(layer.visible)
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = onMenuClose) {
            DropdownMenuItem(text = { Text("重命名", fontSize = 13.sp) }, onClick = { onMenuClose(); onRename() })
            DropdownMenuItem(
                text = { Text("上移一层", fontSize = 13.sp) },
                onClick = { onMenuClose(); ctrl.moveRow(layer.id, -1) },
            )
            DropdownMenuItem(
                text = { Text("下移一层", fontSize = 13.sp) },
                onClick = { onMenuClose(); ctrl.moveRow(layer.id, 1) },
            )
            DropdownMenuItem(
                text = { Text("重置属性", fontSize = 13.sp) },
                onClick = { onMenuClose(); ctrl.resetLayerProps(layer.id) },
            )
            DropdownMenuItem(
                text = { Text("删除", fontSize = 13.sp) },
                onClick = {
                    onMenuClose()
                    ctrl.selectLayer(layer.id)
                    ctrl.deleteSelected()
                },
            )
        }
    }
}

@Composable
private fun DragHandleArt(modifier: Modifier = Modifier) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier.size(16.dp)) {
        val sw = 1.6.dp.toPx()
        for (i in 0..2) {
            val y = size.height * (0.22f + i * 0.28f)
            drawLine(tint, Offset(size.width * 0.1f, y), Offset(size.width * 0.9f, y), sw, StrokeCap.Round)
        }
    }
}

/** 可见性图标：睁眼 / 闭眼（划一道斜线）。 */
@Composable
private fun EyeIcon(visible: Boolean) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        val sw = 1.5.dp.toPx()
        drawOval(
            color = tint,
            topLeft = Offset(w * 0.06f, h * 0.24f),
            size = Size(w * 0.88f, h * 0.52f),
            style = Stroke(sw),
        )
        drawCircle(tint, radius = w * 0.13f, center = Offset(w / 2f, h / 2f))
        if (!visible) {
            drawLine(tint, Offset(w * 0.14f, h * 0.84f), Offset(w * 0.86f, h * 0.16f), sw, StrokeCap.Round)
        }
    }
}

// ------------------------------------------------------------------ 属性页与全局参数

@Composable
private fun CanvasScreen(
    ctrl: AppController,
    layers: List<StageLayer>,
    dm: android.util.DisplayMetrics,
    onPickImage: (String) -> Unit,
    onClose: () -> Unit,
) {
    val cfg = ctrl.config
    val (screenW, screenH) = realScreen(LocalContext.current)
    val base = (screenW * cfg.amplitude).coerceAtLeast(1f)
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    // 抽屉只有两个停靠位：收起（底部只露把手）/ 全占满（参数面板铺满整屏）。
    // 拖动时跟手，松手一定吸附到其中一端——不允许停在中间。
    // 收起态：把手要落在系统手势区（底部导航条）之上，否则根本抓不住
    val navPx = WindowInsets.navigationBars.getBottom(density).toFloat()
    val collapsedPx = with(density) { 56.dp.toPx() } + navPx
    // 展开态铺满状态栏以下的整屏——把手留在状态栏下方，随时能再拉回去
    val statusPx = WindowInsets.statusBars.getTop(density)
    val expandedPx = (screenH - statusPx).toFloat()
    val rangePx = (expandedPx - collapsedPx).coerceAtLeast(1f)
    var dragging by remember { mutableStateOf(false) }
    var rawFraction by remember { mutableStateOf(0f) }
    val settle = remember { Animatable(0f) }
    val sheetPx = collapsedPx + rangePx * (if (dragging) rawFraction else settle.value)
    val sheetExpanded = (if (dragging) rawFraction else settle.value) > 0.5f
    // 注意：这里必须读"当下"的 settle.value，不能在闭包里捕获计算好的布尔值——
    // pointerInput 的 lambda 只创建一次，捕获值会永远停在第一次组合时的状态。
    fun toggleSheet() {
        dragging = false
        val target = if (settle.value > 0.5f) 0f else 1f
        scope.launch { settle.animateTo(target, tween(240)) }
    }

    // 预览模式：跟随倾斜看效果；此时参数面板与画布都不可编辑（只用来"看"）
    var previewMode by remember { mutableStateOf(false) }

    // 兜底吸附：只要不在拖动中，抽屉就必须停在两端之一
    // （手势被系统/其它组件打断时，draggable 的回调可能不完整）
    LaunchedEffect(dragging) {
        if (!dragging) {
            settle.animateTo(if (settle.value > 0.5f) 1f else 0f, tween(200))
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // 画布区当"屏幕"看：空的地方是黑的
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Column(Modifier.fillMaxSize()) {
                // 顶栏：返回 / 撤销 / 完成（画布就是编辑区，这里没有别的"窗口控件"）
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onClose) { Text("←", fontSize = 20.sp) }
                    Text(
                        ctrl.project?.name ?: "",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { ctrl.undo() }, enabled = ctrl.canUndo && !previewMode) {
                        Text("↶", fontSize = 18.sp)
                    }
                    TextButton(
                        onClick = { previewMode = !previewMode },
                        colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                            contentColor = if (previewMode) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        ),
                    ) { Text(if (previewMode) "预览中" else "预览", fontSize = 13.sp) }
                    TextButton(onClick = onClose) { Text("✓", fontSize = 18.sp) }
                }
                Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
                    EditorPreview(
                        ctrl,
                        layers,
                        screenW,
                        screenH,
                        Modifier.fillMaxSize(),
                        editable = !previewMode,
                        tiltEnabled = previewMode,
                        revision = ctrl.revision,
                    )
                }
            }

            // 底部抽屉：收起时只有一个把手，往上拉就是参数面板
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(with(density) { sheetPx.toDp() })
                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .navigationBarsPadding()
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerHigh,
                        RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                    ),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 点一下也能展开/收起（不移动就不算拖动）
                        .pointerInput(Unit) { detectTapGestures { toggleSheet() } }
                        .draggable(
                            orientation = Orientation.Vertical,
                            state = rememberDraggableState { d ->
                                rawFraction = (rawFraction - d / rangePx).coerceIn(0f, 1f)
                            },
                            onDragStarted = {
                                scope.launch { settle.stop() }
                                rawFraction = settle.value
                                dragging = true
                            },
                            onDragStopped = { v ->
                                dragging = false
                                val target = when {
                                    v < -400f -> 1f
                                    v > 400f -> 0f
                                    rawFraction > 0.5f -> 1f
                                    else -> 0f
                                }
                                scope.launch {
                                    settle.snapTo(rawFraction)
                                    settle.animateTo(target, tween(200))
                                }
                            },
                        )
                        .padding(vertical = 20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            Modifier
                                .width(56.dp)
                                .height(5.dp)
                                .background(
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    RoundedCornerShape(3.dp),
                                ),
                        )
                    }
                }
                if (!dragging && settle.value < 0.08f) return@Column
                val sel = ctrl.selectedLayer
                if (sel == null) {
                    Text(
                        "先在图层清单里点一行选中图层，再回来调参数",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                } else {
                    Box(Modifier.weight(1f)) {
                        LayerParams(
                            ctrl,
                            sel,
                            base,
                            onPickImage,
                            Modifier.alpha(if (previewMode) 0.35f else 1f),
                        )
                        if (previewMode) {
                            // 预览模式下参数只读：盖一层透明遮罩吃掉所有触摸
                            Text(
                                "预览模式：参数不可编辑（点顶栏「预览中」退出）",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp),
                            )
                            Box(
                                Modifier
                                    .matchParentSize()
                                    .clickable(enabled = true) {},
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 图层参数面板（全屏画布底部抽屉里的内容）。 */
@Composable
private fun LayerParams(
    ctrl: AppController,
    layer: ProjectLayer,
    base: Float,
    onPickImage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cfg = ctrl.config
    var maskTargetMenu by remember { mutableStateOf(false) }
    // 图层是就地修改的同一个实例，Compose 看不出变化；读一下 revision（每次改动 +1）
    // 才能保证下面的滑块/输入框拿到最新值，而不是"拉了半天才跳一下"。
    val tick = ctrl.revision
    if (tick < 0) return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
    Text("图层属性", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    Text(
        "所在带由清单里的位置决定：把这一行拖过时钟行就换带",
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    OutlinedTextField(
        value = layer.name,
        onValueChange = { v -> ctrl.beginEdit(); ctrl.updateSelected { it.name = v } },
        singleLine = true,
        label = { Text("图层名") },
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
    )

    if (layer.maskShape != null) {
        Text("遮罩形状", fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            listOf("rect" to "方形", "round" to "圆形", "image" to "图片").forEach { (v, label) ->
                FilterChip(
                    selected = layer.maskShape == v,
                    onClick = { ctrl.setMaskShape(layer.id, v) },
                    label = { Text(label, fontSize = 12.sp) },
                )
            }
        }
        Text(
            "方形/圆形都能自由改宽高——拉长就是矩形/椭圆；「图片」用遮罩图本身的明暗当形状。",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (layer.maskShape == "image") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                FilterChip(
                    selected = layer.maskLuminance,
                    onClick = { ctrl.setMaskLuminance(layer.id, true) },
                    label = { Text("按亮度（黑遮白显）", fontSize = 12.sp) },
                )
                FilterChip(
                    selected = !layer.maskLuminance,
                    onClick = { ctrl.setMaskLuminance(layer.id, false) },
                    label = { Text("按透明度", fontSize = 12.sp) },
                )
            }
        }
        val targets = ctrl.project?.layers?.filter { it.maskShape == null } ?: emptyList()
        val current = targets.firstOrNull { it.id == layer.maskTargetId }
        Text("作用于", fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 10.dp))
        Box {
            OutlinedButton(onClick = { maskTargetMenu = true }) {
                Text(current?.name?.take(18) ?: "（选一个图层）", fontSize = 12.sp)
            }
            DropdownMenu(expanded = maskTargetMenu, onDismissRequest = { maskTargetMenu = false }) {
                targets.forEach { t ->
                    DropdownMenuItem(
                        text = { Text(t.name, fontSize = 13.sp) },
                        onClick = {
                            maskTargetMenu = false
                            ctrl.setMaskTarget(layer.id, t.id)
                        },
                    )
                }
            }
        }
        Text(
            "把父级的「启用」关掉，这一层就只显示遮罩以内的部分（显示窗）；" +
                "遮罩跟着自己这一层的位移动，和内容反向运动就是「透过窗口看场景」。",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    } else {
        val mine = ctrl.project?.layers?.filter { it.maskShape != null && it.maskTargetId == layer.id }
            ?: emptyList()
        if (mine.isNotEmpty()) {
            Text(
                "遮罩：${mine.joinToString("、") { it.name }}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                if (layer.maskShape == "image") "遮罩图" else "图片",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                when {
                    layer.image != null -> layer.image!!
                    layer.maskShape != null -> "（预置形状，不用图片）"
                    else -> "（纯色层）"
                },
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        if (layer.maskShape == null || layer.maskShape == "image") {
            OutlinedButton(onClick = { onPickImage(layer.id) }) { Text("替换", fontSize = 12.sp) }
        }
    }
    val color = layer.color
    // 遮罩层不需要调颜色（形状遮罩只要形状，图案遮罩用图片的明暗）
    if (color != null && layer.maskShape == null) {
        Text("纯色 ARGB", fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp))
        val setColor: (Int) -> Unit = { c -> ctrl.beginEdit(); ctrl.updateSelected { it.color = c } }
        HexColorField(color, setColor, Modifier.fillMaxWidth().padding(top = 4.dp))
        ColorPicker(color, setColor, Modifier.fillMaxWidth().padding(top = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
            listOf(
                0xFF3D8BFF, 0xFFFF5A5F, 0xFFFFC24B, 0xFF37D67A,
                0xFF9B6BFF, 0xFFFFFFFF, 0xFF101014, 0xFF000000,
            ).forEach { c ->
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(c.toInt()))
                        .clickable { setColor(c.toInt()) },
                )
            }
        }
    }


    Text("摆放", fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
        NumField("X", layer.x, Modifier.weight(1f)) { v ->
            ctrl.beginEdit(); ctrl.updateSelected { it.x = v }
        }
        NumField("Y", layer.y, Modifier.weight(1f)) { v ->
            ctrl.beginEdit(); ctrl.updateSelected { it.y = v }
        }
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 6.dp),
    ) {
        NumField("宽度", layer.w, Modifier.weight(1f)) { v -> ctrl.setLayerWidth(v) }
        // 链条：点亮＝等比（改一边另一边跟着走）；不点亮＝自由拉伸压缩
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable { ctrl.toggleKeepAspect() },
            contentAlignment = Alignment.Center,
        ) {
            ChainIcon(linked = ctrl.project?.keepAspect ?: true)
        }
        NumField("高度", layer.h, Modifier.weight(1f)) { v -> ctrl.setLayerHeight(v) }
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 6.dp),
    ) {
        NumField("旋转角度", layer.rotation, Modifier.weight(1f), decimals = true) { v ->
            ctrl.beginEdit(); ctrl.updateSelected { it.rotation = v }
        }
        TextButton(onClick = {
            ctrl.beginEdit(); ctrl.updateSelected { it.rotation = 0f }
        }) { Text("归零", fontSize = 12.sp) }
    }

    // ---- 运动：两套参数互斥显隐 ----
    Text(
        if (cfg.paramMode == "coefficient") "运动 · 视差强度（系数模式）" else "运动 · 位移区间（绝对像素模式）",
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(top = 12.dp),
    )
    if (cfg.paramMode == "coefficient") {
        val m = layer.resolvedMotion(base)
        val cx = layer.coefX ?: (maxOf(kotlin.math.abs(m[0]), kotlin.math.abs(m[1])) / base)
        val cy = layer.coefY ?: (maxOf(kotlin.math.abs(m[2]), kotlin.math.abs(m[3])) / base)
        // 系数可以直接输入：iOS 的包常见十几、几十，滑块只负责"快速找感觉"
        val rangeX = maxOf(2f, kotlin.math.ceil(kotlin.math.abs(cx)))
        val rangeY = maxOf(2f, kotlin.math.ceil(kotlin.math.abs(cy)))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 4.dp),
        ) {
            Text("水平视差强度", fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text("负值＝反向", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            NumField("倍", cx, Modifier.width(96.dp).padding(start = 6.dp), decimals = true) { v ->
                ctrl.beginEdit()
                ctrl.updateSelected { l -> l.coefX = v; l.minX = 0f; l.maxX = 0f }
            }
        }
        Slider(
            value = cx.coerceIn(-rangeX, rangeX),
            onValueChange = { v ->
                ctrl.beginEdit()
                ctrl.updateSelected { l -> l.coefX = v; l.minX = 0f; l.maxX = 0f }
            },
            valueRange = -rangeX..rangeX,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("垂直视差强度", fontSize = 12.sp, modifier = Modifier.weight(1f))
            NumField("倍", cy, Modifier.width(96.dp), decimals = true) { v ->
                ctrl.beginEdit()
                ctrl.updateSelected { l -> l.coefY = v; l.minY = 0f; l.maxY = 0f }
            }
        }
        Slider(
            value = cy.coerceIn(-rangeY, rangeY),
            onValueChange = { v ->
                ctrl.beginEdit()
                ctrl.updateSelected { l -> l.coefY = v; l.minY = 0f; l.maxY = 0f }
            },
            valueRange = -rangeY..rangeY,
        )
        Text(
            "远景 0.2~0.4 / 中景 0.5~0.7 / 主体 1.0 / 最前景 1.2~1.6；数值可以直接输入" +
                "（iOS 的包常见十几到几十），滑块的量程会跟着当前值自动放大。",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
            NumField("minX", layer.minX, Modifier.weight(1f)) { v ->
                ctrl.beginEdit(); ctrl.updateSelected { it.minX = v }
            }
            NumField("maxX", layer.maxX, Modifier.weight(1f)) { v ->
                ctrl.beginEdit(); ctrl.updateSelected { it.maxX = v }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
            NumField("minY", layer.minY, Modifier.weight(1f)) { v ->
                ctrl.beginEdit(); ctrl.updateSelected { it.minY = v }
            }
            NumField("maxY", layer.maxY, Modifier.weight(1f)) { v ->
                ctrl.beginEdit(); ctrl.updateSelected { it.maxY = v }
            }
        }
        Text(
            "两个极端姿态下的位移（px）；max 小于 min 即为反向。视差幅度基准在「设置」里。",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }

    Text("不透明度 ${layer.alpha * 100 / 255}%", fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
    Slider(
        value = layer.alpha.toFloat(),
        onValueChange = { v ->
            ctrl.beginEdit()
            ctrl.updateSelected { it.alpha = v.toInt().coerceIn(0, 255) }
        },
        valueRange = 0f..255f,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("启用", fontSize = 12.sp)
        Switch(
            checked = layer.visible,
            onCheckedChange = { ctrl.toggleVisible(layer.id) },
            modifier = Modifier.padding(start = 6.dp),
        )
    }
    val hasMask = ctrl.project?.layers?.any { it.maskShape != null && it.visible && it.maskTargetId == layer.id } == true
    if (layer.maskShape == null && hasMask) {
        Text(
            "这一层挂着遮罩：「启用」打开＝整层完整显示；关掉＝只显示遮罩以内的部分。",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
    ) {
        OutlinedButton(onClick = { ctrl.resetLayerProps(layer.id) }) { Text("重置属性", fontSize = 12.sp) }
        OutlinedButton(onClick = {
            ctrl.selectLayer(layer.id)
            ctrl.deleteSelected()
        }) { Text("删除", fontSize = 12.sp) }
    }
    }
}

/** 全局参数（项目级）：项目名、底图（系统壁纸 / 液态玻璃取样源）、新图层默认带位。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GlobalsSheet(
    ctrl: AppController,
    project: Project,
    onDismiss: () -> Unit,
    onSaveAs: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // 项目对象是就地改的（setBaseLayer / setDefaultBand 只改字段），Compose 看不出变化；
    // 读一下 revision（每次改动 +1）才会让面板跟着重组、chip 重新求值选中态
    ctrl.revision
    var name by remember(project.name) { mutableStateOf(project.name) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Text("全局参数（本项目）", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "参数模式、视差幅度基准、灵敏度、平滑在「设置」页；这里只放这件作品自己的设定。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("项目名") },
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                TextButton(
                    onClick = { ctrl.renameProject(name) },
                    enabled = name.trim().isNotEmpty() && name != project.name,
                ) { Text("改名", fontSize = 12.sp) }
                TextButton(onClick = onSaveAs) { Text("另存为新项目", fontSize = 12.sp) }
            }

            Text(
                "底图（系统锁屏壁纸 · 液态玻璃的取样源）",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                "选中哪一层，应用到锁屏时就把哪一层写进系统壁纸——液态玻璃采样的正是这张纹理。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(modifier = Modifier.padding(top = 4.dp)) {
                val imageLayers = project.layers.filter { it.image != null }
                if (imageLayers.isEmpty()) {
                    Text("（这个项目还没有图片层）", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                imageLayers.forEach { l ->
                    FilterChip(
                        selected = project.baseLayerId == l.id,
                        onClick = { ctrl.setBaseLayer(l.id) },
                        label = { Text(l.name.take(16), fontSize = 12.sp) },
                        modifier = Modifier.padding(end = 6.dp, bottom = 2.dp),
                    )
                }
            }
            OutlinedButton(
                onClick = { ctrl.setLockWallpaper(project.baseLayerId) },
                enabled = project.layers.any { it.image != null },
                modifier = Modifier.padding(top = 6.dp),
            ) { Text("设为锁屏壁纸", fontSize = 12.sp) }

            Text("新图层默认落在", fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                listOf("behind" to "时钟后", "front" to "时钟前", "top" to "最顶层").forEach { (v, label) ->
                    FilterChip(
                        selected = project.defaultBand == v,
                        onClick = { ctrl.setDefaultBand(v) },
                        label = { Text(label, fontSize = 12.sp) },
                    )
                }
            }
            Text(
                "时钟后＝在小时数字后面；时钟前＝小时与分钟之间（主体常用）；最顶层＝压住分钟数字。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box(Modifier.height(24.dp))
        }
    }
}

// ------------------------------------------------------------------ 小控件

/** 数字输入框：回车或失焦时提交（编辑器的 X/Y/宽/高/旋转/min/max 都用它）。 */
@Composable
private fun NumField(
    label: String,
    value: Float,
    modifier: Modifier = Modifier,
    decimals: Boolean = false,
    onCommit: (Float) -> Unit,
) {
    var text by remember(value) { mutableStateOf(if (decimals) "%.1f".format(value) else value.toInt().toString()) }
    var focused by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = text,
        onValueChange = { v ->
            text = v.filter { c -> c.isDigit() || c == '-' || c == '.' }.take(9)
            // 边打边生效：不用等失焦/回车，画布上立刻能看到变化
            text.toFloatOrNull()?.let(onCommit)
        },
        singleLine = true,
        label = { Text(label, fontSize = 10.sp) },
        textStyle = MaterialTheme.typography.bodyMedium,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        keyboardActions = KeyboardActions(onDone = { text.toFloatOrNull()?.let(onCommit) }),
        modifier = modifier.onFocusChanged { st ->
            if (focused && !st.isFocused) text.toFloatOrNull()?.let(onCommit)
            focused = st.isFocused
        },
    )
}

// ------------------------------------------------------------------ 颜色：HEX 输入 + 调色盘

private fun hexOf(color: Int): String = "%08X".format(color)

/** 纯色图层的 HEX 输入框（8 位 ARGB，也接受 6 位 RGB；回车或失焦提交）。 */
@Composable
private fun HexColorField(color: Int, onColor: (Int) -> Unit, modifier: Modifier = Modifier) {
    var text by remember { mutableStateOf(hexOf(color)) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(color) {
        val s = hexOf(color)
        if (!text.equals(s, ignoreCase = true)) text = s
    }
    fun commit() {
        val raw = text.trim().removePrefix("#")
        val v = raw.toLongOrNull(16) ?: return
        val argb = when (raw.length) {
            6 -> (0xFF000000L or v).toInt()
            8 -> v.toInt()
            else -> return
        }
        if (argb != color) onColor(argb)
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(color)),
        )
        Spacer(Modifier.width(10.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { v -> text = v.filter { it.isLetterOrDigit() || it == '#' }.take(9) },
            singleLine = true,
            label = { Text("HEX（ARGB，如 FF3D8BFF）", fontSize = 10.sp) },
            textStyle = MaterialTheme.typography.bodyMedium,
            keyboardActions = KeyboardActions(onDone = { commit() }),
            modifier = Modifier
                .weight(1f)
                .onFocusChanged { st ->
                    if (focused && !st.isFocused) commit()
                    focused = st.isFocused
                },
        )
    }
}

/** 调色盘：左边饱和度/明度方块，右边色相条 + 透明度条。 */
@Composable
private fun ColorPicker(color: Int, onColor: (Int) -> Unit, modifier: Modifier = Modifier) {
    var hue by remember { mutableFloatStateOf(0f) }
    var sat by remember { mutableFloatStateOf(1f) }
    var value by remember { mutableFloatStateOf(1f) }
    var alpha by remember { mutableFloatStateOf(1f) }

    // 外部改了颜色（预置色 / HEX 输入）时同步过来；自己拖动时不要反向覆盖
    LaunchedEffect(color) {
        val mine = Color.hsv(hue, sat, value, alpha).toArgb()
        if (mine != color) {
            val h = FloatArray(3)
            android.graphics.Color.colorToHSV(color, h)
            hue = h[0]
            sat = h[1]
            value = h[2]
            alpha = android.graphics.Color.alpha(color) / 255f
        }
    }
    fun emit() = onColor(Color.hsv(hue, sat, value, alpha).toArgb())

    Row(modifier = modifier, verticalAlignment = Alignment.Top) {
        var boxW by remember { mutableStateOf(1f) }
        var boxH by remember { mutableStateOf(1f) }
        Box(
            Modifier
                .size(132.dp)
                .clip(RoundedCornerShape(10.dp))
                .onSizeChanged {
                    boxW = it.width.toFloat().coerceAtLeast(1f)
                    boxH = it.height.toFloat().coerceAtLeast(1f)
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { off ->
                            sat = (off.x / boxW).coerceIn(0f, 1f)
                            value = 1f - (off.y / boxH).coerceIn(0f, 1f)
                            emit()
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            sat = (change.position.x / boxW).coerceIn(0f, 1f)
                            value = 1f - (change.position.y / boxH).coerceIn(0f, 1f)
                            emit()
                        },
                    )
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawRect(Brush.horizontalGradient(listOf(Color.White, Color.hsv(hue, 1f, 1f))))
                drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
            }
            Canvas(Modifier.fillMaxSize()) {
                val cx = sat * size.width
                val cy = (1f - value) * size.height
                drawCircle(Color.White, radius = 9.dp.toPx(), center = Offset(cx, cy), style = Stroke(2.dp.toPx()))
                drawCircle(
                    Color.Black.copy(alpha = 0.4f),
                    radius = 10.dp.toPx(),
                    center = Offset(cx, cy),
                    style = Stroke(1.dp.toPx()),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            GradientBar(
                brush = Brush.horizontalGradient(
                    listOf(
                        Color.hsv(0f, 1f, 1f), Color.hsv(60f, 1f, 1f), Color.hsv(120f, 1f, 1f),
                        Color.hsv(180f, 1f, 1f), Color.hsv(240f, 1f, 1f), Color.hsv(300f, 1f, 1f),
                        Color.hsv(360f, 1f, 1f),
                    ),
                ),
                value = hue / 360f,
                modifier = Modifier.fillMaxWidth().height(22.dp),
            ) { f -> hue = f * 360f; emit() }
            Spacer(Modifier.height(10.dp))
            GradientBar(
                brush = Brush.horizontalGradient(
                    listOf(Color.hsv(hue, sat, value, 0f), Color.hsv(hue, sat, value, 1f)),
                ),
                value = alpha,
                modifier = Modifier.fillMaxWidth().height(22.dp),
            ) { f -> alpha = f; emit() }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.hsv(hue, sat, value, alpha)),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "H ${hue.toInt()}° S ${(sat * 100).toInt()}% B ${(value * 100).toInt()}% A ${(alpha * 100).toInt()}%",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 带滑块指示的渐变条（色相 / 透明度共用）。 */
@Composable
private fun GradientBar(
    brush: Brush,
    value: Float,
    modifier: Modifier = Modifier,
    onValue: (Float) -> Unit,
) {
    var w by remember { mutableStateOf(1f) }
    Box(
        modifier = modifier
            .onSizeChanged { w = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { off -> onValue((off.x / w).coerceIn(0f, 1f)) },
                    onDrag = { change, _ ->
                        change.consume()
                        onValue((change.position.x / w).coerceIn(0f, 1f))
                    },
                )
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawRoundRect(brush = brush, cornerRadius = CornerRadius(size.height / 2f))
        }
        Canvas(Modifier.fillMaxSize()) {
            val cx = value.coerceIn(0f, 1f) * (size.width - size.height) + size.height / 2f
            drawCircle(Color.White, radius = size.height * 0.42f, center = Offset(cx, size.height / 2f))
            drawCircle(
                Color.Black.copy(alpha = 0.35f),
                radius = size.height * 0.42f,
                center = Offset(cx, size.height / 2f),
                style = Stroke(1.5.dp.toPx()),
            )
        }
    }
}

/** 通用命名对话框（重命名图层 / 另存为新项目）。 */
@Composable
private fun NameDialog(
    title: String,
    label: String,
    initial: String,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text(label) },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.trim().isNotEmpty()) { Text(confirmText) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
