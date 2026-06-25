# BuddyStuddy Admin Frontend

Standalone React admin console for BuddyStuddy.

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
docker build -t buddystuddy-admin ./admin-frontend
docker run --rm -p 3000:80 \
  -e BACKEND_UPSTREAM=http://host.docker.internal:8080 \
  buddystuddy-admin
```

Change `BACKEND_UPSTREAM` to point at production without rebuilding the image.
