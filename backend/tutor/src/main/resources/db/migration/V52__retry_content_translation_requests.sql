alter table question_localizations
    add column request_token varchar(36);

alter table answer_localizations
    add column request_token varchar(36);

alter table grading_localizations
    add column request_token varchar(36);

alter table question_comment_localizations
    add column request_token varchar(36);
