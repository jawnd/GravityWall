package com.gravitywall.lock.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.Canvas
import androidx.compose.material3.TabRow
import androidx.compose.material3.Tab
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.gravitywall.lock.AppController
import com.gravitywall.lock.data.PackEntry
import com.gravitywall.lock.data.Project
import com.gravitywall.lock.data.ProjectLayer
import com.gravitywall.lock.data.RuntimeConfig
import com.gravitywall.lock.hook.TiltMath
import com.gravitywall.lock.render.LayerStage
import com.gravitywall.lock.render.ParallaxDefaults
import com.gravitywall.lock.render.StageLayer

@Composable
internal fun GravityWallTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    val scheme = if (android.os.Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } else {
        if (dark) darkColorScheme() else lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** 应用内实时预览：与锁屏用的是同一个渲染器，手机倾斜时预览跟着动。 */
@Composable
internal fun StagePreview(
    layers: List<StageLayer>,
    designW: Int,
    designH: Int,
    modifier: Modifier = Modifier,
    /** false = 不订阅传感器、位移归零（画布上"只摆放"的状态） */
    tiltEnabled: Boolean = true,
    /** 图层数据版本号：每改动一次就重画一次（图层是就地修改，Compose 看不出来） */
    revision: Int = 0,
) {
    val context = LocalContext.current
    val stage = remember {
        LayerStage(context).apply { setDesignSize(designW, designH) }
    }
    LaunchedEffect(revision) { stage.invalidate() }
    AndroidView(
        factory = { stage },
        modifier = modifier,
        update = {
            it.layers = layers
            if (!tiltEnabled) it.applyTilt(0f, 0f)
        },
    )

    DisposableEffect(tiltEnabled) {
        if (!tiltEnabled) return@DisposableEffect onDispose { }
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        if (sm == null) return@DisposableEffect onDispose { }
        val sensor = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sm.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val tilt = when (event.sensor.type) {
                    Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_ROTATION_VECTOR ->
                        TiltMath.fromRotationVector(event.values)
                    else -> TiltMath.fromGravity(event.values[0], event.values[1], event.values[2])
                } ?: return
                val (nx, ny) = ParallaxDefaults.normalize(tilt[0], tilt[1])
                stage.applyTilt(nx, ny)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        if (sensor != null) sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sm.unregisterListener(listener) }
    }
}

@Composable
internal fun AppRoot(ctrl: AppController) {
    var tab by remember { mutableStateOf(0) }
    // 临时反馈（"已保存/已应用/已隐藏…"）走 Snackbar，页面本身不再放全局状态行
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    LaunchedEffect(ctrl.status) {
        if (ctrl.status.isNotBlank()) snackbar.showSnackbar(ctrl.status)
    }
    Scaffold(
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                listOf("项目", "编辑器", "设置", "关于").forEachIndexed { i, label ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i; if (i == 3) ctrl.refreshStatus() },
                        icon = { Text(label) },
                        label = null,
                    )
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            when (tab) {
                0 -> ProjectsTab(ctrl)
                1 -> EditorTab(ctrl)
                2 -> SettingsTab(ctrl)
                else -> AboutTab(ctrl)
            }
        }
    }
}

/** 选择框上的可抓区域（命中半径比视觉大，参考贴纸/裁剪编辑器的做法）。 */
internal enum class SelZone { NONE, INSIDE, CORNER, ROTATE, DELETE }

/** 把触点转到图层本地坐标后判定命中区：右上角=删除，底中=旋转，其余三角=缩放，内部=移动。 */
internal fun hitZone(layer: ProjectLayer, px: Float, py: Float, touchR: Float): SelZone {
    val cx = layer.x + layer.w / 2f
    val cy = layer.y + layer.h / 2f
    val rad = Math.toRadians(-layer.rotation.toDouble())
    val cosR = kotlin.math.cos(rad).toFloat()
    val sinR = kotlin.math.sin(rad).toFloat()
    val dx = px - cx
    val dy = py - cy
    val lx = (dx * cosR - dy * sinR) + cx
    val ly = (dx * sinR + dy * cosR) + cy
    val left = cx - layer.w / 2f
    val top = cy - layer.h / 2f
    val right = cx + layer.w / 2f
    val bottom = cy + layer.h / 2f

    fun near(x: Float, y: Float) = kotlin.math.hypot((lx - x).toDouble(), (ly - y).toDouble()) <= touchR

    if (near(cx, bottom)) return SelZone.ROTATE
    if (near(left, top) || near(right, top) || near(right, bottom) || near(left, bottom)) return SelZone.CORNER
    if (lx >= left && lx <= right && ly >= top && ly <= bottom) return SelZone.INSIDE
    return SelZone.NONE
}

