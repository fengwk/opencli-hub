import { describe, expect, it } from 'vitest'
import RFB from '@novnc/novnc/lib/rfb.js'
import Websock from '../../../node_modules/@novnc/novnc/lib/websock.js'

function concatenate(chunks: Uint8Array[]): Uint8Array {
  const totalBytes = chunks.reduce((sum, chunk) => sum + chunk.length, 0)
  const result = new Uint8Array(totalBytes)
  let offset = 0
  for (const chunk of chunks) {
    result.set(chunk, offset)
    offset += chunk.length
  }
  return result
}

describe('noVNC Websock', () => {
  it('preserves a UTF-8 byte string in a legacy ClientCutText message', () => {
    // The x11vnc compatibility path depends on noVNC forwarding Latin-1-range code units as unchanged bytes.
    const frames: Uint8Array[] = []
    const rawChannel = {
      send: (data: Uint8Array) => frames.push(Uint8Array.from(data)),
      close: () => undefined,
      binaryType: '',
      onerror: null,
      onmessage: null,
      onopen: null,
      onclose: null,
      protocol: 'binary',
      readyState: WebSocket.OPEN,
    }
    const socket = new Websock()
    socket.attach(rawChannel)
    const payload = new TextEncoder().encode('你好🙂')
    const byteString = Array.from(payload, (byte) => String.fromCharCode(byte)).join('')
    const connectedLegacyRfb = {
      _rfbConnectionState: 'connected',
      _viewOnly: false,
      _clipboardServerCapabilitiesFormats: {},
      _clipboardServerCapabilitiesActions: {},
      _sock: socket,
    }

    RFB.prototype.clipboardPasteFrom.call(connectedLegacyRfb as unknown as RFB, byteString)

    const message = concatenate(frames)
    expect(message.subarray(0, 8)).toEqual(Uint8Array.of(
      6, 0, 0, 0,
      payload.length >>> 24,
      payload.length >>> 16,
      payload.length >>> 8,
      payload.length,
    ))
    expect(Array.from(message.subarray(8))).toEqual(Array.from(payload))
  })

  it('preserves a large clipboard payload across send-queue flushes', () => {
    // noVNC 1.5.0 corrupted payloads spanning its 10 KiB send queue; this locks the upstream fix into the pinned version.
    const frames: Uint8Array[] = []
    const rawChannel = {
      send: (data: Uint8Array) => frames.push(Uint8Array.from(data)),
      close: () => undefined,
      binaryType: '',
      onerror: null,
      onmessage: null,
      onopen: null,
      onclose: null,
      protocol: 'binary',
      readyState: WebSocket.OPEN,
    }
    const socket = new Websock()
    socket.attach(rawChannel)
    const header = Uint8Array.from([6, 0, 0, 0, 0, 4, 0, 0])
    const payload = Uint8Array.from({ length: 256 * 1024 }, (_, index) => index % 251)

    socket.sQpushBytes(header)
    socket.sQpushBytes(payload)
    socket.flush()

    expect(frames.length).toBeGreaterThan(1)
    expect(concatenate(frames)).toEqual(concatenate([header, payload]))
  })
})
