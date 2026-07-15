import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { VncViewer } from '@/features/instances/VncViewer'

const rfbMock = vi.hoisted(() => {
  type Listener = (event: Event) => void
  const instances: Array<{
    target: HTMLElement
    url: string
    clipboardPasteFrom: ReturnType<typeof vi.fn>
    disconnect: ReturnType<typeof vi.fn>
    emit: (type: string, event?: Event) => void
  }> = []
  const RFB = vi.fn(function RFB(target: HTMLElement, url: string) {
    const listeners = new Map<string, Listener>()
    const instance = {
      target,
      url,
      scaleViewport: false,
      addEventListener: vi.fn((type: string, listener: Listener) => listeners.set(type, listener)),
      clipboardPasteFrom: vi.fn(),
      disconnect: vi.fn(),
      emit: (type: string, event = new Event(type)) => listeners.get(type)?.(event),
    }
    instances.push(instance)
    return instance
  })
  return { instances, RFB }
})

vi.mock('@novnc/novnc/lib/rfb.js', () => ({ default: rfbMock.RFB }))

afterEach(() => {
  rfbMock.instances.splice(0)
  Reflect.deleteProperty(navigator, 'clipboard')
  Reflect.deleteProperty(document, 'fullscreenElement')
  Reflect.deleteProperty(document, 'fullscreenEnabled')
  Reflect.deleteProperty(document, 'exitFullscreen')
  vi.clearAllMocks()
})

