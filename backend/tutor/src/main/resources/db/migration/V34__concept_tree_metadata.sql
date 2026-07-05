alter table study_question_concepts
    add column if not exists parent_concept_id bigint,
    add column if not exists depth integer,
    add column if not exists path varchar(1024),
    add column if not exists concept_path varchar(2048),
    add column if not exists leaf boolean;

update study_question_concepts
set depth = 0
where depth is null;

update study_question_concepts
set path = concept_key
where path is null or path = '';

update study_question_concepts
set concept_path = concept_name
where concept_path is null or concept_path = '';

update study_question_concepts
set leaf = true
where leaf is null;

alter table study_question_concepts
    alter column depth set not null,
    alter column path set not null,
    alter column concept_path set not null,
    alter column leaf set not null;

alter table study_question_concepts
    add constraint fk_study_question_concepts_parent
    foreign key (parent_concept_id) references study_question_concepts(id) on delete cascade;

alter table study_question_concepts
    drop constraint if exists uq_study_question_concepts_study_key;

alter table study_question_concepts
    add constraint uq_study_question_concepts_study_path unique (study_id, path);

create index if not exists idx_study_question_concepts_study_tree_order
    on study_question_concepts (study_id, parent_concept_id, display_order, id);

create index if not exists idx_study_question_concepts_study_path
    on study_question_concepts (study_id, path);
