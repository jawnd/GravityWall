package com.gravitywall.lock.hook

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.gravitywall.lock.render.StageLayer

/**
 * 阶段 3 的测试构图：用程序生成的图层替代"用户提供的素材"，
 * 先把"多图层 + 各自系数 + 跨越时钟"这条链路验证通。
 *
 * 注意：**不画底图**。实测发现系统时钟的液态玻璃采样的是真正的锁屏壁纸纹理，
 * 我们自绘一层不透明的"底图"只会挡住壁纸、却影响不到玻璃。
 * 底图应当通过"设为系统锁屏壁纸"提供（见设计方案 6.5）。
 */
internal object TestComposition {

    /** 返回 (时钟之后的图层, 时钟之前的图层, 最顶层图层)。 */
    fun build(width: Int, height: Int, amplitudePx: Float, verticalRatio: Float): Triple<List<StageLayer>, List<StageLayer>, List<StageLayer>> {
        val clockY = height * 0.30f

        val behind = listOf(
            circleLayer("月亮", width, height, size = 260f, coefficient = 0.30f, color = 0xFFD9C173.toInt(), centerY = height * 0.18f, amplitudePx = amplitudePx, verticalRatio = verticalRatio),
            circleLayer("远山", width, height, size = 420f, coefficient = 0.55f, color = 0xFF2E4A6B.toInt(), centerY = height * 0.46f, amplitudePx = amplitudePx, verticalRatio = verticalRatio),
        )
        val front = listOf(
            // 挂"时钟前"这条带（小时钟之上、分钟钟之下）：应当压住小时数字，但被分钟数字压住
            circleLayer("橙球", width, height, size = 320f, coefficient = 1.60f, color = 0xFFFF6A3D.toInt(), centerY = clockY, amplitudePx = amplitudePx, verticalRatio = verticalRatio),
        )
        val top = listOf(
            // 挂最顶层（分钟钟之上）：不会被任何时钟压住
            circleLayer("蓝球", width, height, size = 300f, coefficient = 1.00f, color = 0xFF3D8BFF.toInt(), centerY = clockY + height * 0.11f, amplitudePx = amplitudePx, verticalRatio = verticalRatio),
        )
        return Triple(behind, front, top)
    }

    private fun circleLayer(
        name: String,
        width: Int,
        height: Int,
        size: Float,
        coefficient: Float,
        color: Int,
        centerY: Float,
        amplitudePx: Float,
        verticalRatio: Float,
    ): StageLayer {
        val side = size.toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = color
        canvas.drawCircle(side / 2f, side / 2f, side / 2f, paint)
        // 中心加一个深色十字：位移时更容易看清
        val mark = Paint(Paint.ANTI_ALIAS_FLAG)
        mark.color = 0x66000000
        mark.strokeWidth = side * 0.03f
        canvas.drawLine(side * 0.35f, side / 2f, side * 0.65f, side / 2f, mark)
        canvas.drawLine(side / 2f, side * 0.35f, side / 2f, side * 0.65f, mark)

        val dst = RectF(
            (width - size) / 2f,
            centerY - size / 2f,
            (width + size) / 2f,
            centerY + size / 2f,
        )
        return StageLayer.fromCoefficient(
            name = name,
            bitmap = bmp,
            dst = dst,
            coefficient = coefficient,
            amplitudePx = amplitudePx,
            verticalRatio = verticalRatio,
        )
    }
}
