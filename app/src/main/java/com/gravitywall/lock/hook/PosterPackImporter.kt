package com.gravitywall.lock.hook

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import com.gravitywall.lock.render.ClipWindow
import com.gravitywall.lock.render.StageLayer
import org.w3c.dom.Element
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/**
 * `.tendies` 海报包导入器（阶段 4A）。
 *
 * 格式（已实测核实）：`.tendies` 就是 ZIP，内含
 * ```
 * descriptors/<UUID>/versions/<N>/contents/<名称>.wallpaper/
 *     ├ <名称>_Background...ca/{index.xml, main.caml, assets 目录}
 *     ├ <名称>_Floating...ca/     ← 前景（浮动）层
 *     ├ <名称>_Foreground...ca/   ← 更前的一层（部分包有）
 *     └ Wallpaper.plist           ← wantsDeviceMotion 在这里
 * ```
 * `main.caml` 是 XML：图层树（`CALayer`/`CATransformLayer`）+ 视差规则
 * （`wallpaperParallaxGroups`：layerName / axis / mapMinTo / mapMaxTo）。
 *
 * 保真要点：
 * - **坐标是相对的**：子层 `position` 相对父层坐标系，导入时沿祖先链累加；
 * - **位移沿祖先链累加**：父子都有规则时相加（社区包正是这样让"窗口朝一个方向、
 *   内容朝另一个方向"运动的），相减互为反向时就是"双透视"；
 * - **`masksToBounds="1"` 是裁剪窗口**：把子树裁进自己的 frame，窗口自身也按自己的范围移动。
 *
 * 带位映射：Background → 时钟后；Floating → 时钟前；Foreground → 最顶层。
 * 第一期不处理：文本层、状态机（LKState）、纯色层。
 */
internal object PosterPackImporter {

    data class Bands(
        val behind: List<StageLayer>,
        val front: List<StageLayer>,
        val top: List<StageLayer>,
    )

