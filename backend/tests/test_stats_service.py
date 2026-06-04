from app.services.stats_service import TopicStatisticsService


def test_topic_key_normalizes_case_spacing_and_numbers():
    assert TopicStatisticsService.to_topic_key("SwiftUI", "Study") == "swiftui"
    assert TopicStatisticsService.to_topic_key("swift_ui", "Study") == "swiftui"
    assert TopicStatisticsService.to_topic_key("Kotlin 101", "Study") == "kotlin101"


def test_topic_statistics_aggregates_records():
    records = [
        {
            "topic": "SwiftUI",
            "difficulty": 6,
            "gradingResult": {"score": 90, "isCorrect": True},
            "answeredAt": "2026-06-01T10:00:00Z",
            "question": {"createdAt": "2026-06-01T10:00:00Z"},
        },
        {
            "topic": "swift ui",
            "difficulty": 4,
            "gradingResult": {"score": 70, "isCorrect": True},
            "answeredAt": "2026-06-01T11:00:00Z",
            "question": {"createdAt": "2026-06-01T11:00:00Z"},
        },
    ]

    topic_key = TopicStatisticsService.to_topic_key("Swift UI", "Study")
    stat = TopicStatisticsService.topic_stat(topic_key, records, fallback_topic="Study")

    assert stat is not None
    assert stat["topicKey"] == topic_key
    assert stat["count"] == 2
    assert stat["average"] == 80
    assert stat["correctRate"] == 100
    assert set(stat["topicAliases"]) == {"SwiftUI", "swift ui"}


def test_level_range_is_bounded_and_ordered():
    level_range = TopicStatisticsService.make_level_range(
        center_level=9.8,
        average=95,
        sample_count=5,
        half_width=0.6,
    )
    assert 9 <= level_range["level"] <= 10
    assert level_range["lowerBound"] < level_range["upperBound"]
    assert level_range["upperBound"] <= 1


def test_topic_sorting_prefers_recent_when_requested():
    now = "2026-06-02T00:00:00+00:00"
    payload = [
        {
            "topic": "A",
            "centerLevel": 4,
            "count": 1,
            "latestAt": now,
            "topic_key": "a",
            "topicAliases": ["A"],
            "topicKey": "a",
            "average": 80,
            "best": 90,
            "correctRate": 50,
            "levelRange": {"centerLevel": 4.0},
            "records": [],
        },
        {
            "topic": "B",
            "centerLevel": 5,
            "count": 2,
            "latestAt": ("2026-06-01T00:00:00+00:00"),
            "topic_key": "b",
            "topicAliases": ["B"],
            "topicKey": "b",
            "average": 70,
            "best": 80,
            "correctRate": 60,
            "levelRange": {"centerLevel": 5.0},
            "records": [],
        },
    ]

    sorted_payload = sorted(payload, key=TopicStatisticsService.topic_sort_key("recent"))
    assert sorted_payload[0]["topic"] == "A"
