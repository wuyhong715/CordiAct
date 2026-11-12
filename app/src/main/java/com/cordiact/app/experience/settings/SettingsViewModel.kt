package com.cordiact.app.experience.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(val facade: SettingsFacade) : ViewModel() {
    private val _snapshot = MutableStateFlow(SettingsSnapshot())
    val snapshot = _snapshot.asStateFlow()
    var busy = MutableStateFlow(false); private set
    var error = MutableStateFlow<String?>(null); private set
    var notice = MutableStateFlow<String?>(null); private set
    init { reload() }
    fun selectWorkspace(id: String) = perform { facade.selectWorkspace(id); _snapshot.value = facade.snapshot() }
    fun testRoot() = perform { facade.testRoot(); _snapshot.value = facade.snapshot() }
    fun testScreen() = perform { facade.testScreen(); _snapshot.value = facade.snapshot() }
    fun setCapability(workspaceId: String, id: String, enabled: Boolean) = perform { facade.setCapability(workspaceId, id, enabled); _snapshot.value = facade.snapshot() }
    fun saveAttachments(value: com.cordiact.core.attachment.AttachmentLimits, done: () -> Unit) = perform { facade.saveAttachmentLimits(value); _snapshot.value = facade.snapshot(); done() }
    fun testTermux() = perform { facade.testTermux(); _snapshot.value = facade.snapshot() }
    fun reload() = perform { _snapshot.value = facade.snapshot() }
    fun perform(success: () -> Unit = {}, block: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true; error.value = null
        viewModelScope.launch {
            try {
                block()
                var refreshFailed = false
                try { _snapshot.value = facade.snapshot() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { refreshFailed = true }
                success()
                val warning = facade.takeWriteWarning()
                if (refreshFailed) notice.value = listOfNotNull(warning, "操作已完成，但页面刷新失败；请刷新查看，不要重复提交").joinToString("；")
                else warning?.let { notice.value = it }
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                error.value = settingsError(failure)
                if (failure is com.cordiact.core.settings.SettingsMutationUncertain) {
                    try { _snapshot.value = facade.snapshot() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* keep last known view; error explicitly requires reconciliation */ }
                }
                facade.takeWriteWarning()?.let { notice.value = it }
            }
            finally { busy.value = false }
        }
    }
    companion object {
        fun factory(facade: SettingsFacade) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = SettingsViewModel(facade) as T
        }
    }
}
