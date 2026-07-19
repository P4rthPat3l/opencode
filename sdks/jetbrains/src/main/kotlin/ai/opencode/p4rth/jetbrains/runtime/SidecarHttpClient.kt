package ai.opencode.p4rth.jetbrains.runtime

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64

/**
 * Minimal JSON HTTP client for the managed OpenCode sidecar.
 * Used for non-UI work such as commit-message generation.
 */
class SidecarHttpClient(
  private val sidecar: Sidecar,
  private val client: HttpClient = HttpClient.newBuilder()
    .version(HttpClient.Version.HTTP_1_1)
    .connectTimeout(Duration.ofSeconds(15))
    .build(),
  private val gson: Gson = Gson(),
) {
  private val authHeader: String =
    "Basic " + Base64.getEncoder().encodeToString(
      "${sidecar.username}:${sidecar.password}".toByteArray(StandardCharsets.UTF_8),
    )

  fun createSession(directory: String?, title: String): String {
    val body = JsonObject().apply {
      addProperty("title", title)
      // Deny all tools for this ephemeral session so generation cannot block on permission prompts.
      add(
        "permission",
        JsonArray().apply {
          add(JsonObject().apply {
            addProperty("permission", "*")
            addProperty("pattern", "*")
            addProperty("action", "deny")
          })
        },
      )
    }
    val response = post("/session", body, directory, Duration.ofSeconds(60))
    require(response.statusCode() in 200..299) {
      "Failed to create OpenCode session: HTTP ${response.statusCode()} ${response.body().take(500)}"
    }
    val id = JsonParser.parseString(response.body()).asJsonObject.get("id")?.asString
    require(!id.isNullOrBlank()) { "OpenCode session create returned no id" }
    return id
  }

  fun prompt(
    sessionID: String,
    directory: String?,
    text: String,
    agent: String?,
    system: String?,
    model: ModelRef?,
  ): String {
    val parts = JsonArray().apply {
      add(JsonObject().apply {
        addProperty("type", "text")
        addProperty("text", text)
      })
    }
    val body = JsonObject().apply {
      // Prefer omitting hidden agents like "title"/"summary" — their system prompts force
      // one-line titles or first-person PR prose, which pollutes commit messages.
      if (!agent.isNullOrBlank()) addProperty("agent", agent)
      if (!system.isNullOrBlank()) addProperty("system", system)
      if (model != null) {
        add("model", JsonObject().apply {
          addProperty("providerID", model.providerID)
          addProperty("modelID", model.modelID)
        })
      }
      add("parts", parts)
    }
    // No overall request timeout: LLM generation over a full staged diff can take a long time.
    // The official JS SDK also disables request timeouts for prompt calls.
    val response = try {
      post("/session/$sessionID/message", body, directory, timeout = null)
    } catch (error: HttpTimeoutException) {
      throw IllegalStateException(
        "OpenCode timed out while generating the commit message. Check provider connectivity and try again.",
        error,
      )
    } catch (error: java.io.IOException) {
      throw IllegalStateException(
        "OpenCode connection failed while generating the commit message: ${error.message}",
        error,
      )
    }
    require(response.statusCode() in 200..299) {
      "OpenCode prompt failed: HTTP ${response.statusCode()} ${response.body().take(800)}"
    }
    return extractAssistantText(response.body())
  }

  /**
   * Resolve the model OpenCode would use when the setting is empty:
   * config.model → first entry from /config/providers defaults → first listed model.
   */
  fun resolveDefaultModel(directory: String?): ModelRef? {
    runCatching {
      val configResponse = get("/config", directory, Duration.ofSeconds(30))
      if (configResponse.statusCode() in 200..299) {
        val model = JsonParser.parseString(configResponse.body()).asJsonObject.get("model")?.asString
        parseModelRef(model.orEmpty())?.let { return it }
      }
    }

    val response = get("/config/providers", directory, Duration.ofSeconds(30))
    if (response.statusCode() !in 200..299) return null
    val root = JsonParser.parseString(response.body()).asJsonObject
    val defaults = root.getAsJsonObject("default")
    if (defaults != null) {
      for ((providerID, value) in defaults.entrySet()) {
        val modelID = value.asString
        if (providerID.isNotBlank() && !modelID.isNullOrBlank()) {
          return ModelRef(providerID, modelID)
        }
      }
    }
    val providers = root.getAsJsonArray("providers") ?: return null
    for (element in providers) {
      val provider = element.asJsonObject
      val providerID = provider.get("id")?.asString ?: continue
      val models = provider.getAsJsonObject("models") ?: continue
      val first = models.entrySet().firstOrNull()?.key ?: continue
      return ModelRef(providerID, first)
    }
    return null
  }

  /**
   * Same provider/model catalog the OpenCode web UI model picker uses (`GET /config/providers`).
   * Display labels use model + provider names (e.g. "DeepSeek V4 Flash Free — OpenCode Zen").
   */
  fun listModels(directory: String?): List<ModelOption> {
    val response = get("/config/providers", directory, Duration.ofSeconds(30))
    if (response.statusCode() !in 200..299) return emptyList()
    return parseModelOptions(response.body())
  }

  /** @deprecated Prefer [listModels]; kept for callers that only need id strings. */
  fun listModelRefs(directory: String?): List<String> =
    listModels(directory).map { it.value }

  fun deleteSession(sessionID: String, directory: String?) {
    runCatching {
      client.send(
        requestBuilder("/session/$sessionID", directory)
          .timeout(Duration.ofSeconds(15))
          .DELETE()
          .build(),
        HttpResponse.BodyHandlers.ofString(),
      )
    }
  }

  private fun get(path: String, directory: String?, timeout: Duration): HttpResponse<String> {
    return client.send(
      requestBuilder(path, directory)
        .timeout(timeout)
        .GET()
        .build(),
      HttpResponse.BodyHandlers.ofString(),
    )
  }

  private fun post(
    path: String,
    body: JsonObject,
    directory: String?,
    timeout: Duration?,
  ): HttpResponse<String> {
    val builder = requestBuilder(path, directory)
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
    if (timeout != null) builder.timeout(timeout)
    return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
  }

  private fun requestBuilder(path: String, directory: String?): HttpRequest.Builder {
    val builder = HttpRequest.newBuilder(URI.create(sidecar.url + path))
      .header("Authorization", authHeader)
    // Prefer the header the server documents for instance routing.
    if (!directory.isNullOrBlank()) {
      builder.header("x-opencode-directory", directory)
    }
    return builder
  }

  data class ModelRef(val providerID: String, val modelID: String) {
    fun asSetting(): String = "$providerID/$modelID"
  }

  /** One selectable model for settings, matching the web UI catalog entry. */
  data class ModelOption(
    val providerID: String,
    val modelID: String,
    val providerName: String,
    val modelName: String,
  ) {
    val value: String get() = "$providerID/$modelID"
    /** Combo label: model name first (what the web UI emphasizes), then provider group. */
    val label: String get() = "$modelName — $providerName"
  }

  companion object {
    fun parseModelRef(raw: String): ModelRef? {
      val value = raw.trim()
      val slash = value.indexOf('/')
      if (slash <= 0 || slash >= value.length - 1) return null
      return ModelRef(value.substring(0, slash), value.substring(slash + 1))
    }

    fun parseModelOptions(body: String): List<ModelOption> {
      val root = JsonParser.parseString(body).asJsonObject
      val providers = root.getAsJsonArray("providers") ?: return emptyList()
      val out = ArrayList<ModelOption>()
      for (element in providers) {
        val provider = element.asJsonObject
        val providerID = provider.get("id")?.asString?.trim().orEmpty()
        if (providerID.isEmpty()) continue
        val providerName = provider.get("name")?.asString?.trim().orEmpty().ifBlank { providerID }
        val models = provider.getAsJsonObject("models") ?: continue
        for ((modelID, modelValue) in models.entrySet()) {
          if (modelID.isBlank()) continue
          val modelObj = modelValue.takeIf { it.isJsonObject }?.asJsonObject
          val modelName = modelObj?.get("name")?.asString?.trim().orEmpty()
            .ifBlank { modelID }
            .replace("(latest)", "")
            .trim()
          out += ModelOption(
            providerID = providerID,
            modelID = modelID,
            providerName = providerName,
            modelName = modelName,
          )
        }
      }
      // Match the web picker grouping: provider, then model name.
      return out.sortedWith(compareBy({ it.providerName.lowercase() }, { it.modelName.lowercase() }, { it.value }))
    }

    fun extractAssistantText(body: String): String {
      val root = JsonParser.parseString(body).asJsonObject
      val parts = root.getAsJsonArray("parts") ?: error("OpenCode response missing parts")
      val text = buildString {
        for (element in parts) {
          val part = element.asJsonObject
          if (part.get("type")?.asString != "text") continue
          val chunk = part.get("text")?.asString ?: continue
          if (isNotEmpty()) append('\n')
          append(chunk)
        }
      }.trim()
      require(text.isNotEmpty()) { "OpenCode returned an empty assistant message" }
      return stripFences(text)
    }

    fun stripFences(text: String): String {
      val trimmed = text.trim()
      val fenced = Regex("^```(?:\\w+)?\\s*\\n([\\s\\S]*?)\\n```\\s*$")
      val match = fenced.matchEntire(trimmed) ?: return trimmed
      return match.groupValues[1].trim()
    }

    fun applyReplaceMode(mode: String, existing: String, generated: String): String {
      return when (mode) {
        "append" -> {
          if (existing.isBlank()) generated
          else existing.trimEnd() + "\n\n" + generated
        }
        "replaceIfEmpty" -> if (existing.isBlank()) generated else existing
        else -> generated
      }
    }
  }
}
