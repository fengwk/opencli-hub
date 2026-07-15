declare module '@novnc/novnc/lib/rfb.js' {
  export default class RFB {
    constructor(target: HTMLElement, url: string)
    scaleViewport: boolean
    addEventListener(type: 'connect' | 'disconnect', listener: (event: Event) => void): void
    addEventListener(type: 'clipboard', listener: (event: CustomEvent<{ text: string }>) => void): void
    clipboardPasteFrom(text: string): void
    disconnect(): void
  }
}
