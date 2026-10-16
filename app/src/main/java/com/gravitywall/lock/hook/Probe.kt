package com.gravitywall.lock.hook

import android.content.Context
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.gravitywall.lock.State
import com.gravitywall.lock.render.LayerStage
import com.gravitywall.lock.render.ParallaxDefaults
import com.gravitywall.lock.render.StageLayer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 阶段 0 探测：把"能不能做"从猜测变成事实。
 *
 * 产出：logcat 日志 + 远程偏好里的一份完整报告（应用内可直接查看/复制）。
 * 探测内容：框架信息、目标类存在性、锁屏视图树与候选挂载点、传感器清单与实时倾角。
 */
internal class Probe(
    private val log: (String) -> Unit,
    private val prefsProvider: () -> SharedPreferences?,
    private val reportFile: String,
    private val loaderProvider: () -> ClassLoader?,
) {

    private val liveFile: String = reportFile.replace("report", "live")

    private val sb = StringBuilder()
    private val main = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private var prefs: SharedPreferences? = null
    private var context: Context? = null
    private var sensorManager: SensorManager? = null

    @Volatile private var listener: SensorEventListener? = null
    @Volatile private var subscribed: Sensor? = null
    @Volatile private var lastTilt: FloatArray? = null

    private var lastLiveWrite = 0L
    private var lastLiveLog = 0L
    private var lastLiveFile = 0L

    @Volatile private var viewRequested = false

    @Volatile private var haveView = false

    // ---------------------------------------------------------------- 报告输出

    fun line(s: String) {
        // 报告缓冲是滚动的：长时间运行不能让它无限增长（曾因每帧写日志把内存吃满）。
        if (sb.length > 240_000) {
            val keepFrom = sb.length - 120_000
            val cut = sb.indexOf("\n", keepFrom).let { if (it < 0) keepFrom else it + 1 }
            sb.delete(0, cut)
            sb.insert(0, "[报告] 较早内容已截断（仅保留最近部分）\n")
        }
        sb.append(s).append('\n')
        log(s)
        publish(false)
    }

    fun section(title: String) {
        line("")
        line("── $title ──")
    }

    private var lastPublish = 0L
    private var channelReported = false

    /** 由根视图派生的目标 ClassLoader（热重载自举时使用） */
    @Volatile
    private var derivedLoader: ClassLoader? = null

    /** 从视图树里收集到的全部 ClassLoader */
    @Volatile
    private var loaderCandidates: List<ClassLoader> = emptyList()

    private fun publish(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastPublish < 200) return
        lastPublish = now

        val p = prefs ?: prefsProvider()?.also { prefs = it }
        if (!channelReported) {
            channelReported = true
            sb.append("[通道] 跨进程偏好：").append(if (p != null) "可用" else "不可用（改用文件导出）").append('\n')
        }
        runCatching {
            p?.edit()
                ?.putString(State.KEY_REPORT, sb.toString())
                ?.putLong(State.KEY_TS, now)
                ?.apply()
        }
        runCatching { writeFile(reportFile, sb.toString()) }
    }

    /** 直接写共享存储：不需要 root、不需要解锁，adb 就能取走。 */
    private fun writeFile(path: String, content: String) {
        val f = java.io.File(path)
        f.parentFile?.takeIf { !it.exists() }?.mkdirs()
        f.writeText(content)
    }

    fun finish() {
        line("[完成] 报告已导出：$reportFile")
        publish(true)
    }

    /** 延迟抓取根视图（启动后兜底：万一锚点都没触发，这条路也能拿到树）。 */
    fun dumpRootViewsDelayed(delayMs: Long, from: String) {
        main.postDelayed({
            runCatching { dumpRootViews(from) }
            finish()
        }, delayMs)
    }

    /** 核对 Z 序与图层类型：搞清"为什么我们的视图压不住时钟"。 */
    private fun dumpZOrder() {
        section("Z 序与图层类型核对")
        val roots = rootViews() ?: run { line("拿不到根视图"); return }
        val keyguard = findKeyguardRoot(roots) ?: run { line("找不到锁屏根"); return }
        val panel = findByIdIn(keyguard, "keyguard_panel_view") as? ViewGroup
        if (panel == null) {
            line("找不到 KeyguardPanelView")
            return
        }
        line("KeyguardPanelView 共 ${panel.childCount} 个子视图（z / translationZ / elevation / 图层类型）：")
        for (i in 0 until panel.childCount) {
            val c = panel.getChildAt(i)
            line(
                "  [$i] ${c.javaClass.name}${idName(c)?.let { " #$it" } ?: ""} " +
                    "z=${c.z} tz=${c.translationZ} elev=${c.elevation} layer=${layerTypeName(c)} " +
                    "vis=${c.visibility} ${c.width}x${c.height}",
            )
        }
        for (clockId in listOf("miui_keyguard_clock_container", "miui_keyguard_foreground_clock_container")) {
            val clock = findByIdIn(keyguard, clockId) ?: continue
            line("$clockId 自身：z=${clock.z} tz=${clock.translationZ} elev=${clock.elevation} layer=${layerTypeName(clock)}")
            val surfaces = mutableListOf<String>()

            fun walk(v: View, depth: Int) {
                if (depth > 25) return
                if (v is android.view.SurfaceView || v is android.view.TextureView) {
                    surfaces.add("${v.javaClass.name}${idName(v)?.let { " #$it" } ?: ""}(z=${v.z})")
                }
                if (v is ViewGroup) {
                    for (j in 0 until v.childCount) walk(v.getChildAt(j), depth + 1)
                }
            }

            walk(clock, 0)
            line("  其中 Surface 型视图 ${surfaces.size} 个：${if (surfaces.isEmpty()) "无" else surfaces.joinToString()}")
        }
    }

    private fun layerTypeName(v: View): String = when (v.layerType) {
        View.LAYER_TYPE_NONE -> "none"
        View.LAYER_TYPE_SOFTWARE -> "software"
        View.LAYER_TYPE_HARDWARE -> "hardware"
        else -> v.layerType.toString()
    }

    /** 按需重跑探测（广播/热重载触发）：复用缓存的 Context，并重新收集 ClassLoader。 */
    fun rerunReport() {
        section("重新探测")
        val roots = rootViews()
        if (roots.isNullOrEmpty()) {
            line("拿不到根视图，放弃本次重跑")
            finish()
            return
        }
        loaderCandidates = collectLoaders(roots)
        reportClasses(null)
        dumpTree(roots, "按需抓取")
        dumpZOrder()
        dumpShade()
        if (context != null) reportSensors()
        // 让舞台按最新配置重建一次（改了 config.json 后重跑探测即可看到新构图）
        stagesBuilt = false
        stagesCheckCounter = 0
        noInjection = false
        main.post { runCatching { applyParallax() } }
        line("[构图] 已请求按当前配置重建舞台")
        finish()
    }

    /**
     * 自举：热重载会创建全新的模块实例（没有 ClassLoader、没有 Context、没有已装的钩子）。
     * 但"取根视图"这件事只需要反射，而根视图本身就带着 Context 和目标的 ClassLoader
     * （视图的类来自 SystemUI 的 APK，它的 classLoader 就是目标 ClassLoader）。
     * 于是新实例可以从零把自己拉起来。
     */
    fun bootstrap(processHint: String) {
        section("自举（$processHint）")
        val roots = rootViews()
        if (roots == null || roots.isEmpty()) {
            line("拿不到根视图，自举失败（该进程可能没有界面）")
            finish()
            return
        }
        val anyRoot = roots.first()
        loaderCandidates = collectLoaders(roots)
        derivedLoader = deriveLoader(roots) ?: anyRoot.javaClass.classLoader
        line("收集到 ClassLoader ${loaderCandidates.size} 个：${loaderCandidates.map { it.javaClass.simpleName }}")
        acceptContext(anyRoot.context, "根视图自举")
        reportClasses(null)
        dumpTree(roots, "自举抓取")
        finish()
    }

    /**
     * 从视图树里派生目标的 ClassLoader：找第一个"非系统类"的视图，
     * 它的类来自 SystemUI 的 APK，其 classLoader 就是目标 ClassLoader。
     * （直接用根视图的 classLoader 往往只会拿到 BootClassLoader。）
     */
    private fun deriveLoader(roots: List<View>): ClassLoader? {
        fun walk(v: View, depth: Int): ClassLoader? {
            if (depth > 30) return null
            val name = v.javaClass.name
            if (name.startsWith("com.android.") || name.startsWith("com.miui.") || name.startsWith("androidx.")) {
                v.javaClass.classLoader?.let { return it }
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i), depth + 1)?.let { return it }
            }
            return null
        }
        for (r in roots) walk(r, 0)?.let { return it }
        return null
    }

    // ---------------------------------------------------------------- 1. 框架

    fun reportFramework(api: Int, fwName: String, fwVersion: String, fwVersionCode: Long, fwProps: Long) {
        section("框架与设备")
        line("libxposed API = $api")
        line("框架 = $fwName $fwVersion ($fwVersionCode)，caps = 0x${fwProps.toString(16)}")
        line("设备 = ${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        line("进程 = ${currentProcessNameSafe()}")
    }

    // ---------------------------------------------------------------- 2. 类存在性

    fun reportClasses(loader: ClassLoader?) {
        val loaders = effectiveLoaders().ifEmpty { listOfNotNull(loader) }
        line("使用的 ClassLoader：${loaders.map { it.javaClass.name }}")
        section("目标类存在性")
        line("【已由原模块真机验证】")
        for (name in CLASSES_PROVEN) reportOneClass(loaders, name)
        line("【来自开源情报，待核对】")
        for (name in CLASSES_TO_VERIFY) reportOneClass(loaders, name)
    }

    private fun reportOneClass(loader: ClassLoader?, name: String) {
        reportOneClass(listOfNotNull(loader), name)
    }

    /** 逐个 ClassLoader 尝试（HyperOS 的类可能分散在主 APK 与插件 APK 里）。 */
    private fun reportOneClass(loaders: List<ClassLoader>, name: String) {
        val hits = loaders.filter { Reflect.loadClass(it, name) != null }
        if (hits.isEmpty()) {
            line("✘ $name")
        } else {
            val first = hits.first()
            line("✔ $name（${hits.size} 个 ClassLoader 可见，首个=${first.javaClass.simpleName}）")
        }
    }

    /** 实际用于类存在性检查的 ClassLoader 集合。 */
    private fun effectiveLoaders(): List<ClassLoader> =
        (loaderCandidates + listOfNotNull(loaderProvider() ?: derivedLoader)).distinct()

    /** 从视图树里收集所有出现过的 ClassLoader。 */
    private fun collectLoaders(roots: List<View>): List<ClassLoader> {
        val set = LinkedHashSet<ClassLoader>()

        fun walk(v: View, depth: Int) {
            if (depth > 30) return
            v.javaClass.classLoader?.let { set.add(it) }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i), depth + 1)
            }
        }

        roots.forEach { walk(it, 0) }
        return set.toList()
    }

    // ---------------------------------------------------------------- 3. Context / 视图

    fun acceptContext(c: Context?, from: String) {
        if (c == null) return
        if (context != null) return
        context = runCatching { c.applicationContext }.getOrNull() ?: c
        line("[Context] 取得自 $from")
        registerProbeReceiver(context!!)
        reportSensors()
        publish(true)
    }

    private var receiverRegistered = false

    /**
     * 注册探测控制广播，开发期用它免重启迭代：
     *   adb shell am broadcast -a com.gravitywall.lock.PROBE              （重跑探测）
     *   adb shell am broadcast -a com.gravitywall.lock.RESTART_SYSTEMUI   （让 SystemUI 重启以加载新代码）
     */
    private fun registerProbeReceiver(ctx: Context) {
        if (receiverRegistered) return
        receiverRegistered = true
        runCatching {
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: android.content.Intent?) {
                    when (intent?.action) {
                        ACTION_PROBE -> runCatching {
                            line("[广播] 收到重跑请求")
                            rerunReport()
                        }

                        ACTION_RESTART -> runCatching {
                            line("[广播] 收到重启请求：本进程即将退出，由系统自动拉起以加载新代码")
                            publish(true)
                            main.postDelayed({
                                runCatching { android.os.Process.killProcess(android.os.Process.myPid()) }
                            }, 400)
                        }

                        // 把指定图片设为锁屏壁纸——液态玻璃采样的是"系统壁纸"这张纹理，
                        // 所以这是"指定玻璃读取哪一层"的可行路径。
                        ACTION_SET_WALLPAPER -> runCatching {
                            val path = intent.getStringExtra("path")
                            if (path.isNullOrEmpty()) {
                                line("[壁纸] 缺少 path 参数")
                                return@runCatching
                            }
                            val ctx = context ?: run {
                                line("[壁纸] 还没有 Context")
                                return@runCatching
                            }
                            // 覆盖前先尽力备份当前锁屏壁纸（能读到就备份）
                            runCatching {
                                val wm0 = android.app.WallpaperManager.getInstance(ctx)
                                val cur = wm0.getDrawable(android.app.WallpaperManager.FLAG_LOCK)
                                val bmp = (cur as? android.graphics.drawable.BitmapDrawable)?.bitmap
                                if (bmp != null) {
                                    java.io.File(BACKUP_DIR).mkdirs()
                                    java.io.File(BACKUP_DIR, "wallpaper_backup.png").outputStream().use {
                                        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                                    }
                                    line("[壁纸] 已备份原锁屏壁纸 → $BACKUP_DIR/wallpaper_backup.png")
                                } else {
                                    line("[壁纸] 读不到当前壁纸，无法备份（继续设置）")
                                }
                            }
                            val bmp = android.graphics.BitmapFactory.decodeFile(path)
                            if (bmp == null) {
                                line("[壁纸] 解码失败：$path")
                                return@runCatching
                            }
                            // 只设"锁屏壁纸"：桌面壁纸绝对不碰（原模块那个能同时改桌面的分支已删除，
                            // 否则控制中心的"模糊当前屏幕"背景会被一起改掉）
                            android.app.WallpaperManager.getInstance(ctx)
                                .setBitmap(bmp, null, true, android.app.WallpaperManager.FLAG_LOCK)
                            line("[壁纸] 已设为锁屏壁纸：$path（${bmp.width}x${bmp.height}）")
                        }

                        // 还原：清空配置、卸下注入图层、时钟归位（若有备份还会还原壁纸）
                        ACTION_RESTORE -> runCatching {
                            line("[还原] 清空配置并卸下所有注入图层")
                            runCatching {
                                prefsProvider()?.edit()?.putString(State.KEY_RUNTIME_CONFIG, "{}")?.commit()
                            }
                            runCatching {
                                java.io.File("/sdcard/Download/gravitywall/config.json").writeText("{}")
                            }
                            CompositionLoader.setRuntimeJson("{}")
                            unmountStages()
                            val backup = java.io.File(BACKUP_DIR, "wallpaper_backup.png")
                            if (backup.isFile) {
                                val bmp = android.graphics.BitmapFactory.decodeFile(backup.absolutePath)
                                val ctx = context
                                if (bmp != null && ctx != null) {
                                    runCatching {
                                        android.app.WallpaperManager.getInstance(ctx)
                                            .setBitmap(bmp, null, true, android.app.WallpaperManager.FLAG_LOCK)
                                        line("[还原] 已把锁屏壁纸还原为备份（$BACKUP_DIR/wallpaper_backup.png）")
                                    }
                                }
                            } else {
                                line("[还原] 没有壁纸备份可还原（锁屏壁纸需要你手动重设）")
                            }
                            publish(true)
                        }
                    }
                }
            }
            ctx.registerReceiver(receiver, android.content.IntentFilter(ACTION_PROBE), Context.RECEIVER_EXPORTED)
            ctx.registerReceiver(receiver, android.content.IntentFilter(ACTION_RESTART), Context.RECEIVER_EXPORTED)
            ctx.registerReceiver(receiver, android.content.IntentFilter(ACTION_SET_WALLPAPER), Context.RECEIVER_EXPORTED)
            ctx.registerReceiver(receiver, android.content.IntentFilter(ACTION_RESTORE), Context.RECEIVER_EXPORTED)
            line("[广播] 控制通道已就绪：$ACTION_PROBE / $ACTION_RESTART / $ACTION_SET_WALLPAPER / $ACTION_RESTORE")
        }.onFailure { line("[广播] 注册失败：$it") }
    }

    fun hasView(): Boolean = haveView

    fun acceptView(v: View?, from: String) {
        if (v == null || haveView) return
        haveView = true
        acceptContext(v.context, "$from（视图）")
        if (viewRequested) return
        viewRequested = true
        // 等布局稳定后再抓树
        main.postDelayed({
            runCatching { dumpTree(listOf(v.rootView ?: v), "锚点：$from") }
                .onFailure { line("[视图] 抓取失败：$it") }
        }, 1200)
    }

    /**
     * 不依赖任何 hook 的取树方式：直接向 WindowManagerGlobal 要当前进程所有根视图。
     * 这条路径让探测可以"按需重跑"（例如热重载之后），而不是只能在视图挂载的那一瞬间抓到。
     */
    fun dumpRootViews(from: String, force: Boolean = false) {
        if (viewRequested && !force) {
            line("[视图] 已抓取过，跳过（$from）")
            return
        }
        viewRequested = true
        val roots = rootViews()
        if (roots == null || roots.isEmpty()) {
            line("[视图] 拿不到根视图（$from）")
            return
        }
        dumpTree(roots, from)
    }

    /** 反射取所有根视图（不依赖任何 hook，也不需要 Context）。 */
    private fun rootViews(): List<View>? {
        return runCatching {
            val cls = Class.forName("android.view.WindowManagerGlobal")
            val instance = cls.getDeclaredMethod("getInstance").apply { isAccessible = true }.invoke(null)
            val roots = mutableListOf<View>()
            runCatching {
                @Suppress("UNCHECKED_CAST")
                val list = cls.getDeclaredMethod("getRootViews").apply { isAccessible = true }
                    .invoke(instance) as? List<View>
                list?.let { roots.addAll(it) }
            }.onFailure {
                runCatching {
                    val names = cls.getDeclaredMethod("getViewRootNames").apply { isAccessible = true }
                        .invoke(instance) as? Array<*>
                    val getRoot = cls.getDeclaredMethod("getRootView", String::class.java).apply { isAccessible = true }
                    names?.forEach { n ->
                        (getRoot.invoke(instance, n) as? View)?.let { roots.add(it) }
                    }
                }
            }
            roots
        }.getOrNull()
    }

    fun reportDeductedView(v: View, from: String) {
        line("[抠像层] 来自 $from：类=${v.javaClass.name} 可见=${v.visibility} 尺寸=${v.width}x${v.height} 是否在树中=${v.isAttachedToWindow}")
    }

    // ---------------------------------------------------------------- 4. 视图树

    private fun dumpTree(roots: List<View>, source: String) {
        section("视图树（来源：$source）")

        // 教训：整体遍历会被其它窗口把节点配额吃光，导致锁屏子树根本没走到。
        // 改成先定位锁屏根，只抓它。
        val keyguard = findKeyguardRoot(roots)
        if (keyguard != null) {
            line("锁屏根视图：${keyguard.javaClass.name}${idName(keyguard)?.let { " #$it" } ?: ""} 尺寸=${keyguard.width}x${keyguard.height}")
            if (keyguard is ViewGroup) {
                line("其直接子视图（序号 / 类 / id / 可见性 / 尺寸）：")
                for (i in 0 until keyguard.childCount) {
                    val c = keyguard.getChildAt(i)
                    line("  [$i] ${c.javaClass.name}${idName(c)?.let { " #$it" } ?: ""} vis=${c.visibility} ${c.width}x${c.height}")
                }
            }
            line("锁屏子树（最多 $SUBTREE_NODES 个节点）：")
            dumpSubtree(keyguard, SUBTREE_NODES)
        } else {
            line("未找到 keyguard_root_view，退化为抓取全部根视图")
            roots.forEachIndexed { i, r ->
                line("根 #$i：${r.javaClass.name} 尺寸=${r.width}x${r.height}")
            }
            var budget = MAX_NODES
            for (r in roots) {
                if (budget <= 0) break
                budget -= dumpSubtree(r, budget)
            }
        }

        searchCandidateIds(roots)
        publish(true)
    }

    /** 找锁屏根视图：优先按 id，其次按类名特征。 */
    private fun findKeyguardRoot(roots: List<View>): View? {
        for (root in roots) {
            findByIdIn(root, "keyguard_root_view")?.let { return it }
        }
        for (root in roots) {
            findByClassHint(root, "KeyguardRootView", 0)?.let { return it }
        }
        return null
    }

    private fun findByClassHint(v: View, hint: String, depth: Int): View? {
        if (depth > 20) return null
        if (v.javaClass.simpleName.contains(hint)) return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                findByClassHint(v.getChildAt(i), hint, depth + 1)?.let { return it }
            }
        }
        return null
    }

    /** 按 id 名精确查找：走系统的 id 索引，不受遍历配额限制。 */
    private fun findByIdIn(root: View, name: String): View? {
        for (pkg in ID_PACKAGES) {
            val id = runCatching { root.resources.getIdentifier(name, "id", pkg) }.getOrDefault(0)
            if (id == 0) continue
            runCatching { root.findViewById<View>(id) }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun searchCandidateIds(roots: List<View>) {
        section("候选 id 精确检索")
        for (cand in CANDIDATE_IDS) {
            var hit: String? = null
            for ((i, root) in roots.withIndex()) {
                val v = findByIdIn(root, cand) ?: continue
                val parent = v.parent
                val index = if (parent is ViewGroup) indexOfChild(parent, v) else -1
                hit = "${v.javaClass.name}（根#$i 父=${parent?.javaClass?.simpleName} 序号=$index/${
                    (parent as? ViewGroup)?.childCount ?: 0
                } 可见=${v.visibility} ${v.width}x${v.height} 在树中=${v.isAttachedToWindow}）"
                break
            }
            line(if (hit != null) "✔ $cand → $hit" else "✘ $cand")
        }
    }

    /** 打印一棵子树，返回实际访问节点数（用于消耗配额）。 */
    private fun dumpSubtree(root: View, budget: Int): Int {
        var count = 0

        fun walk(v: View, depth: Int) {
            if (count >= budget || depth > MAX_DEPTH) return
            count++
            if (depth <= PRINT_DEPTH) {
                val idn = idName(v)
                line("${"  ".repeat(depth)}${v.javaClass.simpleName}${idn?.let { " #$it" } ?: ""} vis=${v.visibility}${sizePart(v)}")
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i), depth + 1)
            }
        }

        walk(root, 0)
        return count
    }

    private fun indexOfChild(parent: ViewGroup, child: View): Int {
        for (i in 0 until parent.childCount) if (parent.getChildAt(i) === child) return i
        return -1
    }

    private fun idName(v: View): String? {
        val id = v.id
        if (id == View.NO_ID) return null
        return runCatching { v.resources.getResourceEntryName(id) }.getOrNull()
            ?: "0x${id.toString(16)}"
    }

    private fun sizePart(v: View): String =
        if (v.width > 0 || v.height > 0) " ${v.width}x${v.height}" else ""

    /** 下拉栏/控制中心窗口：列出子视图状态，用来找"面板已展开"的信号。 */
    private fun dumpShade() {
        section("下拉栏/控制中心窗口")
        val roots = rootViews() ?: run { line("拿不到根视图"); return }
        val keyguard = findKeyguardRoot(roots) ?: run { line("找不到锁屏根"); return }
        var host: ViewGroup? = null
        var p: android.view.ViewParent? = keyguard.parent
        while (p != null) {
            if (p is ViewGroup && p.javaClass.simpleName.contains("NotificationShade")) {
                host = p
                break
            }
            p = p.parent
        }
        if (host == null) {
            line("锁屏根的祖先里没有 NotificationShade 窗口")
            return
        }
        line("${host.javaClass.name}：子视图 ${host.childCount} 个")
        line("  [i] 类名 vis y h alpha ty")
        for (i in 0 until host.childCount) {
            val c = host.getChildAt(i)
            line(
                "  [$i] ${c.javaClass.simpleName} vis=${c.visibility} y=${c.y.toInt()} h=${c.height} " +
                    "alpha=${"%.2f".format(c.alpha)} ty=${c.translationY.toInt()}",
            )
        }
        // 再看两层：面板/控制中心里的子视图才是真正会动的东西
        for (i in 0 until host.childCount) {
            val c = host.getChildAt(i)
            val n = c.javaClass.simpleName
            if (n != "NotificationPanelView" && n != "ControlCenterContainer") continue
            line("  ── $n 子树（深度≤2）")
            fun walk(v: View, depth: Int, path: String) {
                line(
                    "    ${"  ".repeat(depth)}$path ${v.javaClass.simpleName} vis=${v.visibility} " +
                        "y=${v.y.toInt()} h=${v.height} ty=${v.translationY.toInt()} alpha=${"%.2f".format(v.alpha)}",
                )
                if (depth >= 2 || v !is ViewGroup) return
                for (k in 0 until v.childCount) walk(v.getChildAt(k), depth + 1, "$path.$k")
            }
            walk(c, 0, "[$i]")
        }
    }

    // ---------------------------------------------------------------- 6. 视差位移（阶段 1+2）

    @Volatile private var parallaxLogged = false
    @Volatile private var cachedHourClock: View? = null
    @Volatile private var cachedMinuteClock: View? = null
    private var smoothX = 0f
    private var smoothY = 0f

    // 三条带上的图层舞台：时钟后 / 时钟前 / 最顶层
    private var stageBehind: com.gravitywall.lock.render.LayerStage? = null
    private var stageFront: com.gravitywall.lock.render.LayerStage? = null
    private var stageTop: com.gravitywall.lock.render.LayerStage? = null

    @Volatile private var stagesBuilt = false
    @Volatile private var stagesCheckCounter = 0

    /** 已确认「配置为空」（系统原状）：停止每帧重扫视图树与配置。 */
    @Volatile private var noInjection = false

    /** 「配置为空」是否已写过报告，避免兜底复查时重复刷屏。 */
    @Volatile private var emptyLogged = false

    /** 是否让系统时钟跟随位移（默认关；调试用） */
    @Volatile private var moveClock = false

    /** 灵敏度与平滑（应用设置里可调；每次重建舞台时重新读取） */
    @Volatile private var fullScaleDeg = FULL_SCALE_DEG
    @Volatile private var smoothFactor = SMOOTH

    @Volatile private var stagesHidden = false
    @Volatile private var lockedCached = false
    /** 检测到"已解锁"的时刻（毫秒）；0 表示当前处于锁屏状态 */
    private var unlockedAt = 0L
    private var lockedCheckCounter = 0

    /** 卸下所有注入的舞台视图，并把时钟位移归零（还原用）。 */
    private fun unmountStages() {
        val had = stageBehind != null || stageFront != null || stageTop != null
        for (stage in listOf(stageBehind, stageFront, stageTop)) {
            runCatching { (stage?.parent as? ViewGroup)?.removeView(stage) }
        }
        stageBehind = null
        stageFront = null
        stageTop = null
        stagesBuilt = false
        stagesHidden = false
        stagesCheckCounter = 0
        runCatching {
            cachedHourClock?.let { it.translationX = 0f; it.translationY = 0f }
            cachedMinuteClock?.let { it.translationX = 0f; it.translationY = 0f }
        }
        if (had) parallaxLogged = false
    }

    /** 把图层舞台挂到三条带上（幂等；系统重建视图树时会自动重挂）。 */
    private fun ensureStageMounted(): Boolean {
        if (noInjection) {
            // 系统原状：配置为空时不再每帧重扫视图树（省电、避免刷写报告文件）。
            // 广播（应用重新应用配置 → rerunReport）会清掉此状态；另每 1800 帧兜底复查一次。
            if (++stagesCheckCounter < 1800) return false
            stagesCheckCounter = 0
            noInjection = false
        }
        if (stagesBuilt) {
            // 不用每帧查（addView 到真正 attach 之间有间隔，查太勤会重复挂载）；
            // 判据用 parent 而不是 isAttachedToWindow——addView 之后 parent 立刻就有。
            if (++stagesCheckCounter < 100) return true
            stagesCheckCounter = 0
            val alive = stageBehind?.parent != null && stageFront?.parent != null && stageTop?.parent != null
            if (alive) return true
            stagesBuilt = false
            line("[舞台] 检测到舞台掉落（系统重建了锁屏视图），准备重挂")
        }

        val roots = rootViews() ?: return false
        val keyguard = findKeyguardRoot(roots) ?: return false
        val hostBehind = findByIdIn(keyguard, "keyguard_background_layer") as? ViewGroup ?: return false
        val hostFront = findByIdIn(keyguard, "keyguard_foreground_layer") as? ViewGroup ?: return false
        val hostTop = findByIdIn(keyguard, "keyguard_panel_view") as? ViewGroup ?: return false

        // 先清掉历史残留，保证每个宿主里只有我们一个舞台（否则会越挂越多）
        for (host in listOf(hostBehind, hostFront, hostTop)) {
            for (i in host.childCount - 1 downTo 0) {
                if (host.getChildAt(i) is LayerStage) host.removeViewAt(i)
            }
        }

        val w = keyguard.width.takeIf { it > 0 } ?: 1080
        val h = keyguard.height.takeIf { it > 0 } ?: 2400

        // 每次重建都重新读一次运行期配置（应用改了设置 → 广播触发重建 → 立即生效）
        runCatching {
            CompositionLoader.setRuntimeJson(prefsProvider()?.getString(State.KEY_RUNTIME_CONFIG, null))
        }
        moveClock = runCatching { CompositionLoader.moveClock() }.getOrDefault(false)
        fullScaleDeg = runCatching { CompositionLoader.fullScaleDeg() }.getOrDefault(FULL_SCALE_DEG)
        smoothFactor = runCatching { CompositionLoader.smooth() }.getOrDefault(SMOOTH)

        // 优先级：项目文件（可直接手改文本）> 海报包 > 图层配置 > 内置测试构图
        val projectPath = runCatching { CompositionLoader.projectPath() }.getOrNull()
        val projectBands = projectPath?.let { path ->
            line("[项目] 尝试加载项目：$path")
            runCatching {
                val json = java.io.File(path).readText()
                val p = com.gravitywall.lock.data.Project.fromJson(json)
                val base = w * runCatching { CompositionLoader.amplitudeRatio() }.getOrDefault(0.03f)
                val bands = p?.bandLayers(w, h, base)
                if (bands != null) {
                    line(
                        "[项目] ${p.name}：时钟后 ${bands.first.size} 层 / 时钟前 ${bands.second.size} 层 / 顶层 ${bands.third.size} 层",
                    )
                }
                bands
            }.getOrNull()
        }

        val packPath = runCatching { CompositionLoader.packPath() }.getOrNull()
        val imported = if (projectBands == null) packPath?.let { path ->
            line("[导入] 尝试导入海报包：$path")
            val flip = runCatching { CompositionLoader.flipY() }.getOrDefault(true)
            val floatingBand = runCatching { CompositionLoader.floatingBand() }.getOrDefault("behind")
            val capRatio = runCatching { CompositionLoader.amplitudeCap() }
                .getOrDefault(ParallaxDefaults.IMPORT_CAP_RATIO)
            runCatching {
                PosterPackImporter.import(path, w, h, flip, floatingBand, capRatio) { note -> line("[导入] $note") }
            }.getOrNull()
        } else {
            null
        }
        val loaded = if (projectBands == null && imported == null) {
            runCatching { CompositionLoader.load({ note -> line("[构图] $note") }, AMPLITUDE_PX, VERTICAL_RATIO) }
                .getOrNull()
        } else {
            null
        }

        val behindLayers: List<StageLayer>
        val frontLayers: List<StageLayer>
        val topLayers: List<StageLayer>
        when {
            projectBands != null -> {
                behindLayers = projectBands.first
                frontLayers = projectBands.second
                topLayers = projectBands.third
            }

            imported != null -> {
                behindLayers = imported.behind
                frontLayers = imported.front
                topLayers = imported.top
            }

            loaded != null -> {
                behindLayers = loaded.behind
                frontLayers = loaded.front
                topLayers = loaded.top
            }

            else -> {
                // 配置为空：不做任何注入（这是"还原系统原状"的状态）
                if (!runCatching { CompositionLoader.testComposition() }.getOrDefault(false)) {
                    val wasInjected = stageBehind != null || stageFront != null || stageTop != null
                    unmountStages()
                    noInjection = true
                    if (wasInjected || !emptyLogged) {
                        emptyLogged = true
                        line("[构图] 配置为空：不注入任何图层（系统原状）")
                    }
                    return false
                }
                val test = TestComposition.build(w, h, AMPLITUDE_PX, VERTICAL_RATIO)
                behindLayers = test.first
                frontLayers = test.second
                topLayers = test.third
                line("[构图] 使用内置测试构图（配置里 testComposition=true）")
            }
        }
        val lp = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        // 关键：系统给 KeyguardPanelView 的子视图显式设了 z（时钟容器 0.0 / 前景层 0.1 /
        // 分钟钟 0.2 / 信息层 1.0）。Android 按 z 排序绘制，同 z 才比索引——
        // 所以我们的舞台必须显式设 z，否则永远被压在系统层下面。
        // 三个舞台各自只画自己的带；但"按 id 找目标层"要看全量（遮罩与目标可能不在同一条带）
        val allRefs = behindLayers + frontLayers + topLayers
        // "时钟后"这条带**不再塞进 keyguard_background_layer**：那个容器同时被系统当作
        // 控制中心的下层背景，塞进去的图层会跟着出现在通知中心里（实测确认）。
        // 改成挂在 KeyguardPanelView 里、紧挨在背景层之后：视觉位置完全一样（壁纸之上、小时数字之下），
        // 但不再借用那个共用容器。z 用 0（与背景层、小时钟同级，靠索引排在小时钟之前）。
        val panel = hostTop
        val bgIndex = indexOfChild(panel, hostBehind)
        val insertAt = if (bgIndex >= 0) (bgIndex + 1).coerceAtMost(panel.childCount) else panel.childCount
        val stageB = LayerStage(hostBehind.context).apply { layers = behindLayers; refs = allRefs; z = 0f }
        panel.addView(stageB, insertAt, lp)

        val stageF = LayerStage(hostFront.context).apply { layers = frontLayers; refs = allRefs; z = 1.0f }
        hostFront.addView(stageF, lp)

        val stageT = LayerStage(hostTop.context).apply { layers = topLayers; refs = allRefs; z = 2.0f }
        hostTop.addView(stageT, lp)

        stageBehind = stageB
        stageFront = stageF
        stageTop = stageT
        if (stagesHidden) {
            for (st in listOf(stageB, stageF, stageT)) st.visibility = View.INVISIBLE
        }
        stagesBuilt = true
        stagesCheckCounter = 0
        noInjection = false
        emptyLogged = false

        line("[舞台] 时钟后已挂载 ${behindLayers.size} 层：${behindLayers.joinToString { "${it.name}(±${"%.0f".format(it.maxX)}px)" }}（挂在 KeyguardPanelView 第 $insertAt 位，z=0）")
        line("[舞台] 时钟前已挂载 ${frontLayers.size} 层：${frontLayers.joinToString { "${it.name}(±${"%.0f".format(it.maxX)}px)" }}（z=1.0）")
        line("[舞台] 最顶层已挂载 ${topLayers.size} 层：${topLayers.joinToString { "${it.name}(±${"%.0f".format(it.maxX)}px)" }}（z=2.0，高于信息层）")
        return true
    }

    /**
     * 已解锁时把注入图层藏起来。
     *
     * 为什么不再"监测面板展开再隐藏"：桌面拉下控制中心的**那一瞬间**，系统会先把锁屏根视图
     * 从 alpha=0 设为 alpha=1，我们再隐藏总是慢一两帧，于是会闪一下锁屏壁纸。
     * 改成"解锁后就一直隐藏"：这段时间锁屏本来就不显示，藏起来没有任何代价，
     * 而控制中心/桌面无论何时都采样不到我们；锁屏出现时立刻恢复显示。
     * 解锁后延迟 UNLOCK_HIDE_DELAY_MS 毫秒再隐藏，避开解锁动画（否则图层会从动画里"跳掉"）。
     */
    private fun syncStagesVisibility() {
        // isKeyguardLocked 是 binder 调用，按回调次数节流（约 200ms 一次）
        if (++lockedCheckCounter >= 10) {
            lockedCheckCounter = 0
            val locked = isKeyguardLocked()
            if (locked != lockedCached) {
                lockedCached = locked
                unlockedAt = if (locked) 0L else System.currentTimeMillis()
            }
        }
        val hide = !lockedCached && unlockedAt > 0L &&
            System.currentTimeMillis() - unlockedAt > UNLOCK_HIDE_DELAY_MS
        if (hide == stagesHidden) return
        stagesHidden = hide
        val vis = if (hide) View.INVISIBLE else View.VISIBLE
        for (stage in listOf(stageBehind, stageFront, stageTop)) stage?.visibility = vis
        line(
            if (hide) {
                "[可见性] 已解锁：隐藏注入图层（桌面/控制中心都看不到，锁屏时自动恢复）"
            } else {
                "[可见性] 回到锁屏：恢复显示注入图层"
            },
        )
    }

    private fun isKeyguardLocked(): Boolean = runCatching {
        val km = context?.getSystemService(android.content.Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
        km?.isKeyguardLocked ?: false
    }.getOrDefault(false)

    /** 解析位移目标：先拿缓存，掉了再按 id 重新找。 */
    private fun parallaxTargets(): Pair<View?, View?> {
        val h = cachedHourClock
        val m = cachedMinuteClock
        if (h?.isAttachedToWindow == true && m?.isAttachedToWindow == true) return h to m
        val roots = rootViews() ?: return h to m
        val keyguard = findKeyguardRoot(roots) ?: return h to m
        if (h?.isAttachedToWindow != true) cachedHourClock = findByIdIn(keyguard, "miui_keyguard_clock_container")
        if (m?.isAttachedToWindow != true) cachedMinuteClock = findByIdIn(keyguard, "miui_keyguard_foreground_clock_container")
        return cachedHourClock to cachedMinuteClock
    }

    /**
     * 把当前倾斜施加成位移。
     * 当前作用于两个时钟容器（永远可见），用于验证"倾斜 → 画面动"这条链路；
     * 阶段 3 会换成我们自己的图层舞台。
     */
    private fun applyParallax() {
        syncStagesVisibility()
        val tilt = lastTilt ?: return
        val (hour, minute) = parallaxTargets()

        val normX = (tilt[0] / fullScaleDeg).coerceIn(-1f, 1f)
        val normY = (tilt[1] / fullScaleDeg).coerceIn(-1f, 1f)
        val targetX = normX * AMPLITUDE_PX
        val targetY = normY * AMPLITUDE_PX * VERTICAL_RATIO
        smoothX += (targetX - smoothX) * smoothFactor
        smoothY += (targetY - smoothY) * smoothFactor

        hour?.let {
            if (moveClock) {
                it.translationX = smoothX
                it.translationY = smoothY
            } else if (it.translationX != 0f || it.translationY != 0f) {
                // 默认不让系统时钟跟着动（方案 Q13）
                it.translationX = 0f
                it.translationY = 0f
            }
        }
        minute?.let {
            if (moveClock) {
                it.translationX = smoothX
                it.translationY = smoothY
            } else if (it.translationX != 0f || it.translationY != 0f) {
                it.translationX = 0f
                it.translationY = 0f
            }
        }

        // 阶段 3：两条带上的图层舞台，每层按自己的系数位移
        if (ensureStageMounted()) {
            stageBehind?.applyTilt(normX, normY)
            stageFront?.applyTilt(normX, normY)
            stageTop?.applyTilt(normX, normY)
        }

        if (!parallaxLogged) {
            parallaxLogged = true
            line("[视差] 已启用：满量程 ${fullScaleDeg}°，幅度 ${AMPLITUDE_PX}px，垂直比 $VERTICAL_RATIO，平滑 $smoothFactor")
        }
    }

    /** 供实时数据展示的位移描述。 */
    private fun parallaxDesc(): String {
        val stage = buildString {
            append(if (stageBehind?.isAttachedToWindow == true) "后✔" else "后✘")
            append(if (stageFront?.isAttachedToWindow == true) "前✔" else "前✘")
            append(if (stageTop?.isAttachedToWindow == true) "顶✔" else "顶✘")
        }
        return "位移 X=%+.1f Y=%+.1f px $stage".format(smoothX, smoothY)
    }

    // ---------------------------------------------------------------- 7. 传感器

    private fun reportSensors() {
        val ctx = context ?: return
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        if (sm == null) {
            line("[传感器] 取不到 SensorManager")
            return
        }
        sensorManager = sm
        section("传感器")
        for ((type, label) in SENSOR_CANDIDATES) {
            val s = sm.getDefaultSensor(type)
            if (s == null) {
                line("✘ $label（type=$type）：无")
                continue
            }
            val wake = sm.getDefaultSensor(type, true)
            val maxHz = if (s.minDelay > 0) "%.1f".format(1_000_000f / s.minDelay) else "?"
            val minHz = if (s.maxDelay > 0) "%.1f".format(1_000_000f / s.maxDelay) else "?"
            line("✔ $label（type=$type）：${s.name}")
            line("    速率 $minHz ~ $maxHz Hz，FIFO=${s.fifoMaxEventCount}${if (wake != null) "，有唤醒版本" else "，无唤醒版本"}")
        }
        line("传感器总数：${sm.getSensorList(Sensor.TYPE_ALL).size}")
        startTilt(sm)
    }

    private fun startTilt(sm: SensorManager) {
        if (subscribed != null) return
        val sensor = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sm.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (sensor == null) {
            line("[倾斜] 无可用传感器")
            return
        }
        val l = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val tilt = when (event.sensor.type) {
                    Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_ROTATION_VECTOR ->
                        TiltMath.fromRotationVector(event.values)
                    else -> TiltMath.fromGravity(event.values[0], event.values[1], event.values[2])
                } ?: return
                lastTilt = tilt
                // 视图操作必须在主线程
                main.post { runCatching { applyParallax() } }
                publishLive()
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        val ok = runCatching { sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_GAME) }.getOrDefault(false)
        if (ok) {
            listener = l
            subscribed = sensor
            line("[倾斜] 已订阅：${sensor.name}（${sensor.stringType}），目标 50Hz")
        } else {
            line("[倾斜] 订阅失败")
        }
    }

    private fun publishLive() {
        val tilt = lastTilt ?: return
        val now = System.currentTimeMillis()
        if (now - lastLiveWrite >= 250) {
            lastLiveWrite = now
            val line = "左右 %+.1f°  前后 %+.1f°  |  %s  @%s".format(
                tilt[0], tilt[1], parallaxDesc(), timeFmt.format(Date(now)),
            )
            val p = prefs ?: prefsProvider()?.also { prefs = it }
            runCatching { p?.edit()?.putString(State.KEY_LIVE, line)?.apply() }
            if (now - lastLiveFile >= 1000) {
                lastLiveFile = now
                runCatching { writeFile(liveFile, "$line\n") }
            }
        }
        if (now - lastLiveLog >= 2000) {
            lastLiveLog = now
            line("[倾斜] 左右 %+.1f°  前后 %+.1f°".format(tilt[0], tilt[1]))
        }
    }

    // ---------------------------------------------------------------- 常量

    private companion object {
        const val MAX_NODES = 260
        const val MAX_DEPTH = 15
        const val PRINT_DEPTH = 12
        /** 单棵子树打印配额（找锁屏根之后只抓它） */
        const val SUBTREE_NODES = 400

        /** 查找 id 时依次尝试的资源包 */
        val ID_PACKAGES = listOf("com.android.systemui", "android")

        /** 重跑探测的广播 action */
        const val ACTION_PROBE = "com.gravitywall.lock.PROBE"

        /** 让 SystemUI 进程退出以重新加载模块代码（开发期用） */
        const val ACTION_RESTART = "com.gravitywall.lock.RESTART_SYSTEMUI"

        /** 把指定图片设为锁屏壁纸：am broadcast -a com.gravitywall.lock.SET_WALLPAPER --es path /sdcard/... [--es which both] */
        const val ACTION_SET_WALLPAPER = "com.gravitywall.lock.SET_WALLPAPER"

        /** 还原系统原状：清空配置、卸下注入图层、时钟归位 */
        const val ACTION_RESTORE = "com.gravitywall.lock.RESTORE"

        /** 壁纸备份目录 */
        const val BACKUP_DIR = "/sdcard/Download/gravitywall"

        // ---- 视差参数（先用常量；阶段 4 接到应用里的可调配置） ----
        /** 满量程角度：倾斜到该角度时达到最大位移 */
        /** 解锁后多久才隐藏注入图层（毫秒）：留出解锁动画的时间 */
        const val UNLOCK_HIDE_DELAY_MS = 600L

        const val FULL_SCALE_DEG = 10f
        /** 系数 1.0 时的最大位移（像素）。当前为**测试值**，便于肉眼验收；最终默认值约 32px（屏宽 3%） */
        const val AMPLITUDE_PX = 96f
        /** 垂直方向相对水平的比例 */
        const val VERTICAL_RATIO = 0.6f
        /** 低通平滑系数 */
        const val SMOOTH = 0.25f

        /** 原模块已在真机验证存在的类 */
        val CLASSES_PROVEN = listOf(
            "com.android.keyguard.depth.KeyguardDepthInteractor",
            "com.android.keyguard.panel.KeyguardPanelViewController",
            "com.android.keyguard.wallpaper.entity.LargeScreenHierarchyEnable",
            "com.android.keyguard.wallpaper.entity.WallpaperInfo",
            "com.android.systemui.keyguard.domain.interactor.KeyguardTouchHandlingInteractor",
            "com.android.systemui.keyguard.ui.binder.KeyguardSettingsViewBinder",
            "com.android.systemui.keyguard.ui.view.layout.sections.DefaultSettingsPopupMenuSection",
            "com.miui.keyguard.editor.data.template.TemplateApiImpl",
            "com.miui.keyguard.editor.data.template.TemplateFileProvider",
            "com.miui.keyguard.editor.edit.base.EffectsTemplateView",
            "com.miui.keyguard.editor.edit.wallpaper.WallpaperController",
            "com.miui.keyguard.editor.utils.HierarchyImageAvoidController",
            "com.miui.clock.utils.avoid.DepthAvoidEvaluator",
        )

        /** 来自开源项目 HyperChanger，需真机核对 */
        val CLASSES_TO_VERIFY = listOf(
            "com.android.keyguard.clock.KeyguardClockContainer",
            "com.miui.miwallpaper.opengl.ImageWallpaperRenderer",
            "com.android.keyguard.shortcut.MiuiShortcutController",
            "com.miui.keyguard.VideoDepthSurfaceHolder",
            "com.android.keyguard.VideoDepthSurfaceHolder",
        )

        /** 目标视图 id（不带 res 前缀） */
        val CANDIDATE_IDS = listOf(
            "keyguard_background_layer",
            "keyguard_foreground_layer",
            "deducted_image_view",
            "miui_keyguard_clock_container",
            "miui_keyguard_foreground_clock_container",
            "keyguard_root_view",
        )

        val SENSOR_CANDIDATES = listOf(
            Sensor.TYPE_ACCELEROMETER to "加速度计",
            Sensor.TYPE_GYROSCOPE to "陀螺仪",
            Sensor.TYPE_GRAVITY to "重力",
            Sensor.TYPE_ROTATION_VECTOR to "旋转矢量",
            Sensor.TYPE_GAME_ROTATION_VECTOR to "游戏旋转矢量",
        )
    }
}

/** 取当前进程名（公开 API 优先，退路读 /proc/self/cmdline）。 */
private fun currentProcessNameSafe(): String =
    runCatching { android.app.Application.getProcessName() }.getOrNull()
        ?: runCatching {
            java.io.File("/proc/self/cmdline").readText().trimEnd('\u0000')
        }.getOrDefault("?")
