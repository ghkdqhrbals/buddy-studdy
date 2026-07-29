alter table question_localizations
    add column request_token varchar(36) null after translation_version;

alter table answer_localizations
    add column request_token varchar(36) null after translation_version;

alter table grading_localizations
    add column request_token varchar(36) null after translation_version;

alter table question_comment_localizations
    add column request_token varchar(36) null after translation_version;
