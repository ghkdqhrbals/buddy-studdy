# BuddyStudy Admin Frontend

Standalone React admin console for BuddyStudy.

React 19 and Vite power the analytics and scheduler administrator UI. Runtime
operations such as Redis Stream inspection live in the unified monitoring
console at `monitoring.lowfidev.cloud`. The Admin `Batch Jobs` navigation links
directly to the paginated operations workspace at
`https://monitoring.lowfidev.cloud/jobs.html`.

## Local

```sh
npm install
npm run dev
```

The dev server proxies `/api` to `http://localhost:8080`.

## Build

```sh
VITE_ADMIN_API_BASE_URL=https://api.ghkdqhrbals.org npm run build
```

If `VITE_ADMIN_API_BASE_URL` is empty, the app calls the same origin.

## Docker

```sh
docker build -t buddystudy-admin ./admin-frontend
docker run --rm -p 3000:80 \
  -e BACKEND_UPSTREAM=http://host.docker.internal:8080 \
  buddystudy-admin
```

Change `BACKEND_UPSTREAM` to point at production without rebuilding the image.
