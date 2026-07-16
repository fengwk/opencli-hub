# OpenCLI Hub Frontend

React + TypeScript + Vite application for the OpenCLI Hub management UI. It
provides the app shell, API client, shared components, and management pages for
Instances, Executions, Commands, Resources, VNC, and Logs.

## Requirements

- Node.js 20 or newer
- npm

Run the commands below from the `frontend` directory. Use the repository's
lockfile for reproducible installs:

```bash
npm ci
npm run dev      # start Vite with the /api and VNC WebSocket proxy
npm test         # Vitest in run mode
npm run lint     # ESLint
npm run build    # TypeScript check + production build
```

## Dev proxy

The Vite dev server proxies `/api/**` to the backend. Both REST and the VNC
WebSocket (`/api/instances/{id}/vnc`) are forwarded. Configure the target with:

```bash
OPENCLI_HUB_API_TARGET=http://127.0.0.1:8080 npm run dev
```

The default target is `http://127.0.0.1:8080`; `API_PROXY_TARGET` is also
supported.

## Structure

```text
src/
├── app/                 # App, providers (QueryClient + Router), route table
├── platform/shell/      # Responsive AppShell + navigation
├── features/            # instances, executions, commands, resources, logs, not-found
└── shared/
    ├── api/             # axios client, convention4j Result unwrap, ApiError, resource url
    └── components/      # Loading, Empty, ErrorState, StatusBadge, ConfirmDialog, FeaturePlaceholder
```

## Production packaging

`npm run build` emits `frontend/dist`. The Maven `web` module copies that dist
into the Spring Boot JAR static resources during `prepare-package`. Maven never
runs npm and never writes the build output back into `src/main/resources`; CI or
Docker must build the frontend before running Maven `package`.
