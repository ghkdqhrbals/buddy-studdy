from datetime import datetime, timedelta
from types import SimpleNamespace

import pytest

from app.storage.models import Device, Schedule, UTC, as_utc_datetime
from app.storage.repository import Database, transactional


def test_boolean_default_sql_uses_database_dialect(db: Database):
    assert db._boolean_default_sql(True) == "1"
    assert db._boolean_default_sql(False) == "0"

    db.engine = SimpleNamespace(dialect=SimpleNamespace(name="postgresql"))
    assert db._boolean_default_sql(True) == "TRUE"
    assert db._boolean_default_sql(False) == "FALSE"


def test_device_registration_and_authentication(db):
    device_id, client_secret = db.register_device(
        apns_token="token-1",
        platform="ios",
        apns_environment="production",
        language="ko",
        timezone="Asia/Seoul",
    )

    assert len(device_id) > 8
    assert db.authenticate_device(device_id, client_secret)
    assert not db.authenticate_device(device_id, client_secret + "x")


def test_upsert_and_get_schedule(db: Database):
    device_id, client_secret = db.register_device(
        apns_token="token-2",
        platform="ios",
        apns_environment="production",
        language="en",
        timezone="America/New_York",
    )
    assert db.authenticate_device(device_id, client_secret)

    first_due = db.upsert_schedule(
        device_id=device_id,
        topic="Swift Advanced",
        difficulty_level=4,
        interval_minutes=15,
        enabled=True,
        openai_api_key_cipher=None,
        notification_sound="default",
        custom_prompt="ask concise",
        app_language="en",
        openai_model="gpt-5.4",
        max_history_count=120,
    )

    schedule = db.get_schedule(device_id)
    assert schedule is not None
    assert schedule["topic"] == "Swift Advanced"
    assert schedule["difficulty_level"] == 4
    assert schedule["next_due_at"] is not None

    # Same schedule should preserve existing due time.
    unchanged_due = db.upsert_schedule(
        device_id=device_id,
        topic="Swift Advanced",
        difficulty_level=4,
        interval_minutes=15,
        enabled=True,
        openai_api_key_cipher=None,
        notification_sound="default",
        custom_prompt="ask concise",
        app_language="en",
        openai_model="gpt-5.4",
        max_history_count=120,
    )
    assert unchanged_due == first_due

    # Changing settings should reset due time.
    changed_due = db.upsert_schedule(
        device_id=device_id,
        topic="Swift Architectures",
        difficulty_level=5,
        interval_minutes=20,
        enabled=True,
        openai_api_key_cipher=None,
        notification_sound="default",
        custom_prompt="ask examples",
        app_language="en",
        openai_model="gpt-5.4",
        max_history_count=120,
    )
    assert changed_due != first_due


def test_questions_and_grading_flow(db: Database):
    device_id, client_secret = db.register_device(
        apns_token="token-3",
        platform="ios",
        apns_environment="production",
        language="ko",
        timezone="Asia/Seoul",
    )
    assert db.authenticate_device(device_id, client_secret)

    scheduled = db.create_question(
        device_id=device_id,
        topic="Algorithms",
        difficulty_level=6,
        question="Binary search의 시간복잡도는?",
        expected_answer_hint="O(log n)",
        source="manual",
    )
    assert scheduled["status"] == "ungraded"
    assert scheduled["gradingResult"] is None

    record_id = scheduled["id"]
    assert db.pending_record_count(device_id) == 1

    updated = db.set_record_answer(device_id, record_id, "정렬된 배열에서 로그로 찾는 탐색입니다.")
    assert updated is not None
    assert updated["answer"] == "정렬된 배열에서 로그로 찾는 탐색입니다."

    graded = db.grade_record(
        device_id=device_id,
        record_id=record_id,
        answer=updated["answer"],
        score=92,
        is_correct=True,
        feedback="Good. 핵심 개념을 정확히 짚었습니다.",
        explanation="이진 탐색은 로그 시간 복잡도를 가집니다.",
    )
    assert graded is not None
    assert graded["status"] == "graded"
    assert graded["gradingResult"]["score"] == 92


