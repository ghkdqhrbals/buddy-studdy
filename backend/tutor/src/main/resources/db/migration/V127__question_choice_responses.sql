create table question_choice_responses (
    question_id bigint not null references questions (id) on delete cascade,
    user_id bigint not null references users (id) on delete cascade,
    option_id varchar(64) not null,
    created_at timestamp with time zone not null,
    primary key (question_id, user_id)
);
create index idx_question_choice_response_counts on question_choice_responses (question_id, option_id);
create index idx_question_choice_response_user on question_choice_responses (user_id);

insert into permissions (code, description, requires_active_account, created_at, updated_at)
values ('public-question:vote', 'Answer a public multiple-choice question', true, now(), now())
on conflict (code) do update set description = excluded.description, requires_active_account = true, updated_at = excluded.updated_at;

insert into role_permissions (role_id, permission_id, created_at, updated_at)
select role.id, permission.id, now(), now()
from roles role join permissions permission on permission.code = 'public-question:vote'
where role.code = 'REGISTERED_USER' and not exists (
    select 1 from role_permissions existing where existing.role_id = role.id and existing.permission_id = permission.id
);
