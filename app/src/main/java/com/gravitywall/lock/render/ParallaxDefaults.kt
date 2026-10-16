package com.gravitywall.lock.render

/**
 * 视差常量：应用内预览与系统进程里的实现共用同一份，
 * 保证"预览看到什么，锁屏上就是什么"。
 */
internal object ParallaxDefaults {
    /** 满量程角度：倾斜到该角度时达到最大位移 */
    const val FULL_SCALE_DEG = 10f

    /** 影响系数 100% 时的位移（像素）——测试期偏大，便于肉眼确认 */
    const val AMPLITUDE_PX = 96f

    /** 垂直方向相对水平的比例 */
    const val VERTICAL_RATIO = 0.6f

    /** 导入海报包时的位移上限（屏宽/屏高的比例）。
     *  iOS 那些 ±500pt 的位移本来是配合裁剪窗口用的，在没有窗口的场景里会飞出屏幕，
     *  所以先限幅；窗口实现后可以逐步放开。 */
    const val IMPORT_CAP_RATIO = 0.30f

    /** 把倾角归一化到 −1~1（超出满量程即截断） */
    fun normalize(rollDeg: Float, pitchDeg: Float): Pair<Float, Float> {
        val x = (rollDeg / FULL_SCALE_DEG).coerceIn(-1f, 1f)
        val y = (pitchDeg / FULL_SCALE_DEG).coerceIn(-1f, 1f)
        return x to y
    }
}
