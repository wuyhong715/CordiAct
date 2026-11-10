package com.cordiact.model.providers

import com.cordiact.core.conversation.ModelAdapterService
import com.cordiact.core.conversation.ModelDescriptor
import com.cordiact.core.conversation.ModelEvent
import com.cordiact.core.conversation.ModelGenerationRequest
import com.cordiact.core.conversation.ModelId
import com.cordiact.core.security.AccessEnvelope
import com.cordiact.core.security.CredentialOperation
import com.cordiact.core.security.Principal
import com.cordiact.core.security.ReviewSnapshot
import com.cordiact.core.security.SecurityApprovalDecision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ModelReviewerClientTest {
    private fun snapshot() = ReviewSnapshot(
        requestId = "review-1",
        intentDigest = "digest-1",
        profileRevision = 3,
        policyEpoch = 4,
        actorChain = listOf(Principal("main")),
        workspaceIdentity = "ws-1",
        runId = "run-1",
        callId = "call-1",
        declaredUserGoalWithSource = "read a token for the user's API task",
        currentEnvelope = AccessEnvelope.WORKSPACE_ONLY,
        normalizedOperation = CredentialOperation.READ_PLAINTEXT,
        actualTarget = null,
        effectCategory = "credential_plaintext_read",
        sanitizedArguments = mapOf("credentialId" to "cred-1"),
        payloadShape = "secret:10",
        credentialTypeAndRevision = "BEARER_TOKEN:2",
        reviewerProviderId = "review-provider",
        reviewerModelId = "review-model",
        reviewerEndpointIdentity = "review.example.com",
        reviewerConfigRevision = 1,
    )

    @Test
    fun `parses strict allow response`() {
        val adapter = object : ModelAdapterService {
            override fun models(): List<ModelDescriptor> =
                listOf(ModelDescriptor(ModelId("review-provider", "review-model"), "review"))

            override fun generate(request: ModelGenerationRequest, onEvent: (ModelEvent) -> Unit): com.cordiact.core.conversation.ModelCall {
                onEvent(
                    ModelEvent.Delta(
                        """{"requestId":"review-1","intentDigest":"digest-1","decision":"ALLOW","risk":"LOW","reasonCode":"OK","reasonSummary":"within scope"}""",
                    ),
                )
                onEvent(ModelEvent.Completed())
                return com.cordiact.core.conversation.ModelCall { }
            }
        }
        val result = ModelReviewerClient { adapter }.review(snapshot())
        assertEquals(SecurityApprovalDecision.ALLOW, result.decision)
        assertEquals("OK", result.reasonCode)
    }

    @Test
    fun `rejects extra fields and markdown`() {
        fun adapterWith(text: String) = object : ModelAdapterService {
            override fun models(): List<ModelDescriptor> = emptyList()
            override fun generate(request: ModelGenerationRequest, onEvent: (ModelEvent) -> Unit): com.cordiact.core.conversation.ModelCall {
                onEvent(ModelEvent.Delta(text))
                onEvent(ModelEvent.Completed())
                return com.cordiact.core.conversation.ModelCall { }
            }
        }
        assertFailsWith<IllegalArgumentException> {
            ModelReviewerClient { adapterWith("""{"requestId":"review-1","intentDigest":"digest-1","decision":"ALLOW","risk":"LOW","reasonCode":"OK","reasonSummary":"ok","extra":true}""") }
                .review(snapshot())
        }
        assertFailsWith<IllegalArgumentException> {
            ModelReviewerClient { adapterWith("```json\n{}\n```") }.review(snapshot())
        }
    }
}
