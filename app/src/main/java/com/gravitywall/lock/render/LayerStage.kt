package com.gravitywall.lock.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/**
 * 裁剪窗口：把内容裁进一个（可带圆角的）矩形，窗口自身也可以按自己的范围移动。
 * 对应 CAML 里带 `masksToBounds="1"` 的层——社区海报常用"窗口往一个方向移动、
 * 窗口里的内容往另一个方向移动"来做出望远镜/纵深效果；`cornerRadius` 等于半个边长时就是圆形窗口。
 */
internal class ClipWindow(
    val rect: RectF,
    val minX: Float = 0f,
    val maxX: Float = 0f,
    val minY: Float = 0f,
    val maxY: Float = 0f,
    val cornerRadius: Float = 0f,
)

/**
 * 一个图层：位图图层或纯色图层（`fillColor != null` 时画圆角矩形）。
 *
 * 运动模型对齐 iOS PosterBoard 的 CAML：每层每轴用 **mapMin / mapMax（像素）** 描述范围，
 * 输入 −1~1 线性映射到该区间。嵌套层的位移在导入时已沿祖先链累加。
 */
internal class StageLayer(
    val name: String,
    val bitmap: Bitmap?,
    val dst: RectF,
    var minX: Float,
    var maxX: Float,
    var minY: Float,
    var maxY: Float,
    var alpha: Int = 255,
    val clip: ClipWindow? = null,
    val cornerRadius: Float = 0f,
    var fillColor: Int? = null,
    /** 图层位图来自包内哪个素材（导入时记录，便于导出/编辑时回写） */
    val assetPath: String? = null,
    /** 旋转角度（度，绕自身中心） */
    var rotation: Float = 0f,
    /** 对应项目图层的 id（应用内编辑时把预览命中还原回图层用；导入的海报包没有 id） */
    val id: String? = null,
    /**
     * 填充方式：
     *   stretch（默认）＝拉伸铺满 dst，比例不同会变形；
     *   crop ＝等比放大到盖满 dst，超出部分裁掉（对齐 CAML 的 resizeAspectFill）；
     *   fit  ＝等比缩到完整放进 dst，空出来的地方透出下层（对齐 resizeAspect）。
     */
    var fill: String = "stretch",
    /** 非 null 时这一层是"遮罩"：不直接绘制，而是按形状/图案作用到 [maskForId] 那一层上 */
    val maskShape: String? = null,
    val maskRadius: Float = 0f,
    /** 遮罩图案按亮度读取（false = 按透明度） */
    val maskLuminance: Boolean = true,
    val maskForId: String? = null,
    /** 只通过遮罩显示（父级"停用"时为 true）：不画整层，只画遮罩以内的部分 */
    val maskOnly: Boolean = false,
) {
    companion object {
        /** 便捷构造：用"影响系数 × 全局幅度"生成对称范围（我们自己的写法）。 */
        fun fromCoefficient(
            name: String,
            bitmap: Bitmap,
            dst: RectF,
            coefficient: Float,
            amplitudePx: Float,
            verticalRatio: Float,
            alpha: Int = 255,
        ): StageLayer {
            val a = coefficient * amplitudePx
            return StageLayer(
                name = name,
                bitmap = bitmap,
                dst = dst,
                minX = -a,
                maxX = a,
                minY = -a * verticalRatio,
                maxY = a * verticalRatio,
                alpha = alpha,
            )
        }

        /** 直接给定各轴范围（导入时用，可为负数以表示反向）。 */
        fun ranged(
            name: String,
            bitmap: Bitmap?,
            dst: RectF,
            minX: Float,
            maxX: Float,
            minY: Float,
            maxY: Float,
            alpha: Int = 255,
            clip: ClipWindow? = null,
            cornerRadius: Float = 0f,
            fillColor: Int? = null,
            assetPath: String? = null,
            rotation: Float = 0f,
            id: String? = null,
            fill: String = "stretch",
            maskShape: String? = null,
            maskRadius: Float = 0f,
            maskLuminance: Boolean = true,
            maskForId: String? = null,
            maskOnly: Boolean = false,
        ): StageLayer =
            StageLayer(
                name, bitmap, dst, minX, maxX, minY, maxY, alpha, clip, cornerRadius, fillColor,
                assetPath, rotation, id, fill, maskShape, maskRadius, maskLuminance, maskForId, maskOnly,
            )
    }
}

/**
 * 图层舞台：按顺序绘制所有图层，每层按自己的 mapMin/mapMax 施加位移，支持裁剪窗口与圆角。
 *
 * 这个类刻意不依赖任何系统内部 API——应用内的实时预览与注入到系统进程里的那份共用同一份代码。
 */
internal class LayerStage(context: Context) : View(context) {

    var layers: List<StageLayer> = emptyList()
        set(value) {
            field = value
            rebuildIndex()
            invalidate()
        }

