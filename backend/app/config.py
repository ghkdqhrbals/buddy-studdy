from __future__ import annotations

import base64
import json
import os
from dataclasses import dataclass
from functools import lru_cache

from .openai_models import DEFAULT_OPENAI_MODEL


def _bool_env(name: str, default: bool) -> bool:
    value = os.getenv(name)
    if value is None:
        return default
    return value.strip().lower() in {"1", "true", "yes", "on"}


def _int_secret_env(name: str, secret_values: dict[str, str], secret_key: str, default: int) -> int:
    raw_value = os.getenv(name) or secret_values.get(secret_key)
    if raw_value is None:
        return default
    try:
        return int(str(raw_value).strip())
    except ValueError:
        return default


def _bool_secret_env(name: str, secret_values: dict[str, str], secret_key: str, default: bool) -> bool:
    raw_value = os.getenv(name)
    if raw_value is None:
        raw_value = secret_values.get(secret_key)
    if raw_value is None:
        return default
    return str(raw_value).strip().lower() in {"1", "true", "yes", "on"}


@lru_cache(maxsize=1)
def _load_aws_secret_values() -> dict[str, str]:
    secret_id = os.getenv("AWS_SECRET_ID")
    if not secret_id:
        return {}

    region = os.getenv("AWS_REGION", "ap-northeast-2")
    try:
        import boto3  # type: ignore

        client = boto3.client("secretsmanager", region_name=region)
        response = client.get_secret_value(SecretId=secret_id)
    except Exception:
        return {}

    raw_secret = response.get("SecretString") or ""
    if not raw_secret:
        return {}
    try:
        parsed = json.loads(raw_secret)
    except json.JSONDecodeError:
        return {}
    return {str(key): str(value) for key, value in parsed.items() if value is not None}


def _secret_env(name: str, secret_values: dict[str, str], secret_key: str, default: str | None = None) -> str | None:
    return os.getenv(name) or secret_values.get(secret_key) or default


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
    auth_jwt_secret: str
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
    redis_host: str | None
    redis_port: int
    redis_password: str | None
    redis_db: int
    redis_ssl: bool
    redis_cluster: bool
    email_verification_ttl_seconds: int

    @classmethod
    def load(cls) -> "Settings":
        secret_values = _load_aws_secret_values()
        auth_key = os.getenv("APNS_AUTH_KEY_P8")
        auth_key_base64 = os.getenv("APNS_AUTH_KEY_BASE64")
        if not auth_key_base64:
            auth_key_base64 = secret_values.get("apnsAuthKeyBase64")
        if not auth_key and auth_key_base64:
            auth_key = base64.b64decode(auth_key_base64).decode("utf-8")

        database_url = _secret_env("DATABASE_URL", secret_values, "databaseUrl")
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
            backend_api_token=_secret_env("BACKEND_API_TOKEN", secret_values, "backendApiToken"),
            backend_master_key=_secret_env("BACKEND_MASTER_KEY", secret_values, "backendMasterKey"),
            auth_jwt_secret=_secret_env("AUTH_JWT_SECRET", secret_values, "authJwtSecret")
            or _secret_env("BACKEND_MASTER_KEY", secret_values, "backendMasterKey")
            or "local-dev-auth-secret",
            apns_auth_key_p8=auth_key,
            apns_key_id=_secret_env("APNS_KEY_ID", secret_values, "apnsKeyId"),
            apns_team_id=_secret_env("APNS_TEAM_ID", secret_values, "apnsTeamId"),
            apns_bundle_id=_secret_env(
                "APNS_BUNDLE_ID",
                secret_values,
                "apnsBundleId",
                "io.github.ghkdqhrbals.StudyMate",
            ),
            apns_env=(_secret_env("APNS_ENV", secret_values, "apnsEnv", "production") or "production").strip().lower(),
            enable_openapi_docs=_bool_env("ENABLE_OPENAPI_DOCS", False),
            openapi_access_token=_secret_env("OPENAPI_ACCESS_TOKEN", secret_values, "openapiAccessToken"),
            google_ios_client_id=_secret_env("GOOGLE_IOS_CLIENT_ID", secret_values, "googleIOSClientId"),
            report_email_to=_secret_env("REPORT_EMAIL_TO", secret_values, "reportEmailTo"),
            smtp_host=_secret_env("SMTP_HOST", secret_values, "smtpHost"),
            smtp_port=_int_secret_env("SMTP_PORT", secret_values, "smtpPort", 587),
            smtp_username=_secret_env("SMTP_USERNAME", secret_values, "smtpUsername"),
            smtp_password=_secret_env("SMTP_PASSWORD", secret_values, "smtpPassword"),
            smtp_from=_secret_env("SMTP_FROM", secret_values, "smtpFrom"),
            redis_host=_secret_env("REDIS_HOST", secret_values, "redisHost"),
            redis_port=_int_secret_env("REDIS_PORT", secret_values, "redisPort", 6379),
            redis_password=_secret_env("REDIS_PASSWORD", secret_values, "redisPassword"),
            redis_db=_int_secret_env("REDIS_DB", secret_values, "redisDB", 0),
            redis_ssl=_bool_secret_env("REDIS_SSL", secret_values, "redisSSL", False),
            redis_cluster=_bool_secret_env("REDIS_CLUSTER", secret_values, "redisCluster", False),
            email_verification_ttl_seconds=max(
                30,
                _int_secret_env("EMAIL_VERIFICATION_TTL_SECONDS", secret_values, "emailVerificationTTLSeconds", 180),
            ),
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
