alter table questions
    add column grading_rubric_json longtext null after explanation,
    add column grading_assessment_json longtext null after grading_rubric_json,
    add column grading_verdict varchar(32) null after grading_assessment_json,
    add column grading_confidence double null after grading_verdict,
    add column grading_policy_version varchar(64) null after grading_confidence,
    add column grading_model varchar(128) null after grading_policy_version;
