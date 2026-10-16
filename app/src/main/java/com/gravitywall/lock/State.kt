package com.gravitywall.lock

/** 模块与应用之间共享的远程偏好组名与键名（两边必须一致）。 */
object State {

    /** 远程偏好组名 */
    const val GROUP = "gravitywall_state"

    /** 阶段 0 探测报告（完整文本，应用直接显示） */
    const val KEY_REPORT = "probe_report"

    /** 实时倾斜数据（应用轮询显示，用于验证传感器通路） */
    const val KEY_LIVE = "probe_live"

    /** 报告生成时间（毫秒） */
    const val KEY_TS = "probe_ts"

    /** 运行期配置（应用写入的 JSON；模块优先读它，其次读 /sdcard/Download/gravitywall/config.json） */
    const val KEY_RUNTIME_CONFIG = "runtime_config"

    /** 模块广播指令 */
    const val ACTION_PROBE = "com.gravitywall.lock.PROBE"
    const val ACTION_SET_WALLPAPER = "com.gravitywall.lock.SET_WALLPAPER"
    const val ACTION_RESTORE = "com.gravitywall.lock.RESTORE"
}
