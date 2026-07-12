# OpenCLI Hub Frontend

React + TypeScript + Vite base for the OpenCLI Hub management UI. This is the
FE0 foundation: app shell, routing, API client, shared state components and
tests. Feature pages are intentional "功能尚未接入" placeholders and are
implemented by the feature agents (FE1/FE2/FE3).

## Requirements

- Node.js >= 20 (developed on Node 24)
- npm

## Scripts

```bash
npm install      # install dependencies (commit package-lock.json)
npm run dev      # start Vite dev server with /api proxy
npm run build    # type-check + production build into frontend/dist
npm run lint     # ESLint
npm test         # Vitest (run mode)
```

## Dev proxy

The Vite dev server proxies `/api/**` to a backend. Both REST and the VNC
WebSocket (`/api/instances/{id}/vnc`) are forwarded. Configure the target with:

```bash
OPENCLI_HUB_API_TARGET=http://127.0.0.1:8080 npm run dev
```

Defaults to `http://127.0.0.1:8080` (also honors `API_PROXY_TARGET`).

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
