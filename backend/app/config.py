from __future__ import annotations

import base64
import os
from dataclasses import dataclass

from .openai_models import DEFAULT_OPENAI_MODEL


def _bool_env(name: str, default: bool) -> bool:
    value = os.getenv(name)
    if value is None:
        return default
    return value.strip().lower() in {"1", "true", "yes", "on"}


@dataclass(frozen=True)
class Settings:
    database_path: str
    database_url: str | None
    allow_sqlite_fallback: bool
    app_host: str
    app_port: int
    scheduler_enabled: bool
    scheduler_poll_seconds: int
    openai_model: str
    openai_api_key: str | None
    backend_api_token: str | None
    backend_master_key: str | None
    apns_auth_key_p8: str | None
    apns_key_id: str | None
    apns_team_id: str | None
    apns_bundle_id: str
    apns_env: str
    enable_openapi_docs: bool
    openapi_access_token: str | None
    google_ios_client_id: str | None
    report_email_to: str | None
    smtp_host: str | None
    smtp_port: int
    smtp_username: str | None
    smtp_password: str | None
    smtp_from: str | None

    @classmethod
    def load(cls) -> "Settings":
        auth_key = os.getenv("APNS_AUTH_KEY_P8")
        auth_key_base64 = os.getenv("APNS_AUTH_KEY_BASE64")
        if not auth_key and auth_key_base64:
            auth_key = base64.b64decode(auth_key_base64).decode("utf-8")

        database_url = os.getenv("DATABASE_URL")
        allow_sqlite_fallback = _bool_env("ALLOW_SQLITE_FALLBACK", False)
        if not database_url and not allow_sqlite_fallback:
            raise RuntimeError(
                "DATABASE_URL is required. BuddyStuddy backend uses PostgreSQL in production. "
                "Set ALLOW_SQLITE_FALLBACK=true only for isolated local tests."
            )

        return cls(
            database_path=os.getenv("DATABASE_PATH", "/data/buddystuddy.db"),
            database_url=database_url,
            allow_sqlite_fallback=allow_sqlite_fallback,
            app_host=os.getenv("APP_HOST", "0.0.0.0"),
            app_port=int(os.getenv("APP_PORT", "8080")),
            scheduler_enabled=_bool_env("SCHEDULER_ENABLED", True),
            scheduler_poll_seconds=max(5, int(os.getenv("SCHEDULER_POLL_SECONDS", "30"))),
            openai_model=os.getenv("OPENAI_MODEL", DEFAULT_OPENAI_MODEL),
            openai_api_key=os.getenv("OPENAI_API_KEY"),
            backend_api_token=os.getenv("BACKEND_API_TOKEN"),
            backend_master_key=os.getenv("BACKEND_MASTER_KEY"),
            apns_auth_key_p8=auth_key,
            apns_key_id=os.getenv("APNS_KEY_ID"),
            apns_team_id=os.getenv("APNS_TEAM_ID"),
            apns_bundle_id=os.getenv("APNS_BUNDLE_ID", "io.github.ghkdqhrbals.StudyMate"),
            apns_env=os.getenv("APNS_ENV", "production").strip().lower(),
            enable_openapi_docs=_bool_env("ENABLE_OPENAPI_DOCS", False),
            openapi_access_token=os.getenv("OPENAPI_ACCESS_TOKEN"),
            google_ios_client_id=os.getenv("GOOGLE_IOS_CLIENT_ID"),
            report_email_to=os.getenv("REPORT_EMAIL_TO"),
            smtp_host=os.getenv("SMTP_HOST"),
            smtp_port=int(os.getenv("SMTP_PORT", "587")),
            smtp_username=os.getenv("SMTP_USERNAME"),
            smtp_password=os.getenv("SMTP_PASSWORD"),
            smtp_from=os.getenv("SMTP_FROM"),
        )

    @property
    def apns_host(self) -> str:
        return self.apns_host_for_environment(self.apns_env)

    @staticmethod
    def apns_host_for_environment(environment: str | None) -> str:
        if (environment or "").strip().lower() == "sandbox":
            return "https://api.sandbox.push.apple.com"
        return "https://api.push.apple.com"

    def require_apns(self) -> None:
        missing = [
            name
            for name, value in {
                "APNS_AUTH_KEY_BASE64": self.apns_auth_key_p8,
                "APNS_KEY_ID": self.apns_key_id,
                "APNS_TEAM_ID": self.apns_team_id,
                "APNS_BUNDLE_ID": self.apns_bundle_id,
            }.items()
            if not value
        ]
        if missing:
            raise RuntimeError(f"Missing APNs settings: {', '.join(missing)}")
