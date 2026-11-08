package com.cordiact.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cordiact.app.experience.ExperienceScreen
import com.cordiact.app.experience.ExperienceTheme
import com.cordiact.app.experience.ExperienceViewModel

/** Fresh UI. Business services remain application-owned; drafts and scroll survive rotation. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ExperienceTheme {
                val factory = remember { ExperienceViewModel.factory(application as CordiActApplication) }
                val model: ExperienceViewModel = viewModel(factory = factory)
                ExperienceScreen(model)
            }
        }
    }
}
