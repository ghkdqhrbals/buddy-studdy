update questions q
join (
    select q0.id as question_id, min(s.id) as topic_study_id
    from questions q0
    join studies s
      on s.user_id = q0.user_id
     and lower(trim(s.topic)) = lower(trim(q0.topic))
    where q0.study_id is not null
    group by q0.id
) matched on matched.question_id = q.id
set q.study_id = matched.topic_study_id
where q.study_id <> matched.topic_study_id;

update question_embeddings qe
join questions q on q.id = qe.question_id
set qe.study_id = q.study_id
where q.study_id is not null
  and qe.study_id <> q.study_id;
