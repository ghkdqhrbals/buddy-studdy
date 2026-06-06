import importlib
import hashlib
from dataclasses import replace

import jwt
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
        "/api/v1/me/settings",
        headers=headers,
        json=_schedule_payload(),
    )
    assert response.status_code == 200

    settings_response = client.get("/api/v1/me/settings", headers=headers)
    assert settings_response.status_code == 200
    assert settings_response.json()["topic"] == "SwiftUI"
    assert settings_response.json()["isQuestionPublic"] is False

    token_payload = jwt.decode(first["accessToken"], "test-jwt-secret", algorithms=["HS256"], issuer="buddystuddy")
    assert token_payload["user_id"]
    assert token_payload["session_id"]
    assert token_payload["device_id"] == first["deviceId"]

    tampered_token = jwt.encode(
        {
            **token_payload,
            "device_id": second["deviceId"],
        },
        "test-jwt-secret",
        algorithm="HS256",
    )
    mismatch = client.get("/api/v1/me/settings", headers={"Authorization": f"Bearer {tampered_token}"})
    assert mismatch.status_code == 401

    me_settings = client.get("/api/v1/me/settings", headers=headers)
    assert me_settings.status_code == 200
    assert me_settings.json()["topic"] == "SwiftUI"

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
        "/api/v1/auth/token",
        headers={
            "X-Device-Id": registered["deviceId"],
            "X-Client-Secret": registered["clientSecret"],
        },
    )

    assert response.status_code == 200
    token = response.json()["accessToken"]
    assert token
    token_payload = jwt.decode(token, "test-jwt-secret", algorithms=["HS256"], issuer="buddystuddy")
    assert token_payload["device_id"] == registered["deviceId"]

    pathless_response = client.post(
        "/api/v1/auth/token",
        headers={
            "X-Device-Id": registered["deviceId"],
            "X-Client-Secret": registered["clientSecret"],
        },
    )
    assert pathless_response.status_code == 200
    assert pathless_response.json()["accessToken"]

    settings_response = client.get(
        "/api/v1/me/settings",
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

    missing_auth_response = client.post("/api/v1/auth/google", json=body)
    assert missing_auth_response.status_code == 401
    assert missing_auth_response.json()["error"]["code"] == "AUTH_ACCESS_TOKEN_REQUIRED"

    legacy_response = client.post(
        "/api/v1/auth/google",
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
        "/api/v1/auth/google",
        headers={"Authorization": f"Bearer {second['accessToken']}"},
        json=body,
    )
    assert access_token_response.status_code == 200
    assert access_token_response.json()["profile"]["displayName"] == "Login User"
    assert access_token_response.json()["accessToken"]


def test_email_login_creates_user_reuses_user_and_hashes_password(monkeypatch, tmp_path):
    main = _load_test_app(monkeypatch, tmp_path)
    client = TestClient(main.app)

    registered = _register(client, "apns-token-email-login-" + "e" * 32)
    body = {"email": "Tester@Example.com", "password": "secret123"}

    response = client.post(
        "/api/v1/auth/email",
        headers={"Authorization": f"Bearer {registered['accessToken']}"},
        json=body,
    )
    assert response.status_code == 200
    payload = response.json()
    assert payload["profile"]["displayName"] == "tester"
    assert payload["profile"]["id"]
    assert payload["accessToken"]

    with main.database.connect() as session:
        from app.storage.models import User

        user = session.query(User).filter(User.provider == "EMAIL", User.provider_id == "tester@example.com").one()
        assert user.password_hash == hashlib.sha256("secret123".encode("utf-8")).hexdigest()
        assert user.password_hash != "secret123"

    second = _register(client, "apns-token-email-login-" + "f" * 32)
    second_response = client.post(
        "/api/v1/auth/email",
        headers={"Authorization": f"Bearer {second['accessToken']}"},
        json={"email": "tester@example.com", "password": "secret123"},
    )
    assert second_response.status_code == 200
    assert second_response.json()["profile"]["id"] == payload["profile"]["id"]

    wrong_password_response = client.post(
        "/api/v1/auth/email",
        headers={"Authorization": f"Bearer {second['accessToken']}"},
        json={"email": "tester@example.com", "password": "wrong123"},
    )
    assert wrong_password_response.status_code == 401
    assert wrong_password_response.json()["error"]["code"] == "AUTH_INVALID_EMAIL_CREDENTIALS"

    legacy_device = _register(client, "apns-token-email-login-" + "g" * 32)
    legacy_response = client.post(
        "/api/v1/auth/email",
        headers={
            "X-Device-Id": legacy_device["deviceId"],
            "X-Client-Secret": legacy_device["clientSecret"],
        },
        json={"email": "legacy@example.com", "password": "secret123"},
    )
    assert legacy_response.status_code == 200
    assert legacy_response.json()["profile"]["displayName"] == "legacy"
    assert legacy_response.json()["accessToken"]


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
        "/api/v1/auth/token",
        headers={
            "X-Device-Id": registered["deviceId"],
            "X-Client-Secret": registered["clientSecret"],
        },
    )
    assert token_response.status_code == 200
    headers = {"Authorization": f"Bearer {token_response.json()['accessToken']}"}

    profile_response = client.patch(
        "/api/v1/me/profile",
        headers=headers,
        json={
            "displayName": "Owner",
            "avatarSymbolName": "pixel-princess",
            "avatarColorSeed": "avatar-color-rose",
        },
    )
    assert profile_response.status_code == 200
    assert profile_response.json()["avatarSymbolName"] == "pixel-princess"
    assert profile_response.json()["avatarColorSeed"] == "avatar-color-rose"

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
    public_questions = public_response.json()["questions"]
    assert [item["id"] for item in public_questions] == [record["id"]]
    assert public_questions[0]["answer"] == "Use it for owned observable state."
    assert public_questions[0]["gradingResult"] == {
        "score": 90,
        "isCorrect": True,
        "feedback": "Good",
        "explanation": "StateObject owns the lifecycle.",
    }
    assert public_questions[0]["answeredAt"] is not None
    assert public_questions[0]["author"]["avatarSymbolName"] == "pixel-princess"
    assert public_questions[0]["author"]["avatarColorSeed"] == "avatar-color-rose"

    privacy_response = client.patch(
        f"/api/v1/me/records/{record['id']}/publicity",
        headers=headers,
        json={"isPublic": False},
    )
    assert privacy_response.status_code == 200
    assert privacy_response.json()["isPublic"] is False

    public_response = client.get("/api/v1/public/questions", headers=headers)
    assert public_response.status_code == 200
    assert public_response.json()["questions"] == []


