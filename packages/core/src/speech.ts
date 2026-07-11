import { existsSync } from "fs"
import { mkdir, rename, rm, writeFile } from "fs/promises"
import { homedir, platform, tmpdir } from "os"
import path from "path"
import { xdgCache } from "xdg-basedir"

export type TranscribeInput = {
  audio: string
  language?: string
  onStatus?: (message: string) => void
}

const MODEL = {
  name: "base-q5_1",
  file: "ggml-base-q5_1.bin",
  size: "60 MB",
  sha256: "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898",
  url: "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base-q5_1.bin",
}

export async function transcribeAudioFile(input: TranscribeInput) {
  const model = await ensureModel(input.onStatus)
  input.onStatus?.("Transcribing locally…")
  const proc = Bun.spawn([speechBinary(), "transcribe", model, input.audio, input.language ?? "auto"], {
    stdout: "pipe",
    stderr: "pipe",
  })
  const [stdout, stderr, exitCode] = await Promise.all([
    new Response(proc.stdout).text(),
    new Response(proc.stderr).text(),
    proc.exited,
  ])
  if (exitCode !== 0) throw new Error(stderr.trim() || "Local voice transcription failed")
  return stdout.trim()
}

export async function transcribeAudioBytes(input: { bytes: Uint8Array; language?: string }) {
  const file = path.join(tmpdir(), `opencode-voice-${crypto.randomUUID()}.wav`)
  await writeFile(file, input.bytes)
  try {
    return await transcribeAudioFile({ audio: file, language: input.language })
  } finally {
    await Bun.file(file)
      .delete()
      .catch(() => undefined)
  }
}

export function recordCommand(output: string) {
  return [speechBinary(), "record", output]
}

async function ensureModel(onStatus?: (message: string) => void) {
  const configured = process.env.OPENCODE_STT_MODEL
  if (configured) return configured

  const directory = path.join(xdgCache ?? path.join(homedir(), ".cache"), "opencode", "speech")
  const file = path.join(directory, MODEL.file)
  if (existsSync(file)) return file

  await mkdir(directory, { recursive: true })
  onStatus?.(`Downloading local speech model ${MODEL.name} (${MODEL.size})…`)
  const response = await fetch(MODEL.url)
  if (!response.ok) throw new Error(`Failed to download the ${MODEL.name} speech model`)
  const bytes = new Uint8Array(await response.arrayBuffer())
  if ((await sha256(bytes)) !== MODEL.sha256) throw new Error("Downloaded speech model checksum did not match")

  const temporary = `${file}.${crypto.randomUUID()}.tmp`
  try {
    await writeFile(temporary, bytes)
    await rename(temporary, file)
  } finally {
    await rm(temporary, { force: true })
  }
  return file
}

function speechBinary() {
  const name = platform() === "win32" ? "opencode-speech.exe" : "opencode-speech"
  const configured = process.env.OPENCODE_SPEECH_BIN
  if (configured) return configured

  const packaged = path.join(path.dirname(process.execPath), name)
  if (existsSync(packaged)) return packaged

  const development = path.resolve(import.meta.dir, "../../speech/target/release", name)
  if (existsSync(development)) return development
  throw new Error("This OpenCode build does not include local voice support")
}

async function sha256(bytes: Uint8Array) {
  return Buffer.from(await crypto.subtle.digest("SHA-256", Uint8Array.from(bytes))).toString("hex")
}

export * as Speech from "./speech"