/** 选中图层的外观：虚线框 + 四角缩放手柄 + 底中旋转手柄 + 右上删除徽章。 */
@Composable
internal fun SelectionOverlay(
    layer: ProjectLayer?,
    k: Float,
    revision: Int,
    modifier: Modifier = Modifier,
) {
    if (layer == null || k <= 0f) return
    val density = LocalDensity.current
    val stroke = with(density) { 1.5.dp.toPx() }
    val handle = with(density) { 6.dp.toPx() }
    Canvas(modifier) {
        // revision 只是为了让画布在图层数据变化时重画（图层是同一个实例，Compose 看不出来）
        if (revision < 0) return@Canvas
        val cx = (layer.x + layer.w / 2f) * k
        val cy = (layer.y + layer.h / 2f) * k
        val w = layer.w * k
        val h = layer.h * k
        withTransform({ rotate(layer.rotation, Offset(cx, cy)) }) {
            val tl = Offset(cx - w / 2f, cy - h / 2f)
            val size = Size(w, h)
            drawRect(
                color = Color.White.copy(alpha = 0.95f),
                topLeft = tl,
                size = size,
                style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))),
            )
            // 四角：缩放
            listOf(
                Offset(tl.x, tl.y), Offset(tl.x + w, tl.y),
                Offset(tl.x, tl.y + h), Offset(tl.x + w, tl.y + h),
            ).forEach { p ->
                drawRect(
                    color = Color.White,
                    topLeft = Offset(p.x - handle, p.y - handle),
                    size = Size(handle * 2f, handle * 2f),
                )
            }
            // 底中：旋转
            val rot = Offset(cx, tl.y + h)
            drawCircle(Color.White, radius = handle * 1.2f, center = rot)
            drawCircle(Color.Black.copy(alpha = 0.35f), radius = handle * 1.2f, center = rot, style = Stroke(width = stroke))
        }
    }
}

/**
 * 编辑器的预览与手势：
 * 单指拖动手柄 = 缩放（三角）/ 旋转（底中）/ 删除（右上），指框内 = 移动；
 * 点空白 = 取消选中；双指仍可缩放旋转（兼容习惯）。
 */
