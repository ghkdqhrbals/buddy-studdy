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
    assert mismatch.json()["error"]["code"] == "AUTH_DEVICE_MISMATCH"
    assert mismatch.json()["error"]["message"] == "Device is not linked to this user."
    assert mismatch.json()["error"]["requestId"]
    assert mismatch.json()["error"]["status"] == 403

    community = client.get("/api/v1/public/questions")
    assert community.status_code == 200
    assert community.json()["questions"] == []

    guest_community = client.get("/api/v1/public/questions", headers=headers)
    assert guest_community.status_code == 200
    assert guest_community.json()["questions"] == []

    invalid_token_community = client.get(
        "/api/v1/public/questions",
        headers={"Authorization": "Bearer invalid-access-token"},
    )
    assert invalid_token_community.status_code == 200
    assert invalid_token_community.json()["questions"] == []


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


def test_google_login_accepts_access_token_or_legacy_device_credentials(monkeypatch, tmp_path):
    main = _load_test_app(monkeypatch, tmp_path)
    client = TestClient(main.app)

    async def fake_verify_google_id_token(id_token: str, expected_audience: str):
        assert id_token == "google-id-token-for-tests"
        assert expected_audience == "google-client"
        return {
            "sub": "google-login-sub",
            "email": "login@example.com",
            "name": "Login User",
            "picture": None,
        }

    monkeypatch.setattr(main, "verify_google_id_token", fake_verify_google_id_token)
    registered = _register(client, "apns-token-google-login-" + "g" * 32)
    body = {"idToken": "google-id-token-for-tests"}

    missing_auth_response = client.post(f"/api/v1/devices/{registered['deviceId']}/auth/google", json=body)
    assert missing_auth_response.status_code == 401
    assert missing_auth_response.json()["error"]["code"] == "AUTH_ACCESS_TOKEN_REQUIRED"

    legacy_response = client.post(
        f"/api/v1/devices/{registered['deviceId']}/auth/google",
        headers={
            "X-Device-Id": registered["deviceId"],
            "X-Client-Secret": registered["clientSecret"],
        },
        json=body,
    )
    assert legacy_response.status_code == 200
    assert legacy_response.json()["profile"]["displayName"] == "Login User"
    assert legacy_response.json()["accessToken"]

    second = _register(client, "apns-token-google-login-" + "h" * 32)
    access_token_response = client.post(
        f"/api/v1/devices/{second['deviceId']}/auth/google",
        headers={"Authorization": f"Bearer {second['accessToken']}"},
        json=body,
    )
    assert access_token_response.status_code == 200
    assert access_token_response.json()["profile"]["displayName"] == "Login User"
    assert access_token_response.json()["accessToken"]


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
    assert public_response.json()["questions"] == []

    graded = main.database.grade_record(
        device_id=registered["deviceId"],
        record_id=record["id"],
        answer="Use it for owned observable state.",
        score=90,
        is_correct=True,
        feedback="Good",
        explanation="StateObject owns the lifecycle.",
        user_id=profile["id"],
    )
    assert graded is not None

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


def test_profile_page_access_can_hide_public_questions_and_reports_private_page_access(monkeypatch, tmp_path):
    main = _load_test_app(monkeypatch, tmp_path)
    client = TestClient(main.app)

    registered = _register(client, "apns-token-page-access-" + "e" * 32)
    profile = main.database.link_google_user_to_device(
        device_id=registered["deviceId"],
        google_sub="google-page-access-owner",
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
        question="What is view identity?",
        is_public=True,
    )
    graded = main.database.grade_record(
        device_id=registered["deviceId"],
        record_id=record["id"],
        answer="It determines whether SwiftUI preserves state.",
        score=88,
        is_correct=True,
        feedback="Good",
        explanation="Identity controls view/state reuse.",
        user_id=profile["id"],
    )
    assert graded is not None

    public_response = client.get("/api/v1/public/questions", headers=headers)
    assert public_response.status_code == 200
    assert [item["id"] for item in public_response.json()["questions"]] == [record["id"]]

    profile_response = client.patch(
        f"/api/v1/devices/{registered['deviceId']}/profile",
        headers=headers,
        json={"pageAccess": {"publicQuestions": False, "statistics": True, "studyDetail": True, "records": True}},
    )
    assert profile_response.status_code == 200
    page_access = profile_response.json()["pageAccess"]
    assert page_access == {
        "publicQuestions": False,
        "statistics": True,
        "studyDetail": True,
        "records": True,
    }

    public_response = client.get("/api/v1/public/questions", headers=headers)
    assert public_response.status_code == 200
    assert public_response.json()["questions"] == []


def test_records_stats_and_study_detail_require_page_access(monkeypatch, tmp_path):
    main = _load_test_app(monkeypatch, tmp_path)
    client = TestClient(main.app)

    registered = _register(client, "apns-token-protected-page-" + "f" * 32)
    guest_headers = {"Authorization": f"Bearer {registered['accessToken']}"}

    settings_response = client.put(
        f"/api/v1/devices/{registered['deviceId']}/settings",
        headers=guest_headers,
        json=_schedule_payload(),
    )
    assert settings_response.status_code == 200

    records_response = client.get(f"/api/v1/devices/{registered['deviceId']}/records", headers=guest_headers)
    assert records_response.status_code == 403
    assert records_response.json()["error"]["code"] == "PAGE_ACCESS_DENIED"
    assert records_response.json()["error"]["message"] == "Page access denied: records."

    stats_response = client.get(f"/api/v1/devices/{registered['deviceId']}/stats", headers=guest_headers)
    assert stats_response.status_code == 403
    assert stats_response.json()["error"]["code"] == "PAGE_ACCESS_DENIED"
    assert stats_response.json()["error"]["message"] == "Page access denied: statistics."

    create_response = client.post(f"/api/v1/devices/{registered['deviceId']}/questions", headers=guest_headers)
    assert create_response.status_code == 403
    assert create_response.json()["error"]["code"] == "PAGE_ACCESS_DENIED"
    assert create_response.json()["error"]["message"] == "Page access denied: studyDetail."

    snapshot_response = client.get(f"/api/v1/devices/{registered['deviceId']}/snapshot", headers=guest_headers)
    assert snapshot_response.status_code == 200
    snapshot = snapshot_response.json()
    assert snapshot["records"] == []
    assert snapshot["stats"] is None
    assert snapshot["totalCount"] == 0

    profile = main.database.link_google_user_to_device(
        device_id=registered["deviceId"],
        google_sub="google-protected-page-owner",
        email="protected@example.com",
        display_name="Protected",
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
    user_headers = {"Authorization": f"Bearer {token_response.json()['accessToken']}"}

    records_response = client.get(f"/api/v1/devices/{registered['deviceId']}/records", headers=user_headers)
    assert records_response.status_code == 200

    stats_response = client.get(f"/api/v1/devices/{registered['deviceId']}/stats", headers=user_headers)
    assert stats_response.status_code == 200
