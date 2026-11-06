package com.cordiact.core.automation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScreenFormatTest {

    private fun snapshot(
        elements: List<ScreenElement>,
        height: Int = 2400,
        title: String? = "设置",
        truncated: Boolean = false,
    ) = ScreenSnapshot(
        handle = "s0",
        packageName = "com.android.settings",
        title = title,
        width = 1080,
        height = height,
        elements = elements,
        capturedAtMillis = 0L,
        truncated = truncated,
    )

    private fun element(
        index: Int,
        label: String,
        top: Int,
        password: Boolean = false,
        sensitive: Boolean = false,
        clickable: Boolean = true,
    ) = ScreenElement(
        index = index,
        fingerprint = "fp-$index",
        role = "Button",
        label = label,
        bounds = ScreenRect(48, top, 1032, top + 100),
        clickable = clickable,
        password = password,
        sensitive = sensitive,
    )

    @Test
    fun rendersHeaderZonesAndNumbers() {
        val text = ScreenFormat.render(
            snapshot(
                listOf(
                    element(1, "搜索", 120),
                    element(2, "网络与互联网", 800),
                    element(3, "系统", 2200),
                ),
            ),
        )
        assertTrue(text.startsWith("s0  com.android.settings  设置  1080x2400"))
        assertTrue(text.contains("顶"))
        assertTrue(text.contains("中"))
        assertTrue(text.contains("底"))
        assertTrue(text.contains("[1] 搜索"))
        assertTrue(text.contains("[2] 网络与互联网"))
        assertTrue(text.contains("[3] 系统"))
    }

    @Test
    fun neverRendersPasswordText() {
        val text = ScreenFormat.render(
            snapshot(listOf(element(1, "hunter2", 800, password = true))),
        )
        assertFalse(text.contains("hunter2"))
        assertTrue(text.contains("密码框（不可读）"))
    }

    @Test
    fun marksNewAndSensitiveElements() {
        val text = ScreenFormat.render(
            snapshot(listOf(element(1, "已连接 WLAN", 800, sensitive = true))),
            previousFingerprints = emptySet(),
        )
        assertTrue(text.contains("⚠"))
        assertTrue(text.contains("+"))
    }

    @Test
    fun respectsCharacterBudget() {
        val many = (1..200).map { element(it, "项目$it", 100 + it * 10) }
        val text = ScreenFormat.render(snapshot(many), maxChars = 600)
        assertTrue(text.length <= 620, "budget exceeded: ${text.length}")
        assertTrue(text.contains("另有"))
    }

    @Test
    fun respectsNodeLimit() {
        val many = (1..120).map { element(it, "项目$it", 100 + it * 10) }
        val text = ScreenFormat.render(snapshot(many, truncated = true), maxNodes = 10)
        assertTrue(text.contains("另有 110 项"))
    }

    @Test
    fun rendersErrorInsteadOfElements() {
        val failed = snapshot(emptyList()).copy(
            error = ScreenError(ScreenErrorCodes.PERMISSION_REQUIRED, "无障碍服务未开启", "去设置开启"),
        )
        val text = ScreenFormat.render(failed)
        assertTrue(text.contains(ScreenErrorCodes.PERMISSION_REQUIRED))
        assertTrue(text.contains("recovery: 去设置开启"))
    }

    @Test
    fun skipsDisabledAndEmptyElements() {
        val text = ScreenFormat.render(
            snapshot(
                listOf(
                    element(1, "可用", 800),
                    element(2, "禁用", 900).copy(enabled = false),
                    element(3, "", 1000, clickable = false),
                ),
            ),
        )
        assertTrue(text.contains("[1] 可用"))
        assertFalse(text.contains("禁用"))
    }

    @Test
    fun handlesZeroHeightWithoutCrashing() {
        val text = ScreenFormat.render(snapshot(listOf(element(1, "搜索", 0)), height = 0))
        assertEquals(true, text.contains("[1] 搜索"))
    }
}
