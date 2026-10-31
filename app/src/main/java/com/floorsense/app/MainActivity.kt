package com.floorsense.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.floorsense.app.ui.dashboard.DashboardScreen
import com.floorsense.app.ui.theme.FloorSenseTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FloorSenseTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    DashboardScreen(viewModel = viewModel())

                    // 步态门控 / 通知 / 蓝牙设备信息所需运行时权限
                    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestMultiplePermissions()
                    ) { /* 拒绝后功能降级：无步态数据时门控退化为纯气压速率判据 */ }

                    LaunchedEffect(Unit) {
                        val permissions = buildList {
                            add(Manifest.permission.ACTIVITY_RECOGNITION)
                            add(Manifest.permission.POST_NOTIFICATIONS)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                add(Manifest.permission.BLUETOOTH_CONNECT)
                            }
                        }
                        permissionLauncher.launch(permissions.toTypedArray())
                    }
                }
            }
        }
    }
}