    /**
     * 只用来按 id 查的"参照层"（不绘制）。
     * 遮罩和目标层可能在不同挂载带（三个舞台视图各挂一处），所以每个舞台都要能查到全量图层。
     */
    var refs: List<StageLayer> = emptyList()
        set(value) {
            field = value
            rebuildIndex()
            invalidate()
        }

    private fun rebuildIndex() {
        val all = layers + refs
        masksByTarget = all.filter { it.maskShape != null && it.maskForId != null }
            .groupBy { it.maskForId!! }
        byId = all.filter { it.id != null }.associateBy { it.id!! }
    }

    /** 目标图层 id → 作用在它身上的遮罩（每次设置图层时重算一次） */
    private var masksByTarget: Map<String, List<StageLayer>> = emptyMap()

    /** 图层 id → 舞台图层（遮罩窗口要按 id 找到"要显示的那一层"） */
    private var byId: Map<String, StageLayer> = emptyMap()

    private var normX = 0f
    private var normY = 0f
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val erasePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clipPath = Path()
    private val fullPath = Path()
    private val windowPath = Path()

    /** 亮度 → 透明度（黑=全透、白=不透明），用于把黑白遮罩图当 alpha 用 */
    private val lumToAlpha = android.graphics.ColorMatrixColorFilter(
        android.graphics.ColorMatrix(
            floatArrayOf(
                0f, 0f, 0f, 0f, 0f,
                0f, 0f, 0f, 0f, 0f,
                0f, 0f, 0f, 0f, 0f,
                0.2126f, 0.7152f, 0.0722f, 0f, 0f,
            ),
        ),
    )

    /** 设计空间尺寸（通常就是屏幕像素尺寸）。图层坐标都在这个空间里。 */
    private var designW = 0f

    /** 设定设计空间尺寸；视图会按自身尺寸等比缩放整个画面（预览就靠它缩小）。 */
    fun setDesignSize(width: Int, height: Int) {
        designW = width.toFloat()
        invalidate()
    }

    /** 施加归一化倾斜（−1~1）；值没变就不重画（传感器静置时也会以固定频率回调）。 */
    fun applyTilt(x: Float, y: Float) {
        if (x == normX && y == normY) return
        normX = x
        normY = y
        invalidate()
    }

    /** 某层当前的位移（供报告/调试用）。 */
    fun offsetOf(layer: StageLayer): Pair<Float, Float> = offsetX(layer) to offsetY(layer)

    private fun lerpOff(min: Float, max: Float, norm: Float) = min + (max - min) * ((norm + 1f) / 2f)

    /**
     * 遮罩窗口：在遮罩自己的层级上，显示"目标图层"的内容（裁成遮罩的形状）。
     * 所以遮罩能盖住它下面的图层；目标层本身仍在自己的层级上正常显示（会被它上面的层挡住）。
     * 目标层"停用"时它自己不画，画面里就只剩这个窗口——就是"打孔显示"。
     */
    private fun drawWindowed(mask: StageLayer, canvas: Canvas) {
        val target = mask.maskForId?.let { byId[it] } ?: return
        if (target.bitmap == null && target.fillColor == null) return
        val imageMask = mask.maskShape == "image"
        if (imageMask && mask.bitmap == null) return

        canvas.save()
        if (imageMask) canvas.saveLayer(null, null)
        canvas.translate(offsetX(mask), offsetY(mask))

        if (imageMask) {
            // 图案遮罩：先把窗口矩形以外擦掉，再按图案明暗留下内容
            erasePaint.reset()
            erasePaint.xfermode =
                android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT)
            erasePaint.color = android.graphics.Color.BLACK
            fullPath.reset()
            fullPath.addRect(-1e5f, -1e5f, 1e5f, 1e5f, Path.Direction.CW)
            windowPath.reset()
            windowPath.addRect(mask.dst, Path.Direction.CW)
            fullPath.op(windowPath, Path.Op.DIFFERENCE)
            canvas.drawPath(fullPath, erasePaint)
            erasePaint.xfermode = null
        } else {
            val r = if (mask.maskShape == "round") {
                minOf(mask.dst.width(), mask.dst.height()) / 2f
            } else {
                mask.maskRadius
            }
            if (r > 0f) {
                clipPath.reset()
                clipPath.addRoundRect(mask.dst, r, r, Path.Direction.CW)
                canvas.clipPath(clipPath)
            } else {
                canvas.clipRect(mask.dst)
            }
        }