@Composable
internal fun EditorPreview(
    ctrl: AppController,
    layers: List<StageLayer>,
    designW: Int,
    designH: Int,
    modifier: Modifier = Modifier,
    /** true 才允许直接拖拽/缩放/旋转图层；编辑器主页的预览是纯预览（编辑在「更多设置」里） */
    editable: Boolean = false,
    /** true 才跟随手机倾斜；画布默认静止（方便摆放） */
    tiltEnabled: Boolean = true,
    /** 图层数据版本号，透传给舞台用于重画 */
    revision: Int = 0,
) {
    var boxW by remember { mutableStateOf(0f) }
    val k = if (designW > 0 && boxW > 0) boxW / designW else 1f
    val touchR = with(LocalDensity.current) { 22.dp.toPx() } / (if (k > 0f) k else 1f)

    val gestureMod = if (editable) {
        Modifier.pointerInput(layers.size, ctrl.selectedLayerId, k) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val p = down.position / k
                    val selected = ctrl.selectedLayer
                    var mode = SelZone.NONE
                    if (selected != null) {
                        when (hitZone(selected, p.x, p.y, touchR)) {
                            SelZone.ROTATE -> mode = SelZone.ROTATE
                            SelZone.CORNER -> mode = SelZone.CORNER
                            SelZone.INSIDE -> mode = SelZone.INSIDE
                            else -> Unit
                        }
                    }
                    if (mode == SelZone.NONE) {
                        // 画布上只编辑"当前选中的图层"：不在它身上就什么都不做
                        // （否则压在下面的图层几乎选不中；选层请用编辑器里的图层清单）
                        down.consume()
                        return@awaitEachGesture
                    }

                    val start = ctrl.selectedLayer?.copy() ?: return@awaitEachGesture
                    val center = Offset(start.x + start.w / 2f, start.y + start.h / 2f)
                    val startVec = p - center
                    val startDist = startVec.getDistance().coerceAtLeast(1f)
                    val startAngle = kotlin.math.atan2(startVec.y.toDouble(), startVec.x.toDouble())
                    ctrl.beginEdit()

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val q = change.position / k
                        when (mode) {
                            SelZone.INSIDE -> ctrl.updateSelected { l ->
                                l.x = start.x + (q.x - p.x)
                                l.y = start.y + (q.y - p.y)
                            }

                            SelZone.CORNER -> {
                                val s = ((q - center).getDistance() / startDist).coerceIn(0.05f, 20f)
                                ctrl.updateSelected { l ->
                                    l.w = (start.w * s).coerceAtLeast(8f)
                                    l.h = (start.h * s).coerceAtLeast(8f)
                                    l.x = center.x - l.w / 2f
                                    l.y = center.y - l.h / 2f
                                }
                            }

                            SelZone.ROTATE -> {
                                val angle = kotlin.math.atan2((q - center).y.toDouble(), (q - center).x.toDouble())
                                ctrl.updateSelected { l ->
                                    l.rotation = start.rotation + Math.toDegrees(angle - startAngle).toFloat()
                                }
                            }

                            else -> Unit
                        }
                        change.consume()
                    }
                    ctrl.clampSelected()
                }
            }
    } else {
        Modifier
    }

    Box(
        // 预览＝屏幕：没放东西的地方是黑的（不是主题底色）
        modifier = modifier
            .background(Color.Black)
            .onSizeChanged { boxW = it.width.toFloat() }
            .then(gestureMod),
    ) {
        StagePreview(
            layers = layers,
            designW = designW,
            designH = designH,
            modifier = Modifier.fillMaxSize().clipToBounds(),
            tiltEnabled = tiltEnabled,
            revision = revision,
        )
        if (editable) SelectionOverlay(ctrl.selectedLayer, k, ctrl.revision, Modifier.fillMaxSize())
    }
}


/** 生成小缩略图（只解码到 ~128px，避免为列表解码大图）。 */
internal fun thumbOf(project: Project, rel: String): android.graphics.Bitmap? = runCatching {
    val f = java.io.File(Project.imagesDir(project.name), rel)
    if (!f.isFile) return null
    val probe = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(f.absolutePath, probe)
    var sample = 1
    while (probe.outWidth / (sample * 2) >= 128 && probe.outHeight / (sample * 2) >= 128) sample *= 2
    android.graphics.BitmapFactory.decodeFile(
        f.absolutePath,
        android.graphics.BitmapFactory.Options().apply { inSampleSize = sample },
    )
}.getOrNull()

internal fun bandLabel(band: String) = when (band) {
    "front" -> "时钟前"
    "top" -> "最顶层"
    else -> "时钟后"
}

@Composable
private fun ProjectsTab(ctrl: AppController) {
    val context = LocalContext.current
    var tab by remember { mutableStateOf(0) } // 0=项目 1=海报包

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶栏：标题 + 右上角"新建空白项目"
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("项目", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                ctrl.newBlankProject()
                tab = 0
            }) { Text("＋", fontSize = 22.sp) }
        }

        TabRow(selectedTabIndex = tab) {
            listOf("项目", "海报包").forEachIndexed { i, label ->
                Tab(
                    selected = tab == i,
                    onClick = { tab = i; ctrl.scan() },
                    text = { Text(label, fontSize = 14.sp) },
                )
            }
        }

        if (!ctrl.hasStorage) {
            Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("需要「全部文件访问」权限", fontWeight = FontWeight.Medium)
                    Text(
                        "项目和海报包都在 /sdcard/Download/gravitywall/，需要该权限才能读取。",
                        fontSize = 12.sp,
                    )
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            runCatching {
                                context.startActivity(com.gravitywall.lock.data.PackStore.storageAccessIntent(context))
                            }
                        }) { Text("去授权") }
                        OutlinedButton(onClick = { ctrl.scan() }) { Text("重新检查") }
                    }
                }
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            if (tab == 0) {
                ProjectList(ctrl)
            } else {
                PackList(ctrl)
                // 右下角：重新扫描
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp)
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .clickable { ctrl.scan() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("↻", fontSize = 22.sp, color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
        }
    }
}

