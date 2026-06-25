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
