import { Speech } from "@opencode-ai/core/speech"
import path from "path"
import { tmpdir } from "os"

export type VoiceRecording = {
  stop: () => Promise<string>
  cancel: () => Promise<void>
}

export async function startVoiceRecording(onStatus?: (message: string) => void) {
  const audio = path.join(tmpdir(), `opencode-voice-${crypto.randomUUID()}.wav`)
  const proc = Bun.spawn(Speech.recordCommand(audio), { stdin: "pipe", stdout: "pipe", stderr: "pipe" })
  const reader = proc.stdout.getReader()
  const decoder = new TextDecoder()
  let ready = ""
  while (!ready.includes("\n")) {
    const chunk = await reader.read()
    if (chunk.done) break
    ready += decoder.decode(chunk.value, { stream: true })
  }
  reader.releaseLock()
  if (ready.trim() !== "ready") {
    const error = (await new Response(proc.stderr).text()).trim()
    await Bun.file(audio)
      .delete()
      .catch(() => undefined)
    throw new Error(error || "Voice recording failed to start")
  }

  const cleanup = async () => {
    await Bun.file(audio)
      .delete()
      .catch(() => undefined)
  }

  return {
    async stop() {
      try {
        proc.stdin.write("stop\n")
        proc.stdin.end()
        const [error, exitCode] = await Promise.all([new Response(proc.stderr).text(), proc.exited])
        if (exitCode !== 0) throw new Error(error.trim() || "Voice recording failed")
        return await Speech.transcribeAudioFile({ audio, onStatus })
      } finally {
        await cleanup()
      }
    },
    async cancel() {
      proc.kill()
      await proc.exited.catch(() => undefined)
      await cleanup()
    },
  }
}
