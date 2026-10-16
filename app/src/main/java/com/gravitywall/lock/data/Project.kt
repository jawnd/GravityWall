package com.gravitywall.lock.data

import android.graphics.BitmapFactory
import android.graphics.RectF
import com.gravitywall.lock.hook.PosterPackImporter
import com.gravitywall.lock.render.ClipWindow
import com.gravitywall.lock.render.ParallaxDefaults
import com.gravitywall.lock.render.StageLayer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

/** 一个可编辑图层。坐标都在"设计空间"（默认就是屏幕像素）里。 */
internal data class ProjectLayer(
    val id: String = UUID.randomUUID().toString().take(8),
    var name: String = "图层",
    /** 工作区内的图片相对路径；纯色层为 null */
    var image: String? = null,
    /** 纯色层颜色（ARGB） */
    var color: Int? = null,
    var x: Float = 0f,
    var y: Float = 0f,
    var w: Float = 0f,
    var h: Float = 0f,
    var band: String = "behind",
    var alpha: Int = 255,
    var minX: Float = 0f,
    var maxX: Float = 0f,
    var minY: Float = 0f,
    var maxY: Float = 0f,
    var z: Int = 0,
    /** 逆时针旋转角度（度） */
    var rotation: Float = 0f,
    /** 是否可见（不可见的层不渲染、也不导出） */
    var visible: Boolean = true,
    /** 影响系数（首选写法）：该层最大位移 = 系数 × 全局视差幅度；负值 = 反向。 设了就优先于 min/max。 */
    var coefX: Float? = null,
    var coefY: Float? = null,
    /** 图片填充方式：stretch（拉伸铺满，默认）/ crop（等比裁切）/ fit（适应，完整可见） */
    var fill: String = "stretch",
    /**
     * 遮罩图层：null = 普通图层；
     *   "rect"  方形（圆角看 maskRadius）
     *   "round" 圆形/椭圆（圆角按短边一半自动算，横向拉伸就成了椭圆）
     *   "image" 用本层的 image 当遮罩形状（按亮度或透明度）
     */
    var maskShape: String? = null,
    /** maskShape="rect" 时的圆角半径（像素）；"round" 不用它 */
    var maskRadius: Float = 0f,
    /** 遮罩图案是否按"亮度"读取（黑=挡住、白=显示）；false = 按图片透明度 */
    var maskLuminance: Boolean = true,
    /** 这一层作为遮罩，作用在哪一层上（目标图层 id） */
    var maskTargetId: String? = null,
) {
    /** 解析出实际生效的位移范围：有系数就用系数 × 基准幅度，否则用手写的 min/max。 */
    fun resolvedMotion(baseAmplitudePx: Float): FloatArray {
        val cx = coefX
        val cy = coefY
        val mnX = cx?.let { -it * baseAmplitudePx } ?: minX
        val mxX = cx?.let { it * baseAmplitudePx } ?: maxX
        val mnY = cy?.let { -it * baseAmplitudePx } ?: minY
        val mxY = cy?.let { it * baseAmplitudePx } ?: maxY
        return floatArrayOf(mnX, mxX, mnY, mxY)
    }
    /** 界面上用"幅度 + 方向"表示（幅度 = 最大绝对值，方向 = mapMax−mapMin 的符号）。 */
    fun amplitudeX(): Float = maxOf(kotlin.math.abs(minX), kotlin.math.abs(maxX))

    fun amplitudeY(): Float = maxOf(kotlin.math.abs(minY), kotlin.math.abs(maxY))

    fun reversedX(): Boolean = maxX < minX

    fun reversedY(): Boolean = maxY < minY

    fun setAmplitudeX(amp: Float, reversed: Boolean) {
        minX = if (reversed) amp else -amp
        maxX = if (reversed) -amp else amp
    }

    fun setAmplitudeY(amp: Float, reversed: Boolean) {
        minY = if (reversed) amp else -amp
        maxY = if (reversed) -amp else amp
    }
}

