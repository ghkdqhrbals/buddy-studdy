import json
import sys
from types import SimpleNamespace

from app.config import Settings, _load_aws_secret_values
from app.crypto import KeyCipher


def clear_secret_cache():
    _load_aws_secret_values.cache_clear()


def test_settings_loads_backend_master_key_from_aws_secret(monkeypatch):
    clear_secret_cache()

    secret_payload = {
        "backendMasterKey": "aws-master-key",
        "backendApiToken": "aws-api-token",
        "apnsKeyId": "AWSKEY1234",
        "apnsTeamId": "TEAM123456",
        "apnsBundleId": "io.github.ghkdqhrbals.StudyMate",
        "apnsEnv": "sandbox",
        "googleIOSClientId": "google-client",
        "redisHost": "redis.example.com",
        "redisPort": "6379",
        "redisPassword": "redis-password",
        "redisCluster": "true",
        "smtpHost": "smtp.gmail.com",
        "smtpPort": "587",
        "smtpUsername": "sender@example.com",
        "smtpPassword": "smtp-password",
        "smtpFrom": "BuddyStuddy <sender@example.com>",
        "emailVerificationTTLSeconds": "180",
    }

    class FakeSecretsManager:
        def get_secret_value(self, SecretId):
            assert SecretId == "buddystuddy/backend"
            return {"SecretString": json.dumps(secret_payload)}

    fake_boto3 = SimpleNamespace(
        client=lambda service_name, region_name: FakeSecretsManager()
    )
    monkeypatch.setitem(sys.modules, "boto3", fake_boto3)
    monkeypatch.setenv("AWS_SECRET_ID", "buddystuddy/backend")
    monkeypatch.setenv("AWS_REGION", "ap-northeast-2")
    monkeypatch.setenv("DATABASE_URL", "postgresql://user:pass@localhost:5432/app")

    settings = Settings.load()

    assert settings.backend_master_key == "aws-master-key"
    assert settings.backend_api_token == "aws-api-token"
    assert settings.apns_key_id == "AWSKEY1234"
    assert settings.apns_team_id == "TEAM123456"
    assert settings.apns_bundle_id == "io.github.ghkdqhrbals.StudyMate"
    assert settings.apns_env == "sandbox"
    assert settings.google_ios_client_id == "google-client"
    assert settings.redis_host == "redis.example.com"
    assert settings.redis_port == 6379
    assert settings.redis_password == "redis-password"
    assert settings.redis_cluster is True
    assert settings.smtp_host == "smtp.gmail.com"
    assert settings.smtp_port == 587
    assert settings.smtp_username == "sender@example.com"
    assert settings.smtp_password == "smtp-password"
    assert settings.smtp_from == "BuddyStuddy <sender@example.com>"
    assert settings.email_verification_ttl_seconds == 180

    clear_secret_cache()


def test_environment_secret_overrides_aws_secret(monkeypatch):
    clear_secret_cache()

    class FakeSecretsManager:
        def get_secret_value(self, SecretId):
            return {"SecretString": json.dumps({"backendMasterKey": "aws-master-key"})}

    fake_boto3 = SimpleNamespace(
        client=lambda service_name, region_name: FakeSecretsManager()
    )
    monkeypatch.setitem(sys.modules, "boto3", fake_boto3)
    monkeypatch.setenv("AWS_SECRET_ID", "buddystuddy/backend")
    monkeypatch.setenv("AWS_REGION", "ap-northeast-2")
    monkeypatch.setenv("DATABASE_URL", "postgresql://user:pass@localhost:5432/app")
    monkeypatch.setenv("BACKEND_MASTER_KEY", "env-master-key")

    settings = Settings.load()

    assert settings.backend_master_key == "env-master-key"

    clear_secret_cache()


def test_openai_key_cipher_round_trips_without_plaintext():
    cipher = KeyCipher("unit-test-master-key")

    encrypted = cipher.encrypt("sk-test-secret")

    assert encrypted != "sk-test-secret"
    assert "sk-test-secret" not in encrypted
    assert cipher.decrypt(encrypted) == "sk-test-secret"