/** 项目标签页：点一行即打开，长按出菜单（重命名 / 删除）。 */
@Composable
private fun ProjectList(ctrl: AppController) {
    var menuFor by remember { mutableStateOf<String?>(null) }
    var renameFor by remember { mutableStateOf<String?>(null) }
    var deleteFor by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        if (ctrl.projectNames.isEmpty()) {
            Text(
                "（还没有项目：点右上角 ＋ 新建，或到「海报包」里导入一个）",
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        ctrl.projectNames.forEach { name ->
            val current = ctrl.project?.name == name
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .combinedClickable(
                        onClick = { ctrl.openProject(name) },
                        onLongClick = { menuFor = name },
                    ),
                colors = if (current) {
                    androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    )
                } else {
                    androidx.compose.material3.CardDefaults.cardColors()
                },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        name,
                        fontSize = 14.sp,
                        fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (current) "编辑中" else "打开",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                DropdownMenu(expanded = menuFor == name, onDismissRequest = { menuFor = null }) {
                    DropdownMenuItem(
                        text = { Text("重命名", fontSize = 13.sp) },
                        onClick = {
                            menuFor = null
                            renameFor = name
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("删除", fontSize = 13.sp) },
                        onClick = {
                            menuFor = null
                            deleteFor = name
                        },
                    )
                }
            }
        }
        Box(Modifier.height(80.dp))
    }

    renameFor?.let { name ->
        ProjectNameDialog(
            title = "重命名项目",
            initial = name,
            onDismiss = { renameFor = null },
            onConfirm = { v ->
                ctrl.renameProjectByName(name, v)
                renameFor = null
            },
        )
    }
    deleteFor?.let { name ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text("删除项目") },
            text = { Text("确定删除「$name」吗？目录会一起删掉，无法撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    ctrl.deleteProject(name)
                    deleteFor = null
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text("取消") } },
        )
    }
}

/** 项目改名对话框。 */
@Composable
private fun ProjectNameDialog(
    title: String,
    initial: String,
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
                label = { Text("项目名") },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.trim().isNotEmpty()) { Text("改名") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 海报包标签页：点「导入为项目」即可编辑。 */
@Composable
private fun PackList(ctrl: AppController) {
    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        if (ctrl.packs.isEmpty()) {
            Text(
                "（这个目录里还没有 .tendies 海报包）",
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        ctrl.packs.forEach { pack ->
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(pack.name, fontSize = 14.sp)
                        Text(
                            "${pack.sizeKb} KB",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = {
                        if (pack.name.lowercase().endsWith(".gwp")) {
                            ctrl.importProjectArchive(java.io.File(pack.path))
                        } else {
                            ctrl.importPackAsProject(pack)
                        }
                    }) { Text("导入为项目", fontSize = 12.sp) }
                }
            }
        }
        Box(Modifier.height(80.dp))
    }
}

@Composable
private fun PackRow(pack: PackEntry, selected: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = if (selected) {
            androidx.compose.material3.CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            )
        } else {
            androidx.compose.material3.CardDefaults.cardColors()
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.fillMaxWidth(0.8f)) {
                Text(pack.name, fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                Text("${pack.sizeKb} KB", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onClick) { Text(if (selected) "已选" else "选择") }
        }
    }
}

