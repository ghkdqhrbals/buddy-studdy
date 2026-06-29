create table if not exists study_question_concepts (
    id bigserial primary key,
    study_id bigint not null references studies(id) on delete cascade,
    concept_key varchar(255) not null,
    concept_name varchar(255) not null,
    display_order integer not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint uq_study_question_concepts_study_key unique (study_id, concept_key)
);

create index if not exists idx_study_question_concepts_study_order
    on study_question_concepts (study_id, display_order, id);

create table if not exists study_question_coverage (
    id bigserial primary key,
    study_id bigint not null references studies(id) on delete cascade,
    concept_id bigint not null references study_question_concepts(id) on delete cascade,
    angle_key varchar(255) not null,
    angle_name varchar(255) not null,
    asked_count bigint not null default 0,
    answer_count bigint not null default 0,
    correct_count bigint not null default 0,
    score_sum bigint not null default 0,
    last_asked_at timestamptz,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint uq_study_question_coverage_concept_angle unique (concept_id, angle_key)
);

create index if not exists idx_study_question_coverage_pick
    on study_question_coverage (study_id, asked_count, last_asked_at, id);

create index if not exists idx_study_question_coverage_study
    on study_question_coverage (study_id, concept_id);

alter table questions
    add column if not exists concept_id bigint,
    add column if not exists concept_key varchar(255),
    add column if not exists angle_key varchar(255);

create index if not exists idx_questions_study_concept_angle
    on questions (study_id, concept_id, angle_key, created_at desc);
