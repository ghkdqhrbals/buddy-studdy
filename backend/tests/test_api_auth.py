import importlib
from dataclasses import replace

from fastapi.testclient import TestClient

from app.storage.repository import Database


def _load_test_app(monkeypatch, tmp_path):
    db_path = tmp_path / "api-auth.db"
    monkeypatch.setenv("ALLOW_SQLITE_FALLBACK", "true")
    monkeypatch.setenv("DATABASE_PATH", str(db_path))
    monkeypatch.setenv("AUTH_JWT_SECRET", "test-jwt-secret")
    monkeypatch.setenv("BACKEND_MASTER_KEY", "test-master-key")
    monkeypatch.setenv("SCHEDULER_ENABLED", "false")

    main = importlib.import_module("app.main")
    main = importlib.reload(main)

    db = Database(path=str(db_path))
    db.init()
    main.database = db
    main.settings = replace(
        main.settings,
        auth_jwt_secret="test-jwt-secret",
        backend_master_key="test-master-key",
        scheduler_enabled=False,
        google_ios_client_id="google-client",
    )
    return main


def _register(client: TestClient, token: str):
    response = client.post(
        "/api/v1/devices/register",
        json={
            "apnsToken": token,
            "platform": "ios",
            "apnsEnvironment": "sandbox",
            "language": "ko",
            "timezone": "Asia/Seoul",
        },
    )
    assert response.status_code == 200
    return response.json()


def _schedule_payload():
    return {
        "topic": "SwiftUI",
        "difficultyLevel": 5,
        "intervalMinutes": 15,
        "enabled": True,
        "openaiApiKey": "sk-test",
        "notificationSound": "default",
        "customPrompt": "",
        "appLanguage": "ko",
        "openaiModel": "gpt-5.4",
        "maxHistoryCount": 100,
        "isQuestionPublic": True,
    }


def test_access_token_is_the_request_principal(monkeypatch, tmp_path):
    main = _load_test_app(monkeypatch, tmp_path)
    client = TestClient(main.app)

    first = _register(client, "apns-token-one-" + "a" * 32)
    second = _register(client, "apns-token-two-" + "b" * 32)
    headers = {"Authorization": f"Bearer {first['accessToken']}"}

    response = client.put(
        f"/api/v1/devices/{first['deviceId']}/settings",
        headers=headers,
        json=_schedule_payload(),
    )
    assert response.status_code == 200

    settings_response = client.get(f"/api/v1/devices/{first['deviceId']}/settings", headers=headers)
    assert settings_response.status_code == 200
    assert settings_response.json()["topic"] == "SwiftUI"
    assert settings_response.json()["isQuestionPublic"] is False

    mismatch = client.get(f"/api/v1/devices/{second['deviceId']}/settings", headers=headers)
    assert mismatch.status_code == 403
    assert mismatch.json()["detail"] == "Device is not linked to this user."

    community = client.get("/api/v1/public/questions")
    assert community.status_code == 401

    guest_community = client.get("/api/v1/public/questions", headers=headers)
    assert guest_community.status_code == 401
    assert guest_community.json()["detail"] == "Google Login is required."


def test_legacy_device_credentials_can_bootstrap_access_token(monkeypatch, tmp_path):
    main = _load_test_app(monkeypatch, tmp_path)
    client = TestClient(main.app)

    registered = _register(client, "apns-token-legacy-" + "c" * 32)

    response = client.post(
        f"/api/v1/devices/{registered['deviceId']}/auth/token",
        headers={
            "X-Device-Id": registered["deviceId"],
            "X-Client-Secret": registered["clientSecret"],
        },
    )

    assert response.status_code == 200
    token = response.json()["accessToken"]
    assert token

    settings_response = client.get(
        f"/api/v1/devices/{registered['deviceId']}/settings",
        headers={"Authorization": f"Bearer {token}"},
    )
    assert settings_response.status_code == 200


def test_public_questions_include_own_public_records_and_allow_privacy_override(monkeypatch, tmp_path):
    main = _load_test_app(monkeypatch, tmp_path)
    client = TestClient(main.app)

    registered = _register(client, "apns-token-public-" + "d" * 32)
    profile = main.database.link_google_user_to_device(
        device_id=registered["deviceId"],
        google_sub="google-public-owner",
        email="owner@example.com",
        display_name="Owner",
    )
    assert profile is not None

    token_response = client.post(
        f"/api/v1/devices/{registered['deviceId']}/auth/token",
        headers={
            "X-Device-Id": registered["deviceId"],
            "X-Client-Secret": registered["clientSecret"],
        },
    )
    assert token_response.status_code == 200
    headers = {"Authorization": f"Bearer {token_response.json()['accessToken']}"}

    record = main.database.create_question(
        device_id=registered["deviceId"],
        user_id=profile["id"],
        topic="SwiftUI",
        difficulty_level=5,
        question="Why use @StateObject?",
        is_public=True,
    )

    public_response = client.get("/api/v1/public/questions", headers=headers)
    assert public_response.status_code == 200
    assert [item["id"] for item in public_response.json()["questions"]] == [record["id"]]

    privacy_response = client.patch(
        f"/api/v1/devices/{registered['deviceId']}/records/{record['id']}/publicity",
        headers=headers,
        json={"isPublic": False},
    )
    assert privacy_response.status_code == 200
    assert privacy_response.json()["isPublic"] is False

    public_response = client.get("/api/v1/public/questions", headers=headers)
    assert public_response.status_code == 200
    assert public_response.json()["questions"] == []
