alter table studies
    add column question_format_preference varchar(32) not null default 'MIXED',
    add constraint chk_study_question_format_preference check (question_format_preference in ('FREE_TEXT', 'MULTIPLE_CHOICE', 'MIXED'));

alter table questions
    add column question_format varchar(32) not null default 'FREE_TEXT',
    add column choice_options_json longtext null,
    add column correct_option_id varchar(64) null,
    add column choice_explanation text null,
    add column selected_option_id varchar(64) null,
    add constraint chk_question_format check (question_format in ('FREE_TEXT', 'MULTIPLE_CHOICE')),
    add constraint chk_multiple_choice_shape check (
        (question_format = 'FREE_TEXT' and choice_options_json is null and correct_option_id is null and choice_explanation is null and selected_option_id is null)
        or (question_format = 'MULTIPLE_CHOICE' and record_type = 'QUESTION' and choice_options_json is not null and correct_option_id is not null and choice_explanation is not null and source not in ('follow_up', 'custom_question'))
    );

alter table question_generation_sagas
    add column question_format varchar(32) not null default 'FREE_TEXT',
    add constraint chk_generation_question_format check (question_format in ('FREE_TEXT', 'MULTIPLE_CHOICE'));
