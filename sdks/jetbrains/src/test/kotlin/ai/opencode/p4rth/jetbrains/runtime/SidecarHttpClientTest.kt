package ai.opencode.p4rth.jetbrains.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SidecarHttpClientTest {
  @Test
  fun parsesProviderModelRef() {
    val ref = SidecarHttpClient.parseModelRef("anthropic/claude-sonnet-4")
    assertEquals("anthropic", ref!!.providerID)
    assertEquals("claude-sonnet-4", ref.modelID)
  }

  @Test
  fun parsesModelIdsWithSlashes() {
    val ref = SidecarHttpClient.parseModelRef("openai/gpt-4o/mini")
    assertEquals("openai", ref!!.providerID)
    assertEquals("gpt-4o/mini", ref.modelID)
  }

  @Test
  fun rejectsInvalidModelRef() {
    assertNull(SidecarHttpClient.parseModelRef(""))
    assertNull(SidecarHttpClient.parseModelRef("noslash"))
    assertNull(SidecarHttpClient.parseModelRef("/onlymodel"))
  }

  @Test
  fun extractsAssistantTextParts() {
    val body = """
      {
        "info": { "id": "msg" },
        "parts": [
          { "type": "reasoning", "text": "thinking" },
          { "type": "text", "text": "feat: add button" },
          { "type": "text", "text": "body line" }
        ]
      }
    """.trimIndent()
    assertEquals("feat: add button\nbody line", SidecarHttpClient.extractAssistantText(body))
  }

  @Test
  fun stripsMarkdownFences() {
    assertEquals(
      "fix: handle null",
      SidecarHttpClient.stripFences("```\nfix: handle null\n```"),
    )
  }

  @Test
  fun applyReplaceModes() {
    assertEquals("new", SidecarHttpClient.applyReplaceMode("alwaysReplace", "old", "new"))
    assertEquals("old", SidecarHttpClient.applyReplaceMode("replaceIfEmpty", "old", "new"))
    assertEquals("new", SidecarHttpClient.applyReplaceMode("replaceIfEmpty", "", "new"))
    assertEquals("old\n\nnew", SidecarHttpClient.applyReplaceMode("append", "old", "new"))
    assertEquals("new", SidecarHttpClient.applyReplaceMode("append", "", "new"))
  }

  @Test
  fun parsesModelOptionsFromConfigProviders() {
    val body = """
      {
        "providers": [
          {
            "id": "openai",
            "name": "OpenAI",
            "models": {
              "gpt-4o": { "id": "gpt-4o", "name": "GPT-4o" },
              "gpt-4o/mini": { "id": "gpt-4o/mini", "name": "GPT-4o Mini" }
            }
          },
          {
            "id": "opencode",
            "name": "OpenCode Zen",
            "models": {
              "deepseek-v4-flash-free": { "id": "deepseek-v4-flash-free", "name": "DeepSeek V4 Flash Free" },
              "hy3-free": { "id": "hy3-free", "name": "Hy3 Free" }
            }
          }
        ],
        "default": {}
      }
    """.trimIndent()
    val options = SidecarHttpClient.parseModelOptions(body)
    assertEquals(4, options.size)
    // Sorted by provider name, then model name (OpenAI before OpenCode Zen).
    assertEquals("GPT-4o — OpenAI", options[0].label)
    assertEquals("openai/gpt-4o", options[0].value)
    assertEquals("GPT-4o Mini — OpenAI", options[1].label)
    assertEquals("openai/gpt-4o/mini", options[1].value)
    assertEquals("DeepSeek V4 Flash Free — OpenCode Zen", options[2].label)
    assertEquals("opencode/deepseek-v4-flash-free", options[2].value)
    assertEquals("Hy3 Free — OpenCode Zen", options[3].label)
  }

  @Test
  fun stripsLatestSuffixFromModelName() {
    val body = """
      {
        "providers": [
          {
            "id": "anthropic",
            "name": "Anthropic",
            "models": {
              "claude": { "id": "claude", "name": "Claude Sonnet (latest)" }
            }
          }
        ]
      }
    """.trimIndent()
    val options = SidecarHttpClient.parseModelOptions(body)
    assertEquals("Claude Sonnet — Anthropic", options.single().label)
  }
}
