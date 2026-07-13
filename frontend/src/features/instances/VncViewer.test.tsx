import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { VncViewer } from '@/features/instances/VncViewer'

const rfbMock = vi.hoisted(() => {
  type Listener = (event: Event) => void
  const instances: Array<{
    url: string
    disconnect: ReturnType<typeof vi.fn>
    emit: (type: string, event?: Event) => void
  }> = []
  const RFB = vi.fn(function RFB(_: HTMLElement, url: string) {
    const listeners = new Map<string, Listener>()
    const instance = {
      url,
      scaleViewport: false,
      addEventListener: vi.fn((type: string, listener: Listener) => listeners.set(type, listener)),
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
  vi.clearAllMocks()
})

describe('VncViewer', () => {
  it('uses noVNC with the same-origin WebSocket URL and disposes on explicit disconnect', async () => {
    // The URL assertion prevents host, port, token, or password fields from leaking into the VNC client contract.
    const user = userEvent.setup()
    render(<VncViewer instanceId={42} available />)

    await user.click(screen.getByRole('button', { name: '连接 VNC' }))
    await waitFor(() => expect(rfbMock.RFB).toHaveBeenCalledTimes(1))
    expect(rfbMock.instances[0].url).toBe(`ws://${window.location.host}/api/instances/42/vnc`)

    act(() => rfbMock.instances[0].emit('connect'))
    expect(screen.getByRole('status')).toHaveTextContent('connected')
    await user.click(screen.getByRole('button', { name: '断开 VNC' }))
    expect(rfbMock.instances[0].disconnect).toHaveBeenCalledTimes(1)
    expect(screen.getByRole('status')).toHaveTextContent('disconnected')
  })

  it('cleans up the noVNC client when instance ID changes or the viewer unmounts', async () => {
    // Cleanup on both lifecycle boundaries prevents an old WebSocket from surviving route navigation.
    const user = userEvent.setup()
    const view = render(<VncViewer instanceId={42} available />)
    await user.click(screen.getByRole('button', { name: '连接 VNC' }))
    await waitFor(() => expect(rfbMock.RFB).toHaveBeenCalledTimes(1))
    const first = rfbMock.instances[0]

    view.rerender(<VncViewer instanceId={43} available />)
    expect(first.disconnect).toHaveBeenCalledTimes(1)
    await user.click(screen.getByRole('button', { name: '连接 VNC' }))
    await waitFor(() => expect(rfbMock.RFB).toHaveBeenCalledTimes(2))
    const second = rfbMock.instances[1]
    view.unmount()
    expect(second.disconnect).toHaveBeenCalledTimes(1)
  })

  it('cleans up and reports failed noVNC connections', async () => {
    // An unclean disconnect before connect is the server/WebSocket failure path and must not leave an RFB instance alive.
    const user = userEvent.setup()
    render(<VncViewer instanceId={42} available />)
    await user.click(screen.getByRole('button', { name: '连接 VNC' }))
    await waitFor(() => expect(rfbMock.RFB).toHaveBeenCalledTimes(1))

    act(() => rfbMock.instances[0].emit('disconnect', new CustomEvent('disconnect', { detail: { clean: false } })))

    expect(rfbMock.instances[0].disconnect).toHaveBeenCalledTimes(1)
    expect(screen.getByRole('alert')).toHaveTextContent('VNC 连接失败或意外断开')
    expect(screen.getByRole('status')).toHaveTextContent('failed')
  })
})
