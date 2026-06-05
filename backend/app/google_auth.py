from __future__ import annotations

import httpx


class GoogleAuthError(RuntimeError):
    pass


async def verify_google_id_token(id_token: str, expected_audience: str) -> dict[str, str | None]:
    async with httpx.AsyncClient(timeout=10) as client:
        response = await client.get(
            "https://oauth2.googleapis.com/tokeninfo",
            params={"id_token": id_token},
        )
    if response.status_code != 200:
        raise GoogleAuthError(f"Google token verification failed: HTTP {response.status_code}")

    payload = response.json()
    if payload.get("aud") != expected_audience:
        raise GoogleAuthError("Google token audience does not match this app.")

    subject = str(payload.get("sub") or "").strip()
    email = str(payload.get("email") or "").strip()
    if not subject or not email:
        raise GoogleAuthError("Google token does not contain a usable identity.")

    return {
        "sub": subject,
        "email": email,
        "name": str(payload.get("name") or email.split("@")[0]),
        "picture": payload.get("picture"),
    }
