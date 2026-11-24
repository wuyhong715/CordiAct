package com.cordiact.app.experience

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cordiact.app.experience.settings.components.SettingsCard
import com.cordiact.core.plugin.SignedPackageCatalog.PublisherSummary
import com.cordiact.core.plugin.SignedPackageCatalog.VersionSummary
import com.cordiact.core.plugin.WorkspaceExtensionPackages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

/** Only mounted by a host that has configured a fixed signed catalog. */
@Composable
internal fun SignedPackageDirectoryPanel(
    trustSourceId: String,
    page: WorkspaceExtensionPackages.Page,
    blocked: Boolean,
    onBusy: (Boolean) -> Unit,
    onPreview: (WorkspaceExtensionPackages.Preview) -> Unit,
    prepareTrust: () -> WorkspaceExtensionPackages.SignedRefresh = { page.prepareTrustRefresh(trustSourceId) },
    workDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    key(page, trustSourceId, lifecycle) {
        val scope = rememberCoroutineScope()
        val latestBlocked by rememberUpdatedState(blocked)
        val latestOnBusy by rememberUpdatedState(onBusy)
        val latestOnPreview by rememberUpdatedState(onPreview)
        var resumed by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
        var action by remember { mutableStateOf<DirectoryAction?>(null) }
        var publishers by remember { mutableStateOf<List<PublisherSummary>?>(null) }
        var selected by remember { mutableStateOf<PublisherSummary?>(null) }
        var versions by remember { mutableStateOf<List<VersionSummary>?>(null) }
        var versionPage by remember { mutableStateOf(0) }
        var expanded by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }

        fun clearVersions() {
            versions = null
            versionPage = 0
        }

        fun cancelActive() {
            expanded = false
            val running = action ?: return
            // Retire the UI token before cancelling the transport/coroutine. Its finally cannot clear a newer busy state.
            action = null
            try { running.cancel() } finally { latestOnBusy(false) }
        }

        DisposableEffect(lifecycle) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> resumed = true
                    Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_DESTROY -> {
                        resumed = false
                        cancelActive()
                        // Returning to this screen does not silently renew or expose an old directory.
                        publishers = null
                        selected = null
                        clearVersions()
                        error = null
                    }
                    else -> Unit
                }
            }
            lifecycle.addObserver(observer)
            onDispose {
                lifecycle.removeObserver(observer)
                resumed = false
                cancelActive()
            }
        }
        LaunchedEffect(blocked) { if (latestBlocked) cancelActive() }

        fun canInteract() = resumed && !latestBlocked && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

        fun <T> start(
            failure: String,
            onStart: () -> Unit = {},
            work: (DirectoryAction) -> T,
            publish: (T) -> Unit,
        ) {
            if (action != null || !canInteract()) return
            val running = DirectoryAction()
            fun isCurrent() = action === running && canInteract()
            running.job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val result = try { runInterruptible(workDispatcher) { work(running) } }
                    catch (cancelled: CancellationException) {
                        // The core can itself reject a retired scope. Only lifecycle/job cancellation is silent.
                        ensureActive()
                        if (isCurrent()) error = failure
                        return@launch
                    } catch (_: Exception) {
                        if (isCurrent()) error = failure
                        return@launch
                    }
                    ensureActive()
                    if (isCurrent()) publish(result)
                } finally {
                    if (action === running) {
                        action = null
                        latestOnBusy(false)
                    }
                }
            }
            action = running
            error = null
            expanded = false
            onStart()
            latestOnBusy(true)
            running.job.start()
        }

        val enabled = resumed && !blocked && action == null
        SettingsCard {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("签名插件包目录", style = MaterialTheme.typography.titleMedium)
                Text("只有手动刷新或下载才会联网。下载并校验后仍须确认，不会直接安装或启用插件。",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedButton(enabled = enabled, onClick = {
                    start("无法刷新或校验信任根。", onStart = {
                        // A new root can revoke every previous selection; never carry the old result forward.
                        publishers = null
                        selected = null
                        clearVersions()
                    }, work = { running ->
                        val operation = prepareTrust()
                        running.withCancel(operation::cancel, operation::refresh)
                        page.signedPublishers()
                    }, publish = { publishers = it })
                }) { Text("刷新信任根") }
                if (action != null) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                val choices = publishers
                val publisher = selected
                when {
                    choices == null -> if (action == null && error == null) {
                        Text("请先刷新信任根。", style = MaterialTheme.typography.bodySmall)
                    }
                    choices.isEmpty() -> Text("信任根没有授权的签发者。", style = MaterialTheme.typography.bodySmall)
                    else -> Box {
                        OutlinedButton(enabled = enabled, onClick = { if (canInteract() && action == null) expanded = true }) {
                            Text(publisher?.let(::publisherLabel) ?: "选择签发者")
                        }
                        DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
                            choices.forEach { choice ->
                                DropdownMenuItem(text = { Text(publisherLabel(choice)) }, onClick = {
                                    if (publishers === choices && canInteract() && action == null) {
                                        if (selected != choice) clearVersions()
                                        selected = choice
                                        error = null
                                    }
                                    expanded = false
                                })
                            }
                        }
                    }
                }
                if (publisher != null) {
                    TextButton(enabled = enabled, onClick = {
                        if (selected === publisher) {
                            start("无法刷新或校验所选签发者的索引。", onStart = ::clearVersions, work = { running ->
                                val operation = page.prepareIndexRefresh(publisher.sourceId, publisher.keyId)
                                running.withCancel(operation::cancel, operation::refresh)
                                page.signedVersions(publisher.sourceId).also { rows ->
                                    check(rows.all {
                                        it.sourceId == publisher.sourceId && it.publisherId == publisher.publisherId && it.keyId == publisher.keyId
                                    })
                                }
                            }, publish = { versions = it })
                        }
                    }) { Text("刷新所选签发者索引") }
                    val releases = versions
                    if (releases?.isEmpty() == true) Text("此索引没有可选版本。", style = MaterialTheme.typography.bodySmall)
                    val displayedPage = versionPage
                    val pageCount = ((releases?.size ?: 0) + VERSIONS_PER_PAGE - 1) / VERSIONS_PER_PAGE
                    if (pageCount > 1) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(enabled = enabled && displayedPage > 0, onClick = {
                                if (canInteract() && action == null && versions === releases && versionPage == displayedPage) versionPage--
                            }) { Text("上一页") }
                            Text("${displayedPage + 1} / $pageCount", Modifier.padding(top = 14.dp),
                                style = MaterialTheme.typography.bodySmall)
                            TextButton(enabled = enabled && displayedPage + 1 < pageCount, onClick = {
                                if (canInteract() && action == null && versions === releases && versionPage == displayedPage) versionPage++
                            }) { Text("下一页") }
                        }
                    }
                    releases.orEmpty().drop(displayedPage * VERSIONS_PER_PAGE).take(VERSIONS_PER_PAGE).forEach { release -> key(release.packageId, release.version) {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("${release.packageId} · ${release.version}", style = MaterialTheme.typography.titleSmall)
                            Text("SHA-256：${release.sha256}", fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall)
                            TextButton(enabled = enabled, onClick = {
                                if (selected === publisher && versions === releases && versionPage == displayedPage) {
                                    start("无法下载或校验此版本；请查看所选签发者的索引。", work = { running ->
                                        val selection = page.selectSignedVersion(release.sourceId, release.packageId, release.version)
                                        // Do not request a package from a replacement index/key that the user did not see.
                                        check(selection.publisherId == publisher.publisherId && selection.keyId == publisher.keyId &&
                                            selection.sha256 == release.sha256)
                                        val download = page.prepareSignedDownload(selection)
                                        running.withCancel(download::cancel, download::fetchPreview).also { preview ->
                                            check(preview.sourceReceipt?.keyId == publisher.keyId)
                                        }
                                    }, publish = { latestOnPreview(it) })
                                }
                            }) { Text("下载并预览 ${release.packageId} ${release.version}") }
                        }
                    } }
                }
            }
        }
    }
}

private const val VERSIONS_PER_PAGE = 8

private fun publisherLabel(publisher: PublisherSummary) =
    "${publisher.publisherId}（来源：${publisher.sourceId} · 签发键：${publisher.keyId}）"

/** Hand cancellation across IO preparation: a handle returned after retirement is cancelled before it runs. */
private class DirectoryAction {
    lateinit var job: Job
    private val lock = Any()
    private var cancelled = false
    private var cancelWork: (() -> Unit)? = null

    fun <T> withCancel(cancel: () -> Unit, work: () -> T): T {
        val admitted = synchronized(lock) {
            if (cancelled) false else { cancelWork = cancel; true }
        }
        if (!admitted) {
            cancel()
            throw CancellationException()
        }
        return try { work() } finally { synchronized(lock) { cancelWork = null } }
    }

    fun cancel() {
        val cancel = synchronized(lock) {
            cancelled = true
            cancelWork.also { cancelWork = null }
        }
        try { cancel?.invoke() } finally { job.cancel() }
    }
}
