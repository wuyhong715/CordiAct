package com.cordiact.core.plugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PackageModelContributionsTest {
    @Test
    fun `model offer belongs to one workspace and one activation`() {
        val catalog = PackageModelContributions()
        val kernel = PluginKernel()
        val manifest = ExtensionPackageSource.compile(SOURCE.toByteArray())
        val retired = mutableListOf<PackageModelContributions.Offer>()
        var approved = false
        val owner = PluginInstanceId("pkg.models-package", "alpha")
        kernel.register(owner, object : ContextPlugin {
            override fun start(context: PluginContext) = catalog.register(context, manifest, { approved }, retired::add)
        })
        kernel.enable(owner)
        assertNull(catalog.find("alpha", ID))
        approved = true
        catalog.refresh()
        val first = assertNotNull(catalog.find("alpha", ID))
        assertEquals(listOf("test-model"), first.models)
        assertEquals("models-package@1.0.0", first.source)
        assertNull(catalog.find("beta", ID))
        assertTrue(catalog.isCurrent(first))
        approved = false
        catalog.refresh()
        assertFalse(catalog.isCurrent(first))
        kernel.disable(owner)
        assertEquals(listOf(first), retired)

        approved = true
        kernel.enable(owner)
        val replacement = assertNotNull(catalog.find("alpha", ID))
        assertEquals(first.revision, replacement.revision)
        assertNotEquals(first.generation, replacement.generation)
        assertFalse(catalog.isCurrent(first))
    }

    @Test
    fun `model package validates exact grant and public endpoint shape`() {
        assertEquals(PluginTarget.ANDROID_NATIVE, extensionPackageSpec(ExtensionPackageSource.compile(SOURCE.toByteArray())).target)
        listOf(
            SOURCE.replace("\"schemaVersion\":7", "\"schemaVersion\":6"),
            SOURCE.replace("\"provider.model.publish\"", ""),
            SOURCE.replace("https://example.test/v1", "http://example.test/v1"),
            SOURCE.replace("https://example.test/v1", "https://name@example.test/v1"),
            SOURCE.replace("https://example.test/v1", "https://example.test/v1?key=hidden"),
            SOURCE.replace("OPENAI_RESPONSES", "OPENAI_IMAGES"),
            SOURCE.replace("[\"test-model\"]", "[\"test-model\",\"test-model\"]"),
        ).forEach { variant -> assertFailsWith<IllegalArgumentException> { ExtensionPackageSource.compile(variant.toByteArray()) } }
    }

    private companion object {
        const val ID = "pkg.models-package.responses"
        val SOURCE = """{
            "schemaVersion":7,"id":"models-package","version":"1.0.0","title":"Model routes","description":"Example route",
            "host":{"api":1,"minVersion":"0.1.0"},"capabilities":["provider.model.publish"],"tools":[],"commands":[],
            "providers":{"model":[{"name":"responses","title":"Example","protocol":"OPENAI_RESPONSES","endpoint":"https://example.test/v1","models":["test-model"]}]}
        }""".trimIndent()
    }
}
