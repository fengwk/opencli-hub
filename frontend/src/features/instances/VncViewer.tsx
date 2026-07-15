import { useCallback, useEffect, useRef, useState } from 'react'
import { ClipboardCopy, ClipboardPaste, Maximize2, Minimize2 } from 'lucide-react'
import type RFB from '@novnc/novnc/lib/rfb.js'
import { buildVncWebSocketUrl } from '@/features/instances/vnc-url'
import type { BackendId } from '@/shared/api/contracts'

type VncConnectionState = 'disconnected' | 'connecting' | 'connected' | 'failed'
type ClipboardOperation = 'read' | 'write' | null

// Apply the same 256 KiB UTF-8 safety bound in both clipboard directions.
const MAX_CLIPBOARD_BYTES = 256 * 1024
const clipboardTextEncoder = new TextEncoder()

function utf8ByteLength(text: string): number {
  return clipboardTextEncoder.encode(text).length
}

// Browser permission prompts for clipboard read/write stay open until the user answers; if they walk away the
// underlying Promise never settles and the busy state would otherwise pin the controls forever. A bounded timeout
// releases the UI without cancelling the user's pending decision: the original Promise may still settle later.
const CLIPBOARD_PERMISSION_TIMEOUT_MS = 30_000

type ClipboardPermissionOutcome<T> =
  | { timedOut: true }
  | { timedOut: false; value: T }
  | { timedOut: false; error: unknown }

function raceClipboardPermission<T>(operation: () => Promise<T>, timeoutMs: number): Promise<ClipboardPermissionOutcome<T>> {
  return new Promise((resolve) => {
    let settled = false
    const timer = setTimeout(() => {
      if (settled) return
      settled = true
      resolve({ timedOut: true })
    }, timeoutMs)
    // Normalize a synchronous throw inside the operation factory into the standard error outcome so callers
    // never see a rejected race promise (which would bypass the timeout/finally busy release).
    let promise: Promise<T>
    try {
      promise = operation()
    } catch (error) {
      if (settled) return
      settled = true
      clearTimeout(timer)
      resolve({ timedOut: false, error })
      return
    }
    promise.then(
      (value) => {
        if (settled) return
        settled = true
        clearTimeout(timer)
        resolve({ timedOut: false, value })
      },
      (error) => {
        if (settled) return
        settled = true
        clearTimeout(timer)
        resolve({ timedOut: false, error })
      },
    )
  })
}

const connectionStateLabels: Record<VncConnectionState, string> = {
  disconnected: '未连接',
  connecting: '连接中',
  connected: '已连接',
  failed: '连接失败',
}

// noVNC measures the target while constructing its internal 100%-height screen.
const vncViewportStyle = { height: '72vh' }

function errorMessageOf(error: unknown): string | null {
  if (typeof error === 'object' && error !== null && 'message' in error && typeof (error as { message: unknown }).message === 'string') {
    return (error as { message: string }).message
  }
  return null
}

function clipboardAccessError(action: '读取' | '写入', error: unknown): string {
  const detail = errorMessageOf(error) ? `（${errorMessageOf(error)}）` : ''
  return `无法${action}本机剪贴板。请确认页面使用 HTTPS 或 localhost，并已授予浏览器剪贴板权限${detail}`
}

function containsNonLatin1(text: string): boolean {
  return Array.from(text).some((character) => (character.codePointAt(0) ?? 0) > 0xff)
}

