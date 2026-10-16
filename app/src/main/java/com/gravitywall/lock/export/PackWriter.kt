package com.gravitywall.lock.export

import com.gravitywall.lock.data.Project
import com.gravitywall.lock.data.ProjectLayer
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 把项目导出成 `.tendies` 海报包（与 iOS PosterBoard 兼容）。
 *
 * 结构（与社区包一致）：
 * ```
 * descriptors/<UUID>/versions/1/contents/<名称>.wallpaper/
 *     ├ <名称>_Background.ca/{index.xml, main.caml, assets}
 *     ├ <名称>_Floating.ca/…      ← 时钟前带
 *     ├ <名称>_Foreground.ca/…    ← 最顶层带
 *     └ Wallpaper.plist           ← wantsDeviceMotion = true
 * ```
 * 坐标按 CoreAnimation 的约定写（`geometryFlipped="0"`，原点左下、y 向上），
 * 与导入器互为逆运算，所以"导出 → 再导入"是无损的。
 */
internal object PackWriter {

    /** 导出用的设计高度（点）；宽度按屏幕比例算，保证等比 */
    const val DESIGN_H = 844f

    data class Result(val ok: Boolean, val message: String)

    fun scaleFor(screenH: Int): Float = screenH / DESIGN_H

    fun write(
        outFile: File,
        project: Project,
        screenW: Int,
        screenH: Int,
        baseAmplitudePx: Float = Project.defaultBaseAmplitude(screenW),
    ): Result {
        val k = scaleFor(screenH)
        val designW = screenW / k
        val safeName = project.name.replace(Regex("[^\\w\\u4e00-\\u9fa5-]"), "_").take(24).ifEmpty { "wallpaper" }
        val bundleBase = "9999.$safeName"

        return runCatching {
            outFile.parentFile?.mkdirs()
            ZipOutputStream(outFile.outputStream().buffered()).use { zip ->
                val descUuid = UUID.randomUUID().toString().uppercase()
                val base = "descriptors/$descUuid/versions/1/contents/$bundleBase-390w-844h@3x~iphone.wallpaper"

                for ((band, suffix) in listOf("behind" to "Background", "front" to "Floating", "top" to "Foreground")) {
                    // 与舞台保持一致：停用但挂着启用中遮罩的层也要导出（它只在遮罩里显示）
                    val layers = project.layers.filter { l ->
                        l.band == band && (l.visible || (l.maskShape == null &&
                            project.layers.any { it.maskShape != null && it.visible && it.maskTargetId == l.id }))
                    }.sortedBy { it.z }
                    val caName = "${bundleBase}_$suffix-390w-844h@3x~iphone.ca"
                    zip.putText("$base/$caName/index.xml", INDEX_XML)
                    zip.putText("$base/$caName/main.caml", camlFor(designW, DESIGN_H, layers, k, baseAmplitudePx))
                    // 该带用到的图片拷进这个 bundle 的 assets
                    val copied = mutableSetOf<String>()
                    for (l in layers) {
                        val rel = l.image ?: continue
                        val src = File(com.gravitywall.lock.data.Project.imagesDir(project.name), rel)
                        if (!src.isFile || !copied.add(rel)) continue
                        zip.putNextEntry(ZipEntry("$base/$caName/assets/$rel"))
                        src.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }

                zip.putText("$base/Wallpaper.plist", wallpaperPlist(safeName))
                zip.putText("descriptors/$descUuid/com.apple.posterkit.provider.descriptor.identifier", descUuid)
                zip.putText("descriptors/$descUuid/com.apple.posterkit.role.identifier", "com.apple.posterkit.role.lock")
                zip.putText("descriptors/$descUuid/providerInfo.plist", providerInfoPlist())
            }
            Result(true, "已导出：${outFile.name}")
        }.getOrElse { Result(false, "导出失败：$it") }
    }

    // ------------------------------------------------------------------ caml 生成

    private fun camlFor(designW: Float, designH: Float, layers: List<ProjectLayer>, k: Float, baseAmplitudePx: Float): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<caml xmlns=\"http://www.apple.com/CoreAnimation/1.0\">\n")
        sb.append(
            "<CALayer name=\"Root Layer\" bounds=\"0 0 ${f(designW)} ${f(designH)}\" " +
                "position=\"${f(designW / 2)} ${f(designH / 2)}\" geometryFlipped=\"0\" opacity=\"1\">\n  <sublayers>\n",
        )
        // 每个"遮罩"都是一扇窗口：它包住目标层的内容副本，窗口自己的 z 决定压住谁。
        // 所以导出时：遮罩层 → 输出成"父层(masksToBounds+cornerRadius) + 目标层副本"；
        // 目标层自己若还显示，则在它自己的 z 上照常再输出一份。
        for (l in layers) {
            if (l.maskShape != null) {
                if (l.maskShape == "image") continue // 图片遮罩不导出（生态里没有这种写法）
                val target = layers.firstOrNull { it.id == l.maskTargetId } ?: continue
                val r = if (l.maskShape == "round") minOf(l.w, l.h) / 2f else l.maskRadius
                val wcx = (l.x + l.w / 2f) / k
                val wcy = designH - (l.y + l.h / 2f) / k
                sb.append("    <CALayer name=\"${esc(l.name)}\" zPosition=\"${l.z}\" opacity=\"1\"")
                sb.append(" bounds=\"0 0 ${f(l.w / k)} ${f(l.h / k)}\" position=\"${f(wcx)} ${f(wcy)}\"")
                sb.append(" masksToBounds=\"1\"")
                if (r > 0f) sb.append(" cornerRadius=\"${f(r / k)}\"")
                sb.append(">\n      <sublayers>\n")
                // 窗口里的副本：坐标相对遮罩框左下角（y 向上）
                val relX = (target.x + target.w / 2f - l.x) / k
                val relY = (l.y + l.h - (target.y + target.h / 2f)) / k
                sb.append("        <CALayer name=\"${esc(target.name)} 窗口内容\" zPosition=\"0\" opacity=\"${f(target.alpha / 255f)}\"")
                if (target.rotation != 0f) {
                    sb.append(" transform=\"rotate(${f(-target.rotation)}deg) rotate(0deg, 0, 1, 0) rotate(0deg, 1, 0, 0)\"")
                }
                if (target.w > 0 && target.h > 0) {
                    sb.append(" bounds=\"0 0 ${f(target.w / k)} ${f(target.h / k)}\" position=\"${f(relX)} ${f(relY)}\"")
                }
                val tc = target.color
                if (target.image == null && tc != null) {
                    val rr = ((tc shr 16) and 0xFF) / 255f
                    val gg = ((tc shr 8) and 0xFF) / 255f
                    val bb = (tc and 0xFF) / 255f
                    sb.append(" backgroundColor=\"${f(rr)} ${f(gg)} ${f(bb)}\"")
                }
                sb.append(
                    when (target.fill) {
                        "crop" -> " contentsGravity=\"resizeAspectFill\""
                        "fit" -> " contentsGravity=\"resizeAspect\""
                        else -> " contentsGravity=\"resize\""
                    },
                )
                sb.append(">\n")
                if (target.image != null) {
                    sb.append("          <contents><CGImage src=\"assets/${esc(target.image!!)}\"/></contents>\n")
                }
                sb.append("          <sublayers/>\n        </CALayer>\n")
                sb.append("      </sublayers>\n    </CALayer>\n")
                continue
            }
            if (!l.visible) continue // 停用的层本身不输出（它的窗口在上面单独输出）
            sb.append("    <CALayer name=\"${esc(l.name)}\" zPosition=\"${l.z}\" opacity=\"${f(l.alpha / 255f)}\"")
            if (l.rotation != 0f) {
                // 屏幕坐标(y 向下) → CAML(y 向上)：旋转取反
                sb.append(" transform=\"rotate(${f(-l.rotation)}deg) rotate(0deg, 0, 1, 0) rotate(0deg, 1, 0, 0)\"")
            }
            if (l.w > 0 && l.h > 0) {
                sb.append(" bounds=\"0 0 ${f(l.w / k)} ${f(l.h / k)}\" position=\"${f((l.x + l.w / 2f) / k)} ${f(designH - (l.y + l.h / 2f) / k)}\"")
            }
            val color = l.color
            if (l.image == null && color != null) {
                val rr = ((color shr 16) and 0xFF) / 255f
                val gg = ((color shr 8) and 0xFF) / 255f
                val bb = (color and 0xFF) / 255f
                sb.append(" backgroundColor=\"${f(rr)} ${f(gg)} ${f(bb)}\"")
            }
            sb.append(
                when (l.fill) {
                    "crop" -> " contentsGravity=\"resizeAspectFill\""
                    "fit" -> " contentsGravity=\"resizeAspect\""
                    else -> " contentsGravity=\"resize\""
                },
            )
            sb.append(">\n")
            if (l.image != null) {
                sb.append("      <contents><CGImage src=\"assets/${esc(l.image!!)}\"/></contents>\n")
            }
            sb.append("      <sublayers/>\n    </CALayer>\n")
        }
        sb.append("  </sublayers>\n</CALayer>\n")

