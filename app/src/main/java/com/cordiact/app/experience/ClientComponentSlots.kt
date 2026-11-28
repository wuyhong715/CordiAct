package com.cordiact.app.experience

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cordiact.core.plugin.PluginContext
import com.cordiact.core.plugin.ServiceKey
import com.cordiact.interaction.TrustedWorkbenchTabs

enum class ClientComponentSlot { SESSION_TOOLBAR, COMPOSER_ACCESSORIES }

interface ClientComponent {
    @Composable fun Content(host: ClientComponentHost)
}

interface ClientComponentHost {
    val workspaceId: String?
    val sessionId: String?
    val landscape: Boolean
    val canCreateSession: Boolean
    fun createSession()
}

val CLIENT_COMPONENT_SLOTS = ServiceKey("client.component-slots", ClientComponentSlots::class.java, applicationWide = true)

/** Registration of application-bundled Compose components. */
class ClientComponentSlots {
    private val directories = ClientComponentSlot.entries.associateWith { TrustedWorkbenchTabs<ClientComponent>() }

    fun register(
        publisher: PluginContext, slot: ClientComponentSlot, id: String, component: ClientComponent, order: Int = 0,
    ) {
        val directory = directories.getValue(slot)
        directory.register(publisher, id, component, order)
        directory.refresh()
    }

    internal fun refresh() = directories.values.forEach { it.refresh() }

    @Composable
    internal fun Mount(
        slot: ClientComponentSlot,
        workspace: String?,
        session: String?,
        isLandscape: Boolean,
        onCreateSession: (() -> Unit)? = null,
    ) {
        val directory = directories.getValue(slot)
        val snapshot by directory.snapshot.collectAsStateWithLifecycle()
        for (entry in snapshot.visible(workspace, session)) {
            key(entry, workspace, session) {
                val create by rememberUpdatedState(onCreateSession)
                var mounted by remember { mutableStateOf(false) }
                DisposableEffect(Unit) {
                    mounted = true
                    onDispose { mounted = false }
                }
                val host = object : ClientComponentHost {
                    override val workspaceId = workspace
                    override val sessionId = session
                    override val landscape = isLandscape
                    override val canCreateSession get() = create != null
                    override fun createSession() {
                        if (mounted && directory.isCurrent(entry, workspace, session)) create?.invoke()
                    }
                }
                entry.content.Content(host)
            }
        }
    }
}