@Composable
private fun SettingsTab(ctrl: AppController) {
    val cfg = ctrl.config
    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        Text(
            "设置",
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
        )
        // Y 轴语义
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.fillMaxWidth(0.75f)) {
                Text("Y 轴向上（CAML 几何）", fontWeight = FontWeight.Medium)
                Text("海报包的默认语义；关掉会整体上下颠倒", fontSize = 11.sp)
            }
            Switch(checked = cfg.flipY, onCheckedChange = { ctrl.update(cfg.copy(flipY = it)) })
        }

        // 幅度上限
        Text("位移上限：${(cfg.amplitudeCap * 100).toInt()}% 屏宽", fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 16.dp))
        Slider(
            value = cfg.amplitudeCap,
            onValueChange = { ctrl.update(cfg.copy(amplitudeCap = it)) },
            valueRange = 0.05f..1.0f,
        )
        Text("社区包常给到 ±500pt（远超屏幕），大位移本来是配合裁剪窗口用的；上限太小会「动不起来」，太大可能让未裁剪的层飞出屏幕。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        // 参数模式（默认系数）
        val ctx = LocalContext.current
        val screenW = ctx.resources.displayMetrics.widthPixels
        Text("参数模式", fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
            FilterChip(
                selected = cfg.paramMode == "coefficient",
                onClick = { ctrl.update(cfg.copy(paramMode = "coefficient")) },
                label = { Text("影响系数（默认）") },
            )
            FilterChip(
                selected = cfg.paramMode == "pixel",
                onClick = { ctrl.update(cfg.copy(paramMode = "pixel")) },
                label = { Text("绝对像素") },
            )
        }
        Text(
            if (cfg.paramMode == "coefficient") {
                "用系数表达运动：远景 0.2~0.4 / 中景 0.5~0.7 / 主体 1.0 / 最前景 1.2~1.6，负值＝反向。导入海报包时会把像素位移换算成系数。"
            } else {
                "用绝对像素表达运动（min/max 区间），适合精确复刻某个包。"
            },
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // 视差幅度基准：只在像素模式下暴露（系数模式固定 3%）
        if (cfg.paramMode == "pixel") {
            Text(
                "视差幅度基准：屏宽 ${(cfg.amplitude * 100).toInt()}%（约 ${(screenW * cfg.amplitude).toInt()}px）",
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 12.dp),
            )
            Slider(
                value = cfg.amplitude,
                onValueChange = { ctrl.update(cfg.copy(amplitude = it)) },
                valueRange = 0f..0.10f,
            )
            Text(
                "这是「系数 1.0 的图层最大位移」的参照值；系数模式下固定为 3%、不显示。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // 灵敏度与平滑（模块侧读取，广播重建后生效）
        Text(
            "满量程角度：${"%.1f".format(cfg.fullScaleDeg)}°（越小越灵敏）",
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 16.dp),
        )
        Slider(
            value = cfg.fullScaleDeg,
            onValueChange = { ctrl.update(cfg.copy(fullScaleDeg = it)) },
            valueRange = 3f..25f,
        )
        Text(
            "倾斜多少度达到最大位移。10° 适合手持；放到 6° 以下会「一动就满」，20° 以上显得迟钝。",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(
            "平滑度：${(cfg.smooth * 100).toInt()}%（越大越跟手）",
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 12.dp),
        )
        Slider(
            value = cfg.smooth,
            onValueChange = { ctrl.update(cfg.copy(smooth = it)) },
            valueRange = 0.05f..1f,
        )
        Text(
            "每帧向目标位置逼近的比例。25% 柔和跟手；调到 60% 以上会有些抖，10% 以下会很「飘」。",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // 解锁后隐藏图层的延迟
        Text(
            "解锁后隐藏图层：${cfg.unlockHideDelayMs} ms",
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 16.dp),
        )
        Slider(
            value = cfg.unlockHideDelayMs.toFloat(),
            onValueChange = { ctrl.update(cfg.copy(unlockHideDelayMs = it.toInt())) },
            valueRange = 100f..1500f,
        )
        Text(
            "解锁后过这么久，就把注入的图层藏起来（桌面与控制中心采样不到它们）。" +
                "调太小：图层可能在解锁动画中途消失；调太大：解锁后马上拉控制中心可能会闪一下。",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(Modifier.padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { ctrl.applyToLockScreen() }) { Text("应用设置") }
        }

        Text("还原", fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp))
        Text(
            "卸下所有注入图层、清空配置、时钟不再跟随倾斜；若之前备份过锁屏壁纸会一并还原（没备份的话需要你手动重设壁纸）。",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.padding(vertical = 8.dp)) {
            OutlinedButton(onClick = { ctrl.restoreSystem() }) { Text("还原系统原状") }
        }
        Box(Modifier.height(24.dp))
    }
}

@Composable
private fun AboutTab(ctrl: AppController) {
    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        Text(
            "关于",
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
        )
        Text(
            if (ctrl.serviceUp) "框架已连接" else "框架未连接（模块未启用或 LSPosed 未运行）",
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text("实时：${ctrl.live.ifEmpty { "（锁屏后点亮并倾斜手机）" }}", fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            OutlinedButton(onClick = { ctrl.refreshStatus() }) { Text("刷新") }
            OutlinedButton(onClick = { ctrl.scan() }) { Text("重扫海报包") }
        }
        Text("诊断报告", fontWeight = FontWeight.Medium)
        Text(
            text = ctrl.report.ifEmpty { "（暂无报告）" },
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(top = 4.dp, bottom = 24.dp),
        )
    }
}
