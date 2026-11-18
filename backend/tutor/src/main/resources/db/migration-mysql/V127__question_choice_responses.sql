create table question_choice_responses (
    question_id bigint not null,
    user_id bigint not null,
    option_id varchar(64) collate utf8mb4_bin not null,
    created_at datetime(6) not null,
    primary key (question_id, user_id),
    index idx_question_choice_response_counts (question_id, option_id),
    index idx_question_choice_response_user (user_id),
    constraint fk_question_choice_response_question foreign key (question_id) references questions (id) on delete cascade,
    constraint fk_question_choice_response_user foreign key (user_id) references users (id) on delete cascade
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci
comment='Immutable single-choice participation, including the question owner';

insert into permissions (code, description, requires_active_account, created_at, updated_at)
values ('public-question:vote', 'Answer a public multiple-choice question', true, utc_timestamp(6), utc_timestamp(6))
on duplicate key update description = values(description), requires_active_account = true, updated_at = values(updated_at);

insert ignore into role_permissions (role_id, permission_id, created_at, updated_at)
select role.id, permission.id, utc_timestamp(6), utc_timestamp(6)
from roles role join permissions permission on permission.code = 'public-question:vote'
where role.code = 'REGISTERED_USER';