/** noVNC-backed viewer for the Hub's same-origin WebSocket bridge. */
export function VncViewer({ instanceId, available }: { instanceId: BackendId; available: boolean }) {
  const viewerRef = useRef<HTMLElement>(null)
  const targetRef = useRef<HTMLDivElement>(null)
  const rfbRef = useRef<RFB | null>(null)
  const connectionAttemptRef = useRef(0)
  // Tracks the latest clipboard/RFB "epoch". Every lifecycle event that invalidates an in-flight clipboard
  // operation (dispose, instance change, available→false, manual disconnect, reconnect) bumps this counter;
  // in-flight ops capture the value at start time and check it after every await so stale results cannot
  // overwrite a fresh connection's alert or busy state.
  const operationEpochRef = useRef(0)
  const [connectionState, setConnectionState] = useState<VncConnectionState>('disconnected')
  const [connectionError, setConnectionError] = useState<string | null>(null)
  const [isFullscreen, setIsFullscreen] = useState(false)
  const [remoteClipboardText, setRemoteClipboardText] = useState<string | null>(null)
  const [clipboardOperation, setClipboardOperation] = useState<ClipboardOperation>(null)
  const [clipboardNotice, setClipboardNotice] = useState<string | null>(null)

  const dispose = useCallback(() => {
    connectionAttemptRef.current += 1
    operationEpochRef.current += 1
    const rfb = rfbRef.current
    rfbRef.current = null
    rfb?.disconnect()
  }, [])

  useEffect(() => {
    // Instance change invalidates any in-flight clipboard op on the previous RFB.
    operationEpochRef.current += 1
    setConnectionState('disconnected')
    setConnectionError(null)
    setRemoteClipboardText(null)
    setClipboardNotice(null)
    setClipboardOperation(null)
    return () => dispose()
  }, [dispose, instanceId])

  useEffect(() => {
    if (!available) {
      dispose()
      operationEpochRef.current += 1
      setConnectionError(null)
      setConnectionState('disconnected')
      setRemoteClipboardText(null)
      setClipboardNotice(null)
      setClipboardOperation(null)
    }
  }, [available, dispose])

  useEffect(() => {
    const handlePageHide = () => {
      dispose()
      setConnectionState('disconnected')
    }
    window.addEventListener('pagehide', handlePageHide)
    return () => window.removeEventListener('pagehide', handlePageHide)
  }, [dispose])

  useEffect(() => {
    const handleFullscreenChange = () => {
      setIsFullscreen(document.fullscreenElement === viewerRef.current)
    }
    document.addEventListener('fullscreenchange', handleFullscreenChange)
    handleFullscreenChange()
    return () => document.removeEventListener('fullscreenchange', handleFullscreenChange)
  }, [])

  async function connect() {
    if (!available || !targetRef.current || rfbRef.current) {
      return
    }
    const attempt = connectionAttemptRef.current + 1
    connectionAttemptRef.current = attempt
    // New connection invalidates any in-flight clipboard op; the buttons must be usable on the fresh RFB.
    operationEpochRef.current += 1
    setClipboardOperation(null)
    setConnectionError(null)
    setRemoteClipboardText(null)
    setClipboardNotice(null)
    setConnectionState('connecting')
    let connected = false
    try {
      // noVNC is only needed on explicit connect; keep it out of the management UI's initial bundle.
      const { default: RFBClient } = await import('@novnc/novnc/lib/rfb.js')
      const target = targetRef.current
      if (connectionAttemptRef.current !== attempt || !target) {
        return
      }
      const rfb = new RFBClient(target, buildVncWebSocketUrl(instanceId))
      rfbRef.current = rfb
      rfb.scaleViewport = true
      rfb.addEventListener('connect', () => {
        if (rfbRef.current !== rfb) {
          return
        }
        connected = true
        setConnectionState('connected')
      })
      rfb.addEventListener('clipboard', (event) => {
        if (rfbRef.current !== rfb || typeof event.detail?.text !== 'string') {
          return
        }
        const inboundText = event.detail.text
        const inboundBytes = utf8ByteLength(inboundText)
        if (inboundBytes > MAX_CLIPBOARD_BYTES) {
          setRemoteClipboardText(null)
          setClipboardNotice(null)
          setConnectionError(`远端剪贴板超过 256 KiB（${inboundBytes} 字节），已忽略。`)
          return
        }
        setConnectionError(null)
        setRemoteClipboardText(inboundText)
        setClipboardNotice(`已接收远端剪贴板（${inboundBytes} 字节）。`)
      })
      rfb.addEventListener('disconnect', (event) => {
        if (rfbRef.current !== rfb) {
          return
        }
        rfbRef.current = null
        rfb.disconnect()
        setRemoteClipboardText(null)
        setClipboardNotice(null)
        const clean = Boolean((event as CustomEvent<{ clean?: boolean }>).detail?.clean)
        if (!connected || !clean) {
          setConnectionError('VNC 连接失败或意外断开。')
          setConnectionState('failed')
          return
        }
        setConnectionState('disconnected')
      })
    } catch (error) {
      if (connectionAttemptRef.current !== attempt) {
        return
      }
      dispose()
      setConnectionError(error instanceof Error ? error.message : '无法建立 VNC 连接。')
      setConnectionState('failed')
    }
  }

  async function disconnect() {
    // Manual disconnect invalidates any pending clipboard op and clears busy state before the async
    // exitFullscreen() below yields control; this prevents the buttons from staying disabled.
    operationEpochRef.current += 1
    setClipboardOperation(null)
    dispose()
    setConnectionError(null)
    setConnectionState('disconnected')
    setRemoteClipboardText(null)
    setClipboardNotice(null)
    if (document.fullscreenElement === viewerRef.current && document.exitFullscreen) {
      await document.exitFullscreen().catch(() => undefined)
    }
  }

  async function toggleFullscreen() {
    const viewer = viewerRef.current
    if (!viewer) {
      return
    }
    setConnectionError(null)
    try {
      if (document.fullscreenElement === viewer) {
        await document.exitFullscreen()
        return
      }
      if (typeof viewer.requestFullscreen !== 'function' || document.fullscreenEnabled === false) {
        throw new Error('当前浏览器不支持页面全屏')
      }
      if (document.fullscreenElement) {
        await document.exitFullscreen()
      }
      await viewer.requestFullscreen()
    } catch (error) {
      const detail = error instanceof Error && error.message ? `：${error.message}` : ''
      setConnectionError(`无法切换浏览器全屏${detail}`)
    }
  }

  async function pasteLocalClipboardToRemote() {
    const rfb = rfbRef.current
    if (!rfb || connectionState !== 'connected') {
      return
    }
    if (!navigator.clipboard?.readText) {
      setConnectionError('当前浏览器不支持读取系统剪贴板，请使用 HTTPS 或 localhost。')
      return
    }
    const epoch = operationEpochRef.current
    setConnectionError(null)
    setClipboardOperation('read')
    try {
      const outcome = await raceClipboardPermission(
        () => navigator.clipboard.readText(),
        CLIPBOARD_PERMISSION_TIMEOUT_MS,
      )
      // The lifecycle guard fires first: any disconnect, reconnect, instance change, or available→false
      // happened after this op started. The late result belongs to a stale interaction and must not write
      // any alert or trigger a paste on the fresh connection; the user can retry via the now-enabled button.
      if (epoch !== operationEpochRef.current) {
        return
      }
      if (outcome.timedOut) {
        setConnectionError('等待浏览器剪贴板权限超时，请处理地址栏权限提示后重试。')
        return
      }
      if ('error' in outcome) {
        setConnectionError(clipboardAccessError('读取', outcome.error))
        return
      }
      const text = outcome.value as string
      const outboundBytes = utf8ByteLength(text)
      if (outboundBytes > MAX_CLIPBOARD_BYTES) {
        setConnectionError(`剪贴板文本 UTF-8 编码后不能超过 256 KiB（${outboundBytes} 字节）。`)
        return
      }
      if (rfbRef.current !== rfb) {
        return
      }
      rfb.clipboardPasteFrom(text)
      setClipboardNotice(containsNonLatin1(text)
        ? `已向远端发送 ${outboundBytes} 字节；传统 RFB 剪贴板可能替换非 Latin-1 字符。`
        : `已向远端发送 ${outboundBytes} 字节，请在远端按 Ctrl+V。`)
    } finally {
      // Only clear busy if this op still owns it; a concurrent invalidating lifecycle event has already
      // cleared (or re-assigned) clipboardOperation for the new connection.
      if (epoch === operationEpochRef.current) {
        setClipboardOperation(null)
      }
    }
  }

  async function copyRemoteClipboardToLocal() {
    const rfb = rfbRef.current
    if (!rfb || remoteClipboardText === null || connectionState !== 'connected') {
      return
    }
    if (!navigator.clipboard?.writeText) {
      setConnectionError('当前浏览器不支持写入系统剪贴板，请使用 HTTPS 或 localhost。')
      return
    }
    const epoch = operationEpochRef.current
    setConnectionError(null)
    setClipboardOperation('write')
    try {
      const outcome = await raceClipboardPermission(
        () => navigator.clipboard.writeText(remoteClipboardText),
        CLIPBOARD_PERMISSION_TIMEOUT_MS,
      )
      if (epoch !== operationEpochRef.current) {
        return
      }
      if (outcome.timedOut) {
        setConnectionError('等待浏览器剪贴板权限超时，请处理地址栏权限提示后重试。')
        return
      }
      if ('error' in outcome) {
        setConnectionError(clipboardAccessError('写入', outcome.error))
        return
      }
      if (rfbRef.current !== rfb) {
        return
      }
      setClipboardNotice(`已将远端剪贴板复制到本机（${utf8ByteLength(remoteClipboardText)} 字节）。`)
    } finally {
      if (epoch === operationEpochRef.current) {
        setClipboardOperation(null)
      }
    }
  }

  const connected = connectionState === 'connected'

  return (
    <section
      ref={viewerRef}
      className={`vnc-viewer${isFullscreen ? ' is-fullscreen' : ''}`}
      aria-label="VNC 控制台"
    >
      <div className="section-heading-row vnc-toolbar">
        <div>
          <p className="eyebrow">LIVE SESSION</p>
          <p className="muted">通过当前 Hub 地址安全建立浏览器 VNC 连接。</p>
        </div>
        <p className="vnc-status" role="status">连接状态：{connectionStateLabels[connectionState]}</p>
      </div>
      {connectionError ? <p className="inline-error" role="alert">{connectionError}</p> : null}
      {!available ? <p className="muted">VNC 当前不可用。请先启动实例并等待运行时注册。</p> : null}
      <div className="vnc-controls">
        <button type="button" className="btn btn-primary" disabled={!available || connectionState === 'connecting' || connected} onClick={connect}>连接 VNC</button>
        <button type="button" className="btn" disabled={connectionState === 'disconnected' || connectionState === 'failed'} onClick={() => void disconnect()}>断开 VNC</button>
        <button type="button" className="btn" disabled={!connected || clipboardOperation !== null} onClick={() => void pasteLocalClipboardToRemote()}>
          <ClipboardPaste aria-hidden="true" />本机剪贴板 → 远端
        </button>
        <button type="button" className="btn" disabled={!connected || remoteClipboardText === null || clipboardOperation !== null} onClick={() => void copyRemoteClipboardToLocal()}>
          <ClipboardCopy aria-hidden="true" />远端剪贴板 → 本机
        </button>
        <button type="button" className="btn" disabled={!connected} onClick={() => void toggleFullscreen()}>
          {isFullscreen ? <Minimize2 aria-hidden="true" /> : <Maximize2 aria-hidden="true" />}
          {isFullscreen ? '退出全屏' : '进入全屏'}
        </button>
      </div>
      <p className="vnc-clipboard-help">
        文本剪贴板需要显式操作：本机发送后在远端按 Ctrl+V；远端复制后再点击“远端剪贴板 → 本机”。
      </p>
      {clipboardNotice ? <p className="vnc-operation-status" aria-live="polite">{clipboardNotice}</p> : null}
      <div
        ref={targetRef}
        className="vnc-canvas"
        style={vncViewportStyle}
        aria-label="VNC 远程桌面"
      />
    </section>
  )
}
