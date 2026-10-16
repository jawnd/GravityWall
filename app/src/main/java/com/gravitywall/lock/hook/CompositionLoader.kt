package com.gravitywall.lock.hook

import android.graphics.BitmapFactory
import android.graphics.RectF
import com.gravitywall.lock.render.StageLayer
import org.json.JSONObject
import java.io.File

/**
 * 从配置文件读入用户自己的图层（阶段 3.5）。
 *
 * 配置文件：/sdcard/Download/gravitywall/config.json
 * ```json
 * {
 *   "layers": [
 *     {"name":"远山","file":"/sdcard/Download/gravitywall/far.png",
 *      "band":"behind","x":0,"y":600,"w":1080,"h":700,"coefficient":0.4,"alpha":255},
 *     {"name":"人物","file":"/sdcard/Download/gravitywall/subject.png",
 *      "band":"front","x":200,"y":900,"w":700,"h":1200,"coefficient":1.0},
 *     {"name":"前景花","file":"/sdcard/Download/gravitywall/flowers.png",
 *      "band":"top","x":0,"y":1800,"w":1080,"h":600,"coefficient":1.5,"alpha":180}
 *   ]
 * }
 * ```
 * band 三选一（对应三条挂载带）：
 *   behind = 时钟之后（被时钟压住）／front = 时钟之前（压小时数字、被分钟数字压住）／top = 最顶层（压住一切）
 * 缺省：band=behind，coefficient=1.0，alpha=255，w/h=图片原始尺寸。
 */
internal object CompositionLoader {

    const val DIR = "/sdcard/Download/gravitywall"
    const val CONFIG_PATH = "$DIR/config.json"

    data class Result(
        val behind: List<StageLayer>,
        val front: List<StageLayer>,
        val top: List<StageLayer>,
    )

    /** 运行期配置（来自应用的远程偏好）：优先于文件，用于"应用内改设置后立即生效" */
    @Volatile
    private var runtimeJson: String? = null

    fun setRuntimeJson(json: String?) {
        runtimeJson = json?.takeIf { it.isNotBlank() }
    }

    /** 从配置里读海报包路径（可选）。 */
    fun packPath(): String? = configValue("pack")

    /** 从配置里读"项目文件"路径（可选）——项目 JSON 可以直接手改文本调试。 */
    fun projectPath(): String? = configValue("project")

    /**
     * 全局视差幅度基准（屏宽比例，默认 3%）：
     * **影响系数 1.0 的图层，其最大位移 = 该比例 × 屏宽**（1080 屏约 32px）。
     */
    fun amplitudeRatio(): Float =
        configValue("amplitude")?.toFloatOrNull()?.coerceIn(0f, 0.5f) ?: 0.03f

    /**
     * Y 轴语义开关：CAML 的 `geometryFlipped="0"` 表示 CoreAnimation 的
     * "原点左下、y 向上"，需要换算成屏幕坐标。默认按翻转处理（与 iOS 一致），
     * 可用配置里的 `"flipY": false` 关掉做对照。
     */
    fun flipY(): Boolean = configValue("flipY")?.toBooleanStrictOrNull() ?: true

    /**
     * 海报里"浮动层"（_Floating bundle / caml 里的 FLOATING 层）放到哪条带：
     * - `behind`（默认）：整幅海报在时钟之下，**时钟（小时+分钟）完整可见**；
     * - `front`：浮动层压住小时数字、位于分钟数字之下（HyperOS 自带景深的三明治位）；
     * - `top`：浮动层盖住整个时钟。
     */
    fun floatingBand(): String =
        configValue("floatingBand")?.takeIf { it in setOf("behind", "front", "top") } ?: "behind"

    /** 导入时的位移上限比例（可调；窗口裁剪覆盖更多场景后可逐步放开） */
    fun amplitudeCap(): Float =
        configValue("amplitudeCap")?.toFloatOrNull()?.coerceIn(0.05f, 2f)
            ?: com.gravitywall.lock.render.ParallaxDefaults.IMPORT_CAP_RATIO