/** 项目：可编辑的源；海报包只是它的导入/导出格式。 */
internal class Project(
    var name: String,
    val layers: MutableList<ProjectLayer> = mutableListOf(),
    /** 新图层默认落在哪条带（项目级设置，v0.6） */
    var defaultBand: String = "front",
    /** 底图 / 系统壁纸源图层 id（项目级设置，v0.6）：应用到锁屏时它会成为真正的锁屏壁纸 */
    var baseLayerId: String? = null,
    /** 宽高锁定：点亮时改宽度自动等比对齐高度（图片按自身比例缩放，不拉伸） */
    var keepAspect: Boolean = true,
) {

    /**
     * 解码某层的位图（预览与"设为锁屏壁纸"共用）；长边不超过 screen 尺寸时按 2 的幂降采样。
     *
     * 带缓存：编辑器每动一下都要重建舞台图层，若每次都从磁盘解码，拖动会卡成幻灯片。
     * 缓存里放的就是舞台正在用的那些位图，不额外吃内存；换项目时清空。
     */
    fun bitmapOf(l: ProjectLayer, screenW: Int, screenH: Int): android.graphics.Bitmap? {
        val rel = l.image ?: return null
        val key = "$name|$rel|${screenW}x$screenH"
        synchronized(bitmapCache) { bitmapCache[key] }?.let { return it }
        val f = File(imagesDir(name), rel)
        if (!f.isFile) return null
        val bmp = if (screenW <= 0 || screenH <= 0) {
            android.graphics.BitmapFactory.decodeFile(f.absolutePath)
        } else {
            val probe = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(f.absolutePath, probe)
            var sample = 1
            while (probe.outWidth / (sample * 2) >= screenW && probe.outHeight / (sample * 2) >= screenH) sample *= 2
            android.graphics.BitmapFactory.decodeFile(
                f.absolutePath,
                android.graphics.BitmapFactory.Options().apply { inSampleSize = sample },
            )
        }
        if (bmp != null) {
            synchronized(bitmapCache) {
                if (bitmapCache.size >= 12) bitmapCache.remove(bitmapCache.keys.firstOrNull())
                bitmapCache[key] = bmp
            }
        }
        return bmp
    }

    /** 单个图层 → 舞台图层。 */
    private fun toStageLayer(l: ProjectLayer, screenW: Int, screenH: Int, baseAmplitudePx: Float): StageLayer? {
        val bmp = bitmapOf(l, screenW, screenH)
        // 形状遮罩不需要底图或颜色，照样要进舞台（它只贡献"洞"的形状）
        if (bmp == null && l.color == null && l.maskShape == null) return null
        val motion = l.resolvedMotion(baseAmplitudePx)
        return StageLayer.ranged(
            name = l.name,
            bitmap = bmp,
            dst = RectF(l.x, l.y, l.x + l.w, l.y + l.h),
            minX = motion[0], maxX = motion[1], minY = motion[2], maxY = motion[3],
            alpha = l.alpha,
            fillColor = if (bmp == null) l.color else null,
            rotation = l.rotation,
            id = l.id,
            fill = l.fill,
            maskShape = l.maskShape,
            maskRadius = l.maskRadius,
            maskLuminance = l.maskLuminance,
            maskForId = l.maskTargetId,
            maskOnly = !l.visible,
        )
    }

    /**
     * 这一层是否挂着"启用中"的遮罩。
     * 挂着的层即使自己「启用」关掉也要画——只是缩成"只在遮罩里显示"（打孔显示）。
     */
    private fun hasActiveMask(l: ProjectLayer): Boolean =
        layers.any { it.maskShape != null && it.visible && it.maskTargetId == l.id }

    /** 默认视差幅度基准：屏宽的 3%（系数 1.0 的图层最大位移）。 */
    fun stageLayers(
        screenW: Int = 0,
        screenH: Int = 0,
        baseAmplitudePx: Float = defaultBaseAmplitude(if (screenW > 0) screenW else 1080),
    ): List<StageLayer> = layers
        // 停用但挂着遮罩的层仍然要进舞台：它只显示遮罩以内的部分
        .filter { it.visible || (it.maskShape == null && hasActiveMask(it)) }
        .sortedBy { it.z }
        .mapNotNull { toStageLayer(it, screenW, screenH, baseAmplitudePx) }

    /** 按带位分组的舞台图层（模块渲染用）。 */
    fun bandLayers(
        screenW: Int,
        screenH: Int,
        baseAmplitudePx: Float = defaultBaseAmplitude(screenW),
    ): Triple<List<StageLayer>, List<StageLayer>, List<StageLayer>> {
        // 与 stageLayers 保持同一套规则：停用但挂着启用中遮罩的层也要进舞台（只显示遮罩里那部分）
        fun convert(band: String) = layers
            .filter { it.band == band && (it.visible || (it.maskShape == null && hasActiveMask(it))) }
            .sortedBy { it.z }
            .mapNotNull { toStageLayer(it, screenW, screenH, baseAmplitudePx) }

        return Triple(convert("behind"), convert("front"), convert("top"))
    }

    fun toJson(): String = JSONObject().apply {
        put("name", name)
        put("defaultBand", defaultBand)
        put("keepAspect", keepAspect)
        baseLayerId?.let { put("baseLayerId", it) }
        put("layers", JSONArray().apply {
            layers.forEach { l ->
                put(JSONObject().apply {
                    put("id", l.id)
                    put("name", l.name)
                    l.image?.let { put("image", it) }
                    l.color?.let { put("color", it) }
                    put("x", l.x.toDouble()); put("y", l.y.toDouble())
                    put("w", l.w.toDouble()); put("h", l.h.toDouble())
                    put("band", l.band)
                    put("alpha", l.alpha)
                    put("minX", l.minX.toDouble()); put("maxX", l.maxX.toDouble())
                    put("minY", l.minY.toDouble()); put("maxY", l.maxY.toDouble())
                    put("z", l.z)
                    put("rotation", l.rotation.toDouble())
                    put("visible", l.visible)
                    l.coefX?.let { put("coefX", it.toDouble()) }
                    l.coefY?.let { put("coefY", it.toDouble()) }
                    put("fill", l.fill)
                    l.maskShape?.let { put("maskShape", it) }
                    if (l.maskRadius != 0f) put("maskRadius", l.maskRadius.toDouble())
                    put("maskLuminance", l.maskLuminance)
                    l.maskTargetId?.let { put("maskTargetId", it) }
                })
            }
        })
    }.toString()

    companion object {
        const val ROOT = "projects"

        /** 解码缓存（key = 项目|文件|目标尺寸）。换项目时清空，避免旧项目的位图被一直持有。 */
        private val bitmapCache = java.util.LinkedHashMap<String, android.graphics.Bitmap>(8, 0.75f, true)

        fun clearBitmapCache() {
            synchronized(bitmapCache) { bitmapCache.clear() }
        }

        /** 默认视差幅度基准：屏宽的 3%（系数 1.0 的图层最大位移）。 */
        fun defaultBaseAmplitude(screenW: Int): Float = screenW * 0.03f

        /** 项目 → 预览用的摘要（结构与导入海报包得到的一致）。 */
        fun toSummary(p: Project, screenW: Int = 0, screenH: Int = 0): ImportSummary = ImportSummary(
            behind = p.layers.count { it.band == "behind" },
            front = p.layers.count { it.band == "front" },
            top = p.layers.count { it.band == "top" },
            windows = 0,
            layers = p.stageLayers(screenW, screenH),
            notes = emptyList(),
        )

        fun projectsDir(): File = File(PackStore.DIR, ROOT)

        fun projectDir(name: String): File = File(projectsDir(), name)

        fun imagesDir(name: String): File = File(projectDir(name), "images")

        fun fromJson(text: String): Project? = runCatching {
            val o = JSONObject(text)
            val p = Project(o.optString("name", "未命名"))
            p.defaultBand = o.optString("defaultBand", "front")
            p.keepAspect = o.optBoolean("keepAspect", true)
            p.baseLayerId = o.optString("baseLayerId").takeIf { it.isNotEmpty() }
            val arr = o.optJSONArray("layers") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val l = arr.getJSONObject(i)
                p.layers.add(
                    ProjectLayer(
                        id = l.optString("id", UUID.randomUUID().toString().take(8)),
                        name = l.optString("name", "图层"),
                        image = l.optString("image").takeIf { it.isNotEmpty() },
                        color = if (l.has("color")) l.optInt("color") else null,
                        x = l.optDouble("x", 0.0).toFloat(), y = l.optDouble("y", 0.0).toFloat(),
                        w = l.optDouble("w", 0.0).toFloat(), h = l.optDouble("h", 0.0).toFloat(),
                        band = l.optString("band", "behind"),
                        alpha = l.optInt("alpha", 255),
                        minX = l.optDouble("minX", 0.0).toFloat(), maxX = l.optDouble("maxX", 0.0).toFloat(),
                        minY = l.optDouble("minY", 0.0).toFloat(), maxY = l.optDouble("maxY", 0.0).toFloat(),
                        z = l.optInt("z", 0),
                        rotation = l.optDouble("rotation", 0.0).toFloat(),
                        visible = l.optBoolean("visible", true),
                        coefX = if (l.has("coefX")) l.optDouble("coefX").toFloat() else null,
                        coefY = if (l.has("coefY")) l.optDouble("coefY").toFloat() else null,
                        fill = l.optString("fill", "stretch"),
                        maskShape = l.optString("maskShape").takeIf { it.isNotEmpty() },
                        maskRadius = l.optDouble("maskRadius", 0.0).toFloat(),
                        maskLuminance = l.optBoolean("maskLuminance", true),
                        maskTargetId = l.optString("maskTargetId").takeIf { it.isNotEmpty() },
                    ),
                )
            }
            p
        }.getOrNull()

        fun save(project: Project): Boolean = runCatching {
            val dir = projectDir(project.name)
            dir.mkdirs()
            File(dir, "project.json").writeText(project.toJson())
            true
        }.getOrDefault(false)

        fun load(name: String): Project? = runCatching {
            val f = File(projectDir(name), "project.json")
            if (!f.isFile) return null
            fromJson(f.readText())
        }.getOrNull()

        fun list(): List<String> =
            projectsDir().listFiles { f -> f.isDirectory }?.map { it.name }?.sorted() ?: emptyList()

        /**
         * 从海报包建一个项目：把包里的素材解压到项目工作区，并把导入结果转成可编辑图层。
         * 带位取自导入器的分配结果（单 bundle 包按 FLOATING/BACKGROUND 层名判断）。
         */
        fun fromPack(packPath: String, screenW: Int, screenH: Int, cfg: RuntimeConfig): Project? {
            val notes = mutableListOf<String>()
            val importer = PosterPackImporter
            val bands = importer.import(packPath, screenW, screenH, cfg.flipY, cfg.floatingBand, cfg.amplitudeCap) {
                notes.add(it)
            } ?: return null

            val baseName = File(packPath).nameWithoutExtension.take(24)
            val projectName = baseName.ifEmpty { "导入作品" }
            val project = Project(projectName)
            imagesDir(projectName).mkdirs()

            // 解压包内素材到工作区，供编辑与再导出
            val copied = mutableMapOf<String, String>()
            runCatching {
                ZipFile(packPath).use { z ->
                    val entries = z.entries().toList().filter { it.name.contains("/assets/") && !it.isDirectory }
                    for (e in entries) {
                        val out = File(imagesDir(projectName), e.name.substringAfterLast('/'))
                        if (!out.isFile) {
                            runCatching { z.getInputStream(e).use { input -> out.outputStream().use { input.copyTo(it) } } }
                        }
                        copied[e.name] = out.name
                    }
                }
            }

            var z = 0
            val base = cfg.amplitude * screenW
            val coefMode = cfg.paramMode == "coefficient"
            var biasDropped = 0
            for ((list, band) in listOf(bands.behind to "behind", bands.front to "front", bands.top to "top")) {
                for (layer in list) {
                    val rel = layer.assetPath?.let { copied[it] }
                    // 系数模式：把大位移换算成"影响系数 ±R"（围绕原位对称），偏置会丢失
                    var coefX: Float? = null
                    var coefY: Float? = null
                    if (coefMode && base > 1f) {
                        val magX = maxOf(kotlin.math.abs(layer.minX), kotlin.math.abs(layer.maxX))
                        val magY = maxOf(kotlin.math.abs(layer.minY), kotlin.math.abs(layer.maxY))
                        val revX = layer.maxX < layer.minX
                        val revY = layer.maxY < layer.minY
                        coefX = (if (revX) -magX else magX) / base
                        coefY = (if (revY) -magY else magY) / base
                        if (kotlin.math.abs(layer.minX + layer.maxX) > base * 0.15f ||
                            kotlin.math.abs(layer.minY + layer.maxY) > base * 0.15f
                        ) {
                            biasDropped++
                        }
                    }
                    project.layers.add(
                        ProjectLayer(
                            name = layer.name,
                            image = rel,
                            color = if (layer.bitmap == null) layer.fillColor else null,
                            x = layer.dst.left, y = layer.dst.top,
                            w = layer.dst.width(), h = layer.dst.height(),
                            band = band,
                            alpha = layer.alpha,
                            minX = if (coefMode) 0f else layer.minX,
                            maxX = if (coefMode) 0f else layer.maxX,
                            minY = if (coefMode) 0f else layer.minY,
                            maxY = if (coefMode) 0f else layer.maxY,
                            z = z++,
                            coefX = coefX,
                            coefY = coefY,
                            fill = layer.fill,
                        ),
                    )
                }
            }
            if (coefMode) {
                notes.add(
                    "已按系数模式换算：基准幅度 = 屏宽 ${(cfg.amplitude * 100).toInt()}%（约 ${base.toInt()}px）" +
                        if (biasDropped > 0) "；注意 $biasDropped 层的位移含不对称成分，换算成系数时被丢掉了" else "",
                )
            }
            // 海报包里的窗口（masksToBounds 父层）→ 拆成一个"形状遮罩"图层，作用于它里面的那一层
            var zTop = project.layers.maxOfOrNull { it.z } ?: 0
            for ((list, _) in listOf(bands.behind to "behind", bands.front to "front", bands.top to "top")) {
                for (layer in list) {
                    val c = layer.clip ?: continue
                    val target = project.layers.lastOrNull { it.name == layer.name } ?: continue
                    val isRound = kotlin.math.abs(c.cornerRadius - minOf(c.rect.width(), c.rect.height()) / 2f) < 3f
                    project.layers.add(
                        ProjectLayer(
                            name = "${layer.name} 遮罩",
                            x = c.rect.left, y = c.rect.top,
                            w = c.rect.width(), h = c.rect.height(),
                            band = target.band,
                            z = ++zTop,
                            minX = c.minX, maxX = c.maxX, minY = c.minY, maxY = c.maxY,
                            coefX = 0f, coefY = 0f,
                            maskShape = if (isRound) "round" else "rect",
                            maskRadius = if (isRound) 0f else c.cornerRadius,
                            maskTargetId = target.id,
                        ),
                    )
                }
            }

            // 底图：优先取"时钟后"带里最底下那张图片层（通常就是整幅背景）
            project.baseLayerId = project.layers
                .firstOrNull { it.image != null && it.band == "behind" }
                ?.id
                ?: project.layers.firstOrNull { it.image != null }?.id
            return if (project.layers.isEmpty()) null else project
        }

        /** 新建空白项目（一个全屏纯色底）。 */
        fun blank(name: String, screenW: Int, screenH: Int): Project {
            val p = Project(name)
            p.layers.add(
                ProjectLayer(
                    name = "底色", color = 0xFF10203A.toInt(),
                    x = 0f, y = 0f, w = screenW.toFloat(), h = screenH.toFloat(),
                    band = "behind", z = 0,
                ),
            )
            return p
        }

        fun defaultConfig() = ParallaxDefaults.IMPORT_CAP_RATIO
    }
}
