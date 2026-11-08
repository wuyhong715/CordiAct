package com.cordiact.app.ui.window.preview

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 预览窗口 ViewModel
 * 
 * 管理代码 Diff、Web 页面、屏幕截图的数据状态
 */
class PreviewViewModel : ViewModel() {
    
    private val _previewItems = MutableStateFlow<List<PreviewItem>>(emptyList())
    val previewItems: StateFlow<List<PreviewItem>> = _previewItems.asStateFlow()
    
    private val _selectedItemId = MutableStateFlow<String?>(null)
    val selectedItemId: StateFlow<String?> = _selectedItemId.asStateFlow()
    
    init {
        // 加载演示数据
        _previewItems.value = PreviewItem.demoItems()
        _selectedItemId.value = _previewItems.value.firstOrNull()?.id
    }
    
    fun selectItem(itemId: String) {
        _selectedItemId.value = itemId
    }
    
    fun removeItem(itemId: String) {
        _previewItems.value = _previewItems.value.filter { it.id != itemId }
        if (_selectedItemId.value == itemId) {
            _selectedItemId.value = _previewItems.value.firstOrNull()?.id
        }
    }
}

/**
 * 预览项
 */
sealed class PreviewItem {
    abstract val id: String
    abstract val timestamp: Long
    abstract val title: String
    
    data class CodeDiff(
        override val id: String,
        override val timestamp: Long,
        override val title: String,
        val filePath: String,
        val additions: Int,
        val deletions: Int,
        val hunks: List<DiffHunk>
    ) : PreviewItem()
    
    data class WebPage(
        override val id: String,
        override val timestamp: Long,
        override val title: String,
        val url: String,
        val content: String
    ) : PreviewItem()
    
    data class Screenshot(
        override val id: String,
        override val timestamp: Long,
        override val title: String,
        val imagePath: String,
        val description: String?
    ) : PreviewItem()
    
    companion object {
        fun demoItems(): List<PreviewItem> {
            val now = System.currentTimeMillis()
            return listOf(
                CodeDiff(
                    id = "diff-1",
                    timestamp = now - 120_000,
                    title = "MainActivity.kt",
                    filePath = "app/src/main/java/com/cordiact/app/MainActivity.kt",
                    additions = 15,
                    deletions = 3,
                    hunks = listOf(
                        DiffHunk(
                            oldStart = 45,
                            oldLines = 5,
                            newStart = 45,
                            newLines = 8,
                            lines = listOf(
                                DiffLine(DiffLineType.CONTEXT, "    override fun onCreate(savedInstanceState: Bundle?) {"),
                                DiffLine(DiffLineType.CONTEXT, "        super.onCreate(savedInstanceState)"),
                                DiffLine(DiffLineType.REMOVED, "        val showDrawerDemo = intent.getBooleanExtra(\"drawer_demo\", true)"),
                                DiffLine(DiffLineType.ADDED, "        val showMultiWindowUI = intent.getBooleanExtra(\"multi_window\", true)"),
                                DiffLine(DiffLineType.ADDED, "        val showDrawerDemo = intent.getBooleanExtra(\"drawer_demo\", false)"),
                                DiffLine(DiffLineType.CONTEXT, "        "),
                                DiffLine(DiffLineType.CONTEXT, "        setContent {"),
                                DiffLine(DiffLineType.ADDED, "            when {"),
                                DiffLine(DiffLineType.ADDED, "                showMultiWindowUI -> {"),
                                DiffLine(DiffLineType.ADDED, "                    com.cordiact.app.ui.theme.CordiActTheme {")
                            )
                        )
                    )
                ),
                WebPage(
                    id = "web-1",
                    timestamp = now - 60_000,
                    title = "Jetpack Compose 文档",
                    url = "https://developer.android.com/jetpack/compose",
                    content = "Jetpack Compose is Android's recommended modern toolkit for building native UI..."
                ),
                Screenshot(
                    id = "screen-1",
                    timestamp = now - 30_000,
                    title = "轨迹窗口截图",
                    imagePath = "/sdcard/Pictures/Moxw/Screenshots/trajectory_demo.png",
                    description = "时空甘特图 + 因果树演示"
                )
            )
        }
    }
}

data class DiffHunk(
    val oldStart: Int,
    val oldLines: Int,
    val newStart: Int,
    val newLines: Int,
    val lines: List<DiffLine>
)

data class DiffLine(
    val type: DiffLineType,
    val content: String
)

enum class DiffLineType {
    CONTEXT,
    ADDED,
    REMOVED
}
