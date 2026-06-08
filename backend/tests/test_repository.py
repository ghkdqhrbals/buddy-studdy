from datetime import datetime, timedelta
from types import SimpleNamespace

import pytest

from app.storage.models import (
    AggregationCheckpoint,
    Device,
    QuestionComment,
    QuestionLike,
    QuestionReactionEvent,
    QuestionStats,
    Schedule,
    UserDevice,
    UTC,
    as_utc_datetime,
)
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
    principal = db.get_device_principal(device_id)
    with db.connect() as session:
        mapping = (
            session.query(UserDevice)
            .filter(UserDevice.device_id == device_id, UserDevice.user_id == principal["user_id"])
            .one()
        )
        assert mapping.last_login_at is None


def test_upsert_and_get_schedule(db: Database):
    device_id, client_secret = db.register_device(
        apns_token="token-2",
        platform="ios",
        apns_environment="production",
        language="en",
        timezone="America/New_York",
    )
    assert db.authenticate_device(device_id, client_secret)
    user_id = db.get_device_principal(device_id)["user_id"]

    first_due = db.upsert_schedule(
        device_id=device_id,
        user_id=user_id,
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
        user_id=user_id,
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
        user_id=user_id,
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

    original_schedule = db.get_schedule(device_id, user_id=user_id, topic="Swift Advanced")
    second_schedule = db.get_schedule(device_id, user_id=user_id, topic="swift architectures")
    assert original_schedule is not None
    assert second_schedule is not None
    assert original_schedule["topic"] == "Swift Advanced"
    assert second_schedule["topic"] == "Swift Architectures"


def test_topic_schedules_are_independently_deferred(db: Database):
    device_id, client_secret = db.register_device(
        apns_token="token-topic-schedules",
        platform="ios",
        apns_environment="production",
        language="en",
        timezone="UTC",
    )
    assert db.authenticate_device(device_id, client_secret)
    user_id = db.get_device_principal(device_id)["user_id"]

    swift_due = db.upsert_schedule(
        device_id=device_id,
        user_id=user_id,
        topic="Swift",
        difficulty_level=5,
        interval_minutes=15,
        enabled=True,
        openai_api_key_cipher="cipher",
        notification_sound="default",
        custom_prompt="swift",
        app_language="en",
        openai_model="gpt-5.4",
        max_history_count=100,
    )
    python_due = db.upsert_schedule(
        device_id=device_id,
        user_id=user_id,
        topic="Python",
        difficulty_level=3,
        interval_minutes=15,
        enabled=True,
        openai_api_key_cipher="cipher",
        notification_sound="default",
        custom_prompt="python",
        app_language="en",
        openai_model="gpt-5.4",
        max_history_count=100,
    )

    db.defer_schedule(device_id, minutes=30, user_id=user_id, topic="Swift")

    swift_schedule = db.get_schedule(device_id, user_id=user_id, topic="Swift")
    python_schedule = db.get_schedule(device_id, user_id=user_id, topic="Python")
    assert swift_schedule is not None
    assert python_schedule is not None
    assert swift_schedule["next_due_at"] != swift_due
    assert db._response_timestamp(python_schedule["next_due_at"]) == python_due


def test_active_user_switch_does_not_reassign_schedule_or_records(db: Database):
    device_id, client_secret = db.register_device(
        apns_token="token-user-switch",
        platform="ios",
        apns_environment="production",
        language="ko",
        timezone="Asia/Seoul",
    )
    assert db.authenticate_device(device_id, client_secret)

    profile_a, mismatch_a = db.link_email_user_to_device(
        device_id=device_id,
        email="first@example.com",
        password="secret123",
    )
    assert not mismatch_a
    assert profile_a is not None
    user_a = int(profile_a["id"])

    db.upsert_schedule(
        device_id=device_id,
        user_id=user_a,
        topic="First Account Topic",
        difficulty_level=4,
        interval_minutes=15,
        enabled=True,
        openai_api_key_cipher=None,
        notification_sound="default",
        custom_prompt="",
        app_language="ko",
        openai_model="gpt-5.4",
        max_history_count=100,
    )
    db.create_question(
        device_id=device_id,
        user_id=user_a,
        topic="First Account Topic",
        difficulty_level=4,
        question="A 계정 질문",
        expected_answer_hint=None,
        source="manual",
    )

    profile_b, mismatch_b = db.link_email_user_to_device(
        device_id=device_id,
        email="second@example.com",
        password="secret123",
    )
    assert not mismatch_b
    assert profile_b is not None
    user_b = int(profile_b["id"])
    with db.connect() as session:
        mapping_b = (
            session.query(UserDevice)
            .filter(UserDevice.device_id == device_id, UserDevice.user_id == user_b)
            .one()
        )
        assert mapping_b.last_login_at is not None

    assert db.get_schedule(device_id, user_id=user_b) is None
    records_b, total_b = db.list_records(device_id, user_id=user_b, limit=20, offset=0)
    assert records_b == []
    assert total_b == 0

    profile_a_again, mismatch_a_again = db.link_email_user_to_device(
        device_id=device_id,
        email="first@example.com",
        password="secret123",
    )
    assert not mismatch_a_again
    assert profile_a_again is not None
    assert int(profile_a_again["id"]) == user_a

    schedule_a = db.get_schedule(device_id, user_id=user_a)
    assert schedule_a is not None
    assert schedule_a["topic"] == "First Account Topic"
    records_a, total_a = db.list_records(device_id, user_id=user_a, limit=20, offset=0)
    assert total_a == 1
    assert records_a[0]["question"]["question"] == "A 계정 질문"


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
    assert total == 0
    assert questions == []

    graded = db.grade_record(
        device_id=device_id,
        record_id=created["id"],
        answer="조건부 View 구성을 선언적으로 만들 때 씁니다.",
        score=90,
        is_correct=True,
        feedback="Good",
        explanation="ViewBuilder는 여러 View 표현식을 하나의 View로 구성합니다.",
    )
    assert graded is not None

    questions, total = db.list_public_questions(exclude_device_id="", limit=10, offset=0)
    assert total == 1
    assert questions[0]["id"] == created["id"]
    assert questions[0]["author"]["displayName"] == "테스터"

    with db.connect() as session:
        device = session.query(Device).filter(Device.device_id == device_id).first()
        assert device is not None
        assert device.google_session_expires_at is not None
        assert as_utc_datetime(device.google_session_expires_at) > datetime.now(UTC) + timedelta(days=89)
        mapping = session.query(UserDevice).filter(UserDevice.device_id == device_id).first()
        assert mapping is not None
        assert mapping.session_expires_at is not None
        mappings = session.query(UserDevice).filter(UserDevice.device_id == device_id).all()
        assert mappings
        for user_device in mappings:
            user_device.session_expires_at = datetime.now(UTC) - timedelta(seconds=1)

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
    all_records_after_delete, all_total_after_delete = db.list_records(
        device_id,
        include_deleted=True,
        limit=20,
        offset=0,
    )
    assert all_total_after_delete == 10
    assert all(item["id"] not in {record_ids[0], record_ids[1]} for item in all_records_after_delete)

    db.clear_records(device_id)
    page_after_clear, total_after_clear = db.list_records(device_id, limit=20, offset=0)
    assert total_after_clear == 0
    all_records, all_total = db.list_records(device_id, include_deleted=True, limit=20, offset=0)
    assert all_total == 0
    assert all_records == []


def test_due_schedules(db: Database):
    device_id, client_secret = db.register_device(
        apns_token="token-5",
        platform="ios",
        apns_environment="production",
        language="en",
        timezone="UTC",
    )
    assert db.authenticate_device(device_id, client_secret)
    user_id = db.get_device_principal(device_id)["user_id"]

    db.upsert_schedule(
        device_id=device_id,
        user_id=user_id,
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


def test_due_schedules_use_current_device_user(db: Database):
    device_id, client_secret = db.register_device(
        apns_token="token-active-schedule",
        platform="ios",
        apns_environment="production",
        language="ko",
        timezone="Asia/Seoul",
    )
    assert db.authenticate_device(device_id, client_secret)

    profile_a, mismatch_a = db.link_email_user_to_device(
        device_id=device_id,
        email="active-a@example.com",
        password="secret123",
    )
    assert not mismatch_a
    user_a = int(profile_a["id"])
    db.upsert_schedule(
        device_id=device_id,
        user_id=user_a,
        topic="A Topic",
        difficulty_level=3,
        interval_minutes=10,
        enabled=True,
        openai_api_key_cipher=None,
        notification_sound="default",
        custom_prompt="",
        app_language="ko",
        openai_model="gpt-5.4",
        max_history_count=100,
    )

    profile_b, mismatch_b = db.link_email_user_to_device(
        device_id=device_id,
        email="active-b@example.com",
        password="secret123",
    )
    assert not mismatch_b
    user_b = int(profile_b["id"])
    db.upsert_schedule(
        device_id=device_id,
        user_id=user_b,
        topic="B Topic",
        difficulty_level=6,
        interval_minutes=10,
        enabled=True,
        openai_api_key_cipher=None,
        notification_sound="default",
        custom_prompt="",
        app_language="ko",
        openai_model="gpt-5.4",
        max_history_count=100,
    )

    with db.connect() as session:
        rows = session.query(Schedule).filter(Schedule.device_id == device_id).all()
        assert len(rows) == 2
        for row in rows:
            row.next_due_at = datetime.now(UTC) - timedelta(minutes=1)
            row.updated_at = datetime.now(UTC)

    due_rows = db.due_schedules(limit=10)
    due_for_device = [row for row in due_rows if row["device_id"] == device_id]
    assert len(due_for_device) == 1
    assert due_for_device[0]["user_id"] == user_b
    assert due_for_device[0]["topic"] == "B Topic"


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
    db.grade_record(
        device_id=device_a,
        record_id=public_one["id"],
        answer="answer",
        score=92,
        is_correct=True,
        feedback="ok",
        explanation="ok",
    )
    public_two = db.create_question(
        device_id=device_b,
        topic="Python",
        difficulty_level=6,
        question="Public Python question",
        expected_answer_hint="python",
        is_public=True,
    )
    db.grade_record(
        device_id=device_b,
        record_id=public_two["id"],
        answer="answer",
        score=91,
        is_correct=True,
        feedback="ok",
        explanation="ok",
    )
    ungraded_public = db.create_question(
        device_id=device_b,
        topic="Python",
        difficulty_level=6,
        question="Ungraded public question",
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
    assert swift_only[0]["answer"] == "answer"
    assert swift_only[0]["gradingResult"] == {
        "score": 92,
        "isCorrect": True,
        "feedback": "ok",
        "explanation": "ok",
    }
    assert swift_only[0]["answeredAt"] is not None

    # Pagination returns next page correctly.
    page_one, page_total = db.list_public_questions(exclude_device_id=None, limit=1, offset=0)
    page_two, _ = db.list_public_questions(exclude_device_id=None, limit=1, offset=1)
    assert page_total == 2
    assert len(page_one) == 1
    assert len(page_two) == 1
    assert page_one[0]["id"] != page_two[0]["id"]
    assert unlinked_public["id"] not in {page_one[0]["id"], page_two[0]["id"]}
    assert ungraded_public["id"] not in {page_one[0]["id"], page_two[0]["id"]}


def test_public_question_author_uses_question_user_not_current_device_user(db: Database):
    device_id, client_secret = db.register_device(
        apns_token="token-public-author-switch",
        platform="ios",
        apns_environment="production",
        language="en",
        timezone="UTC",
    )
    assert db.authenticate_device(device_id, client_secret)

    profile_a = db.link_google_user_to_device(
        device_id=device_id,
        google_sub="public-author-switch-a",
        email="author-switch-a@example.com",
        display_name="Original Author",
    )
    assert profile_a is not None
    public_question = db.create_question(
        device_id=device_id,
        user_id=int(profile_a["id"]),
        topic="Swift",
        difficulty_level=6,
        question="Which user authored this?",
        expected_answer_hint="Original author",
        is_public=True,
    )
    db.grade_record(
        device_id=device_id,
        record_id=public_question["id"],
        answer="Original author",
        score=90,
        is_correct=True,
        feedback="ok",
        explanation="ok",
        user_id=int(profile_a["id"]),
    )

    profile_b = db.link_google_user_to_device(
        device_id=device_id,
        google_sub="public-author-switch-b",
        email="author-switch-b@example.com",
        display_name="Current Device User",
    )
    assert profile_b is not None

    questions, total = db.list_public_questions(exclude_device_id=None, limit=20, offset=0)
    assert total == 1
    assert questions[0]["id"] == public_question["id"]
    assert questions[0]["author"]["id"] == int(profile_a["id"])
    assert questions[0]["author"]["displayName"] == "Original Author"


def test_pending_record_count_can_be_limited_per_topic(db: Database):
    device_id, client_secret = db.register_device(
        apns_token="token-topic-pending",
        platform="ios",
        apns_environment="production",
        language="en",
        timezone="UTC",
    )
    assert db.authenticate_device(device_id, client_secret)
    profile = db.link_google_user_to_device(
        device_id=device_id,
        google_sub="topic-pending",
        email="topic-pending@example.com",
        display_name="Topic Pending",
    )
    assert profile is not None
    user_id = int(profile["id"])

    for index in range(3):
        db.create_question(
            device_id=device_id,
            user_id=user_id,
            topic="Swift",
            difficulty_level=5,
            question=f"Swift pending {index}",
            expected_answer_hint="swift",
            is_public=False,
        )
    db.create_question(
        device_id=device_id,
        user_id=user_id,
        topic="Python",
        difficulty_level=5,
        question="Python pending",
        expected_answer_hint="python",
        is_public=False,
    )

    assert db.pending_record_count(device_id, user_id=user_id) == 4
    assert db.pending_record_count(device_id, user_id=user_id, topic="Swift") == 3
    assert db.pending_record_count(device_id, user_id=user_id, topic="python") == 1


def test_public_question_likes_and_comments_are_counted(db: Database):
    device_id, client_secret = db.register_device(
        apns_token="token-public-social",
        platform="ios",
        apns_environment="production",
        language="en",
        timezone="UTC",
    )
    assert db.authenticate_device(device_id, client_secret)
    author = db.link_google_user_to_device(
        device_id=device_id,
        google_sub="public-social-author",
        email="public-social-author@example.com",
        display_name="Author",
    )
    assert author is not None
    author_id = int(author["id"])
    question = db.create_question(
        device_id=device_id,
        user_id=author_id,
        topic="Swift",
        difficulty_level=7,
        question="What is actor isolation?",
        expected_answer_hint="actors",
        is_public=True,
    )
    db.grade_record(
        device_id=device_id,
        record_id=question["id"],
        answer="It protects mutable state.",
        score=95,
        is_correct=True,
        feedback="ok",
        explanation="ok",
        user_id=author_id,
    )

    viewer = db.link_google_user_to_device(
        device_id=device_id,
        google_sub="public-social-viewer",
        email="public-social-viewer@example.com",
        display_name="Viewer",
    )
    assert viewer is not None
    viewer_id = int(viewer["id"])

    like = db.set_public_question_like(question["id"], user_id=viewer_id, is_liked=True)
    assert like == {"questionId": question["id"], "likeCount": 1, "isLikedByMe": True}
    duplicate_like = db.set_public_question_like(question["id"], user_id=viewer_id, is_liked=True)
    assert duplicate_like == {"questionId": question["id"], "likeCount": 1, "isLikedByMe": True}

    created_comment = db.create_public_question_comment(question["id"], user_id=viewer_id, body=" Helpful ")
    assert created_comment is not None
    assert created_comment["body"] == "Helpful"

    assert db.aggregate_question_reaction_events() == 2
    questions, total = db.list_public_questions(
        exclude_device_id=None,
        limit=20,
        offset=0,
        viewer_user_id=viewer_id,
    )
    assert total == 1
    assert questions[0]["likeCount"] == 1
    assert questions[0]["commentCount"] == 1
    assert questions[0]["isLikedByMe"] is True

    comments = db.list_public_question_comments(question["id"], limit=20, offset=0)
    assert comments is not None
    comment_rows, comment_total = comments
    assert comment_total == 1
    assert comment_rows[0]["author"]["displayName"] == "Viewer"


def test_question_like_comment_events_sync_to_stats_by_checkpoint(db: Database):
    device_id, client_secret = db.register_device(
        apns_token="token-public-social-sync",
        platform="ios",
        apns_environment="production",
        language="en",
        timezone="UTC",
    )
    assert db.authenticate_device(device_id, client_secret)
    author = db.link_google_user_to_device(
        device_id=device_id,
        google_sub="public-social-sync-author",
        email="public-social-sync-author@example.com",
        display_name="Author",
    )
    viewer = db.link_google_user_to_device(
        device_id=device_id,
        google_sub="public-social-sync-viewer",
        email="public-social-sync-viewer@example.com",
        display_name="Viewer",
    )
    assert author is not None
    assert viewer is not None
    author_id = int(author["id"])
    viewer_id = int(viewer["id"])
    question = db.create_question(
        device_id=device_id,
        user_id=author_id,
        topic="Swift",
        difficulty_level=7,
        question="How does structured concurrency cancel child tasks?",
        expected_answer_hint="Cancellation propagates through task hierarchy.",
        is_public=True,
    )
    db.grade_record(
        device_id=device_id,
        record_id=question["id"],
        answer="Parent task cancellation propagates to child tasks.",
        score=91,
        is_correct=True,
        feedback="ok",
        explanation="ok",
        user_id=author_id,
    )
    question_id = int(question["id"])

    db.set_public_question_like(question["id"], user_id=viewer_id, is_liked=True)
    db.set_public_question_like(question["id"], user_id=viewer_id, is_liked=True)
    db.create_public_question_comment(question["id"], user_id=viewer_id, body="Clear example")
    db.set_public_question_like(question["id"], user_id=viewer_id, is_liked=False)

    with db.connect() as session:
        assert session.query(QuestionLike).filter(QuestionLike.question_id == question_id).count() == 0
        assert session.query(QuestionComment).filter(QuestionComment.question_id == question_id).count() == 1
        event_types = [
            row.event_type
            for row in session.query(QuestionReactionEvent)
            .filter(QuestionReactionEvent.question_id == question_id)
            .order_by(QuestionReactionEvent.id.asc())
            .all()
        ]
        assert event_types == ["LIKE_CREATED", "COMMENT_CREATED", "LIKE_REMOVED"]
        assert session.query(QuestionStats).filter(QuestionStats.question_id == question_id).first() is None

    assert db.aggregate_question_reaction_events(batch_size=2) == 2
    with db.connect() as session:
        checkpoint = (
            session.query(AggregationCheckpoint)
            .filter(AggregationCheckpoint.name == "question_reactions")
            .one()
        )
        stats = session.query(QuestionStats).filter(QuestionStats.question_id == question_id).one()
        assert checkpoint.last_event_id == 2
        assert stats.like_count == 1
        assert stats.comment_count == 1

    assert db.aggregate_question_reaction_events(batch_size=2) == 1
    with db.connect() as session:
        checkpoint = (
            session.query(AggregationCheckpoint)
            .filter(AggregationCheckpoint.name == "question_reactions")
            .one()
        )
        stats = session.query(QuestionStats).filter(QuestionStats.question_id == question_id).one()
        assert checkpoint.last_event_id == 3
        assert stats.like_count == 0
        assert stats.comment_count == 1

    assert db.aggregate_question_reaction_events(batch_size=2) == 0
    db._public_questions_cache.clear()
    questions, total = db.list_public_questions(
        exclude_device_id=None,
        limit=20,
        offset=0,
        viewer_user_id=viewer_id,
    )
    assert total == 1
    assert questions[0]["likeCount"] == 0
    assert questions[0]["commentCount"] == 1
    assert questions[0]["isLikedByMe"] is False


def test_public_question_reactions_can_skip_db_event_log_for_stream_mode(db: Database):
    device_id, client_secret = db.register_device(
        apns_token="token-public-social-stream-mode",
        platform="ios",
        apns_environment="production",
        language="en",
        timezone="UTC",
    )
    assert db.authenticate_device(device_id, client_secret)
    author = db.link_google_user_to_device(
        device_id=device_id,
        google_sub="public-social-stream-mode-author",
        email="public-social-stream-mode-author@example.com",
        display_name="Author",
    )
    viewer = db.link_google_user_to_device(
        device_id=device_id,
        google_sub="public-social-stream-mode-viewer",
        email="public-social-stream-mode-viewer@example.com",
        display_name="Viewer",
    )
    assert author is not None
    assert viewer is not None
    question = db.create_question(
        device_id=device_id,
        user_id=int(author["id"]),
        topic="Swift",
        difficulty_level=7,
        question="What is AsyncSequence?",
        expected_answer_hint="An asynchronous sequence of values.",
        is_public=True,
    )
    db.grade_record(
        device_id=device_id,
        record_id=question["id"],
        answer="It yields values over time asynchronously.",
        score=92,
        is_correct=True,
        feedback="ok",
        explanation="ok",
        user_id=int(author["id"]),
    )
    question_id = int(question["id"])

    db.set_public_question_like(
        question["id"],
        user_id=int(viewer["id"]),
        is_liked=True,
        emit_reaction_event=False,
    )
    db.create_public_question_comment(
        question["id"],
        user_id=int(viewer["id"]),
        body="Nice",
        emit_reaction_event=False,
    )

    with db.connect() as session:
        assert session.query(QuestionLike).filter(QuestionLike.question_id == question_id).count() == 1
        assert session.query(QuestionComment).filter(QuestionComment.question_id == question_id).count() == 1
        assert session.query(QuestionReactionEvent).filter(QuestionReactionEvent.question_id == question_id).count() == 0

    assert db.reconcile_question_stats(question_ids=[question_id]) == 1
    db._public_questions_cache.clear()
    questions, _ = db.list_public_questions(exclude_device_id=None, limit=20, offset=0)
    assert questions[0]["likeCount"] == 1
    assert questions[0]["commentCount"] == 1

    record = db.get_record(device_id, question["id"], user_id=int(author["id"]))
    assert record is not None
    assert record["likeCount"] == 1
    assert record["commentCount"] == 1


def test_public_question_view_count_is_cached_in_question_stats(db: Database):
    device_id, client_secret = db.register_device(
        apns_token="token-public-view-count",
        platform="ios",
        apns_environment="production",
        language="en",
        timezone="UTC",
    )
    assert db.authenticate_device(device_id, client_secret)
    author = db.link_google_user_to_device(
        device_id=device_id,
        google_sub="public-view-count-author",
        email="public-view-count-author@example.com",
        display_name="Author",
    )
    assert author is not None
    question = db.create_question(
        device_id=device_id,
        user_id=int(author["id"]),
        topic="Swift",
        difficulty_level=7,
        question="What is actor reentrancy?",
        expected_answer_hint="Actors may suspend and later process another message.",
        is_public=True,
    )
    db.grade_record(
        device_id=device_id,
        record_id=question["id"],
        answer="An actor can process another message while awaiting.",
        score=90,
        is_correct=True,
        feedback="ok",
        explanation="ok",
        user_id=int(author["id"]),
    )

    detail = db.get_public_question(question["id"])
    assert detail is not None
    assert detail["viewCount"] == 0

    db.increment_question_view_count(question["id"], 3)
    db.increment_question_view_count(question["id"], 2)

    detail = db.get_public_question(question["id"])
    assert detail is not None
    assert detail["viewCount"] == 5
    questions, total = db.list_public_questions(exclude_device_id=None, limit=20, offset=0)
    assert total == 1
    assert questions[0]["viewCount"] == 5

    with db.connect() as session:
        stats = session.query(QuestionStats).filter(QuestionStats.question_id == int(question["id"])).one()
        assert stats.view_count == 5

    record = db.get_record(device_id, question["id"], user_id=int(author["id"]))
    assert record is not None
    assert record["viewCount"] == 5
    assert record["commentCount"] == 0
    assert record["likeCount"] == 0

    records, records_total = db.list_records(device_id, limit=20, offset=0, user_id=int(author["id"]))
    assert records_total == 1
    assert records[0]["viewCount"] == 5
    assert records[0]["commentCount"] == 0
    assert records[0]["likeCount"] == 0


def test_public_question_list_uses_cached_counts_but_merges_viewer_like_state(db: Database):
    device_id, client_secret = db.register_device(
        apns_token="token-public-social-cache",
        platform="ios",
        apns_environment="production",
        language="en",
        timezone="UTC",
    )
    assert db.authenticate_device(device_id, client_secret)
    author = db.link_google_user_to_device(
        device_id=device_id,
        google_sub="public-social-cache-author",
        email="public-social-cache-author@example.com",
        display_name="Author",
    )
    assert author is not None
    author_id = int(author["id"])
    question = db.create_question(
        device_id=device_id,
        user_id=author_id,
        topic="Swift",
        difficulty_level=7,
        question="What is Sendable?",
        expected_answer_hint="concurrency",
        is_public=True,
    )
    db.grade_record(
        device_id=device_id,
        record_id=question["id"],
        answer="It marks concurrency-safe values.",
        score=90,
        is_correct=True,
        feedback="ok",
        explanation="ok",
        user_id=author_id,
    )

    viewer = db.link_google_user_to_device(
        device_id=device_id,
        google_sub="public-social-cache-viewer",
        email="public-social-cache-viewer@example.com",
        display_name="Viewer",
    )
    assert viewer is not None
    viewer_id = int(viewer["id"])

    questions, total = db.list_public_questions(exclude_device_id=None, limit=20, offset=0, viewer_user_id=viewer_id)
    assert total == 1
    assert questions[0]["likeCount"] == 0
    assert questions[0]["isLikedByMe"] is False

    db.set_public_question_like(question["id"], user_id=viewer_id, is_liked=True)
    cached_questions, cached_total = db.list_public_questions(
        exclude_device_id=None,
        limit=20,
        offset=0,
        viewer_user_id=viewer_id,
    )
    assert cached_total == 1
    assert cached_questions[0]["likeCount"] == 0
    assert cached_questions[0]["isLikedByMe"] is True

    assert db.aggregate_question_reaction_events() == 1
    db._public_questions_cache.clear()
    fresh_questions, fresh_total = db.list_public_questions(
        exclude_device_id=None,
        limit=20,
        offset=0,
        viewer_user_id=viewer_id,
    )
    assert fresh_total == 1
    assert fresh_questions[0]["likeCount"] == 1
    assert fresh_questions[0]["isLikedByMe"] is True


def test_question_stats_checkpoint_and_reconcile_repair_counts(db: Database):
    device_id, client_secret = db.register_device(
        apns_token="token-public-social-reconcile",
        platform="ios",
        apns_environment="production",
        language="en",
        timezone="UTC",
    )
    assert db.authenticate_device(device_id, client_secret)
    author = db.link_google_user_to_device(
        device_id=device_id,
        google_sub="public-social-reconcile-author",
        email="public-social-reconcile-author@example.com",
        display_name="Author",
    )
    viewer = db.link_google_user_to_device(
        device_id=device_id,
        google_sub="public-social-reconcile-viewer",
        email="public-social-reconcile-viewer@example.com",
        display_name="Viewer",
    )
    assert author is not None
    assert viewer is not None
    author_id = int(author["id"])
    viewer_id = int(viewer["id"])
    question = db.create_question(
        device_id=device_id,
        user_id=author_id,
        topic="Swift",
        difficulty_level=7,
        question="What is MainActor?",
        expected_answer_hint="main thread isolation",
        is_public=True,
    )
    db.grade_record(
        device_id=device_id,
        record_id=question["id"],
        answer="It isolates work to the main actor.",
        score=93,
        is_correct=True,
        feedback="ok",
        explanation="ok",
        user_id=author_id,
    )

    db.set_public_question_like(question["id"], user_id=viewer_id, is_liked=True)
    db.create_public_question_comment(question["id"], user_id=viewer_id, body="Useful")
    assert db.aggregate_question_reaction_events(batch_size=1) == 1
    assert db.aggregate_question_reaction_events(batch_size=10) == 1

    questions, _ = db.list_public_questions(exclude_device_id=None, limit=20, offset=0)
    assert questions[0]["likeCount"] == 1
    assert questions[0]["commentCount"] == 1

    with db.connect() as session:
        stats = session.query(QuestionStats).filter(QuestionStats.question_id == int(question["id"])).first()
        assert stats is not None
        stats.like_count = 99
        stats.comment_count = 99

    assert db.reconcile_question_stats(question_ids=[int(question["id"])]) == 1
    db._public_questions_cache.clear()
    repaired_questions, _ = db.list_public_questions(exclude_device_id=None, limit=20, offset=0)
    assert repaired_questions[0]["likeCount"] == 1
    assert repaired_questions[0]["commentCount"] == 1