def test_google_profile_is_attached_to_public_questions(db: Database):
    device_id, client_secret = db.register_device(
        apns_token="token-profile",
        platform="ios",
        apns_environment="production",
        language="ko",
        timezone="Asia/Seoul",
    )
    assert db.authenticate_device(device_id, client_secret)
    assert not db.device_has_user(device_id)

    profile = db.link_google_user_to_device(
        device_id=device_id,
        google_sub="google-sub-1",
        email="tester@example.com",
        display_name="테스터",
        avatar_url="https://example.com/avatar.png",
    )
    assert profile is not None
    assert profile["displayName"] == "테스터"
    assert db.device_has_user(device_id)
    assert db.get_device_profile(device_id) is not None

    created = db.create_question(
        device_id=device_id,
        topic="SwiftUI",
        difficulty_level=5,
        question="ViewBuilder는 언제 쓰나요?",
        source="manual",
        is_public=True,
    )
    questions, total = db.list_public_questions(exclude_device_id="", limit=10, offset=0)
    assert total == 1
    assert questions[0]["id"] == created["id"]
    assert questions[0]["author"]["displayName"] == "테스터"

    with db.connect() as session:
        device = session.query(Device).filter(Device.device_id == device_id).first()
        assert device is not None
        assert device.google_session_expires_at is not None
        assert as_utc_datetime(device.google_session_expires_at) > datetime.now(UTC) + timedelta(days=89)
        device.google_session_expires_at = datetime.now(UTC) - timedelta(seconds=1)

    assert not db.device_has_user(device_id)
    assert db.get_device_profile(device_id) is None


def test_report_question_is_persisted(db: Database):
    reporter_id, reporter_secret = db.register_device(
        apns_token="token-reporter",
        platform="ios",
        apns_environment="production",
        language="ko",
        timezone="Asia/Seoul",
    )
    author_id, _ = db.register_device(
        apns_token="token-author",
        platform="ios",
        apns_environment="production",
        language="ko",
        timezone="Asia/Seoul",
    )
    assert db.authenticate_device(reporter_id, reporter_secret)

    created = db.create_question(
        device_id=author_id,
        topic="Security",
        difficulty_level=4,
        question="Bad public question",
        source="manual",
        is_public=True,
    )

    report = db.create_report(
        reporter_device_id=reporter_id,
        question_id=created["id"],
        reason="부적절한 질문",
        message="확인 필요",
    )
    assert report is not None
    assert report["reason"] == "부적절한 질문"
    assert report["reporterDeviceId"] == reporter_id


def test_pending_count_tracks_only_unanswered_or_unguarded_questions(db: Database):
    device_id, client_secret = db.register_device(
        apns_token="token-7",
        platform="ios",
        apns_environment="production",
        language="ko",
        timezone="Asia/Seoul",
    )
    assert db.authenticate_device(device_id, client_secret)

    db.create_question(
        device_id=device_id,
        topic="SwiftUI",
        difficulty_level=6,
        question="Pending 1",
        source="manual",
    )

    graded = db.create_question(
        device_id=device_id,
        topic="SwiftUI",
        difficulty_level=6,
        question="Graded one",
        source="manual",
    )
    db.grade_record(
        device_id=device_id,
        record_id=graded["id"],
        answer="ans",
        score=80,
        is_correct=True,
        feedback="ok",
        explanation="ok",
    )

    assert db.pending_record_count(device_id) == 1


def test_records_paging_and_clear(db: Database):
    device_id, client_secret = db.register_device(
        apns_token="token-4",
        platform="ios",
        apns_environment="production",
        language="ko",
        timezone="Asia/Seoul",
    )
    assert db.authenticate_device(device_id, client_secret)

    record_ids = []
    for idx in range(12):
        created = db.create_question(
            device_id=device_id,
            topic="Data",
            difficulty_level=3,
            question=f"Q{idx}",
            expected_answer_hint="answer",
            source="manual",
        )
        record_ids.append(created["id"])

    page, total = db.list_records(device_id, limit=5, offset=0)
    assert total == 12
    assert len(page) == 5
    assert page[0]["question"]["question"] == "Q11"

    db.delete_record(device_id, record_ids[0])
    db.delete_record(device_id, record_ids[1])
    page_after, total_after = db.list_records(device_id, limit=20, offset=0)
    assert total_after == 10
    assert all(item["status"] != "deleted" for item in page_after)

    db.clear_records(device_id)
    page_after_clear, total_after_clear = db.list_records(device_id, limit=20, offset=0)
    assert total_after_clear == 0
    all_records, all_total = db.list_records(device_id, include_deleted=True, limit=20, offset=0)
    assert all_total == 12
    assert any(item["status"] == "deleted" for item in all_records)


