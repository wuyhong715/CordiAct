package com.cordiact.app.image

import com.cordiact.app.CordiActApplication
import com.cordiact.core.tool.*
import com.cordiact.model.providers.ModelProtocol
import com.cordiact.model.providers.image.OpenAiImageGenerator
import java.util.UUID

fun registerImageGenerationTools(app: CordiActApplication, registry: ToolDefinitionRegistry) {
    fun providers() = app.modelProviders.providers().filter { it.protocol == ModelProtocol.OPENAI_IMAGES }
    registry.register(ToolDefinition(
        descriptor = ToolDescriptor(
            id = "generate_image", name = "Generate image",
            description = "Generate one PNG with a configured GPT image model (may incur charges). Use only when the user requests image creation. Image is saved in the current workspace and displayed in chat. No automatic retries; a timeout may still have incurred charges. Not an image editing tool. Optional provider_id/model_id select among enabled image providers/models.",
            ownerPluginId = com.cordiact.model.providers.MODEL_PROVIDER_REGISTRY_PLUGIN_ID, defaultEnabled = true, timeoutMs = 300_000,
            inputSchema = ToolInputSchema(listOf(
                ToolParameter("prompt", ToolValueType.STRING, required = true),
                ToolParameter("provider_id", ToolValueType.STRING),
                ToolParameter("model_id", ToolValueType.STRING),
                ToolParameter("size", ToolValueType.STRING, enumValues = listOf("auto", "1024x1024", "1536x1024", "1024x1536")),
                ToolParameter("quality", ToolValueType.STRING, enumValues = listOf("auto", "low", "medium", "high")),
            )),
        ),
        executor = ToolExecutor { context, args ->
            fun arg(name: String) = (args.values[name] as? ToolValue.StringValue)?.value
            val candidates = providers().filter { arg("provider_id") == null || it.providerId == arg("provider_id") }
            val provider = candidates.singleOrNull() ?: error("Select provider_id explicitly when multiple image providers exist")
            val model = arg("model_id") ?: provider.models.singleOrNull() ?: error("Select model_id explicitly when multiple image models exist")
            val sessionId = requireNotNull(context.sessionId) { "Image generation requires a conversation session" }
            // Validate workspace access before a potentially billable network request.
            app.workspaceFiles.list(context.workspaceId)
            val config = com.cordiact.model.providers.ModelProviderConfig(provider.providerId, provider.displayName, provider.protocol, provider.endpoint, provider.models, connectTimeoutMillis = 15_000, readTimeoutMillis = 240_000)
            val image = OpenAiImageGenerator().generate(config, app.providerApiKeyStore.provider(provider.providerId),
                OpenAiImageGenerator.Request(model, arg("prompt").orEmpty(), arg("size") ?: "auto", arg("quality") ?: "auto"))
            val id = UUID.randomUUID().toString()
            val path = "generated-$id.png"
            app.workspaceFiles.write(context.workspaceId, path, image.bytes)
            GeneratedImageStore(app).record(context.workspaceId, sessionId, id, path, model, image.bytes)
            ToolResult(linkedMapOf(
                "path" to ToolValue.StringValue(path),
                "mime_type" to ToolValue.StringValue(image.mimeType),
                "model" to ToolValue.StringValue(model),
                "bytes" to ToolValue.NumberValue(image.bytes.size.toDouble()),
                "note" to ToolValue.StringValue("Saved in workspace and displayed in chat. Do not generate again unless requested."),
            ))
        },
    ))
    registry.register(ToolDefinition(
        descriptor = ToolDescriptor("list_image_models", "List image models", "List enabled GPT image providers and model IDs for generate_image.", ToolInputSchema(), com.cordiact.model.providers.MODEL_PROVIDER_REGISTRY_PLUGIN_ID, defaultEnabled = true),
        executor = ToolExecutor { _, _ -> ToolResult(mapOf("models" to ToolValue.ArrayValue(providers().flatMap { p -> p.models.map { m -> ToolValue.ObjectValue(mapOf("provider_id" to ToolValue.StringValue(p.providerId), "model_id" to ToolValue.StringValue(m))) } }))) },
    ))
    registry.registerVisibility("generate_image", ToolVisibilityGate { providers().any { p -> p.models.any { it.startsWith("gpt-image-") } } })
    registry.registerVisibility("list_image_models", ToolVisibilityGate { providers().isNotEmpty() })
}
