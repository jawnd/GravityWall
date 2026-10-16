package com.gravitywall.lock.data

import android.content.Context
import android.content.Intent
import android.os.Environment
import com.gravitywall.lock.State
import com.gravitywall.lock.hook.PosterPackImporter
import com.gravitywall.lock.render.ParallaxDefaults
import com.gravitywall.lock.render.StageLayer
import org.json.JSONObject
import java.io.File

/** 运行期配置：写进远程偏好，模块优先读它（其次读共享目录里的 config.json）。 */
internal data class RuntimeConfig(
    val pack: String? = null,
    val flipY: Boolean = true,
    val floatingBand: String = "behind",
    val amplitudeCap: Float = ParallaxDefaults.IMPORT_CAP_RATIO,
    /** 全局视差幅度基准（屏宽比例）：影响系数 1.0 的图层最大位移 = 该比例 × 屏宽 */
    val amplitude: Float = 0.03f,
    /** 参数模式：`coefficient`（默认，用影响系数表达运动）/ `pixel`（用绝对像素 min/max 表达）。 */
    val paramMode: String = "coefficient",
    /** 液态玻璃读取哪一层：`layer:<名称>` / `none`（应用侧使用，模块不读） */
    val glassLayer: String = "none",
    /** 直接渲染的项目文件路径（应用"应用到锁屏"时写入；模块优先渲染它） */
    val project: String? = null,
    /** 满量程角度：倾斜多少度达到最大位移（默认 10°） */
    val fullScaleDeg: Float = 10f,
    /** 平滑系数：每帧向目标位移逼近的比例（0.02~1；越大越跟手） */
    val smooth: Float = 0.25f,
    /** 解锁后多久隐藏注入图层（毫秒）：太短会在解锁动画中途"跳掉"，太长则解锁后立刻下拉控制中心可能闪一下 */
    val unlockHideDelayMs: Int = 400,
) {
    fun toJson(): String = JSONObject().apply {
        pack?.let { put("pack", it) }
        project?.let { put("project", it) }
        put("flipY", flipY)
        put("floatingBand", floatingBand)
        put("amplitudeCap", amplitudeCap.toDouble())
        put("amplitude", amplitude.toDouble())
        put("paramMode", paramMode)
        put("glassLayer", glassLayer)
        put("fullScaleDeg", fullScaleDeg.toDouble())
        put("smooth", smooth.toDouble())
        put("unlockHideDelayMs", unlockHideDelayMs)
    }.toString()
}

internal data class PackEntry(val name: String, val path: String, val sizeKb: Long)

/** 一次导入的结果摘要（供界面展示与层选择）。 */
internal data class ImportSummary(
    val behind: Int,
    val front: Int,
    val top: Int,
    val windows: Int,
    val layers: List<StageLayer>,
    val notes: List<String>,
)

/**
 * 共享目录里的海报包读写。
 *
 * 目录与模块共用：`/sdcard/Download/gravitywall/`。应用需要"全部文件访问"权限
 * （一次性在系统设置里授予），这样才能扫描/读取包、做应用内预览，以及写配置文件。
 */
internal object PackStore {

    val DIR: File get() = File(Environment.getExternalStorageDirectory(), "Download/gravitywall")
    const val CONFIG_NAME = "config.json"

    fun hasStorageAccess(): Boolean = runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)

    fun storageAccessIntent(context: Context): Intent =
        Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            .setData(android.net.Uri.parse("package:${context.packageName}"))

    /** 扫描共享目录里的海报包。 */
    fun packs(): List<PackEntry> {
        val files = DIR.listFiles { f ->
            f.isFile && (f.name.lowercase().endsWith(".tendies") || f.name.lowercase().endsWith(".gwp"))
        } ?: return emptyList()
        return files.sortedBy { it.name }.map { PackEntry(it.name, it.absolutePath, it.length() / 1024) }
    }

    /** 在应用进程内导入（用的是与系统进程完全相同的导入器与渲染器）。 */
    fun import(path: String, screenW: Int, screenH: Int, cfg: RuntimeConfig): ImportSummary? {
        val notes = mutableListOf<String>()
        val bands = PosterPackImporter.import(
            path, screenW, screenH, cfg.flipY, cfg.floatingBand, cfg.amplitudeCap,
        ) { notes.add(it) } ?: return null
        return ImportSummary(
            behind = bands.behind.size,
            front = bands.front.size,
            top = bands.top.size,
            windows = notes.count { it.contains("裁剪窗口") },
            layers = bands.behind + bands.front + bands.top,
            notes = notes,
        )
    }

    /** 把运行期配置写进共享目录的 config.json（模块读它作为后备）。 */
    fun writeConfigFile(cfg: RuntimeConfig): Boolean = runCatching {
        DIR.mkdirs()
        File(DIR, CONFIG_NAME).writeText(cfg.toJson())
        true
    }.getOrDefault(false)

    /** 读取共享目录里的配置（用于界面回显）。 */
    fun readConfigFile(): RuntimeConfig? = runCatching {
        val f = File(DIR, CONFIG_NAME)
        if (!f.isFile) return null
        val o = JSONObject(f.readText())
        RuntimeConfig(
            pack = o.optString("pack").takeIf { it.isNotEmpty() },
            flipY = o.optBoolean("flipY", true),
            floatingBand = o.optString("floatingBand", "behind"),
            amplitudeCap = o.optDouble("amplitudeCap", ParallaxDefaults.IMPORT_CAP_RATIO.toDouble()).toFloat(),
            amplitude = o.optDouble("amplitude", 0.03).toFloat(),
            paramMode = o.optString("paramMode", "coefficient"),
            glassLayer = o.optString("glassLayer", "none"),
            project = o.optString("project").takeIf { it.isNotEmpty() },
            fullScaleDeg = o.optDouble("fullScaleDeg", 10.0).toFloat(),
            smooth = o.optDouble("smooth", 0.25).toFloat(),
            unlockHideDelayMs = o.optInt("unlockHideDelayMs", 400),
        )
    }.getOrNull()

    fun remoteConfigKey(): String = State.KEY_RUNTIME_CONFIG
}
