from __future__ import annotations

import hashlib
import hmac
import json
import secrets
import time
from dataclasses import dataclass

from .config import Settings


class EmailVerificationUnavailable(RuntimeError):
    pass


@dataclass(frozen=True)
class IssuedEmailVerification:
    email: str
    code: str
    expires_in_seconds: int


class EmailVerificationStore:
    def __init__(self, settings: Settings):
        self.settings = settings
        self._memory: dict[str, tuple[float, str]] = {}
        self._redis_client = None

    def issue_code(self, email: str) -> IssuedEmailVerification:
        normalized_email = self.normalize_email(email)
        ttl = self.settings.email_verification_ttl_seconds
        code = f"{secrets.randbelow(1_000_000):06d}"
        value = json.dumps(
            {
                "email": normalized_email,
                "codeHash": self._code_hash(normalized_email, code),
            }
        )

        redis_client = self._redis()
        key = self._key(normalized_email)
        if redis_client is not None:
            try:
                redis_client.setex(key, ttl, value)
            except Exception as error:
                raise EmailVerificationUnavailable("Email verification Redis is not available.") from error
        elif self.settings.allow_sqlite_fallback:
            self._memory[key] = (time.time() + ttl, value)
        else:
            raise EmailVerificationUnavailable("Email verification Redis is not configured.")

        return IssuedEmailVerification(email=normalized_email, code=code, expires_in_seconds=ttl)

    def verify_and_consume(self, email: str, code: str) -> bool:
        normalized_email = self.normalize_email(email)
        normalized_code = code.strip()
        if not normalized_code:
            return False

        key = self._key(normalized_email)
        redis_client = self._redis()
        if redis_client is not None:
            try:
                raw_value = redis_client.get(key)
            except Exception as error:
                raise EmailVerificationUnavailable("Email verification Redis is not available.") from error
            if raw_value is None:
                return False
            if isinstance(raw_value, bytes):
                raw_value = raw_value.decode("utf-8", errors="replace")
        elif self.settings.allow_sqlite_fallback:
            stored = self._memory.get(key)
            if stored is None:
                return False
            expires_at, raw_value = stored
            if expires_at < time.time():
                self._memory.pop(key, None)
                return False
        else:
            raise EmailVerificationUnavailable("Email verification Redis is not configured.")

        try:
            payload = json.loads(str(raw_value))
        except json.JSONDecodeError:
            self._delete(key)
            return False

        expected_hash = str(payload.get("codeHash") or "")
        actual_hash = self._code_hash(normalized_email, normalized_code)
        if not hmac.compare_digest(expected_hash, actual_hash):
            return False

        self._delete(key)
        return True

    @staticmethod
    def normalize_email(email: str) -> str:
        return email.strip().lower()

    def _redis(self):
        if self._redis_client is not None:
            return self._redis_client
        if not self.settings.redis_host:
            return None

        try:
            import redis
            from redis.cluster import RedisCluster

            client_class = RedisCluster if self.settings.redis_cluster else redis.Redis
            kwargs = {
                "host": self.settings.redis_host,
                "port": self.settings.redis_port,
                "password": self.settings.redis_password,
                "ssl": self.settings.redis_ssl,
                "socket_connect_timeout": 5,
                "socket_timeout": 5,
                "decode_responses": True,
            }
            if not self.settings.redis_cluster:
                kwargs["db"] = self.settings.redis_db
            self._redis_client = client_class(**kwargs)
            self._redis_client.ping()
            return self._redis_client
        except Exception as error:
            self._redis_client = None
            raise EmailVerificationUnavailable("Email verification Redis is not available.") from error

    def _delete(self, key: str) -> None:
        redis_client = self._redis()
        if redis_client is not None:
            try:
                redis_client.delete(key)
            except Exception:
                self._redis_client = None
        else:
            self._memory.pop(key, None)

    def _key(self, normalized_email: str) -> str:
        digest = hashlib.sha256(normalized_email.encode("utf-8")).hexdigest()
        return f"buddystuddy:email_signup:{digest}"

    def _code_hash(self, normalized_email: str, code: str) -> str:
        message = f"{normalized_email}:{code}".encode("utf-8")
        secret = self.settings.auth_jwt_secret.encode("utf-8")
        return hmac.new(secret, message, hashlib.sha256).hexdigest()
