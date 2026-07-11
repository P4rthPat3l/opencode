export type VoiceRecorder = {
  stop: () => Promise<Blob>
  cancel: () => Promise<void>
}

const MAX_RECORDING_SECONDS = 60 * 5

export async function startVoiceRecorder(options?: { onLevel?: (level: number) => void }) {
  const stream = await navigator.mediaDevices.getUserMedia({ audio: true })
  const context = new AudioContext({ sampleRate: 16_000 })
  const source = context.createMediaStreamSource(stream)
  const processor = context.createScriptProcessor(4096, 1, 1)
  const chunks: Float32Array[] = []
  const maxSamples = context.sampleRate * MAX_RECORDING_SECONDS
  let sampleCount = 0
  let stopped = false

  const cleanup = async () => {
    if (stopped) return
    stopped = true
    processor.disconnect()
    source.disconnect()
    stream.getTracks().forEach((track) => track.stop())
    await context.close()
  }

  processor.onaudioprocess = (event) => {
    const input = event.inputBuffer.getChannelData(0)
    if (options?.onLevel) {
      let sum = 0
      for (let i = 0; i < input.length; i++) sum += input[i]! * input[i]!
      options.onLevel(Math.sqrt(sum / input.length))
    }
    if (sampleCount >= maxSamples) return
    const chunk = new Float32Array(input.subarray(0, Math.min(input.length, maxSamples - sampleCount)))
    chunks.push(chunk)
    sampleCount += chunk.length
  }
  source.connect(processor)
  processor.connect(context.destination)

  return {
    async stop() {
      await cleanup()
      return new Blob([encodeWav(chunks, context.sampleRate)], { type: "audio/wav" })
    },
    cancel: cleanup,
  }
}

export async function blobToBase64(blob: Blob) {
  const buffer = await blob.arrayBuffer()
  let binary = ""
  const bytes = new Uint8Array(buffer)
  for (let i = 0; i < bytes.byteLength; i++) binary += String.fromCharCode(bytes[i]!)
  return btoa(binary)
}

function encodeWav(chunks: Float32Array[], sampleRate: number) {
  const length = chunks.reduce((sum, chunk) => sum + chunk.length, 0)
  const buffer = new ArrayBuffer(44 + length * 2)
  const view = new DataView(buffer)
  writeString(view, 0, "RIFF")
  view.setUint32(4, 36 + length * 2, true)
  writeString(view, 8, "WAVE")
  writeString(view, 12, "fmt ")
  view.setUint32(16, 16, true)
  view.setUint16(20, 1, true)
  view.setUint16(22, 1, true)
  view.setUint32(24, sampleRate, true)
  view.setUint32(28, sampleRate * 2, true)
  view.setUint16(32, 2, true)
  view.setUint16(34, 16, true)
  writeString(view, 36, "data")
  view.setUint32(40, length * 2, true)

  let offset = 44
  chunks.forEach((chunk) => {
    chunk.forEach((sample) => {
      const value = Math.max(-1, Math.min(1, sample))
      view.setInt16(offset, value < 0 ? value * 0x8000 : value * 0x7fff, true)
      offset += 2
    })
  })
  return buffer
}

function writeString(view: DataView, offset: number, value: string) {
  for (let i = 0; i < value.length; i++) view.setUint8(offset + i, value.charCodeAt(i))
}
