package com.gravitywall.lock.hook

import android.hardware.SensorManager

/**
 * 把传感器读数换算成"左右 / 前后"两个倾角（单位：度）。
 * 结果 [0] = 左右倾斜（roll），[1] = 前后俯仰（pitch）。
 */
internal object TiltMath {

    /** 重力 / 加速度计：直接由重力向量分量求角。 */
    fun fromGravity(x: Float, y: Float, z: Float): FloatArray {
        val roll = Math.toDegrees(kotlin.math.atan2(x.toDouble(), (-y).toDouble()))
        val pitch = Math.toDegrees(kotlin.math.atan2((-z).toDouble(), (-y).toDouble()))
        return floatArrayOf(roll.toFloat(), pitch.toFloat())
    }

    /** 旋转矢量类（含游戏旋转矢量）：交给系统的欧拉角换算。 */
    fun fromRotationVector(values: FloatArray): FloatArray? {
        if (values.size < 3) return null
        val rm = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(rm, values)
        val orientation = FloatArray(3)
        SensorManager.getOrientation(rm, orientation)
        val pitch = Math.toDegrees(orientation[1].toDouble()).toFloat()
        val roll = Math.toDegrees(orientation[2].toDouble()).toFloat()
        return floatArrayOf(roll, pitch)
    }
}
