package com.gravitywall.lock

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.gravitywall.lock.ui.AppRoot
import com.gravitywall.lock.ui.GravityWallTheme
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

/**
 * 应用入口：Compose 界面 + 与框架服务的连接。
 *
 * 三个页签：作品（扫描/选择海报包、应用内实时预览、应用到锁屏）、设置（带位/Y 轴/幅度/玻璃源）、关于（诊断）。
 */
class MainActivity : ComponentActivity(), XposedServiceHelper.OnServiceListener {

    private lateinit var controller: AppController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = AppController(this)
        runCatching { XposedServiceHelper.registerListener(this) }
        setContent {
            GravityWallTheme {
                AppRoot(controller)
            }
        }
        controller.scan()
    }

    override fun onResume() {
        super.onResume()
        controller.refreshStatus()
    }

    override fun onServiceBind(service: XposedService) {
        controller.onService(service)
    }

    override fun onServiceDied(service: XposedService) {
        controller.onService(null)
    }
}
