import { useCallback, useEffect, useRef, useState } from 'react'
import type RFB from '@novnc/novnc/lib/rfb.js'
import { buildVncWebSocketUrl } from '@/features/instances/vnc-url'

type VncConnectionState = 'disconnected' | 'connecting' | 'connected' | 'failed'

const connectionStateLabels: Record<VncConnectionState, string> = {
  disconnected: '未连接',
  connecting: '连接中',
  connected: '已连接',
  failed: '连接失败',
}

/** noVNC-backed viewer for the Hub's same-origin WebSocket bridge. */
export function VncViewer({ instanceId, available }: { instanceId: number; available: boolean }) {
  const targetRef = useRef<HTMLDivElement>(null)
  const rfbRef = useRef<RFB | null>(null)
  const connectionAttemptRef = useRef(0)
  const [connectionState, setConnectionState] = useState<VncConnectionState>('disconnected')
  const [connectionError, setConnectionError] = useState<string | null>(null)

  const dispose = useCallback(() => {
    connectionAttemptRef.current += 1
    const rfb = rfbRef.current
    rfbRef.current = null
    rfb?.disconnect()
  }, [])

  useEffect(() => {
    setConnectionState('disconnected')
    setConnectionError(null)
    return () => dispose()
  }, [dispose, instanceId])

  useEffect(() => {
    if (!available) {
      dispose()
      setConnectionError(null)
      setConnectionState('disconnected')
    }
  }, [available, dispose])

  async function connect() {
    if (!available || !targetRef.current || rfbRef.current) {
      return
    }
    const attempt = connectionAttemptRef.current + 1
    connectionAttemptRef.current = attempt
    setConnectionError(null)
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
      rfb.addEventListener('disconnect', (event) => {
        if (rfbRef.current !== rfb) {
          return
        }
        rfbRef.current = null
        rfb.disconnect()
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

  function disconnect() {
    dispose()
    setConnectionError(null)
    setConnectionState('disconnected')
  }

  return (
    <section className="vnc-viewer" aria-label="VNC 控制台">
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
        <button type="button" className="btn btn-primary" disabled={!available || connectionState === 'connecting' || connectionState === 'connected'} onClick={connect}>连接 VNC</button>
        <button type="button" className="btn" disabled={connectionState === 'disconnected'} onClick={disconnect}>断开 VNC</button>
      </div>
      <div ref={targetRef} className="vnc-canvas" aria-label="VNC 远程桌面" />
    </section>
  )
}