    /** 满量程角度：倾斜多少度达到最大位移（默认 10°；越小越灵敏）。 */
    fun fullScaleDeg(): Float = configValue("fullScaleDeg")?.toFloatOrNull()?.coerceIn(2f, 40f) ?: 10f

    /** 平滑系数：每帧向目标位移逼近的比例（0.02~1；越大越跟手，越小越柔和）。 */
    fun smooth(): Float = configValue("smooth")?.toFloatOrNull()?.coerceIn(0.02f, 1f) ?: 0.25f

    /** 解锁后多久隐藏注入图层（毫秒，默认 400；100~3000）。 */
    fun unlockHideDelayMs(): Long =
        configValue("unlockHideDelayMs")?.toLongOrNull()?.coerceIn(100L, 3000L) ?: 400L

    /**
     * 是否让系统时钟跟随倾斜位移。
     * 默认 **关闭**：时钟由系统控制（方案 Q13），只有调试时才打开。
     */
    fun moveClock(): Boolean = configValue("moveClock")?.toBooleanStrictOrNull() ?: false

    /**
     * 是否启用内置测试构图（几个圆盘）。
     * 默认 **关闭**——配置为空时模块就是"什么都不做"，这样"还原"才有意义。
     */
    fun testComposition(): Boolean = configValue("testComposition")?.toBooleanStrictOrNull() ?: false

    private fun configValue(key: String): String? {
        runtimeJson?.let { json ->
            runCatching { JSONObject(json).optString(key).takeIf { it.isNotEmpty() } }.getOrNull()?.let { return it }
        }
        val config = File(CONFIG_PATH)
        if (!config.isFile) return null
        return runCatching { JSONObject(config.readText()).optString(key).takeIf { it.isNotEmpty() } }.getOrNull()
    }

    /** 读取配置；文件不存在、解析失败或没有任何有效图层时返回 null（调用方退回测试构图）。 */
    fun load(note: (String) -> Unit, amplitudePx: Float, verticalRatio: Float): Result? {
        val config = File(CONFIG_PATH)
        if (!config.isFile) {
            note("没有配置文件（$CONFIG_PATH）")
            return null
        }

        val root = runCatching { JSONObject(config.readText()) }.getOrElse { e ->
            note("配置解析失败：$e")
            return null
        }
        val layers = root.optJSONArray("layers") ?: run {
            note("配置里没有 layers 数组")
            return null
        }

        val behind = mutableListOf<StageLayer>()
        val front = mutableListOf<StageLayer>()
        val top = mutableListOf<StageLayer>()

        for (i in 0 until layers.length()) {
            val item = runCatching { layers.getJSONObject(i) }.getOrNull() ?: continue
            val name = item.optString("name").ifEmpty { "层$i" }
            val path = item.optString("file")
            if (path.isEmpty()) {
                note("$name：缺 file 字段，跳过")
                continue
            }
            val bitmap = runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
            if (bitmap == null) {
                note("$name：图片读不到（$path），跳过")
                continue
            }
            val x = item.optDouble("x", 0.0).toFloat()
            val y = item.optDouble("y", 0.0).toFloat()
            val w = item.optDouble("w", bitmap.width.toDouble()).toFloat()
            val h = item.optDouble("h", bitmap.height.toDouble()).toFloat()
            val coefficient = item.optDouble("coefficient", 1.0).toFloat()
            val alpha = item.optInt("alpha", 255).coerceIn(0, 255)
            val band = item.optString("band", "behind")

            val layer = StageLayer.fromCoefficient(
                name = name,
                bitmap = bitmap,
                dst = RectF(x, y, x + w, y + h),
                coefficient = coefficient,
                amplitudePx = amplitudePx,
                verticalRatio = verticalRatio,
                alpha = alpha,
            )
            when (band) {
                "front" -> front.add(layer)
                "top" -> top.add(layer)
                else -> behind.add(layer)
            }
            note("$name → $band ×$coefficient（原图 ${bitmap.width}x${bitmap.height} → 摆放 ${w.toInt()}x${h.toInt()}）")
        }

        if (behind.isEmpty() && front.isEmpty() && top.isEmpty()) {
            note("配置里没有任何可用图层")
            return null
        }
        return Result(behind, front, top)
    }
}