def test_due_schedules(db: Database):
    device_id, client_secret = db.register_device(
        apns_token="token-5",
        platform="ios",
        apns_environment="production",
        language="en",
        timezone="UTC",
    )
    assert db.authenticate_device(device_id, client_secret)

    db.upsert_schedule(
        device_id=device_id,
        topic="Distributed systems",
        difficulty_level=7,
        interval_minutes=60,
        enabled=True,
        openai_api_key_cipher=None,
        notification_sound="default",
        custom_prompt="",
        app_language="en",
        openai_model="gpt-5.4",
        max_history_count=120,
    )

    with db.connect() as session:
        row = session.query(Schedule).filter(Schedule.device_id == device_id).first()
        assert row is not None
        row.next_due_at = datetime.now(UTC) - timedelta(minutes=1)
        row.updated_at = datetime.now(UTC)

    due_rows = db.due_schedules(limit=10)
    assert len(due_rows) == 1
    assert due_rows[0]["device_id"] == device_id
    assert due_rows[0]["next_due_at"] is not None
    assert due_rows[0]["next_due_at"] <= datetime.now(UTC)


def test_transactional_context_rolls_back_on_exception(db: Database):
    now = datetime.now(UTC)

    with db.connect() as session:
        before_count = session.query(Device).count()

    with pytest.raises(ValueError):
        with db.transactional() as session:
            session.add(
                Device(
                    device_id="rollback-device",
                    client_secret_hash="hash",
                    apns_token="token-rollback",
                    platform="ios",
                    apns_environment="production",
                    language="en",
                    timezone="Asia/Seoul",
                    created_at=now,
                    updated_at=now,
                    last_seen_at=now,
                )
            )
            raise ValueError("rollback")

    with db.connect() as session:
        after_count = session.query(Device).count()

    assert after_count == before_count


def test_transactional_context_returns_value(db: Database):
    with db.connect() as session:
        before_count = session.query(Device).count()

    with db.transactional() as session:
        record = Device(
            device_id="return-value-device",
            client_secret_hash="hash",
            apns_token="token-returns",
            platform="ios",
            apns_environment="production",
            language="en",
            timezone="Asia/Seoul",
            created_at=datetime.now(UTC),
            updated_at=datetime.now(UTC),
            last_seen_at=datetime.now(UTC),
        )
        session.add(record)
        session.flush()
        result = str(record.id)

    with db.connect() as after:
        after_count = after.query(Device).count()

    assert result is not None
    assert after_count == before_count + 1


def test_transactional_context_propagates_exception(db: Database):
    with db.connect() as session:
        before_count = session.query(Device).count()

    class TransError(RuntimeError):
        pass

    with pytest.raises(TransError):
        with db.transactional() as session:
            session.add(
                Device(
                    device_id="propagated-error-device",
                    client_secret_hash="hash",
                    apns_token="token-error",
                    platform="ios",
                    apns_environment="production",
                    language="en",
                    timezone="Asia/Seoul",
                    created_at=datetime.now(UTC),
                    updated_at=datetime.now(UTC),
                    last_seen_at=datetime.now(UTC),
                )
            )
            raise TransError("propagate")

    with db.connect() as after:
        after_count = after.query(Device).count()

    assert after_count == before_count


def test_transactional_decorator_commits_with_service_db_attr(db: Database):
    class RecordService:
        def __init__(self, db_instance: Database):
            self.db = db_instance

        @transactional
        def create(self, device_id: str, secret: str, session):
            session.add(
                Device(
                    device_id=device_id,
                    client_secret_hash=secret,
                    apns_token="token-service",
                    platform="ios",
                    apns_environment="production",
                    language="en",
                    timezone="Asia/Seoul",
                    created_at=datetime.now(UTC),
                    updated_at=datetime.now(UTC),
                    last_seen_at=datetime.now(UTC),
                )
            )

    with db.connect() as session:
        before_count = session.query(Device).count()

    service = RecordService(db)
    service.create("decorator-device", "secret")

    with db.connect() as after:
        after_count = after.query(Device).count()

    assert after_count == before_count + 1


