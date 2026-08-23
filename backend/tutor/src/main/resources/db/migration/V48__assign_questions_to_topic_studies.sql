with matched as (
    select q.id as question_id, min(s.id) as topic_study_id
    from questions q
    join studies s
      on s.user_id = q.user_id
     and lower(trim(s.topic)) = lower(trim(q.topic))
    where q.study_id is not null
    group by q.id
)
update questions q
set study_id = matched.topic_study_id
from matched
where q.id = matched.question_id
  and q.study_id <> matched.topic_study_id;

update question_embeddings qe
set study_id = q.study_id
from questions q
where q.id = qe.question_id
  and q.study_id is not null
  and qe.study_id <> q.study_id;
