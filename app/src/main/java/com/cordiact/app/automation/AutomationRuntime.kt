package com.cordiact.app.automation

import android.content.Context
import com.cordiact.app.browser.BrowserPlugin
import com.cordiact.app.browser.BrowserSessionPool
import com.cordiact.app.browser.registerBrowserTools
import com.cordiact.app.display.BackgroundScreenDriver
import com.cordiact.app.display.DisplayPlugin
import com.cordiact.app.privilege.PrivilegePlugin
import com.cordiact.app.privilege.PrivilegeRouter
import com.cordiact.app.privilege.PrivilegeService
import com.cordiact.app.privilege.RootChannel
import com.cordiact.app.privilege.ShizukuChannel
import com.cordiact.app.privilege.registerPrivilegeTools
import com.cordiact.app.screen.ConfirmationGateImpl
import com.cordiact.app.screen.MainScreenDriver
import com.cordiact.app.screen.ScreenGuard
import com.cordiact.app.screen.ScreenPlugin
import com.cordiact.app.screen.ScreenRegistryImpl
import com.cordiact.app.screen.ScreenService
import com.cordiact.app.screen.registerScreenTools
import com.cordiact.app.ssh.SshHostStore
import com.cordiact.app.ssh.SshPlugin
import com.cordiact.app.ssh.registerSshTools
import com.cordiact.app.termux.TermuxBridge
import com.cordiact.app.termux.TermuxPlugin
import com.cordiact.app.termux.registerTermuxTools
import com.cordiact.core.automation.ScreenDriver
import com.cordiact.core.automation.ScreenHandle
import com.cordiact.core.plugin.ManagedPlugin
import com.cordiact.core.tool.ToolDefinitionRegistry

/**
 * Assembles the automation plugins.
 *
 * Dependency direction is deliberate: privilege is the base, display sits on
 * top of it, and screen is the single tool surface the model sees. The display
 * plugin contributes background drivers but no tools of its own, so the model
 * never has to choose between `screen_*` and `vd_*`.
 *
 * SSH and Termux plugins provide execution backends for remote hosts and the
 * local Termux Linux environment.
 */
class AutomationRuntime(context: Context) {

    private val appContext = context.applicationContext

    val shizukuChannel = ShizukuChannel(appContext)
    val rootChannel = RootChannel()
    val privilegeRouter = PrivilegeRouter(shizukuChannel, rootChannel)
    val privilegeService = PrivilegeService(privilegeRouter)
    val privilegePlugin = PrivilegePlugin(privilegeService)
    val displayPlugin = DisplayPlugin()

    val mainDriver: ScreenDriver = MainScreenDriver(appContext)

    private val backgroundDrivers = mutableListOf<BackgroundScreenDriver>()

    val screenService = ScreenService(
        ScreenRegistryImpl(
            mainDriver = mainDriver,
            backgroundDriver = { handle -> backgroundDriver(handle) },
        ),
    )

    val screenGuard = ScreenGuard(ConfirmationGateImpl(appContext))
    val screenPlugin = ScreenPlugin(screenService)

    val browserPool = BrowserSessionPool(appContext)
    val browserPlugin = BrowserPlugin(browserPool)

    val sshHostStore = SshHostStore(appContext)
    val sshPlugin = SshPlugin(hostStore = sshHostStore)

    val termuxBridge = TermuxBridge(
        appContext,
        privilegedShellRunner = { cmd ->
            val status = privilegeRouter.status()
            if (status.ready) {
                val res = privilegeRouter.exec(cmd)
                res.exitCode to res.stdout
            } else {
                -1 to (status.reason ?: "privileged router not ready")
            }
        },
    )
    val termuxPlugin = TermuxPlugin(bridge = termuxBridge)

    /** Returns the background driver for [handle], creating it on first use. */
    fun backgroundDriver(handle: ScreenHandle): BackgroundScreenDriver =
        backgroundDrivers.firstOrNull { it.handle == handle }
            ?: BackgroundScreenDriver(shizukuChannel, handle).also { backgroundDrivers += it }

    fun plugins(): List<ManagedPlugin> = listOf(
        privilegePlugin,
        displayPlugin,
        screenPlugin,
        browserPlugin,
        sshPlugin,
        termuxPlugin,
    )

    fun registerTools(
        registry: ToolDefinitionRegistry,
        jobRegistry: com.cordiact.core.jobs.JobRegistry? = null,
    ) {
        registerPrivilegeTools(registry, privilegeService)
        registerScreenTools(registry, screenService, screenGuard)
        registerBrowserTools(registry, browserPool, screenService)
        // The SSH backend owns the only thing a detached job needs to be checked on later: how to
        // reach its host. Registering the probe here keeps that knowledge out of the registry.
        val sshProbe = com.cordiact.app.ssh.SshJobProbe(
            execute = sshPlugin.service::execute,
            resolveSavedHost = sshPlugin.hostStore::get,
        ).also { probe ->
            jobRegistry?.let(probe::registerWith)
        }
        registerSshTools(registry, sshPlugin, jobRegistry, sshProbe)
        registerTermuxTools(registry, termuxPlugin, jobRegistry)
    }
}
