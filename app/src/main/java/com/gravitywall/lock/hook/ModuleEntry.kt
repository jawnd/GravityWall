package com.gravitywall.lock.hook

import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.view.View
import com.gravitywall.lock.State
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * 模块入口（由 META-INF/xposed/java_init.list 声明）。
 *
 * 阶段 0 的工作：在 SystemUI 进程里装一组"锚点"，把锁屏的视图树、目标类、传感器能力
 * 探测出来并写成报告，供应用内查看。所有锚点都做失败隔离——任何一个装不上都不会
 * 影响系统运行，只会记进报告。
 */
class ModuleEntry : XposedModule() {

    private var probe: Probe? = null
    private var targetLoader: ClassLoader? = null

    @Volatile
    private var cachedPrefs: SharedPreferences? = null

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        log(Log.INFO, TAG, "模块已加载：api=$apiVersion 框架=$frameworkName $frameworkVersion 进程=${param.processName} 系统服务=${param.isSystemServer}")
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        val pkg = param.packageName
        log(Log.INFO, TAG, "包已加载：$pkg（first=${param.isFirstPackage}）")
        if (probe != null) return
        targetLoader = param.defaultClassLoader

        val p = Probe(
            { m -> log(Log.INFO, TAG, m) },
            { remotePreferences() },
            reportPathFor(pkg),
            { targetLoader },
        )
        probe = p
        try {
            p.reportFramework(apiVersion, frameworkName, frameworkVersion, frameworkVersionCode, frameworkProperties)
            p.line("[触发] 首次加载：$pkg")
            p.reportClasses(targetLoader)
            if (pkg == SYSTEMUI_PACKAGE) {
                installAnchors(p, targetLoader)
                // 兜底路径：无论锚点是否触发，启动后 3 秒直接从 WindowManagerGlobal 抓一次根视图
                p.dumpRootViewsDelayed(3000, "启动后定时抓取")
            } else {
                p.line("（该进程只做类存在性核对，不装锚点）")
                p.finish()
            }
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "探测过程异常", t)
            p.line("[异常] $t")
            p.finish()
        }
    }

    /** 每个进程写自己的报告文件，互不覆盖。 */
    private fun reportPathFor(pkg: String): String {
        val suffix = when (pkg) {
            "com.android.systemui" -> "systemui"
            "com.miui.aod" -> "aod"
            "com.miui.miwallpaper" -> "miwallpaper"
            else -> pkg.replace('.', '_')
        }
        return "/sdcard/Download/gravitywall_report_$suffix.txt"
    }

    /** 允许热重载：改完代码重新安装后，不必重启手机就能让新代码生效。 */
    override fun onHotReloading(param: XposedModuleInterface.HotReloadingParam): Boolean {
        log(Log.INFO, TAG, "收到热重载请求：允许")
        return true
    }

    override fun onHotReloaded(param: XposedModuleInterface.HotReloadedParam) {
        val process = param.processName
        log(Log.INFO, TAG, "热重载完成（进程=$process）：卸旧钩子并自举")

        // 旧实例装的锚点还在，会继续跑旧代码——逐个卸载
        var unhooked = 0
        for (h in param.oldHookHandles) {
            if (runCatching { h.unhook() }.isSuccess) unhooked++
        }
        log(Log.INFO, TAG, "已卸载旧钩子 $unhooked 个")

        probe = null
        targetLoader = null

        val p = Probe(
            { m -> log(Log.INFO, TAG, m) },
            { remotePreferences() },
            reportPathFor(process ?: SYSTEMUI_PACKAGE),
            { targetLoader },
        )
        probe = p
        runCatching { p.bootstrap(process ?: "未知进程") }
            .onFailure { log(Log.ERROR, TAG, "自举失败", it) }
    }

    // ------------------------------------------------------------------ 远程偏好

    private fun remotePreferences(): SharedPreferences? {
        cachedPrefs?.let { return it }
        return runCatching { getRemotePreferences(State.GROUP) }
            .onFailure { log(Log.WARN, TAG, "取远程偏好失败：$it") }
            .getOrNull()
            ?.also { cachedPrefs = it }
    }

    // ------------------------------------------------------------------ 锚点

    private fun installAnchors(p: Probe, loader: ClassLoader?) {
        p.section("锚点安装")

        // A. Context 来源：Instrumentation 会在进程启动时回调带 Application 的方法
        hookOne(
            p,
            "Instrumentation#callApplicationOnCreate",
            runCatching {
                Instrumentation::class.java.getDeclaredMethod("callApplicationOnCreate", Application::class.java)
            }.getOrNull(),
        ) { chain ->
            p.acceptContext(chain.getArg(0) as? Application, "Instrumentation")
        }

        // B. Context 来源：Application.attachBaseContext（子类通常不会重写）
        hookOne(
            p,
            "Application#attachBaseContext",
            runCatching {
                Application::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
            }.getOrNull(),
        ) { chain ->
            p.acceptContext(chain.getArg(0) as? Context, "Application.attachBaseContext")
        }

        // C. 视图来源：从已证实存在的类的方法里捞 View（字段优先，其次参数）
        for (name in VIEW_ANCHOR_CLASSES) {
            val cls = Reflect.loadClass(loader, name)
            if (cls == null) {
                p.line("· $name 不存在，跳过")
                continue
            }
            var installed = 0
            for (m in Reflect.hookableMethods(cls, cap = 10)) {
                if (hookOne(p, null, m) { chain -> harvestView(p, chain, name) }) installed++
            }
            p.line("· $name：已挂钩 $installed 个方法用于取视图")
        }
    }

    /** 从被钩方法的 this 与参数里找 View，顺便专项核对抠像层字段。 */
    private fun harvestView(p: Probe, chain: XposedInterface.Chain, from: String) {
        if (p.hasView()) return
        val self = chain.getThisObject()

        if (from.endsWith("KeyguardDepthInteractor")) {
            (Reflect.readField(self, "deductedImageView") as? View)
                ?.let { p.reportDeductedView(it, "字段 deductedImageView") }
        }

        (Reflect.readFieldByType(self, View::class.java) as? View)
            ?.let { p.acceptView(it, "$from（字段）") }

        val args = chain.args
        for (i in args.indices) {
            (args[i] as? View)?.let { p.acceptView(it, "$from（参数$i）") }
        }
    }

    /**
     * 挂一个钩子。失败只记报告，不抛出；label 为 null 时静默（批量挂钩用）。
     */
    private fun hookOne(
        p: Probe,
        label: String?,
        exe: java.lang.reflect.Executable?,
        action: (XposedInterface.Chain) -> Unit,
    ): Boolean {
        if (exe == null) {
            label?.let { p.line("✘ $it：目标方法不存在") }
            return false
        }
        return runCatching {
            hook(exe).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    // 只包住我们自己的处理逻辑，proceed 必须照常执行
                    runCatching { action(chain) }
                        .onFailure { log(Log.WARN, TAG, "锚点处理异常（${chain.executable}）", it) }
                    return chain.proceed()
                }
            })
            label?.let { l -> p.line("✔ $l") }
            true
        }.getOrElse {
            label?.let { l -> p.line("✘ $l：$it") }
            false
        }
    }

    private companion object {
        const val TAG = "GravityWall"
        const val SYSTEMUI_PACKAGE = "com.android.systemui"

        /** 用来取视图的锚点类：优先选"已被原模块真机验证存在"的控制器类。 */
        val VIEW_ANCHOR_CLASSES = listOf(
            "com.android.keyguard.depth.KeyguardDepthInteractor",
            "com.android.keyguard.panel.KeyguardPanelViewController",
            "com.android.keyguard.clock.KeyguardClockContainer",
            "com.android.systemui.keyguard.ui.binder.KeyguardSettingsViewBinder",
        )
    }
}