describe('VncViewer', () => {
  it('provides a large definite viewport height before noVNC initializes', () => {
    // noVNC measures the target during construction; a definite larger height keeps its
    // internal screen visible and gives the remote browser most of the detail workspace.
    render(<VncViewer instanceId="343020517415976960" available />)

    expect(getComputedStyle(screen.getByLabelText('VNC 远程桌面')).height)
      .toBe('72vh')
  })

  it('uses noVNC with the same-origin WebSocket URL and disposes on explicit disconnect', async () => {
    // The URL assertion prevents host, port, token, or password fields from leaking into the VNC client contract.
    const user = userEvent.setup()
    render(<VncViewer instanceId="343020517415976960" available />)

    await user.click(screen.getByRole('button', { name: '连接 VNC' }))
    await waitFor(() => expect(rfbMock.RFB).toHaveBeenCalledTimes(1))
    expect(rfbMock.instances[0].url).toBe(`ws://${window.location.host}/api/instances/343020517415976960/vnc`)

    act(() => rfbMock.instances[0].emit('connect'))
    expect(screen.getByRole('status')).toHaveTextContent('已连接')
    await user.click(screen.getByRole('button', { name: '断开 VNC' }))
    expect(rfbMock.instances[0].disconnect).toHaveBeenCalledTimes(1)
    expect(screen.getByRole('status')).toHaveTextContent('未连接')
  })

  it('cleans up the noVNC client when instance ID changes or the viewer unmounts', async () => {
    // Cleanup on both lifecycle boundaries prevents an old WebSocket from surviving route navigation.
    const user = userEvent.setup()
    const view = render(<VncViewer instanceId="42" available />)
    await user.click(screen.getByRole('button', { name: '连接 VNC' }))
    await waitFor(() => expect(rfbMock.RFB).toHaveBeenCalledTimes(1))
    const first = rfbMock.instances[0]

    view.rerender(<VncViewer instanceId="43" available />)
    expect(first.disconnect).toHaveBeenCalledTimes(1)
    await user.click(screen.getByRole('button', { name: '连接 VNC' }))
    await waitFor(() => expect(rfbMock.RFB).toHaveBeenCalledTimes(2))
    const second = rfbMock.instances[1]
    view.unmount()
    expect(second.disconnect).toHaveBeenCalledTimes(1)
  })

  it('disconnects the noVNC client when the page is hidden or closed', async () => {
    // pagehide covers refresh, tab close, navigation away, and entry into the back-forward cache.
    const user = userEvent.setup()
    render(<VncViewer instanceId="42" available />)
    await user.click(screen.getByRole('button', { name: '连接 VNC' }))
    await waitFor(() => expect(rfbMock.RFB).toHaveBeenCalledTimes(1))

    act(() => window.dispatchEvent(new Event('pagehide')))

    expect(rfbMock.instances[0].disconnect).toHaveBeenCalledTimes(1)
    expect(screen.getByRole('status')).toHaveTextContent('未连接')
  })

  it('transfers text through the explicit local and remote clipboard controls', async () => {
    // Clipboard access remains user-triggered while noVNC carries text over the existing RFB session.
    const readText = vi.fn().mockResolvedValue('https://example.com/from-local')
    const writeText = vi.fn().mockResolvedValue(undefined)
    const user = userEvent.setup()
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { readText, writeText },
    })
    render(<VncViewer instanceId="42" available />)
    await user.click(screen.getByRole('button', { name: '连接 VNC' }))
    await waitFor(() => expect(rfbMock.RFB).toHaveBeenCalledTimes(1))
    const rfb = rfbMock.instances[0]
    act(() => rfb.emit('connect'))

    await user.click(screen.getByRole('button', { name: '本机剪贴板 → 远端' }))
    expect(readText).toHaveBeenCalledTimes(1)
    expect(rfb.clipboardPasteFrom).toHaveBeenCalledWith('https://example.com/from-local')

    act(() => rfb.emit('clipboard', new CustomEvent('clipboard', {
      detail: { text: 'remote clipboard text' },
    })))
    await user.click(screen.getByRole('button', { name: '远端剪贴板 → 本机' }))
    expect(writeText).toHaveBeenCalledWith('remote clipboard text')
    expect(screen.getByText(/已将远端剪贴板复制到本机/)).toBeInTheDocument()
  })

  it('reports browser clipboard permission failures without dropping VNC', async () => {
    // Clipboard permission errors must be actionable and must not tear down the live RFB session.
    const user = userEvent.setup()
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: {
        readText: vi.fn().mockRejectedValue(new DOMException('Permission denied', 'NotAllowedError')),
        writeText: vi.fn(),
      },
    })
    render(<VncViewer instanceId="42" available />)
    await user.click(screen.getByRole('button', { name: '连接 VNC' }))
    await waitFor(() => expect(rfbMock.RFB).toHaveBeenCalledTimes(1))
    act(() => rfbMock.instances[0].emit('connect'))

    await user.click(screen.getByRole('button', { name: '本机剪贴板 → 远端' }))

    expect(screen.getByRole('alert')).toHaveTextContent('HTTPS 或 localhost')
    expect(rfbMock.instances[0].disconnect).not.toHaveBeenCalled()
    expect(screen.getByRole('status')).toHaveTextContent('已连接')
  })

  it('enters and exits native fullscreen without reconnecting RFB', async () => {
    // fullscreenchange is the browser source of truth, including Escape-driven exits.
    const user = userEvent.setup()
    render(<VncViewer instanceId="42" available />)
    await user.click(screen.getByRole('button', { name: '连接 VNC' }))
    await waitFor(() => expect(rfbMock.RFB).toHaveBeenCalledTimes(1))
    act(() => rfbMock.instances[0].emit('connect'))

    const viewer = screen.getByLabelText('VNC 控制台')
    let fullscreenElement: Element | null = null
    const requestFullscreen = vi.fn(async () => {
      fullscreenElement = viewer
      document.dispatchEvent(new Event('fullscreenchange'))
    })
    const exitFullscreen = vi.fn(async () => {
      fullscreenElement = null
      document.dispatchEvent(new Event('fullscreenchange'))
    })
    Object.defineProperty(viewer, 'requestFullscreen', { configurable: true, value: requestFullscreen })
    Object.defineProperty(document, 'fullscreenElement', {
      configurable: true,
      get: () => fullscreenElement,
    })
    Object.defineProperty(document, 'fullscreenEnabled', { configurable: true, value: true })
    Object.defineProperty(document, 'exitFullscreen', { configurable: true, value: exitFullscreen })

    await user.click(screen.getByRole('button', { name: '进入全屏' }))
    await waitFor(() => expect(screen.getByRole('button', { name: '退出全屏' })).toBeInTheDocument())
    expect(requestFullscreen).toHaveBeenCalledTimes(1)
    expect(rfbMock.RFB).toHaveBeenCalledTimes(1)

    await user.click(screen.getByRole('button', { name: '退出全屏' }))
    await waitFor(() => expect(screen.getByRole('button', { name: '进入全屏' })).toBeInTheDocument())
    expect(exitFullscreen).toHaveBeenCalledTimes(1)
    expect(rfbMock.instances[0].disconnect).not.toHaveBeenCalled()
  })

  it('cleans up and reports failed noVNC connections', async () => {
    // An unclean disconnect before connect is the server/WebSocket failure path and must not leave an RFB instance alive.
    const user = userEvent.setup()
    const view = render(<VncViewer instanceId="42" available />)
    await user.click(screen.getByRole('button', { name: '连接 VNC' }))
    await waitFor(() => expect(rfbMock.RFB).toHaveBeenCalledTimes(1))

    act(() => rfbMock.instances[0].emit('disconnect', new CustomEvent('disconnect', { detail: { clean: false } })))

    expect(rfbMock.instances[0].disconnect).toHaveBeenCalledTimes(1)
    expect(screen.getByRole('alert')).toHaveTextContent('VNC 连接失败或意外断开')
    expect(screen.getByRole('status')).toHaveTextContent('连接失败')

    view.rerender(<VncViewer instanceId="42" available={false} />)
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('未连接')
  })

  it('keeps dispose idempotent so pagehide and explicit disconnect do not double-close RFB', async () => {
    // dispose() must be safe to call repeatedly; the second call finds rfbRef.current already null.
    const user = userEvent.setup()
    render(<VncViewer instanceId="42" available />)
    await user.click(screen.getByRole('button', { name: '连接 VNC' }))
    await waitFor(() => expect(rfbMock.RFB).toHaveBeenCalledTimes(1))
    const rfb = rfbMock.instances[0]

    await user.click(screen.getByRole('button', { name: '断开 VNC' }))
    act(() => window.dispatchEvent(new Event('pagehide')))

    expect(rfb.disconnect).toHaveBeenCalledTimes(1)
    expect(screen.getByRole('status')).toHaveTextContent('未连接')
  })

  it('disconnects the RFB when VNC becomes unavailable', async () => {
    // The availability effect is the only thing that closes the RFB when the health check flips to false.
    const user = userEvent.setup()
    const view = render(<VncViewer instanceId="42" available />)
    await user.click(screen.getByRole('button', { name: '连接 VNC' }))
    await waitFor(() => expect(rfbMock.RFB).toHaveBeenCalledTimes(1))
    const rfb = rfbMock.instances[0]

    view.rerender(<VncViewer instanceId="42" available={false} />)

    expect(rfb.disconnect).toHaveBeenCalledTimes(1)
    expect(screen.getByRole('status')).toHaveTextContent('未连接')
    expect(screen.getByText('VNC 当前不可用。请先启动实例并等待运行时注册。')).toBeInTheDocument()
  })

  it('does not flip state to failed when dispose triggers a stale disconnect event', async () => {
    // dispose() calls rfb.disconnect(), which fires the disconnect event; the handler must not run the failed branch for an already-disposed RFB.
    const user = userEvent.setup()
    render(<VncViewer instanceId="42" available />)
    await user.click(screen.getByRole('button', { name: '连接 VNC' }))
    await waitFor(() => expect(rfbMock.RFB).toHaveBeenCalledTimes(1))
    act(() => rfbMock.instances[0].emit('connect'))

    await user.click(screen.getByRole('button', { name: '断开 VNC' }))

    expect(screen.getByRole('status')).toHaveTextContent('未连接')
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('refuses clipboard payloads larger than 256 KiB after UTF-8 encoding', async () => {
    // The cap is the UTF-8 byte count rather than JS character length; one ASCII byte over the limit must be rejected.
    const user = userEvent.setup()
    const readText = vi.fn().mockResolvedValue('a'.repeat(256 * 1024 + 1))
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { readText, writeText: vi.fn() },
    })
    render(<VncViewer instanceId="42" available />)
    await user.click(screen.getByRole('button', { name: '连接 VNC' }))
    await waitFor(() => expect(rfbMock.RFB).toHaveBeenCalledTimes(1))
    const rfb = rfbMock.instances[0]
    act(() => rfb.emit('connect'))

    await user.click(screen.getByRole('button', { name: '本机剪贴板 → 远端' }))

    expect(rfb.clipboardPasteFrom).not.toHaveBeenCalled()
    expect(screen.getByRole('alert')).toHaveTextContent('UTF-8')
    expect(screen.getByRole('alert')).toHaveTextContent('256 KiB')
    expect(screen.getByRole('status')).toHaveTextContent('已连接')
  })

  it('counts multi-byte UTF-8 characters against the 256 KiB clipboard cap', async () => {
    // Each '你' is 3 UTF-8 bytes, so 90_000 characters (270_000 bytes) must be rejected even though JS sees only 90k chars.
    const user = userEvent.setup()
    const readText = vi.fn().mockResolvedValue('你'.repeat(90_000))
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { readText, writeText: vi.fn() },
    })
    render(<VncViewer instanceId="42" available />)
    await user.click(screen.getByRole('button', { name: '连接 VNC' }))
    await waitFor(() => expect(rfbMock.RFB).toHaveBeenCalledTimes(1))
    const rfb = rfbMock.instances[0]
    act(() => rfb.emit('connect'))

    await user.click(screen.getByRole('button', { name: '本机剪贴板 → 远端' }))

    expect(rfb.clipboardPasteFrom).not.toHaveBeenCalled()
    expect(screen.getByRole('alert')).toHaveTextContent('UTF-8')
    expect(screen.getByRole('status')).toHaveTextContent('已连接')
  })

  it('accepts clipboard payloads exactly at the 256 KiB UTF-8 boundary', async () => {
    // Exactly 262_144 ASCII bytes is on the cap; one byte less already covered above, this asserts the inclusive edge.
    const user = userEvent.setup()
    const readText = vi.fn().mockResolvedValue('a'.repeat(256 * 1024))
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { readText, writeText: vi.fn() },
    })
    render(<VncViewer instanceId="42" available />)
    await user.click(screen.getByRole('button', { name: '连接 VNC' }))
    await waitFor(() => expect(rfbMock.RFB).toHaveBeenCalledTimes(1))
    const rfb = rfbMock.instances[0]
    act(() => rfb.emit('connect'))

    await user.click(screen.getByRole('button', { name: '本机剪贴板 → 远端' }))

    expect(rfb.clipboardPasteFrom).toHaveBeenCalledWith('a'.repeat(256 * 1024))
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('ignores inbound clipboard payloads larger than 256 KiB after UTF-8 encoding', async () => {
    // The viewer must not store or expose an oversized remote clipboard payload; the local copy button stays disabled.
    const user = userEvent.setup()
    render(<VncViewer instanceId="42" available />)
    await user.click(screen.getByRole('button', { name: '连接 VNC' }))
    await waitFor(() => expect(rfbMock.RFB).toHaveBeenCalledTimes(1))
    const rfb = rfbMock.instances[0]
    act(() => rfb.emit('connect'))

    act(() => rfb.emit('clipboard', new CustomEvent('clipboard', {
      detail: { text: 'a'.repeat(256 * 1024 + 1) },
    })))

    expect(screen.getByRole('alert')).toHaveTextContent('256 KiB')
    expect(screen.getByRole('alert')).toHaveTextContent('远端剪贴板')
    expect(screen.getByRole('button', { name: '远端剪贴板 → 本机' })).toBeDisabled()
  })

  it('still accepts inbound clipboard payloads within the 256 KiB UTF-8 limit', async () => {
    // Sanity: the same handler must keep the happy path working so the rejection is scoped to oversized payloads only.
    const user = userEvent.setup()
    render(<VncViewer instanceId="42" available />)
    await user.click(screen.getByRole('button', { name: '连接 VNC' }))
    await waitFor(() => expect(rfbMock.RFB).toHaveBeenCalledTimes(1))
    const rfb = rfbMock.instances[0]
    act(() => rfb.emit('connect'))

    act(() => rfb.emit('clipboard', new CustomEvent('clipboard', {
      detail: { text: 'remote clipboard text' },
    })))

    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '远端剪贴板 → 本机' })).not.toBeDisabled()
  })

  it('warns when the local clipboard contains characters outside the legacy Latin-1 range', async () => {
    // nonVNC's legacy clipboard substitutes '?' for code points above 0xff, so the UI must call this out.
    const user = userEvent.setup()
    const readText = vi.fn().mockResolvedValue('你好')
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { readText, writeText: vi.fn() },
    })
    render(<VncViewer instanceId="42" available />)
    await user.click(screen.getByRole('button', { name: '连接 VNC' }))
    await waitFor(() => expect(rfbMock.RFB).toHaveBeenCalledTimes(1))
    act(() => rfbMock.instances[0].emit('connect'))

    await user.click(screen.getByRole('button', { name: '本机剪贴板 → 远端' }))

    expect(rfbMock.instances[0].clipboardPasteFrom).toHaveBeenCalledWith('你好')
    expect(screen.getByText(/Latin-1/)).toBeInTheDocument()
  })

  it('exits fullscreen when the user clicks disconnect', async () => {
    // A manual disconnect while in fullscreen must release both the RFB and the browser fullscreen surface.
    const user = userEvent.setup()
    render(<VncViewer instanceId="42" available />)
    await user.click(screen.getByRole('button', { name: '连接 VNC' }))
    await waitFor(() => expect(rfbMock.RFB).toHaveBeenCalledTimes(1))
    act(() => rfbMock.instances[0].emit('connect'))

    const viewer = screen.getByLabelText('VNC 控制台')
    let fullscreenElement: Element | null = null
    const requestFullscreen = vi.fn(async () => {
      fullscreenElement = viewer
      document.dispatchEvent(new Event('fullscreenchange'))
    })
    const exitFullscreen = vi.fn(async () => {
      fullscreenElement = null
      document.dispatchEvent(new Event('fullscreenchange'))
    })
    Object.defineProperty(viewer, 'requestFullscreen', { configurable: true, value: requestFullscreen })
    Object.defineProperty(document, 'fullscreenElement', {
      configurable: true,
      get: () => fullscreenElement,
    })
    Object.defineProperty(document, 'fullscreenEnabled', { configurable: true, value: true })
    Object.defineProperty(document, 'exitFullscreen', { configurable: true, value: exitFullscreen })

    await user.click(screen.getByRole('button', { name: '进入全屏' }))
    await waitFor(() => expect(screen.getByRole('button', { name: '退出全屏' })).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: '断开 VNC' }))

    await waitFor(() => expect(exitFullscreen).toHaveBeenCalledTimes(1))
    expect(rfbMock.instances[0].disconnect).toHaveBeenCalledTimes(1)
  })

  it('mirrors an external fullscreen exit (such as Escape) without disconnecting RFB', async () => {
    // The browser fires fullscreenchange on Esc; the viewer must follow without touching the live RFB.
    const user = userEvent.setup()
    render(<VncViewer instanceId="42" available />)
    await user.click(screen.getByRole('button', { name: '连接 VNC' }))
    await waitFor(() => expect(rfbMock.RFB).toHaveBeenCalledTimes(1))
    act(() => rfbMock.instances[0].emit('connect'))

    const viewer = screen.getByLabelText('VNC 控制台')
    let fullscreenElement: Element | null = null
    const requestFullscreen = vi.fn(async () => {
      fullscreenElement = viewer
      document.dispatchEvent(new Event('fullscreenchange'))
    })
    Object.defineProperty(viewer, 'requestFullscreen', { configurable: true, value: requestFullscreen })
    Object.defineProperty(document, 'fullscreenElement', {
      configurable: true,
      get: () => fullscreenElement,
    })
    Object.defineProperty(document, 'fullscreenEnabled', { configurable: true, value: true })

    await user.click(screen.getByRole('button', { name: '进入全屏' }))
    await waitFor(() => expect(screen.getByRole('button', { name: '退出全屏' })).toBeInTheDocument())

    act(() => {
      fullscreenElement = null
      document.dispatchEvent(new Event('fullscreenchange'))
    })

    expect(await screen.findByRole('button', { name: '进入全屏' })).toBeInTheDocument()
    expect(rfbMock.instances[0].disconnect).not.toHaveBeenCalled()
  })

  it('removes fullscreen and pagehide listeners on unmount', async () => {
    // Without listener cleanup a stale VncViewer could still call dispose() while the user navigates between instances.
    const removeEventListenerSpy = vi.spyOn(document, 'removeEventListener')
    const removeWindowListenerSpy = vi.spyOn(window, 'removeEventListener')
    const view = render(<VncViewer instanceId="42" available />)

    view.unmount()

    expect(removeEventListenerSpy).toHaveBeenCalledWith('fullscreenchange', expect.any(Function))
    expect(removeWindowListenerSpy).toHaveBeenCalledWith('pagehide', expect.any(Function))
  })
})
