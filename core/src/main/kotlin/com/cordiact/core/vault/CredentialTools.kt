package com.cordiact.core.vault

import com.cordiact.core.security.CREDENTIAL_VAULT_PLUGIN_ID
import com.cordiact.core.security.SecurityContext
import com.cordiact.core.security.SecurityDeniedException
import com.cordiact.core.security.SensitivePayload
import com.cordiact.core.security.TargetDescriptor
import com.cordiact.core.tool.ToolArguments
import com.cordiact.core.tool.ToolDefinition
import com.cordiact.core.tool.ToolDescriptor
import com.cordiact.core.tool.ToolExecutor
import com.cordiact.core.tool.ToolInvocationContext
import com.cordiact.core.tool.ToolParameter
import com.cordiact.core.tool.ToolResult
import com.cordiact.core.tool.ToolValue
import com.cordiact.core.tool.ToolValueType

fun interface SecurityContextResolver {
    fun resolve(invocation: ToolInvocationContext): SecurityContext?
}

/** Resolves a one-time inputRef created by the trusted local input surface. */
fun interface TrustedInputBroker {
    fun resolve(inputRef: String): CredentialPayload?
}

object CredentialToolIds {
    const val LIST = "credential_list"
    const val READ = "credential_read"
    const val WRITE = "credential_write"
    const val DELETE = "credential_delete"
    const val USE = "credential_use"
}

/**
 * Tool surface for the credential vault. Every executor resolves the host-issued SecurityContext
 * and delegates to [CredentialVaultService], which runs the mandatory SensitiveOperationGate.
 */
