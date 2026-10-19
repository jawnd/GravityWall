package com.gravitywall.lock

import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.gravitywall.lock.data.ImportSummary
import com.gravitywall.lock.data.PackEntry
import com.gravitywall.lock.data.PackStore
import com.gravitywall.lock.data.Project
import com.gravitywall.lock.data.ProjectLayer
import com.gravitywall.lock.data.RuntimeConfig
import io.github.libxposed.service.XposedService

/**
 * 应用侧控制器：扫描海报包、应用内导入（供预览）、把配置写给模块、触发模块重建、设置玻璃源壁纸。
 *
 * 职责划分：应用负责界面与预览；模块负责在 SystemUI 里渲染与其它特权操作。
 * 两者靠"远程偏好（配置）+ 广播（指令）"通信。
 */
internal class AppController(private val context: Context) {

    var packs by mutableStateOf<List<PackEntry>>(emptyList())
        private set
    var selected by mutableStateOf<PackEntry?>(null)
        private set
    var summary by mutableStateOf<ImportSummary?>(null)
        private set
    var config by mutableStateOf(PackStore.readConfigFile() ?: RuntimeConfig())
        private set
    var report by mutableStateOf("")
        private set
    var live by mutableStateOf("")
        private set
    var status by mutableStateOf("就绪")
        private set
    var serviceUp by mutableStateOf(false)
        private set
    var hasStorage by mutableStateOf(PackStore.hasStorageAccess())
        private set

    // ---- 编辑器状态 ----
    var projectNames by mutableStateOf<List<String>>(emptyList())
        private set
    var project by mutableStateOf<Project?>(null)
        private set
    var selectedLayerId by mutableStateOf<String?>(null)
        private set

    /** 每次图层数据变化就 +1：供画布上的选中框之类的"看不见状态"跟着重画。 */
    var revision by mutableStateOf(0)
        private set

    val selectedLayer: ProjectLayer?
        get() = project?.layers?.firstOrNull { it.id == selectedLayerId }

    // ---- 撤销 / 重做 ----
    private val undoStack = ArrayDeque<String>()
    private val redoStack = ArrayDeque<String>()
    private var lastSnapshotAt = 0L
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    /**
     * 开始一次编辑：用 800ms 节流做快照——拖动/缩放这类连续操作
     * 只会在手势开始时留下一条撤销记录。
     */
    fun beginEdit() {
        val p = project ?: return
        val now = System.currentTimeMillis()
        // 新编辑即使被合并进上一条快照，也必须使重做失效。
        redoStack.clear()
        canRedo = false
        if (now - lastSnapshotAt < 800 && undoStack.isNotEmpty()) return
        lastSnapshotAt = now
        undoStack.addLast(p.toJson())
        while (undoStack.size > 40) undoStack.removeFirst()
        redoStack.clear()
        canUndo = true
        canRedo = false
    }

    private fun clearHistory() {
        undoStack.clear()
        redoStack.clear()
        lastSnapshotAt = 0L
        canUndo = false
        canRedo = false
    }

    private fun restore(json: String) {
        val p = Project.fromJson(json) ?: return
        pendingSave?.let { mainHandler.removeCallbacks(it) }
        pendingSave = null
        lastSnapshotAt = 0L
        project = p
        revision++
        if (p.layers.none { it.id == selectedLayerId }) selectedLayerId = p.layers.firstOrNull()?.id
        Project.save(p)
        summary = Project.toSummary(p, screenW(), screenH(), screenW() * config.amplitude)
    }

    fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        project?.let { redoStack.addLast(it.toJson()) }
        restore(prev)
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
        status = "已撤销"
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        project?.let { undoStack.addLast(it.toJson()) }
        restore(next)
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
        status = "已重做"
    }

    /** 与相邻图层交换层级（按 z 排序后换位，再统一编号）。 */
    fun moveLayer(up: Boolean) {
        val p = project ?: return
        val l = selectedLayer ?: return
        val sorted = p.layers.sortedBy { it.z }
        val idx = sorted.indexOfFirst { it.id == l.id }
        val other = if (up) sorted.getOrNull(idx + 1) else sorted.getOrNull(idx - 1)
        if (other == null) {
            status = "已经在最${if (up) "上" else "下"}面"
            return
        }
        beginEdit()
        val tmp = l.z
        l.z = other.z
        other.z = tmp
        p.layers.sortedBy { it.z }.forEachIndexed { i, item -> item.z = i }
        Project.save(p)
        summary = Project.toSummary(p, screenW(), screenH(), screenW() * config.amplitude)
        status = "已${if (up) "上移" else "下移"}「${l.name}」"
    }

    private var service: XposedService? = null

    fun onService(s: XposedService?) {
        service = s
        serviceUp = s != null
        refreshStatus()
    }

    private fun prefs() = runCatching { service?.getRemotePreferences(State.GROUP) }.getOrNull()

    fun refreshStatus() {
        val p = prefs()
        report = p?.getString(State.KEY_REPORT, "") ?: ""
        live = p?.getString(State.KEY_LIVE, "") ?: ""
        hasStorage = PackStore.hasStorageAccess()
    }

    fun scan() {
        hasStorage = PackStore.hasStorageAccess()
        if (!hasStorage) {
            status = "需要「全部文件访问」权限才能读取海报包"
            return
        }
        val moved = PackStore.migrateLegacyPacks()
        packs = PackStore.packs()
        projectNames = Project.list()
        val tail = if (moved > 0) "（已把 $moved 个包移入 packs/）" else ""
        status = "扫描到 ${packs.size} 个海报包 / 作品包、${projectNames.size} 个项目$tail"
        if (selected == null) {
            PackStore.readConfigFile()?.pack?.let { path ->
                selected = packs.firstOrNull { it.path == path }
            }
        }
    }

    // ------------------------------------------------------------------ 编辑器

    private fun screenW() = com.gravitywall.lock.ui.realScreen(context).first
    private fun screenH() = com.gravitywall.lock.ui.realScreen(context).second

    fun newBlankProject() {
        flushSave()
        val name = Project.uniqueName("项目${Project.list().size + 1}")
        clearHistory()
        Project.clearBitmapCache()
        project = Project.blank(name, screenW(), screenH())
        Project.save(project!!)
        projectNames = Project.list()
        selectedLayerId = project?.layers?.firstOrNull()?.id
        summary = project?.let { Project.toSummary(it, screenW(), screenH(), screenW() * config.amplitude) }
        status = "已新建项目：$name"
    }

    fun openProject(name: String) {
        flushSave()
        val p = Project.load(name)
        if (p == null) {
            status = "打开失败：$name"
            return
        }
        clearHistory()
        Project.clearBitmapCache()
        project = p
        selectedLayerId = p.layers.firstOrNull()?.id
        summary = Project.toSummary(p, screenW(), screenH(), screenW() * config.amplitude)
        status = "已打开项目：$name（${p.layers.size} 层）"
    }

    /** 把海报包导入成可编辑项目。 */
    fun importPackAsProject(pack: PackEntry) {
        selected = pack
        status = "正在把 ${pack.name} 导入为项目 …"
        Thread {
            val p = runCatching { Project.fromPack(pack.path, screenW(), screenH(), config) }.getOrNull()
            post {
                if (p == null) {
                    status = "导入失败（可能不是 CA 类海报）"
                    return@post
                }
                flushSave()
                clearHistory()
                Project.clearBitmapCache()
                Project.save(p)
                project = p
                selectedLayerId = p.layers.firstOrNull()?.id
                projectNames = Project.list()
                summary = Project.toSummary(p, screenW(), screenH(), screenW() * config.amplitude)
                status = "已导入为项目：${p.name}（${p.layers.size} 层）"
            }
        }.start()
    }

    fun selectLayer(id: String) {
        selectedLayerId = id
    }

    fun clearSelection() {
        selectedLayerId = null
    }

    fun toggleVisible(id: String) {
        val l = project?.layers?.firstOrNull { it.id == id } ?: return
        beginEdit()
        l.visible = !l.visible
        revision++
        project?.let {
            Project.save(it)
            summary = Project.toSummary(it, screenW(), screenH(), screenW() * config.amplitude)
        }
        status = if (l.visible) "已显示「${l.name}」" else "已隐藏「${l.name}」"
    }

    /** 软边界：图层不能被完全拖出画布（至少留 15% 可见）。 */
    fun clampSelected() {
        val l = selectedLayer ?: return
        val marginX = minOf(l.w * 0.15f, screenW() * 0.5f)
        val marginY = minOf(l.h * 0.15f, screenH() * 0.5f)
        l.x = l.x.coerceIn(-l.w + marginX, screenW() - marginX)
        l.y = l.y.coerceIn(-l.h + marginY, screenH() - marginY)
        updateSelected { }
        flushSave()
    }

    fun updateSelected(block: (ProjectLayer) -> Unit) {
        val l = selectedLayer ?: return
        block(l)
        revision++
        // 就地改舞台上这一层：不重建位图、不重算全部图层、不写盘（滑块才跟手）
        summary?.layers?.firstOrNull { it.id == l.id }?.let { sl ->
            sl.dst.set(l.x, l.y, l.x + l.w, l.y + l.h)
            sl.alpha = l.alpha
            sl.rotation = l.rotation
            sl.fillColor = if (l.image == null) l.color else null
            sl.fill = l.fill
            val motion = l.resolvedMotion(screenW() * config.amplitude)
            sl.minX = motion[0]; sl.maxX = motion[1]
            sl.minY = motion[2]; sl.maxY = motion[3]
        }
        project?.let { saveDeferred(it) }
    }

    fun addColorLayer(color: Int) {
        val p = project ?: return
        beginEdit()
        val layer = ProjectLayer(
            name = "色块${p.layers.size + 1}", color = color,
            x = screenW() * 0.2f, y = screenH() * 0.4f, w = screenW() * 0.6f, h = screenH() * 0.2f,
            band = p.defaultBand, z = (p.layers.maxOfOrNull { it.z } ?: 0) + 1,
            coefX = 0f, coefY = 0f,
        )
        p.layers.add(layer)
        selectedLayerId = layer.id
        Project.save(p)
        summary = Project.toSummary(p, screenW(), screenH(), screenW() * config.amplitude)
        status = "已添加纯色层"
    }

    fun deleteSelected() {
        val p = project ?: return
        val l = selectedLayer ?: return
        beginEdit()
        p.layers.remove(l)
        selectedLayerId = p.layers.firstOrNull()?.id
        Project.save(p)
        summary = Project.toSummary(p, screenW(), screenH(), screenW() * config.amplitude)
        status = "已删除「${l.name}」"
    }

    fun addImageLayer(uri: android.net.Uri) {
        val p = project ?: return
        status = "正在导入图片 …"
        Thread {
            val rel = runCatching {
                val dir = Project.imagesDir(p.name).apply { mkdirs() }
                val name = "img_${System.currentTimeMillis()}.png"
                val out = java.io.File(dir, name)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    out.outputStream().use { input.copyTo(it) }
                }
                if (out.isFile && out.length() > 0) name else null
            }.getOrNull()
            if (rel == null) {
                post { status = "图片导入失败" }
                return@Thread
            }
            val bounds = runCatching {
                val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(java.io.File(Project.imagesDir(p.name), rel).absolutePath, o)
                o.outWidth to o.outHeight
            }.getOrDefault(1 to 1)
            post {
                if (project !== p) return@post
                if (bounds.first <= 0 || bounds.second <= 0) {
                    status = "图片导入失败：无法识别图片"
                    return@post
                }
                beginEdit()
                val scale = minOf(screenW().toFloat() / bounds.first, screenH().toFloat() / bounds.second, 1f)
                val w = bounds.first * scale
                val h = bounds.second * scale
                val layer = ProjectLayer(
                    name = "图片${p.layers.size + 1}", image = rel,
                    x = (screenW() - w) / 2f, y = (screenH() - h) / 2f, w = w, h = h,
                    band = p.defaultBand, z = (p.layers.maxOfOrNull { it.z } ?: 0) + 1,
                    coefX = 0f, coefY = 0f,
                )
                p.layers.add(layer)
                selectedLayerId = layer.id
                Project.save(p)
                summary = Project.toSummary(p, screenW(), screenH(), screenW() * config.amplitude)
                status = "已添加图片层（${bounds.first}×${bounds.second}）"
            }
        }.start()
    }

    // ------------------------------------------------------------------ v0.6 · 图层清单与项目级设置

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingSave: Runnable? = null

    /**
     * 拉滑杆/拖动时每个事件都写一次 JSON 会把主线程拖住（表现为"拉不动，过一会儿才跳"）。
     * 这里攒 250ms 写一次；最后一次也一定会写（延迟回调里落盘）。
     * 离散操作（新增/删除/重排/应用/保存）仍走 [commit] 立即写。
     */
    private fun saveDeferred(p: Project) {
        pendingSave?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable { pendingSave = null; Project.save(p) }
        pendingSave = r
        mainHandler.postDelayed(r, 250)
    }

    /** 立即把待写的改动落盘（导出/应用/还原前必须先落盘）。 */
    fun flushSave() {
        pendingSave?.let { mainHandler.removeCallbacks(it); pendingSave = null }
        project?.let { Project.save(it) }
    }

    /** 落盘 + 刷新预览摘要（编辑器里所有改动都走这里，保证预览立即跟上）。 */
    private fun commit(p: Project) {
        revision++
        Project.save(p)
        summary = Project.toSummary(p, screenW(), screenH(), screenW() * config.amplitude)
    }

    /** 手动保存（编辑器里每次改动其实都已落盘，这里给一个明确的反馈）。 */
    fun saveNow() {
        val p = project ?: return
        flushSave()
        commit(p)
        status = "已保存「${p.name}」（${p.layers.size} 层）"
    }

    private fun bandLabel(band: String?): String = when (band) {
        "front" -> "时钟前"
        "top" -> "最顶层"
        else -> "时钟后"
    }

    /**
     * 图层清单（自上而下 = 压叠自上而下）：
     * 最顶层带 → 分钟行 → 时钟前带 → 小时行 → 时钟后带。
     * 行 id 与图层 id 同域，两行系统时钟用 [MARK_MINUTE] / [MARK_HOUR]。
     */
    fun rows(): List<String> {
        val p = project ?: return emptyList()
        fun idsOf(band: String) = p.layers.filter { it.band == band }
            .sortedByDescending { it.z }.map { it.id }
        return idsOf("top") + MARK_MINUTE + idsOf("front") + MARK_HOUR + idsOf("behind")
    }

    /** 按清单顺序重排：越过哪条时钟行就换带，列表位置决定带内 z（越靠上 z 越大）。 */
    private fun applyRows(rows: List<String>) {
        val p = project ?: return
        var band = "top"
        val order = ArrayList<Pair<String, String>>(p.layers.size)
        for (r in rows) {
            when (r) {
                MARK_MINUTE -> band = "front"
                MARK_HOUR -> band = "behind"
                else -> order.add(r to band)
            }
        }
        if (order.size != p.layers.size) return
        val n = order.size
        order.forEachIndexed { i, (id, b) ->
            p.layers.firstOrNull { it.id == id }?.let { it.band = b; it.z = n - i }
        }
    }

    /** 在清单里移动某层 delta 行（拖拽与"上移/下移"共用；可跨过时钟行换带）。 */
    fun moveRow(id: String, delta: Int) {
        val p = project ?: return
        val rows = rows().toMutableList()
        val from = rows.indexOf(id)
        if (from < 0 || delta == 0) return
        val to = (from + delta).coerceIn(0, rows.size - 1)
        if (to == from) return
        rows.add(to, rows.removeAt(from))
        beginEdit()
        applyRows(rows)
        commit(p)
        status = "「${p.layers.firstOrNull { it.id == id }?.name}」已移到${bandLabel(p.layers.firstOrNull { it.id == id }?.band)}"
    }

    /** 图层的自然宽高比：有图片用图片本身的比例，纯色层用当前框的比例。 */
    private fun aspectOf(l: ProjectLayer): Float {
        val p = project ?: return 1f
        val bmp = p.bitmapOf(l, screenW(), screenH())
        if (bmp != null && bmp.height > 0) return bmp.width.toFloat() / bmp.height
        return if (l.h > 0f) l.w / l.h else 1f
    }

    /** 改宽度：宽高锁定开启时，高度按图层自身比例跟着走（不变形）。 */
    fun setLayerWidth(v: Float) {
        val l = selectedLayer ?: return
        beginEdit()
        val keep = project?.keepAspect ?: true
        val r = aspectOf(l)
        updateSelected { x ->
            x.w = v.coerceAtLeast(8f)
            if (keep) x.h = (x.w / r).coerceAtLeast(8f)
        }
    }

    /** 改高度：宽高锁定开启时，宽度按图层自身比例跟着走。 */
    fun setLayerHeight(v: Float) {
        val l = selectedLayer ?: return
        beginEdit()
        val keep = project?.keepAspect ?: true
        val r = aspectOf(l)
        updateSelected { x ->
            x.h = v.coerceAtLeast(8f)
            if (keep) x.w = (x.h * r).coerceAtLeast(8f)
        }
    }

    fun toggleKeepAspect() {
        val p = project ?: return
        p.keepAspect = !p.keepAspect
        revision++
        Project.save(p)
        status = if (p.keepAspect) "宽高已锁定：等比缩放" else "宽高已解锁：可自由拉伸"
    }

    fun renameLayer(id: String, name: String) {
        val p = project ?: return
        val l = p.layers.firstOrNull { it.id == id } ?: return
        val clean = name.trim().ifEmpty { l.name }
        if (clean == l.name) return
        beginEdit()
        l.name = clean
        commit(p)
        status = "已重命名为「$clean」"
    }

    /** 重置属性：居中、旋转归零、不透明度 100%、视差归零；图片 / 颜色 / 尺寸保留。 */
    fun resetLayerProps(id: String) {
        val p = project ?: return
        val l = p.layers.firstOrNull { it.id == id } ?: return
        beginEdit()
        l.x = (screenW() - l.w) / 2f
        l.y = (screenH() - l.h) / 2f
        l.rotation = 0f
        l.alpha = 255
        l.coefX = 0f
        l.coefY = 0f
        l.minX = 0f; l.maxX = 0f; l.minY = 0f; l.maxY = 0f
        commit(p)
        status = "已重置「${l.name}」的位置与视差（图片保留）"
    }

    /** 新建遮罩图层（预置形状）：默认套在目标图层的框上，可再拖/改。 */
    fun addMaskLayer(shape: String) {
        val p = project ?: return
        beginEdit()
        val target = selectedLayer?.takeIf { it.maskShape == null }
            ?: p.layers.lastOrNull { it.maskShape == null }
        val x = target?.x ?: (screenW() * 0.2f)
        val y = target?.y ?: (screenH() * 0.35f)
        val w = target?.w ?: (screenW() * 0.6f)
        val h = target?.h ?: (screenH() * 0.3f)
        val layer = ProjectLayer(
            name = "遮罩${p.layers.count { it.maskShape != null } + 1}",
            x = x, y = y, w = w, h = h,
            band = target?.band ?: p.defaultBand,
            z = (p.layers.maxOfOrNull { it.z } ?: 0) + 1,
            coefX = 0f, coefY = 0f,
            maskShape = shape,
            maskTargetId = target?.id,
        )
        p.layers.add(layer)
        selectedLayerId = layer.id
        commit(p)
        status = if (target == null) {
            "已加遮罩层：还要在参数里选「作用于」哪一层"
        } else {
            "已加遮罩层，作用于「${target.name}」"
        }
    }

    /** 导入一张图片当遮罩（按亮度或透明度读取）。 */
    fun addMaskImageLayer(uri: android.net.Uri) {
        val p = project ?: return
        val target = selectedLayer?.takeIf { it.maskShape == null }
            ?: p.layers.lastOrNull { it.maskShape == null }
        status = "正在导入遮罩图 …"
        Thread {
            val rel = runCatching {
                val dir = Project.imagesDir(p.name).apply { mkdirs() }
                val out = java.io.File(dir, "mask_${System.currentTimeMillis()}.png")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    out.outputStream().use { input.copyTo(it) }
                }
                if (out.isFile && out.length() > 0) out.name else null
            }.getOrNull()
            post {
                if (rel == null) {
                    status = "遮罩图导入失败"
                    return@post
                }
                if (project !== p) return@post
                beginEdit()
                val layer = ProjectLayer(
                    name = "遮罩${p.layers.count { it.maskShape != null } + 1}",
                    image = rel,
                    x = target?.x ?: (screenW() * 0.2f),
                    y = target?.y ?: (screenH() * 0.35f),
                    w = target?.w ?: (screenW() * 0.6f),
                    h = target?.h ?: (screenH() * 0.3f),
                    band = target?.band ?: p.defaultBand,
                    z = (p.layers.maxOfOrNull { it.z } ?: 0) + 1,
                    coefX = 0f, coefY = 0f,
                    maskShape = "image",
                    maskLuminance = true,
                    maskTargetId = target?.id,
                )
                p.layers.add(layer)
                selectedLayerId = layer.id
                commit(p)
                status = if (target == null) "已导入遮罩图：还要选「作用于」哪一层" else "已导入遮罩图，作用于「${target.name}」"
            }
        }.start()
    }

    /** 改遮罩作用的目标图层。 */
    fun setMaskTarget(layerId: String, targetId: String?) {
        val p = project ?: return
        val l = p.layers.firstOrNull { it.id == layerId } ?: return
        beginEdit()
        l.maskTargetId = targetId
        commit(p)
        val n = p.layers.firstOrNull { it.id == targetId }?.name
        status = if (n == null) "遮罩改为未指定作用图层" else "遮罩作用于「$n」"
    }

    fun setMaskShape(layerId: String, shape: String) {
        val p = project ?: return
        val l = p.layers.firstOrNull { it.id == layerId } ?: return
        beginEdit()
        l.maskShape = shape
        commit(p)
        status = when (shape) {
            "round" -> "遮罩形状：圆形（横向拉伸就是椭圆）"
            "image" -> "遮罩形状：自定义图片"
            else -> "遮罩形状：方形"
        }
    }

    fun setMaskLuminance(layerId: String, value: Boolean) {
        val p = project ?: return
        val l = p.layers.firstOrNull { it.id == layerId } ?: return
        beginEdit()
        l.maskLuminance = value
        commit(p)
        status = if (value) "遮罩按亮度读取（黑遮白显）" else "遮罩按透明度读取"
    }

    fun setDefaultBand(band: String) {
        val p = project ?: return
        beginEdit()
        p.defaultBand = band
        revision++
        Project.save(p)
        status = "新图层默认落在「${bandLabel(band)}」"
    }

    /** 指定底图（系统壁纸 / 液态玻璃取样源）；传 null 表示不指定。 */
    fun setBaseLayer(id: String?) {
        val p = project ?: return
        beginEdit()
        p.baseLayerId = id
        revision++
        Project.save(p)
        val name = p.layers.firstOrNull { it.id == id }?.name
        status = if (name == null) "已取消指定底图" else "底图已设为「$name」"
    }

    /** 替换某层的图片（位置 / 尺寸保留，方便换上同构图的另一版）。 */
    fun replaceImage(layerId: String, uri: android.net.Uri) {
        val p = project ?: return
        val l = p.layers.firstOrNull { it.id == layerId } ?: return
        status = "正在替换图片 …"
        Thread {
            val rel = runCatching {
                val dir = Project.imagesDir(p.name).apply { mkdirs() }
                val out = java.io.File(dir, "img_${System.currentTimeMillis()}.png")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    out.outputStream().use { input.copyTo(it) }
                }
                if (out.isFile && out.length() > 0) out.name else null
            }.getOrNull()
            post {
                if (rel == null) {
                    status = "替换失败"
                    return@post
                }
                if (project !== p) return@post
                beginEdit()
                l.image = rel
                l.color = null
                commit(p)
                status = "已替换「${l.name}」的图片"
            }
        }.start()
    }

    /** 改名当前项目（编辑器「全局参数」里用）。 */
    fun renameProject(newName: String) {
        val p = project ?: return
        renameProjectByName(p.name, newName)
    }

    /** 按名字改名项目（列表里长按重命名用，不要求它当前打开着）。 */
    fun renameProjectByName(oldName: String, newName: String) {
        val clean = newName.trim()
        if (!Project.isValidName(clean)) {
            status = "项目名不能包含路径分隔符或使用 .、.."
            return
        }
        if (clean == oldName) return
        flushSave()
        if (Project.list().contains(clean)) {
            status = "已有同名项目：$clean"
            return
        }
        val oldDir = Project.projectDir(oldName)
        val newDir = Project.projectDir(clean)
        if (oldDir.isDirectory && !oldDir.renameTo(newDir)) {
            status = "改名失败（项目目录被占用）"
            return
        }
        // 即使改名的项目未打开，也要同步 JSON 内部的名称。
        val renamed = if (project?.name == oldName) project?.apply { name = clean }
            else Project.load(clean)
        renamed?.let { it.name = clean; Project.save(it) }
        if (project === renamed) clearHistory()
        // 正被应用到锁屏的话，配置里的路径要同步，否则模块加载不到
        val applied = config.project == java.io.File(oldDir, "project.json").absolutePath
        if (applied) {
            config = config.copy(project = java.io.File(newDir, "project.json").absolutePath)
            persistConfig()
        }
        revision++
        Project.clearBitmapCache()
        projectNames = Project.list()
        status = "项目已改名为「$clean」" + if (applied) "（锁屏已同步）" else ""
    }

    /** 删除项目（连目录一起）。它是当前打开的、或正被应用的话，一并清干净。 */
    fun deleteProject(name: String) {
        flushSave()
        val wasOpen = project?.name == name
        val dir = Project.projectDir(name)
        val wasApplied = config.project == java.io.File(dir, "project.json").absolutePath
        runCatching { dir.deleteRecursively() }.getOrDefault(false)
        if (dir.exists()) {
            status = "删除失败：$name（目录被占用？）"
            return
        }
        if (wasOpen) {
            clearHistory()
            Project.clearBitmapCache()
            project = null
            selectedLayerId = null
            summary = null
        }
        if (wasApplied) {
            config = config.copy(project = null, pack = null)
            persistConfig()
        }
        projectNames = Project.list()
        status = "已删除项目「$name」" + if (wasApplied) "（锁屏上的作品也已撤下）" else ""
    }

    /**
     * 导出"作品包"（后缀 .gwp，本质就是一个 zip：project.json + images/）。
     * 比海报包轻——不追求 iOS 兼容，只用来在自己设备/备份之间搬运作品。
     */
    fun exportProjectArchive() {
        val p = project ?: return
        flushSave()
        status = "正在导出作品包 …"
        Thread {
            PackStore.PACK_DIR.mkdirs()
            val out = java.io.File(PackStore.PACK_DIR, "${p.name}.gwp")
            val ok = runCatching {
                java.util.zip.ZipOutputStream(out.outputStream().buffered()).use { zip ->
                    zip.putNextEntry(java.util.zip.ZipEntry("project.json"))
                    zip.write(p.toJson().toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                    Project.imagesDir(p.name).listFiles()?.forEach { f ->
                        if (f.isFile) {
                            zip.putNextEntry(java.util.zip.ZipEntry("images/${f.name}"))
                            f.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                    }
                }
                out.isFile && out.length() > 0
            }.getOrDefault(false)
            post {
                status = if (ok) "已导出作品包：${out.name}（在「海报包」页可再次导入）" else "导出作品包失败"
                scan()
            }
        }.start()
    }

    /**
     * 从"文件管理器"选来的文件导入：先拷到缓存里的临时文件（名字用原文件名），
     * 再按扩展名分派——`.tendies` 走海报包导入器，其余（`.gwp`）走作品包导入器。
     */
    fun importFromUri(uri: android.net.Uri) {
        status = "正在导入 …"
        Thread {
            val display = queryDisplayName(uri) ?: "import_${System.currentTimeMillis()}"
            val dir = java.io.File(context.cacheDir, "import").apply { mkdirs() }
            // 上一次导入的临时文件（导入器是异步读的，这里只清旧的）
            dir.listFiles()?.filter { it.lastModified() < System.currentTimeMillis() - 3_600_000L }?.forEach { it.deleteRecursively() }
            val suffix = if (display.endsWith(".tendies", ignoreCase = true)) ".tendies" else ".gwp"
            val stem = display.substringBeforeLast('.').map { c ->
                if (c == '/' || c == '\\' || c.isISOControl()) '_' else c
            }.joinToString("").take(60).ifBlank { "导入作品" }
            val taskDir = java.io.File(dir, java.util.UUID.randomUUID().toString()).apply { mkdirs() }
            val tmp = java.io.File(taskDir, "$stem$suffix")
            val ok = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { input.copyTo(it) }
                }
                tmp.isFile && tmp.length() > 0
            }.getOrDefault(false)
            if (!ok) {
                post { status = "导入失败：读不到所选文件" }
                return@Thread
            }
            if (tmp.name.lowercase().endsWith(".tendies")) {
                importPackAsProject(
                    PackEntry(tmp.name, tmp.absolutePath, tmp.length() / 1024),
                )
            } else {
                importProjectArchive(tmp)
            }
        }.start()
    }

    private fun queryDisplayName(uri: android.net.Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    }.getOrNull()

    /** 把 .gwp 作品包导入成新项目（重名自动加序号）。 */
    fun importProjectArchive(file: java.io.File) {
        status = "正在导入作品包 ${file.name} …"
        Thread {
            val result = runCatching {
                java.util.zip.ZipFile(file).use { zip ->
                    val entry = zip.getEntry("project.json") ?: return@use null
                    val json = zip.getInputStream(entry).bufferedReader().readText()
                    val p = Project.fromJson(json) ?: return@use null
                    val baseName = p.name.ifEmpty { file.nameWithoutExtension }
                    require(Project.isValidName(baseName)) { "Invalid project name" }
                    val target = Project.uniqueName(baseName)
                    Project.imagesDir(target).mkdirs()
                    zip.entries().toList()
                        .filter { it.name.startsWith("images/") && !it.isDirectory }
                        .forEach { e ->
                            val f = java.io.File(Project.imagesDir(target), e.name.substringAfterLast('/'))
                            zip.getInputStream(e).use { input -> f.outputStream().use { input.copyTo(it) } }
                        }
                    p.name = target
                    Project.save(p)
                    target
                }
            }.getOrNull()
            post {
                projectNames = Project.list()
                if (result != null) {
                    openProject(result)
                    status = "已导入作品包：$result"
                } else {
                    status = "导入作品包失败：${file.name}"
                }
            }
        }.start()
    }

    fun saveAs(newName: String) {
        val p = project ?: return
        val clean = newName.trim()
        if (!Project.isValidName(clean)) {
            status = "项目名不能包含路径分隔符或使用 .、.."
            return
        }
        flushSave()
        if (clean == p.name) {
            status = "名称没有变化"
            return
        }
        if (Project.list().contains(clean)) {
            status = "已有同名项目：$clean"
            return
        }
        Project.save(p)
        val ok = runCatching {
            Project.projectDir(p.name).copyRecursively(Project.projectDir(clean), overwrite = false)
        }.getOrDefault(false)
        if (!ok) {
            status = "另存失败（无法复制项目目录）"
            return
        }
        val copy = Project.load(clean)
        if (copy == null) {
            status = "另存失败（副本读不回来）"
            return
        }
        copy.name = clean
        Project.save(copy)
        clearHistory()
        project = copy
        selectedLayerId = copy.layers.firstOrNull()?.id
        projectNames = Project.list()
        summary = Project.toSummary(copy, screenW(), screenH(), screenW() * config.amplitude)
        status = "已另存为「$clean」"
    }

    /**
     * 把某层图片设为锁屏壁纸：应用侧先降采样写到共享目录，再由模块（系统进程）
     * 备份原壁纸后设置——这样"还原"才有东西可还原。
     */
    fun setLockWallpaper(layerId: String?, then: String? = null) {
        val p = project ?: return
        val l = layerId?.let { id -> p.layers.firstOrNull { it.id == id } }
            ?: p.layers.minByOrNull { it.z }
            ?: return
        val bmp = p.bitmapOf(l, screenW(), screenH())
        if (bmp == null) {
            status = "「${l.name}」没有图片，无法设为壁纸"
            return
        }
        status = "正在准备壁纸 …"
        Thread {
            val out = java.io.File(PackStore.DIR, "wallpaper_src.png")
            val ok = runCatching {
                PackStore.DIR.mkdirs()
                out.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                out.isFile && out.length() > 0
            }.getOrDefault(false)
            post {
                if (!ok) {
                    status = "写壁纸文件失败（需要「全部文件访问」权限）"
                    return@post
                }
                runCatching {
                    context.sendBroadcast(Intent(State.ACTION_SET_WALLPAPER).putExtra("path", out.absolutePath))
                }
                status = then ?: "已请求把「${l.name}」设为锁屏壁纸（模块会先备份原来的）"
            }
        }.start()
    }

    /**
     * 应用到锁屏：直接把模块指向项目文件（不经海报包中转），并广播重建；
     * 系统锁屏壁纸仅由用户手动设置。
     */
    fun applyProject() {
        val p = project ?: return
        flushSave()
        val cfg = config.copy(
            pack = null,
            project = java.io.File(Project.projectDir(p.name), "project.json").absolutePath,
        )
        config = cfg
        val json = cfg.toJson()
        val written = runCatching {
            prefs()?.edit()?.putString(State.KEY_RUNTIME_CONFIG, json)?.commit() ?: false
        }.getOrDefault(false)
        val fileOk = PackStore.writeConfigFile(cfg)
        runCatching { context.sendBroadcast(Intent(State.ACTION_PROBE)) }
        status = when {
            written -> "已应用：模块正在重建（灭屏再亮屏即可看到）"
            fileOk -> "已写入配置文件（框架未连接，改动会在下次重建时生效）"
            else -> "应用失败：框架未连接且无文件访问权限"
        }
        // 不自动写系统锁屏壁纸：控制中心的背景读的就是"锁屏壁纸"，写了它通知中心就会显示作品。
        // 只有用户在「全局参数」里手动点「设为锁屏壁纸」时才写（那时玻璃最准，代价是控制中心会跟着变）。
    }

    /** 导出成海报包，并把配置指向它（应用内也能直接看到结果）。 */
    fun exportProject(apply: Boolean) {
        val p = project ?: return
        flushSave()
        status = "正在导出海报包 …"
        Thread {
            PackStore.PACK_DIR.mkdirs()
            val out = java.io.File(PackStore.PACK_DIR, "${p.name}.tendies")
            val r = runCatching {
                com.gravitywall.lock.export.PackWriter.write(
                    out, p, screenW(), screenH(), config.amplitude * screenW(),
                )
            }.getOrElse { com.gravitywall.lock.export.PackWriter.Result(false, "导出异常：$it") }
            post {
                status = r.message
                if (r.ok && apply) {
                    selected = packs.firstOrNull { it.path == out.absolutePath }
                    config = config.copy(pack = out.absolutePath)
                    applyToLockScreen()
                }
                scan()
            }
        }.start()
    }

    fun select(pack: PackEntry) {
        selected = pack
        status = "正在导入 ${pack.name} …"
        val dm = context.resources.displayMetrics
        Thread {
            val result = runCatching { PackStore.import(pack.path, dm.widthPixels, dm.heightPixels, config) }.getOrNull()
            post {
                summary = result
                status = when {
                    result == null -> "导入失败（可能不是 CA 类海报）"
                    else -> "已导入：时钟后 ${result.behind} 层 / 时钟前 ${result.front} 层 / 顶层 ${result.top} 层"
                }
            }
        }.start()
    }

    fun update(cfg: RuntimeConfig) {
        config = cfg
        project?.let { summary = Project.toSummary(it, screenW(), screenH(), screenW() * cfg.amplitude) }
    }

    /** 应用到锁屏：写远程偏好 + 写 config.json（后备）+ 广播让模块立即重建。 */
    fun applyToLockScreen() {
        persistConfig()
    }

    private fun persistConfig() {
        val cfg = config
        val json = cfg.toJson()
        val written = runCatching {
            prefs()?.edit()?.putString(State.KEY_RUNTIME_CONFIG, json)?.commit() ?: false
        }.getOrDefault(false)
        val fileOk = PackStore.writeConfigFile(cfg)
        runCatching { context.sendBroadcast(Intent(State.ACTION_PROBE)) }
        status = when {
            written -> "已应用：模块正在重建（灭屏再亮屏即可看到）"
            fileOk -> "已写入配置文件（框架未连接，改动会在下次重建时生效）"
            else -> "应用失败：框架未连接且无文件访问权限"
        }
    }

    /**
     * 还原系统原状：清空配置、卸下所有注入图层、时钟归位；
     * 若之前备份过锁屏壁纸，模块会一并还原（没备份就要手动重设）。
     */
    fun restoreSystem() {
        runCatching { prefs()?.edit()?.putString(State.KEY_RUNTIME_CONFIG, "{}")?.commit() }
        PackStore.writeConfigFile(RuntimeConfig())
        runCatching { context.sendBroadcast(Intent(State.ACTION_RESTORE)) }
        flushSave()
        clearHistory()
        project = null
        selectedLayerId = null
        selected = null
        summary = null
        config = RuntimeConfig()
        status = "已还原：注入图层已卸下、配置已清空、时钟不再跟随倾斜"
    }

    /** 把当前选定的图层设为锁屏壁纸——液态玻璃采样的就是这张系统壁纸。 */
    fun setGlassWallpaper() {
        val want = config.glassLayer.removePrefix("layer:")
        val layer = summary?.layers?.firstOrNull { it.name == want && it.bitmap != null }
        val bitmap = layer?.bitmap
        if (bitmap == null) {
            status = "请先导入海报包，并在设置里选择「液态玻璃读取」的图层"
            return
        }
        Thread {
            val ok = runCatching {
                WallpaperManager.getInstance(context).setBitmap(bitmap, null, true, WallpaperManager.FLAG_LOCK)
                true
            }.getOrDefault(false)
            post { status = if (ok) "已把「${layer.name}」设为锁屏壁纸，液态玻璃会采样它" else "设置壁纸失败" }
        }.start()
    }

    private fun post(block: () -> Unit) =
        android.os.Handler(android.os.Looper.getMainLooper()).post(block)

    companion object {
        /** 清单里的两条固定分界线（系统时钟行，不可拖动）：分钟在上、小时在下。 */
        const val MARK_MINUTE = "::clock_minute"
        const val MARK_HOUR = "::clock_hour"
    }
}
