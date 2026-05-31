# BuddyStuddy Deploy

Deployment repository for the BuddyStuddy Python push backend.

This repo is triggered by `repository_dispatch` from the app repository after a backend Docker image is published to GHCR.

## Required Secrets

- `EC2_HOST`
- `EC2_USER`
- `EC2_SSH_PRIVATE_KEY`
- `GHCR_USERNAME`
- `GHCR_TOKEN`
- `BACKEND_MASTER_KEY`
- `BACKEND_API_TOKEN`
- `APNS_AUTH_KEY_BASE64`
- `APNS_KEY_ID`
- `APNS_TEAM_ID`
- `APNS_BUNDLE_ID`
- `APNS_ENV`

## Manual Deploy

Run the `Deploy Backend to EC2` workflow and provide the image ref, for example:

```text
ghcr.io/ghkdqhrbals/buddy-studdy-backend:latest
```

