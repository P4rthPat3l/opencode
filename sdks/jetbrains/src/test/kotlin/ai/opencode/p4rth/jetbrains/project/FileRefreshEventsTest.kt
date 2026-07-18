package ai.opencode.p4rth.jetbrains.project

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FileRefreshEventsTest {
  private fun edited(file: String, directory: String = "/projA") =
    """{"directory":"$directory","project":"p","payload":{"id":"e","type":"file.edited","properties":{"file":"$file"}}}"""

  @Test
  fun `parses a file edited event`() {
    val parsed = FileRefreshEvents.parse(edited("/projA/src/x.ts"))
    assertEquals(FileRefreshEvents.FileEdited("/projA", "/projA/src/x.ts"), parsed)
  }

  @Test
  fun `ignores non file events`() {
    assertNull(FileRefreshEvents.parse("""{"payload":{"id":"e","type":"server.heartbeat","properties":{}}}"""))
    assertNull(FileRefreshEvents.parse("""{"payload":{"type":"sync","syncEvent":{"type":"session.updated.1"}}}"""))
    assertNull(FileRefreshEvents.parse("not json"))
  }

  @Test
  fun `resolves paths inside the project base`() {
    val event = FileRefreshEvents.FileEdited("/projA", "/projA/src/x.ts")
    val path = FileRefreshEvents.affectedPath(event, Path.of("/projA"))
    assertEquals(Path.of("/projA/src/x.ts").toAbsolutePath().normalize(), path)
  }

  @Test
  fun `rejects paths outside the project base so project A never refreshes project B`() {
    val event = FileRefreshEvents.FileEdited("/projB", "/projB/src/y.ts")
    assertNull(FileRefreshEvents.affectedPath(event, Path.of("/projA")))
  }

  @Test
  fun `rejects path traversal escaping the project base`() {
    val event = FileRefreshEvents.FileEdited("/projA", "/projA/../projB/secret.ts")
    assertNull(FileRefreshEvents.affectedPath(event, Path.of("/projA")))
  }
}
