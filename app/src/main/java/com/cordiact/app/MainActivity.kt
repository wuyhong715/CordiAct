package com.cordiact.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cordiact.app.experience.ExperienceScreen
import com.cordiact.app.experience.ExperienceViewModel

/** Fresh UI. Business services remain application-owned; drafts and scroll survive rotation. */
class MainActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        (application as CordiActApplication).let {
            it.authoredPluginForeground.resume(this)
            it.workspaceExtensionPackages.resume(this)
        }
        runCatching { com.cordiact.app.service.ExecutionPresenceHost.onUserForeground(this) }
            .onFailure { android.util.Log.w("MainActivity", "Execution state refresh failed", it) }
    }

    override fun onPause() {
        (application as CordiActApplication).let {
            it.workspaceExtensionPackages.pause(this)
            it.authoredPluginForeground.pause(this)
        }
        super.onPause()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        enableEdgeToEdge()
        setContent {
            val factory = remember { ExperienceViewModel.factory(application as CordiActApplication) }
            val model: ExperienceViewModel = viewModel(factory = factory)
            ExperienceScreen(model)
        }
    }
}