def test_profile_withdrawal_deletes_user_data_and_returns_anonymous_token(monkeypatch, tmp_path):
    main = _load_test_app(monkeypatch, tmp_path)
    client = TestClient(main.app)

    registered = _register(client, "apns-token-withdraw-" + "w" * 32)
    profile = main.database.link_google_user_to_device(
        device_id=registered["deviceId"],
        google_sub="google-withdraw-owner",
        email="owner@example.com",
        display_name="Owner",
    )
    assert profile is not None

    token_response = client.post(
        "/api/v1/auth/token",
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
        answer="It determines state reuse.",
        score=88,
        is_correct=True,
        feedback="Good",
        explanation="Identity controls view/state reuse.",
        user_id=profile["id"],
    )
    assert graded is not None
    assert client.get("/api/v1/public/questions").json()["totalCount"] == 1

    withdrawal = client.delete("/api/v1/me/profile", headers=headers)
    assert withdrawal.status_code == 200
    anonymous_token = withdrawal.json()["accessToken"]
    token_payload = jwt.decode(anonymous_token, "test-jwt-secret", algorithms=["HS256"], issuer="buddystuddy")
    assert token_payload["device_id"] == registered["deviceId"]
    assert token_payload["is_anonymous"] is True

    assert client.get("/api/v1/public/questions").json()["questions"] == []
    records, records_total = main.database.list_records(
        registered["deviceId"],
        user_id=profile["id"],
        include_deleted=True,
        limit=20,
        offset=0,
    )
    assert records == []
    assert records_total == 0
    assert client.get("/api/v1/me/profile", headers={"Authorization": f"Bearer {anonymous_token}"}).status_code == 401
    assert main.database.get_public_profile(profile["id"]) is None


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
        "/api/v1/auth/token",
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
        "/api/v1/me/profile",
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
        "/api/v1/me/settings",
        headers=guest_headers,
        json=_schedule_payload(),
    )
    assert settings_response.status_code == 200

    records_response = client.get("/api/v1/me/records", headers=guest_headers)
    assert records_response.status_code == 403
    assert records_response.json()["error"]["code"] == "PAGE_ACCESS_DENIED"
    assert records_response.json()["error"]["message"] == "Page access denied: records."

    stats_response = client.get("/api/v1/me/stats", headers=guest_headers)
    assert stats_response.status_code == 403
    assert stats_response.json()["error"]["code"] == "PAGE_ACCESS_DENIED"
    assert stats_response.json()["error"]["message"] == "Page access denied: statistics."

    create_response = client.post("/api/v1/me/questions", headers=guest_headers)
    assert create_response.status_code == 403
    assert create_response.json()["error"]["code"] == "PAGE_ACCESS_DENIED"
    assert create_response.json()["error"]["message"] == "Page access denied: studyDetail."

    snapshot_response = client.get("/api/v1/me/snapshot", headers=guest_headers)
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
        "/api/v1/auth/token",
        headers={
            "X-Device-Id": registered["deviceId"],
            "X-Client-Secret": registered["clientSecret"],
        },
    )
    assert token_response.status_code == 200
    user_headers = {"Authorization": f"Bearer {token_response.json()['accessToken']}"}

    records_response = client.get("/api/v1/me/records", headers=user_headers)
    assert records_response.status_code == 200

    stats_response = client.get("/api/v1/me/stats", headers=user_headers)
    assert stats_response.status_code == 200