class CredentialToolset(
    private val service: CredentialVaultService,
    private val contextResolver: SecurityContextResolver,
    private val inputBroker: TrustedInputBroker = TrustedInputBroker { null },
) {
    fun definitions(): List<ToolDefinition> = listOf(
        definition(
            id = CredentialToolIds.LIST,
            name = "List credentials",
            description = "List credential metadata in the current Workspace after approval. Does not return secret values.",
            parameters = listOf(
                ToolParameter("query", ToolValueType.STRING, required = false, maxLength = 200),
                ToolParameter(
                    "type",
                    ToolValueType.STRING,
                    required = false,
                    enumValues = CredentialType.entries.map { it.name },
                ),
                ToolParameter("limit", ToolValueType.INTEGER, required = false, minimum = 1.0, maximum = 100.0),
                ToolParameter("purpose", ToolValueType.STRING, required = true, maxLength = 500),
            ),
        ) { context, args -> list(context, args) },
        definition(
            id = CredentialToolIds.READ,
            name = "Read a credential",
            description = "Read credential metadata or plaintext. Plaintext is returned only through the approved sensitive-result path.",
            parameters = listOf(
                ToolParameter("credentialId", ToolValueType.STRING, required = true),
                ToolParameter(
                    "mode",
                    ToolValueType.STRING,
                    required = true,
                    enumValues = listOf("metadata", "plaintext"),
                ),
                ToolParameter("purpose", ToolValueType.STRING, required = true, maxLength = 500),
            ),
        ) { context, args -> read(context, args) },
        definition(
            id = CredentialToolIds.WRITE,
            name = "Create or update a credential",
            description = "Create or update a credential after approval. Use inputRef for values typed in the trusted local form; value is a fallback for values already held by the agent.",
            parameters = listOf(
                ToolParameter("credentialId", ToolValueType.STRING, required = false),
                ToolParameter("expectedRevision", ToolValueType.INTEGER, required = false, minimum = 1.0),
                ToolParameter(
                    "type",
                    ToolValueType.STRING,
                    required = true,
                    enumValues = CredentialType.entries.map { it.name },
                ),
                ToolParameter("label", ToolValueType.STRING, required = true, maxLength = 200),
                ToolParameter("notes", ToolValueType.STRING, required = false, maxLength = 2_000),
                ToolParameter("accountHint", ToolValueType.STRING, required = false, maxLength = 200),
                ToolParameter("inputRef", ToolValueType.STRING, required = false, maxLength = 500),
                ToolParameter("value", ToolValueType.STRING, required = false, maxLength = 65_536, sensitive = true),
                ToolParameter("purpose", ToolValueType.STRING, required = true, maxLength = 500),
            ),
        ) { context, args -> write(context, args) },
        definition(
            id = CredentialToolIds.DELETE,
            name = "Delete a credential",
            description = "Delete one credential at an explicit revision after approval.",
            parameters = listOf(
                ToolParameter("credentialId", ToolValueType.STRING, required = true),
                ToolParameter("expectedRevision", ToolValueType.INTEGER, required = true, minimum = 1.0),
                ToolParameter("purpose", ToolValueType.STRING, required = true, maxLength = 500),
            ),
        ) { context, args -> delete(context, args) },
        definition(
            id = CredentialToolIds.USE,
            name = "Use a credential for an HTTPS request",
            description = "Inject a credential into the host HTTPS adapter. The model receives status and a sanitized summary, not the secret value.",
            parameters = listOf(
                ToolParameter("credentialId", ToolValueType.STRING, required = true),
                ToolParameter("url", ToolValueType.STRING, required = true, maxLength = 2_000),
                ToolParameter(
                    "method",
                    ToolValueType.STRING,
                    required = false,
                    enumValues = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD"),
                ),
                ToolParameter(
                    "authMode",
                    ToolValueType.STRING,
                    required = false,
                    enumValues = CredentialAuthMode.entries.map { it.name },
                ),
                ToolParameter("headerName", ToolValueType.STRING, required = false, maxLength = 100),
                ToolParameter("body", ToolValueType.STRING, required = false, maxLength = 64 * 1024),
                ToolParameter("purpose", ToolValueType.STRING, required = true, maxLength = 500),
            ),
        ) { context, args -> use(context, args) },
    )

    fun register(registry: com.cordiact.core.tool.ToolDefinitionRegistry) {
        definitions().forEach(registry::register)
    }

    private fun list(context: ToolInvocationContext, args: ToolArguments): ToolResult = runTool {
        val security = securityContext(context)
        val type = args.string("type")?.let(CredentialType::valueOf)
        val items = service.list(
            context = security,
            query = args.string("query"),
            type = type,
            limit = args.int("limit")?.coerceIn(1, 100)?.toInt() ?: 20,
            purpose = args.requireString("purpose"),
        )
        success(
            "items" to ToolValue.ArrayValue(
                items.map { item ->
                    ToolValue.ObjectValue(
                        mapOf(
                            "id" to ToolValue.StringValue(item.id),
                            "type" to ToolValue.StringValue(item.type.name),
                            "label" to ToolValue.StringValue(item.label),
                            "revision" to ToolValue.NumberValue(item.revision.toDouble()),
                            "disabled" to ToolValue.BooleanValue(item.disabled),
                        ),
                    )
                },
            ),
        )
    }

    private fun read(context: ToolInvocationContext, args: ToolArguments): ToolResult = runTool {
        val security = securityContext(context)
        val credentialId = args.requireString("credentialId")
        when (val mode = args.requireString("mode")) {
            "metadata" -> {
                val details = service.readMetadata(security, credentialId, purpose = args.requireString("purpose"))
                success(
                    "id" to ToolValue.StringValue(details.summary.id),
                    "label" to ToolValue.StringValue(details.summary.label),
                    "notes" to ToolValue.StringValue(details.notes),
                    "accountHint" to ToolValue.StringValue(details.summary.accountHint),
                    "revision" to ToolValue.NumberValue(details.summary.revision.toDouble()),
                )
            }
            "plaintext" -> {
                val payload = service.readPlaintext(security, credentialId, purpose = args.requireString("purpose"))
                ToolResult(
                    values = mapOf(
                        "ok" to ToolValue.BooleanValue(true),
                        "message" to ToolValue.StringValue("credential delivered through the approved sensitive-result path"),
                    ),
                    sensitivePayload = payload,
                )
            }
            else -> failure("INVALID_READ_MODE")
        }
    }

    private fun write(context: ToolInvocationContext, args: ToolArguments): ToolResult = runTool {
        val security = securityContext(context)
        val type = CredentialType.valueOf(args.requireString("type"))
        val inputRef = args.string("inputRef")
        val value = args.string("value")
        if ((inputRef == null) == (value == null)) return@runTool failure("INPUT_REF_XOR_VALUE_REQUIRED")
        val payload = when {
            inputRef != null -> inputBroker.resolve(inputRef) ?: return@runTool failure("INPUT_REF_INVALID")
            type in setOf(CredentialType.API_KEY, CredentialType.BEARER_TOKEN, CredentialType.GENERIC_SECRET) ->
                CredentialPayload.Secret(value!!.toByteArray(Charsets.UTF_8))
            else -> return@runTool failure("TYPED_INPUT_REQUIRED")
        }
        val metadata = CredentialMetadata(
            label = args.requireString("label"),
            notes = args.string("notes") ?: "",
            accountHint = args.string("accountHint") ?: "",
        )
        val summary = service.write(
            security,
            CredentialWriteRequest(
                credentialId = args.string("credentialId"),
                expectedRevision = args.int("expectedRevision"),
                type = type,
                metadata = metadata,
                payload = payload,
            ),
            purpose = args.requireString("purpose"),
        )
        success(
            "id" to ToolValue.StringValue(summary.id),
            "revision" to ToolValue.NumberValue(summary.revision.toDouble()),
            "label" to ToolValue.StringValue(summary.label),
        )
    }

    private fun delete(context: ToolInvocationContext, args: ToolArguments): ToolResult = runTool {
        val security = securityContext(context)
        val deleted = service.delete(
            security,
            credentialId = args.requireString("credentialId"),
            expectedRevision = args.requireLong("expectedRevision"),
            purpose = args.requireString("purpose"),
        )
        success("deleted" to ToolValue.BooleanValue(deleted))
    }

    private fun use(context: ToolInvocationContext, args: ToolArguments): ToolResult = runTool {
        val security = securityContext(context)
        val target = TargetDescriptor.parseHttps(
            url = args.requireString("url"),
            method = args.string("method") ?: "GET",
        )
        val result = service.use(
            security,
            CredentialUseRequest(
                credentialId = args.requireString("credentialId"),
                target = target,
                authMode = args.string("authMode")?.let(CredentialAuthMode::valueOf) ?: CredentialAuthMode.AUTO,
                headerName = args.string("headerName"),
                body = args.string("body"),
            ),
            purpose = args.requireString("purpose"),
        )
        success(
            "statusCode" to ToolValue.NumberValue(result.statusCode.toDouble()),
            "summary" to ToolValue.StringValue(result.responseSummary),
            "body" to ToolValue.StringValue(result.responseBytes?.toString(Charsets.UTF_8) ?: ""),
        )
    }

    private fun securityContext(context: ToolInvocationContext): SecurityContext =
        contextResolver.resolve(context) ?: throw SecurityDeniedException("SECURITY_CONTEXT_MISSING")

    private fun definition(
        id: String,
        name: String,
        description: String,
        parameters: List<ToolParameter>,
        handler: (ToolInvocationContext, ToolArguments) -> ToolResult,
    ): ToolDefinition = ToolDefinition(
        descriptor = ToolDescriptor(
            id = id,
            name = name,
            description = description,
            inputSchema = com.cordiact.core.tool.ToolInputSchema(parameters = parameters),
            ownerPluginId = CREDENTIAL_VAULT_PLUGIN_ID,
            defaultEnabled = true,
            timeoutMs = 130_000,
        ),
        executor = ToolExecutor(handler),
    )

    private fun runTool(block: () -> ToolResult): ToolResult = try {
        block()
    } catch (denied: SecurityDeniedException) {
        failure(denied.reasonCode)
    } catch (failure: Throwable) {
        failure(failure::class.simpleName ?: "CREDENTIAL_OPERATION_FAILED")
    }

    private fun success(vararg values: Pair<String, ToolValue>): ToolResult =
        ToolResult(values = mapOf("ok" to ToolValue.BooleanValue(true)) + values)

    private fun failure(code: String): ToolResult = ToolResult(
        values = mapOf(
            "ok" to ToolValue.BooleanValue(false),
            "error" to ToolValue.StringValue(code),
        ),
    )

    private fun ToolArguments.string(name: String): String? = (values[name] as? ToolValue.StringValue)?.value

    private fun ToolArguments.requireString(name: String): String =
        string(name) ?: throw IllegalArgumentException("missing parameter: $name")

    private fun ToolArguments.int(name: String): Long? = when (val value = values[name]) {
        is ToolValue.NumberValue -> value.value.toLong()
        else -> null
    }

    private fun ToolArguments.requireLong(name: String): Long =
        int(name) ?: throw IllegalArgumentException("missing parameter: $name")
}
