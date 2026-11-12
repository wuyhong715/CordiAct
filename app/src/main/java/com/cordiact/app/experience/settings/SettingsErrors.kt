package com.cordiact.app.experience.settings

/** Error codes are mapped locally. Never surface a remote response body or credential in UI. */
fun settingsError(failure: Throwable): String {
    val text = failure.message.orEmpty()
    return when {
        failure is com.cordiact.core.settings.SettingsAttemptAuditFailed -> "审计存储不可用，未执行本次设置写入"
        failure is com.cordiact.core.settings.SettingsMutationUncertain -> "设置写入未完整完成，可能已有部分更改；请刷新核对后再操作，不要直接重复提交"
        "SETTINGS_REVISION_CONFLICT" in text -> "设置已被其他操作修改，请刷新后重试"
        "REVIEWER_REQUIRED" in text -> "自动审批必须选择独立审核模型"
        "REVIEWER_UNAVAILABLE" in text -> "审核模型不可用或与对话默认模型相同，请重新选择"
        "401" in text || "403" in text -> "Key 无效或未获授权"
        "404" in text -> "该地址不是所选协议端点，请检查 URL"
        "ENDPOINT_REQUIRES_NEW_PROVIDER" in text -> "地址变更请添加新提供商并重新填写 Key，避免把旧密钥发给新主机"
        "MODEL_UNAVAILABLE" in text -> "模型已删除或停用，请重新选择"
        "already" in text.lowercase() -> "提供商已存在"
        failure is java.io.IOException -> "无法连接，请检查地址、网络或代理"
        failure is IllegalArgumentException -> "配置不合法，请检查 HTTPS 地址、协议和模型选择"
        else -> "操作未完成；输入已保留，请检查连接和配置后重试"
    }
}
