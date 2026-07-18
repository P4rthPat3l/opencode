package ai.opencode.p4rth.jetbrains.runtime

object SecretRedactor {
  private val patterns = listOf(
    Regex("(?i)(authorization: basic )[^\\s]+"),
    Regex("(?i)(api[_-]?key[\\\"' ]*[:=][\\\"' ]*)[^\\\"'\\s,]+"),
    Regex("(?i)(token[\\\"' ]*[:=][\\\"' ]*)[^\\\"'\\s,]+"),
    Regex("sk-[A-Za-z0-9_-]{12,}"),
  )

  fun redact(input: String) = patterns.fold(input) { value, pattern ->
    pattern.replace(value) { match ->
      val prefix = if (match.groups.size > 1) match.groups[1]?.value.orEmpty() else ""
      "${prefix}[redacted]"
    }
  }
}
