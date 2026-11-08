package com.cordiact.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * 应用入口。
 *
 * 旧 UI 已整体移除（见 ui/ 包的删除），等待重新设计。
 * 后端能力（CordiActApplication、core、interaction、tools、sandbox 等）不受影响，
 * 仍由 [CordiActApplication] 持有并对外暴露。
 *
 * 重新设计时：在 setContent 中挂载新的根 Composable 即可，无需改动清单或依赖。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(text = "UI 重新设计中")
                    }
                }
            }
        }
    }
}
