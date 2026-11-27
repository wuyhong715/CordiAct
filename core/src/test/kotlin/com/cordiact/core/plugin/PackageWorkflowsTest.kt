package com.cordiact.core.plugin

import com.cordiact.core.context.StandardSpillStore
import com.cordiact.core.jobs.DefaultJobRegistry
import com.cordiact.core.jobs.JobKind
import com.cordiact.core.jobs.JobState
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.Executor
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PackageWorkflowsTest {
    @Test
    fun `steps chain text and withdrawal cancels queued work`(@TempDir temp: File) {
        val jobs = DefaultJobRegistry(StandardSpillStore(temp))
        val queue = ArrayDeque<Runnable>()
        val workflows = PackageWorkflows(jobs, Executor { queue.addLast(it) })
        val manifest = ExtensionPackageSource.compile(SOURCE.encodeToByteArray())
        val kernel = PluginKernel()
        val owner = PluginInstanceId("pkg.notes-flow", "workspace-a")
        val calls = mutableListOf<Pair<String, String>>()
        val runtime = PackageScriptRuntime {
            object : PackageScriptSession {
                override fun evaluate(source: ByteArray, input: ByteArray): ByteArray {
                    val name = source.decodeToString()
                    val text = input.decodeToString()
                    calls += name to text
                    return when (name) {
                        "trim" -> text.trim().encodeToByteArray()
                        else -> text.lowercase().encodeToByteArray()
                    }
                }
                override fun close() = Unit
            }
        }
        kernel.register(owner, object : ContextPlugin {
            override fun start(context: PluginContext) = workflows.register(context, manifest, runtime) { true }
        })
        kernel.enable(owner)
        assertTrue(workflows.offers("workspace-b", owner.pluginId).isEmpty())
        val offer = workflows.offers("workspace-a", owner.pluginId).single()
        assertFailsWith<IllegalStateException> { workflows.start(offer, "session-a", "Test") { false } }
        val first = workflows.start(offer, "session-a", " Test ") { true }
        assertEquals(JobKind.PLUGIN_WORKFLOW, first.kind)
        assertFalse(first.isTerminal)
        assertFailsWith<IllegalStateException> { workflows.start(offer, "session-a", "Concurrent") { true } }
        queue.removeFirst().run()
        assertEquals(listOf("trim" to " Test ", "lower" to "Test"), calls)
        assertEquals("test", jobs.read(first.id)?.text)
        assertEquals(JobState.SUCCEEDED, jobs.get(first.id)?.state)

        val cancelledByUser = workflows.start(offer, "session-a", "Cancelled") { true }
        jobs.kill(cancelledByUser.id)
        val cancelledByWithdrawal = workflows.start(offer, "session-a", "Withdrawn") { true }
        kernel.disable(owner)
        assertEquals(JobState.KILLED, jobs.get(cancelledByUser.id)?.state)
        assertEquals(JobState.KILLED, jobs.get(cancelledByWithdrawal.id)?.state)
        queue.removeFirst().run()
        queue.removeFirst().run()
        assertEquals(2, calls.size)
        assertFailsWith<IllegalStateException> { workflows.start(offer, "session-a", "Stale") { true } }
    }

    private companion object {
        val SOURCE = """{
            "schemaVersion":9,"id":"notes-flow","version":"1.0.0","title":"Notes","description":"Clean up notes",
            "host":{"api":1,"minVersion":"0.1.0"},"capabilities":["workflow.publish"],"tools":[],"commands":[],
            "workflows":[{"name":"normalize","title":"Clean up","steps":[
                {"title":"Trim","source":"trim"},{"title":"Lower","source":"lower"}]}]
        }""".trimIndent()
    }
}