def test_transactional_decorator_rolls_back(db: Database):
    class RecordService:
        def __init__(self, db_instance: Database):
            self.db = db_instance

        @transactional
        def create_and_fail(self, device_id: str, session):
            session.add(
                Device(
                    device_id=device_id,
                    client_secret_hash="secret",
                    apns_token="token-rollback-decorator",
                    platform="ios",
                    apns_environment="production",
                    language="en",
                    timezone="Asia/Seoul",
                    created_at=datetime.now(UTC),
                    updated_at=datetime.now(UTC),
                    last_seen_at=datetime.now(UTC),
                )
            )
            raise ValueError("rollback via decorator")

    with db.connect() as session:
        before_count = session.query(Device).count()

    service = RecordService(db)
    with pytest.raises(ValueError):
        service.create_and_fail("decorator-rollback")

    with db.connect() as after:
        after_count = after.query(Device).count()

    assert after_count == before_count


def test_transactional_decorator_works_with_db_param(db: Database):
    @transactional
    def add_record(db: Database, device_id: str, session) -> None:
        session.add(
            Device(
                device_id=device_id,
                client_secret_hash="secret",
                apns_token="token-direct-db",
                platform="ios",
                apns_environment="production",
                language="en",
                timezone="Asia/Seoul",
                created_at=datetime.now(UTC),
                updated_at=datetime.now(UTC),
                last_seen_at=datetime.now(UTC),
            )
        )

    with db.connect() as before:
        before_count = before.query(Device).count()

    add_record(db, "decorator-with-db-param")

    with db.connect() as after:
        after_count = after.query(Device).count()

    assert after_count == before_count + 1


def test_transactional_decorator_uses_db_session(db: Database):
    @transactional
    def add_with_db_session(db: Database, device_id: str, db_session) -> None:
        db_session.add(
            Device(
                device_id=device_id,
                client_secret_hash="secret",
                apns_token="token-db-session",
                platform="ios",
                apns_environment="production",
                language="en",
                timezone="Asia/Seoul",
                created_at=datetime.now(UTC),
                updated_at=datetime.now(UTC),
                last_seen_at=datetime.now(UTC),
            )
        )

    with db.connect() as before:
        before_count = before.query(Device).count()

    add_with_db_session(db, "decorator-with-db-session-param")

    with db.connect() as after:
        after_count = after.query(Device).count()

    assert after_count == before_count + 1


def test_transactional_decorator_preserves_external_session(db: Database):
    used_session = {}

    @transactional
    def read_session(db: Database, session) -> None:
        used_session["session"] = session

    with db.connect() as session:
        read_session(db, session=session)

    assert used_session["session"] is session


def test_transactional_decorator_returns_result_and_uses_default_return(db: Database):
    @transactional
    def create_and_return_id(db: Database, device_id: str, session) -> str:
        record = Device(
            device_id=device_id,
            client_secret_hash="secret",
            apns_token="token-return-id",
            platform="ios",
            apns_environment="production",
            language="en",
            timezone="Asia/Seoul",
            created_at=datetime.now(UTC),
            updated_at=datetime.now(UTC),
            last_seen_at=datetime.now(UTC),
        )
        session.add(record)
        session.flush()
        return str(record.id)

    with db.connect() as before:
        before_count = before.query(Device).count()

    record_id = create_and_return_id(db, "decorator-return")
    assert record_id is not None

    with db.connect() as after:
        after_count = after.query(Device).count()

    assert after_count == before_count + 1


def test_transactional_decorator_requires_database(db):
    @transactional
    def without_db():
        return "noop"

    try:
        without_db()
    except TypeError as error:
        assert "transactional function" in str(error)
    else:
        raise AssertionError("Expected TypeError when no Database instance is available")


