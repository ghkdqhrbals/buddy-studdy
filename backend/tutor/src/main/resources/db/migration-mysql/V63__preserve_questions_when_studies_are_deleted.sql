update questions q
left join studies s on s.id = q.study_id
set q.study_id = null
where q.study_id is not null
  and s.id is null;

alter table questions
    add constraint fk_questions_study
        foreign key (study_id) references studies(id) on delete set null;
