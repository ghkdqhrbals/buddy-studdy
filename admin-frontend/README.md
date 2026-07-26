# BuddyStudy Admin Frontend

Standalone React admin console for BuddyStudy.

React 19 and Vite are the source of truth for administrator UI. New operational
views, including Redis Stream and database outbox inspection, belong here
instead of in parallel static monitoring pages.

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

## Event Streams

`Operations > Event Streams` provides cursor-paginated Redis Stream,
`redis_event_outbox`, and `question_push_outbox` inspection. The backend limits
pages to 100 rows and redacts nested credentials before returning details.

## Docker

```sh
docker build -t buddystudy-admin ./admin-frontend
docker run --rm -p 3000:80 \
  -e BACKEND_UPSTREAM=http://host.docker.internal:8080 \
  buddystudy-admin
```

Change `BACKEND_UPSTREAM` to point at production without rebuilding the image.
