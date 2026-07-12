import { AppRouter } from '@/app/router'
import { AppShell } from '@/platform/shell/AppShell'

export default function App() {
  return (
    <AppShell>
      <AppRouter />
    </AppShell>
  )
}