    fun import(
        packPath: String,
        screenW: Int,
        screenH: Int,
        flipY: Boolean,
        floatingBand: String,
        capRatio: Float,
        note: (String) -> Unit,
    ): Bands? {
        val zip = runCatching { ZipFile(packPath) }.getOrElse {
            note("打不开压缩包：$it")
            return null
        }
        zip.use { z ->
            val entries = z.entries().toList()
                .filterNot { it.name.startsWith("__MACOSX") || it.name.endsWith(".DS_Store") || it.isDirectory }
            note("包内条目 ${entries.size} 个")

            val bundles = entries.filter { it.name.endsWith("main.caml") && it.name.contains(".ca/") }
                .groupBy { it.name.substringBefore(".ca/") + ".ca" }
            if (bundles.isEmpty()) {
                note("包内没有 .ca 场景（可能不是 CA 类海报）")
                return null
            }
            note("找到 ${bundles.size} 个场景 bundle")

            var scale = 1f
            var scaleKnown = false
            val behind = mutableListOf<StageLayer>()
            val front = mutableListOf<StageLayer>()
            val top = mutableListOf<StageLayer>()

            for ((bundle, camlEntries) in bundles) {
                val camlEntry = camlEntries.first()
                val caml = runCatching { z.getInputStream(camlEntry).readBytes().toString(Charsets.UTF_8) }
                    .getOrElse { note("$bundle：caml 读取失败"); continue }

                val doc = runCatching {
                    DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }
                        .newDocumentBuilder().parse(caml.byteInputStream())
                }.getOrElse { note("$bundle：caml 解析失败（$it）"); continue }

                val root = doc.documentElement
                if (!scaleKnown) {
                    val rootBounds = (root.getElementsByTagName("CALayer").item(0) as? Element)
                        ?.getAttribute("bounds")?.split(" ")?.getOrNull(3)?.toFloatOrNull()
                    if (rootBounds != null && rootBounds > 0) {
                        scale = screenH / rootBounds
                        scaleKnown = true
                        note("设计尺寸 ${rootBounds}pt → 屏幕 ${screenH}px，缩放 ${"%.2f".format(scale)}")
                    }
                }

                val rules = parseParallaxGroups(root, note)
                val defaultBand = bandOf(bundle, floatingBand)
                // 三根层齐全（BACKGROUND + FLOATING + FOREGROUND）视为"本模块导出的包"：
                // 此时 FLOATING 语义固定为"时钟前"，保证"导出 → 再导入"无损；
                // 只有二分结构的社区包才受 floatingBand 配置影响。
                val explicitThreeRoots = caml.contains("name=\"FOREGROUND\"")
                val effectiveFloating = if (explicitThreeRoots) "front" else floatingBand
                note("Y 轴语义：${if (flipY) "几何未翻转（原点左下、y 向上）→ 已换算成屏幕坐标" else "左上原点、y 向下"}")
                note(
                    "浮动层带位：$effectiveFloating" +
                        if (explicitThreeRoots) "（包内显式三根层，语义固定）" else "（来自配置：behind=时钟完整可见 / front=压小时数字 / top=盖住时钟）",
                )
                collectImageLayers(
                    z, bundle, root, rules, scale, screenW, screenH,
                    behind, front, top, note, defaultBand, flipY, effectiveFloating, capRatio,
                )
            }

            if (behind.isEmpty() && front.isEmpty() && top.isEmpty()) {
                note("没有导入到任何图像图层")
                return null
            }
            return Bands(behind, front, top)
        }
    }

    // ------------------------------------------------------------------ 解析

    /** 每个 bundle 的带位：`_Floating` → 配置指定的浮动带位，`_Foreground` → 最顶层，其余 → 时钟后。 */
    private fun bandOf(bundle: String, floatingBand: String): String = when {
        bundle.contains("_Foreground", ignoreCase = true) -> "top"
        bundle.contains("_Floating", ignoreCase = true) -> floatingBand
        else -> "behind"
    }

    /** 一条视差规则：轴 + 映射区间（单位 pt）。 */
    private data class Rule(val axis: String, val mapMin: Float, val mapMax: Float)

    /** 沿祖先链累加出来的位移（单位 pt，之后统一乘缩放）。 */
    private data class Motion(val minX: Float, val maxX: Float, val minY: Float, val maxY: Float) {
        operator fun plus(o: Motion) = Motion(minX + o.minX, maxX + o.maxX, minY + o.minY, maxY + o.maxY)

        /** 兜底限幅：窗口裁剪还没覆盖到的场景里，防止图层飞出屏幕。 */
        fun capped(capX: Float, capY: Float) = Motion(
            minX.coerceIn(-capX, capX), maxX.coerceIn(-capX, capX),
            minY.coerceIn(-capY, capY), maxY.coerceIn(-capY, capY),
        )

        companion object {
            val ZERO = Motion(0f, 0f, 0f, 0f)
        }
    }

    private fun parseParallaxGroups(root: Element, note: (String) -> Unit): Map<String, List<Rule>> {
        val out = mutableMapOf<String, MutableList<Rule>>()
        val groups = root.getElementsByTagName("wallpaperParallaxGroups")
        if (groups.length == 0) return out
        val dicts = (groups.item(0) as Element).getElementsByTagName("NSDictionary")
        for (i in 0 until dicts.length) {
            val d = dicts.item(i) as Element

            fun value(tag: String): String =
                (d.getElementsByTagName(tag).item(0) as? Element)?.getAttribute("value") ?: ""

            val layer = value("layerName")
            val axis = value("axis")
            val min = value("mapMinTo").toFloatOrNull()
            val max = value("mapMaxTo").toFloatOrNull()
            if (layer.isEmpty() || axis.isEmpty() || min == null || max == null) continue
            out.getOrPut(layer) { mutableListOf() }.add(Rule(axis, min, max))
        }
        if (out.isNotEmpty()) note("视差规则 ${out.size} 层（含反向：mapMin>mapMax）")
        return out
    }

    /** 递归收集图层：累加坐标与位移，处理裁剪窗口，按 zPosition 排序并按带位分发。 */
    private fun collectImageLayers(
        z: ZipFile,
        bundle: String,
        root: Element,
        rules: Map<String, List<Rule>>,
        scale: Float,
        screenW: Int,
        screenH: Int,
        behindHost: MutableList<StageLayer>,
        frontHost: MutableList<StageLayer>,
        topHost: MutableList<StageLayer>,
        note: (String) -> Unit,
        defaultBand: String,
        flipY: Boolean,
        floatingBand: String,
        capRatio: Float,
    ) {
        val assets = z.entries().asSequence()
            .filter { it.name.startsWith("$bundle/") && it.name.contains("/assets/") && !it.isDirectory }
            .associateBy { it.name.substringAfterLast('/') }

        val capX = screenW * capRatio
        val capY = screenH * capRatio
        val collected = mutableListOf<Bands2>() // 带位 / zPosition / 序 / 图层
        var clipped = 0

        fun motionOf(list: List<Rule>?): Motion {
            if (list == null) return Motion.ZERO

            fun axis(a: String): Pair<Float, Float> {
                val r = list.firstOrNull { it.axis == a } ?: return 0f to 0f
                return (r.mapMin * scale) to (r.mapMax * scale)
            }

            val (minX, maxX) = axis("x")
            val (minY, maxY) = axis("y")
            return Motion(minX, maxX, minY, maxY)
        }

        fun walk(
            el: Element,
            inherited: Motion,
            parentLeftPx: Float,
            parentTopPx: Float,
            parentHeightPt: Float,
            clip: ClipWindow?,
            hint: String?,
            depth: Int,
        ) {
            if (depth > 24) return
            val tag = el.tagName
            if (tag != "CALayer" && tag != "CATransformLayer") {
                // caml 根或其它容器：直接下钻
                for (c in directChildren(el)) walk(c, inherited, parentLeftPx, parentTopPx, parentHeightPt, clip, hint, depth + 1)
                return
            }

            val name = el.getAttribute("name")
            // 带位提示：iOS 用 BACKGROUND / FLOATING 区分（单 bundle 的包靠这个）
            val myHint = when {
                name.equals("FLOATING", ignoreCase = true) -> floatingBand
                name.equals("BACKGROUND", ignoreCase = true) -> "behind"
                name.equals("FOREGROUND", ignoreCase = true) -> "top"
                else -> hint
            }
            val motion = inherited + motionOf(rules[name])
            val bounds = parseRect(el.getAttribute("bounds"))
            val position = parsePair(el.getAttribute("position"))
            val anchor = parsePair(el.getAttribute("anchorPoint")) ?: (0.5f to 0.5f)
            val opacity = el.getAttribute("opacity").toFloatOrNull() ?: 1f
            val hidden = el.getAttribute("hidden") == "1"
            val zPos = el.getAttribute("zPosition").toFloatOrNull() ?: 0f
            val masks = el.getAttribute("masksToBounds") == "1"
            val kids = directChildren(el)

            // 本层 frame（子层坐标相对本层，所以要把 frame 原点与高度传下去）
            var frameLeft = parentLeftPx
            var frameTop = parentTopPx
            var w = 0f
            var h = 0f
            var hPt = parentHeightPt
            if (bounds != null && position != null) {
                w = bounds.third * scale
                h = bounds.fourth * scale
                hPt = bounds.fourth
                frameLeft = parentLeftPx + (position.first - anchor.first * bounds.third) * scale
                // geometryFlipped=0 → CoreAnimation 原点在左下、y 向上：
                //   该层顶边距父层"底边"的距离 = posY + (1-anchorY) × H
                //   换算成屏幕坐标（y 向下）= 父层高度 − 该距离
                val topFromParentTopPt =
                    if (flipY) {
                        parentHeightPt - (position.second + (1f - anchor.second) * bounds.fourth)
                    } else {
                        position.second - anchor.second * bounds.fourth
                    }
                frameTop = parentTopPx + topFromParentTopPt * scale
            }

            // 图片：只看直接子元素 contents。实测两种写法：
            //   <CGImage src="assets/xx.png"/>（社区包常用，且图层名可能与文件名不同）
            //   <CGImage name="xx.png"/>
            var assetName: String? = null
            kids.firstOrNull { it.tagName == "contents" }?.let { contents ->
                directChildren(contents).firstOrNull()?.let { img ->
                    val ref = img.getAttribute("src").takeIf { it.isNotEmpty() }
                        ?: img.getAttribute("name").takeIf { it.isNotEmpty() }
                    if (ref != null) assetName = ref
                }
            }
            if (assetName == null && name.contains('.') && name.length > 4) assetName = name

            val corner = el.getAttribute("cornerRadius").toFloatOrNull().let { (it ?: 0f) * scale }
            // CAML 的 contentsGravity → 我们的填充方式（缺省 resize = 拉伸）
            val fillMode = when (el.getAttribute("contentsGravity")) {
                "resizeAspectFill" -> "crop"
                "resizeAspect" -> "fit"
                else -> "stretch"
            }
            val fill = parseBackgroundColor(el)
            val rotation = parseRotation(el.getAttribute("transform"))
            val dstRect = RectF(frameLeft, frameTop, frameLeft + w, frameTop + h)
            val alpha = (opacity * 255).toInt().coerceIn(0, 255)

            if (!hidden && bounds != null && position != null && opacity > 0.01f) {
                val m = motion.capped(capX, capY)
                val entry = assetName?.let { assets[it.substringAfterLast('/')] }
                if (entry != null) {
                    val bitmap = decodeDownsampled(z, entry, screenW, screenH)
                    if (bitmap != null) {
                        collected.add(
                            Bands2(
                                myHint ?: defaultBand, zPos, collected.size,
                                StageLayer.ranged(
                                    name.ifEmpty { assetName ?: "image" }, bitmap, dstRect,
                                    m.minX, m.maxX, m.minY, m.maxY, alpha, clip, corner,
                                    assetPath = entry.name,
                                    rotation = rotation,
                                    fill = fillMode,
                                ),
                            ),
                        )
                    }
                } else if (fill != null) {
                    // 纯色（圆角）层：这个包里那两圈浅蓝"环"就是它，不只是裁剪容器
                    collected.add(
                        Bands2(
                            myHint ?: defaultBand, zPos, collected.size,
                            StageLayer.ranged(
                                name.ifEmpty { "色块" }, null, dstRect,
                                m.minX, m.maxX, m.minY, m.maxY, alpha, clip, corner, fill,
                                rotation = rotation,
                            ),
                        ),
                    )
                }
            }

            // 子层：坐标相对本层；masksToBounds 时为自己加一个裁剪窗口（带圆角）
            val childClip = if (masks && bounds != null && position != null) {
                clipped++
                val m = motion.capped(capX, capY)
                ClipWindow(
                    RectF(frameLeft, frameTop, frameLeft + w, frameTop + h),
                    m.minX, m.maxX, m.minY, m.maxY,
                    el.getAttribute("cornerRadius").toFloatOrNull().let { (it ?: 0f) * scale },
                )
            } else {
                clip
            }
            kids.firstOrNull { it.tagName == "sublayers" }?.let { subs ->
                for (c in directChildren(subs)) walk(c, motion, frameLeft, frameTop, hPt, childClip, myHint, depth + 1)
            }
        }

        walk(root, Motion.ZERO, 0f, 0f, screenH / scale, null, null, 0)
        val byBand = collected.groupBy { it.band }
        for ((b, list) in byBand) {
            val target = when (b) {
                "front" -> frontHost
                "top" -> topHost
                else -> behindHost
            }
            list.sortedWith(compareBy({ it.z }, { it.order })).forEach { target.add(it.layer) }
        }
        note(
            "$bundle 末尾：导入 ${collected.size} 层" +
                "（后 ${byBand["behind"]?.size ?: 0} / 前 ${byBand["front"]?.size ?: 0} / 顶 ${byBand["top"]?.size ?: 0}" +
                if (clipped > 0) "，含 $clipped 个裁剪窗口）" else "）",
        )
        // 逐层诊断：位置 / 位移 / 窗口，便于与 caml 里的数值手工核对
        collected.sortedWith(compareBy({ it.z }, { it.order })).forEach { e ->
            val l = e.layer
            val clip = l.clip
            note(
                "  · ${e.band} | ${l.name} | 位置 ${l.dst.left.toInt()},${l.dst.top.toInt()} " +
                    "${l.dst.width().toInt()}x${l.dst.height().toInt()}" +
                    " | 位移X ${l.minX.toInt()}~${l.maxX.toInt()} Y ${l.minY.toInt()}~${l.maxY.toInt()}" +
                    (if (clip != null) {
                        " | 窗口 ${clip.rect.left.toInt()},${clip.rect.top.toInt()} " +
                            "${clip.rect.width().toInt()}x${clip.rect.height().toInt()} 圆角=${clip.cornerRadius.toInt()}" +
                            " 窗口位移X ${clip.minX.toInt()}~${clip.maxX.toInt()}"
                    } else {
                        ""
                    }) +
                    (if (l.fillColor != null) " | 纯色层" else ""),
            )
        }
    }

    private data class Bands2(val band: String, val z: Float, val order: Int, val layer: StageLayer)

    /** 只取直接子元素（不递归），避免 DOM 的 getElementsByTagName 把后代当成自己的内容。 */
    private fun directChildren(el: Element): List<Element> {
        val out = mutableListOf<Element>()
        val nodes = el.childNodes
        for (i in 0 until nodes.length) {
            (nodes.item(i) as? Element)?.let { out.add(it) }
        }
        return out
    }

    /**
     * 从 transform 属性取 2D 旋转角（形如 "rotate(12deg) rotate(0deg, 0, 1, 0) …"）。
     * CAML 的旋转在"原点左下、y 向上"的空间里是逆时针为正；换算到屏幕坐标（y 向下）需取反。
     */
    private fun parseRotation(transform: String): Float {
        if (transform.isEmpty()) return 0f
        val m = Regex("rotate\\(\\s*(-?[\\d.]+)deg\\s*\\)").find(transform) ?: return 0f
        return -(m.groupValues[1].toFloatOrNull() ?: 0f)
    }

    /** backgroundColor="0.3765 0.6471 0.9804"（0~1 浮点 RGB）→ ARGB。 */
    private fun parseBackgroundColor(el: Element): Int? {        val s = el.getAttribute("backgroundColor")
        if (s.isEmpty()) return null
        val p = s.trim().split(" ").mapNotNull { it.toFloatOrNull() }
        if (p.size < 3) return null

        fun c(v: Float) = (v.coerceIn(0f, 1f) * 255f).toInt()
        return (0xFF shl 24) or (c(p[0]) shl 16) or (c(p[1]) shl 8) or c(p[2])
    }

    private fun decodeDownsampled(z: ZipFile, entry: ZipEntry, screenW: Int, screenH: Int): Bitmap? {
        val bytes = runCatching { z.getInputStream(entry).readBytes() }.getOrNull() ?: return null
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        var sample = 1
        while (opts.outWidth / (sample * 2) >= screenW && opts.outHeight / (sample * 2) >= screenH) sample *= 2
        val real = BitmapFactory.Options().apply { inSampleSize = sample }
        return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, real) }.getOrNull()
    }

    /** caml 的 bounds 形如 "0 0 390 844"（x y w h）。 */
    private fun parseRect(s: String): Quadruple? {
        val p = s.trim().split(" ").filter { it.isNotEmpty() }
        if (p.size < 4) return null
        val v = p.take(4).mapNotNull { it.toFloatOrNull() }
        if (v.size < 4) return null
        return Quadruple(v[0], v[1], v[2], v[3])
    }

    private fun parsePair(s: String): Pair<Float, Float>? {
        val p = s.trim().split(" ").filter { it.isNotEmpty() }
        if (p.size < 2) return null
        val v = p.take(2).mapNotNull { it.toFloatOrNull() }
        if (v.size < 2) return null
        return v[0] to v[1]
    }

    private data class Quadruple(val first: Float, val second: Float, val third: Float, val fourth: Float)
}