        // 关键：内容要回到"设计坐标"再画——只加目标层自己的位移，
        // 不能把遮罩的位移叠加进去（遮罩的位移只挪窗口，不挪内容）。
        canvas.translate(-offsetX(mask), -offsetY(mask))
        canvas.save()
        canvas.translate(offsetX(target), offsetY(target))
        if (target.rotation != 0f) {
            canvas.rotate(target.rotation, target.dst.centerX(), target.dst.centerY())
        }
        val color = target.fillColor
        if (color != null) {
            fillPaint.color = color
            fillPaint.alpha = target.alpha
            if (target.cornerRadius > 0f) {
                canvas.drawRoundRect(target.dst, target.cornerRadius, target.cornerRadius, fillPaint)
            } else {
                canvas.drawRect(target.dst, fillPaint)
            }
        } else {
            val bmp = target.bitmap
            if (bmp != null) {
                bitmapPaint.alpha = target.alpha
                when (target.fill) {
                    "crop" -> {
                        canvas.clipRect(target.dst)
                        canvas.drawBitmap(bmp, null, scaleRect(target.dst, bmp, cover = true), bitmapPaint)
                    }

                    "fit" -> canvas.drawBitmap(bmp, null, scaleRect(target.dst, bmp, cover = false), bitmapPaint)
                    else -> canvas.drawBitmap(bmp, null, target.dst, bitmapPaint)
                }
            }
        }
        canvas.restore()

        if (imageMask) {
            maskPaint.reset()
            maskPaint.isAntiAlias = true
            maskPaint.alpha = mask.alpha
            maskPaint.xfermode =
                android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
            if (mask.maskLuminance) maskPaint.colorFilter = lumToAlpha
            mask.bitmap?.let { canvas.drawBitmap(it, null, mask.dst, maskPaint) }
            maskPaint.xfermode = null
            maskPaint.colorFilter = null
        }
        canvas.restore()
    }

    /** 等比缩放后的目标框：cover=true 盖满（裁切），false 完整放入（适应）。 */
    private fun scaleRect(dst: RectF, bmp: Bitmap, cover: Boolean): RectF {
        if (bmp.width <= 0 || bmp.height <= 0 || dst.width() <= 0f || dst.height() <= 0f) return dst
        val sx = dst.width() / bmp.width
        val sy = dst.height() / bmp.height
        val k = if (cover) maxOf(sx, sy) else minOf(sx, sy)
        val w = bmp.width * k
        val h = bmp.height * k
        return RectF(
            dst.centerX() - w / 2f,
            dst.centerY() - h / 2f,
            dst.centerX() + w / 2f,
            dst.centerY() + h / 2f,
        )
    }

    private fun offsetX(l: StageLayer) = lerpOff(l.minX, l.maxX, normX)

    private fun offsetY(l: StageLayer) = lerpOff(l.minY, l.maxY, normY)

    override fun onDraw(canvas: Canvas) {
        val k = if (designW > 0f && width > 0) width / designW else 1f
        canvas.save()
        canvas.scale(k, k)
        for (layer in layers) {
            if (layer.maskShape != null) {
                // 遮罩层：在它自己的层级上开一个窗口，显示目标层的内容
                drawWindowed(layer, canvas)
                continue
            }
            // 停用的父级本身不画（只出现在遮罩窗口里）
            if (layer.maskOnly) continue
            canvas.save()

            val clip = layer.clip
            if (clip != null) {
                val r = RectF(clip.rect)
                r.offset(lerpOff(clip.minX, clip.maxX, normX), lerpOff(clip.minY, clip.maxY, normY))
                if (clip.cornerRadius > 0f) {
                    clipPath.reset()
                    clipPath.addRoundRect(r, clip.cornerRadius, clip.cornerRadius, Path.Direction.CW)
                    canvas.clipPath(clipPath)
                } else {
                    canvas.clipRect(r)
                }
            }

            canvas.translate(offsetX(layer), offsetY(layer))
            if (layer.rotation != 0f) {
                // 绕自身中心旋转
                canvas.rotate(layer.rotation, layer.dst.centerX(), layer.dst.centerY())
            }

            val color = layer.fillColor
            if (color != null) {
                fillPaint.color = color
                fillPaint.alpha = layer.alpha
                if (layer.cornerRadius > 0f) {
                    canvas.drawRoundRect(layer.dst, layer.cornerRadius, layer.cornerRadius, fillPaint)
                } else {
                    canvas.drawRect(layer.dst, fillPaint)
                }
            } else {
                val bmp = layer.bitmap
                if (bmp != null) {
                    bitmapPaint.alpha = layer.alpha
                    when (layer.fill) {
                        "crop" -> {
                            // 先裁到 dst（此时的坐标系已含位移与旋转，所以裁出来的框是跟着转的）
                            canvas.clipRect(layer.dst)
                            canvas.drawBitmap(bmp, null, scaleRect(layer.dst, bmp, cover = true), bitmapPaint)
                        }

                        "fit" -> canvas.drawBitmap(bmp, null, scaleRect(layer.dst, bmp, cover = false), bitmapPaint)
                        else -> canvas.drawBitmap(bmp, null, layer.dst, bitmapPaint)
                    }
                }
            }
            canvas.restore()

            // 遮罩只在遮罩层自己的层级上起作用（见 drawWindowed），这里不再对图层做裁剪

        }
        canvas.restore()
    }
}
