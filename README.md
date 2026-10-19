# 重力壁纸（GravityWall）

HyperOS 4 锁屏的**多图层重力感应视差壁纸**：一个运行在 SystemUI 进程里的 libxposed 模块
（负责订阅传感器、按倾斜实时位移各图层），外加一个 Compose 编辑器应用（负责编排图层、
实时预览、导入/导出海报包）。

- 📐 设计方案与开发日志：[GravityWall-docs](https://github.com/jawnd/GravityWall-docs)
- 📱 目标平台：HyperOS 4 / Android 17（`compileSdk 37`），`minSdk 35`
- 🧩 模块 API：libxposed API 102（`io.github.libxposed:api`）

> 仅面向 HyperOS 4 的锁屏。不同机型/系统版本的视图树可能不同，项目里对内部类与视图 id
> 的引用都集中管理，装不上时会在报告里明确写出来，不会影响系统运行。

## 当前能力

- **三条挂载带**：时钟后（壁纸之上、小时数字之下）、时钟前（小时与分钟之间）、最顶层；
  系统时钟被拆成「小时 / 分钟」两行，图层可以直接排在两者之间。
- **图层**：图片 / 纯色、位置 / 尺寸 / 旋转 / 不透明度、填充方式、圆角、显示隐藏；
  图层清单拖拽排序，长按菜单（重命名 / 上下移 / 重置 / 删除），撤销重做。
- **遮罩图层**：方形 / 圆形 / 自定义图片（按亮度或按透明度读取），通过「作用于」选定
  目标图层；遮罩本身是图层，有独立的视差与层级（窗口语义：只露出目标层在形状内的部分）。
- **视差**：影响系数 / 绝对像素两种互斥模式，数值可直接输入；满量程角度、平滑度、幅度
  基准可在设置页调整。
- **海报包**：兼容 iOS PosterBoard `.tendies` 的导入与导出；另有自创的作品包 `.gwp`
  （本质是 `project.json` + `images/` 的压缩包）用于本机备份 / 搬运。
- **目录**：项目在 `/sdcard/Download/gravitywall/projects/`，海报包 / 作品包在
  `/sdcard/Download/gravitywall/packs/`（应用内可浏览、导入、导出）。
- **液态玻璃**：可指定「锁屏壁纸源图层」并手动写入系统锁屏壁纸（应用不会自动改壁纸）。

## 构建

依赖：

- JDK 17
- Android SDK：`platforms;android-37.0`、`build-tools;37.0.0`
- Gradle 9.6.1（AGP 9 内置 Kotlin 支持，**不要**再应用 `org.jetbrains.kotlin.android`）

工程必须放在**纯 ASCII 路径**下——AGP 会直接拒绝含非 ASCII 字符的路径（不要用
`android.overridePathCheck` 绕过）。

```bash
# 修改 tools/build.sh 里的 JAVA_HOME 与 gradle 路径后：
./tools/build.sh assembleDebug

# 或直接用 gradle：
JAVA_HOME=/path/to/jdk17 /path/to/gradle assembleDebug --console=plain
```

产物：`app/build/outputs/apk/debug/app-debug.apk`。

`local.properties` 需要指向本机 Android SDK。

## 安装

1. 安装 APK；
2. 在 Xposed 管理器（LSPosed）里启用「重力壁纸」，作用域勾选 **系统界面
   （com.android.systemui）**；`com.miui.aod` 已在 `scope.list` 中声明（用于后续能力）；
3. 重启系统界面（或重启手机）；
4. 打开应用：项目页新建 / 导入项目 → 编辑器里编排 → 「保存 ▾ → 应用到锁屏」，
   灭屏再亮即可看到效果。

首次使用需要授予「全部文件访问」权限（项目和包都在共享存储里）。