def test_stats_response_groups_by_topic(db: Database):
    device_id, client_secret = db.register_device(
        apns_token="token-6",
        platform="ios",
        apns_environment="production",
        language="en",
        timezone="UTC",
    )
    assert db.authenticate_device(device_id, client_secret)

    topics = [
        ("SwiftUI", 70, True),
        ("swift ui", 80, True),
        ("Kotlin", 55, True),
        ("Kotlin 101", 60, False),
    ]

    for topic, score, grade in topics:
        record = db.create_question(
            device_id=device_id,
            topic=topic,
            difficulty_level=5,
            question=f"Question about {topic}",
            expected_answer_hint="hint",
            source="manual",
        )
        db.grade_record(
            device_id=device_id,
            record_id=record["id"],
            answer="draft",
            score=score,
            is_correct=grade,
            feedback="ok",
            explanation="ok",
        )

    payload = db.stats_response(device_id=device_id, limit=20, offset=0, fallback_topic="Study")
    assert payload["totalResponses"] == 4
    assert payload["totalTopics"] == 2
    assert any(topic["topicKey"] == "swiftui" for topic in payload["topics"])
    assert any(topic["topicKey"] == "kotlin" for topic in payload["topics"])


def test_public_questions_filters_and_paginates(db: Database):
    device_a, secret_a = db.register_device(
        apns_token="token-a",
        platform="ios",
        apns_environment="production",
        language="en",
        timezone="UTC",
    )
    device_b, secret_b = db.register_device(
        apns_token="token-b",
        platform="ios",
        apns_environment="production",
        language="en",
        timezone="UTC",
    )
    assert db.authenticate_device(device_a, secret_a)
    assert db.authenticate_device(device_b, secret_b)
    db.link_google_user_to_device(
        device_id=device_a,
        google_sub="public-author-a",
        email="author-a@example.com",
        display_name="Author A",
    )
    db.link_google_user_to_device(
        device_id=device_b,
        google_sub="public-author-b",
        email="author-b@example.com",
        display_name="Author B",
    )

    hidden = db.create_question(
        device_id=device_a,
        topic="Swift",
        difficulty_level=6,
        question="Hidden Swift question",
        expected_answer_hint="swift",
        is_public=False,
    )
    public_one = db.create_question(
        device_id=device_a,
        topic="Swift",
        difficulty_level=6,
        question="Public Swift question",
        expected_answer_hint="swift",
        is_public=True,
    )
    public_two = db.create_question(
        device_id=device_b,
        topic="Python",
        difficulty_level=6,
        question="Public Python question",
        expected_answer_hint="python",
        is_public=True,
    )
    unlinked_device, _ = db.register_device(
        apns_token="token-unlinked-public",
        platform="ios",
        apns_environment="production",
        language="en",
        timezone="UTC",
    )
    unlinked_public = db.create_question(
        device_id=unlinked_device,
        topic="Swift",
        difficulty_level=6,
        question="Unlinked public question",
        expected_answer_hint="swift",
        is_public=True,
    )

    # Excluding device A should hide A's own public question.
    questions, total = db.list_public_questions(exclude_device_id=device_a, limit=20, offset=0)
    assert total == 1
    assert {item["id"] for item in questions} == {public_two["id"]}

    # Include all public questions.
    all_public, all_total = db.list_public_questions(exclude_device_id=None, limit=20, offset=0)
    assert all_total == 2
    assert {item["id"] for item in all_public} == {public_one["id"], public_two["id"]}

    # Topic filtering keeps only matching public questions.
    swift_only, swift_total = db.list_public_questions(exclude_device_id=None, topic="Swift", limit=20, offset=0)
    assert swift_total == 1
    assert swift_only[0]["id"] == public_one["id"]

    # Pagination returns next page correctly.
    page_one, page_total = db.list_public_questions(exclude_device_id=None, limit=1, offset=0)
    page_two, _ = db.list_public_questions(exclude_device_id=None, limit=1, offset=1)
    assert page_total == 2
    assert len(page_one) == 1
    assert len(page_two) == 1
    assert page_one[0]["id"] != page_two[0]["id"]
    assert unlinked_public["id"] not in {page_one[0]["id"], page_two[0]["id"]}
