from __future__ import annotations

from dataclasses import dataclass
from datetime import UTC, datetime, timedelta

import jwt


@dataclass(frozen=True)
class AccessTokenClaims:
    user_id: int
    expires_at: datetime


class AccessTokenError(ValueError):
    pass


def create_access_token(user_id: int, secret: str, expires_in: timedelta = timedelta(days=90)) -> tuple[str, datetime]:
    now = datetime.now(tz=UTC)
    expires_at = now + expires_in
    token = jwt.encode(
        {
            "iss": "buddystuddy",
            "sub": str(user_id),
            "user_id": int(user_id),
            "iat": int(now.timestamp()),
            "exp": int(expires_at.timestamp()),
        },
        secret,
        algorithm="HS256",
    )
    return token, expires_at


def decode_access_token(token: str, secret: str) -> AccessTokenClaims:
    try:
        payload = jwt.decode(token, secret, algorithms=["HS256"], issuer="buddystuddy")
    except jwt.PyJWTError as error:
        raise AccessTokenError(str(error)) from error

    raw_user_id = payload.get("user_id") or payload.get("sub")
    try:
        user_id = int(raw_user_id)
    except (TypeError, ValueError) as error:
        raise AccessTokenError("Access token does not contain a valid user_id.") from error

    raw_exp = payload.get("exp")
    if raw_exp is None:
        raise AccessTokenError("Access token does not contain an expiration.")

    return AccessTokenClaims(
        user_id=user_id,
        expires_at=datetime.fromtimestamp(int(raw_exp), tz=UTC),
    )
