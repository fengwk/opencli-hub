declare module '@novnc/novnc/lib/rfb.js' {
  export default class RFB {
    constructor(target: HTMLElement, url: string)
    scaleViewport: boolean
    addEventListener(type: 'connect' | 'disconnect', listener: (event: Event) => void): void
    disconnect(): void
  }
}
