# 重力壁纸（GravityWall）

HyperOS 4 锁屏的多图层重力感应视差模块 + 配套应用。
设计方案见 `设计方案.md`（在会话工作目录里）。

**当前进度：阶段 0 —— 探测与核对**。本阶段的产物是一个"诊断版"模块：它在 SystemUI 进程里
探测锁屏视图树、目标类存在性和传感器能力，把结果写成一份报告，应用内可直接查看与复制。
这部分代码同时也是后续所有功能的基础设施（锚点安装、反射工具、跨进程通道）。

---

## 工程位置

工程必须放在**纯英文路径**下：AGP 会拒绝在含非 ASCII 字符的路径上构建（这是官方校验，
不要用 `android.overridePathCheck` 去绕）。当前位置：

```
C:\Users\98687\Documents\GravityWall
```

## 构建

依赖：JDK 17、Android SDK（platform 36 + build-tools 36.1.0）、Gradle 9.6.1、本机代理
（`~/.gradle/gradle.properties` 里已配好 `127.0.0.1:7890`）。

```bash
cd /c/Users/98687/Documents/GravityWall
JAVA_HOME='C:\Users\98687\Documents\Codex\2026-10-03\wei\work\jdk17\jdk-17.0.20.1+1' \
  /c/Users/98687/tools/gradle/gradle-9.6.1/bin/gradle.bat assembleDebug --console=plain
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

`local.properties` 已指向本机 SDK；换机器时需要改这一行。

## 安装与验收（阶段 0）

1. 安装 APK（可以把这个文件发到手机上直接安装，或 `adb install -r app-debug.apk`）；
2. 打开 LSPosed 管理器 → 模块 → 勾选「重力壁纸」；
3. 作用域勾选 **系统界面（com.android.systemui）**（`com.miui.aod` 已声明，用于后续的遮罩替换线，本阶段不用）；
4. 重启系统界面或重启手机；
5. **锁屏一次，然后点亮屏幕**（这一步会触发锁屏视图树抓取）；
6. 打开「重力壁纸」应用，看三样东西：
   - **框架状态**：应显示"框架已连接 + 作用域"；
   - **实时倾角**：倾斜手机，数字应实时变化（这一条验证了传感器通路）；
   - **探测报告**：里面是我们需要核对的全部事实；
7. 点「复制报告」，把内容发我。

### 报告里我们要核对什么

| 报告段落 | 核对目的 |
|---|---|
| 目标类存在性 | 哪些 HyperOS 内部类真实存在（两个来源的情报各自对不对） |
| 锚点安装 | 哪些挂钩点装上了、哪些失败（失败不影响系统） |
| 锁屏视图树 | 锁屏的真实层级结构 |
| 候选挂载点检索 | `keyguard_background_layer` / `keyguard_foreground_layer` / `deducted_image_view` 等 id 是否真实存在、在谁下面、序号多少 |
| 传感器 | 各候选传感器是否存在、速率范围，以及实际订阅到哪一个 |

## 代码结构

```
app/src/main/java/com/gravitywall/lock/
├─ State.kt              模块与应用共享的远程偏好键名
├─ MainActivity.kt       诊断页（读报告、看实时倾角、复制报告）
└─ hook/
   ├─ ModuleEntry.kt     模块入口：装锚点、捕获 Context 与视图
   ├─ Probe.kt           探测逻辑与报告生成
   ├─ Reflect.kt         反射工具（API 102 不提供 XposedHelpers）
   └─ TiltMath.kt        重力/旋转矢量 → 左右、前后倾角
app/src/main/resources/META-INF/xposed/
├─ module.prop           模块元数据 (minApiVersion=102, staticScope=true)
├─ java_init.list        入口类声明
└─ scope.list            作用域：com.android.systemui / com.miui.aod
```

## 设计约定（后续阶段沿用）

- **所有系统内部类名/方法名/字段名集中在 `Probe` 与 `ModuleEntry` 的常量里**，并区分
  「已由原模块真机验证」与「来自开源情报待核对」两档可信度；
- **每个 hook 独立失败隔离**：装不上只记报告，绝不影响系统运行；
- **失效必须可见**：报告与诊断页会明确写出哪个点没装上，避免"效果没了但不知道原因"。
