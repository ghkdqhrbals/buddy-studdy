alter table devices
    add column question_api_version int not null default 1,
    add constraint chk_device_question_api_version check (question_api_version in (1, 3));