        // 视差规则：只给有位移的图层写
        val rules = StringBuilder()
        for (l in layers) {
            if (l.maskShape != null) {
                val m = l.resolvedMotion(baseAmplitudePx)
                if (m[0] != 0f || m[1] != 0f) rules.append(rule("x", l.name, m[0] / k, m[1] / k))
                if (m[2] != 0f || m[3] != 0f) rules.append(rule("y", l.name, m[2] / k, m[3] / k))
                // 窗口里的副本：目标层的位移要减掉遮罩带进来的那份，才是"内容不跟着窗口走"
                if (l.maskShape != "image") {
                    layers.firstOrNull { it.id == l.maskTargetId }?.let { t ->
                        val tm = t.resolvedMotion(baseAmplitudePx)
                        val wx0 = tm[0] - m[0]
                        val wx1 = tm[1] - m[1]
                        val wy0 = tm[2] - m[2]
                        val wy1 = tm[3] - m[3]
                        if (wx0 != 0f || wx1 != 0f) rules.append(rule("x", "${t.name} 窗口内容", wx0 / k, wx1 / k))
                        if (wy0 != 0f || wy1 != 0f) rules.append(rule("y", "${t.name} 窗口内容", wy0 / k, wy1 / k))
                    }
                }
                continue
            }
            val m = l.resolvedMotion(baseAmplitudePx)
            if (m[0] != 0f || m[1] != 0f) rules.append(rule("x", l.name, m[0] / k, m[1] / k))
            if (m[2] != 0f || m[3] != 0f) rules.append(rule("y", l.name, m[2] / k, m[3] / k))
        }
        sb.append("<style>\n<wallpaperBackgroundAssetNames type=\"NSArray\"/>\n")
        sb.append("<wallpaperFloatingAssetNames type=\"NSArray\"/>\n")
        sb.append("<wallpaperParallaxGroups type=\"NSArray\">\n")
        sb.append(rules)
        sb.append("</wallpaperParallaxGroups>\n</style>\n</caml>\n")
        return sb.toString()
    }

    private fun rule(axis: String, layerName: String, min: Float, max: Float): String = """
  <NSDictionary>
    <axis type="string" value="$axis"/>
    <keyPath type="string" value="transform.translation.$axis"/>
    <layerName type="string" value="${esc(layerName)}"/>
    <mapMinTo type="integer" value="${min.toInt()}"/>
    <mapMaxTo type="integer" value="${max.toInt()}"/>
    <title type="string" value="gravity"/>
    <view type="string" value="Floating"/>
  </NSDictionary>
"""

    private fun wallpaperPlist(name: String): String = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>name</key><string>$name</string>
  <key>identifier</key><integer>9999</integer>
  <key>version</key><integer>6</integer>
  <key>wantsDeviceMotion</key><true/>
  <key>appearanceAware</key><false/>
  <key>logicalScreenClass</key><string>any</string>
  <key>assets</key>
  <dict><key>lockAndHome</key><dict><key>default</key><dict>
    <key>assetFileName</key><string>9999.$name.ca</string>
    <key>identifier</key><integer>9999</integer>
    <key>name</key><string>$name</string>
    <key>type</key><string>ParameterizedCA</string>
  </dict></dict></dict>
</dict>
</plist>
"""

    private fun providerInfoPlist(): String = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>configurationLastUseDateKey</key><dict/>
</dict>
</plist>
"""

    private val INDEX_XML = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>rootDocument</key>
  <string>main.caml</string>
</dict>
</plist>
"""

    // ------------------------------------------------------------------ 工具

    private fun f(v: Float): String = if (v == v.toInt().toFloat()) v.toInt().toString() else "%.3f".format(v)

    private fun esc(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun ZipOutputStream.putText(path: String, text: String) {
        putNextEntry(ZipEntry(path))
        write(text.toByteArray(Charsets.UTF_8))
        closeEntry()
    }
}
