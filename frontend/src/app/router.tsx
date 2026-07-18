import { Navigate, Route, Routes } from 'react-router-dom'
import { CommandsPage } from '@/features/commands/CommandsPage'
import { ExecutionDetailPage } from '@/features/executions/ExecutionDetailPage'
import { ExecutionsPage } from '@/features/executions/ExecutionsPage'
import { InstanceDetailPage } from '@/features/instances/InstanceDetailPage'
import { InstancesPage } from '@/features/instances/InstancesPage'
import { LogsPage } from '@/features/logs/LogsPage'
import { NotFoundPage } from '@/features/not-found/NotFoundPage'
import { PluginsPage } from '@/features/plugins/PluginsPage'
import { ResourcesPage } from '@/features/resources/ResourcesPage'
import { SettingsPage } from '@/features/settings/SettingsPage'

export function AppRouter() {
  return (
    <Routes>
      <Route path="/" element={<Navigate to="/instances" replace />} />
      <Route path="/instances" element={<InstancesPage />} />
      <Route path="/instances/:id" element={<InstanceDetailPage />} />
      <Route path="/executions" element={<ExecutionsPage />} />
      <Route path="/executions/:id" element={<ExecutionDetailPage />} />
      <Route path="/commands" element={<CommandsPage />} />
      <Route path="/resources" element={<ResourcesPage />} />
      <Route path="/settings" element={<SettingsPage />} />
      <Route path="/plugins" element={<PluginsPage />} />
      <Route path="/logs" element={<LogsPage />} />
      <Route path="*" element={<NotFoundPage />} />
    </Routes>
  )
}
