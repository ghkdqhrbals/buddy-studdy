-- Older iOS settings synchronization could create a real root study from the
-- localized client fallback title before the user explicitly added a study.
-- These localized titles are reserved client sentinels. Require the exact
-- generated signature and preserve every row with learning content,
-- descendants, or generation history. Do not use a created_at cutoff here:
-- client and backend rollout times differ, so a cutoff could leave a legacy
-- sentinel that was created during a delayed rollout.
create temporary table legacy_default_studies_to_remove (
    id bigint primary key
) engine=memory;

insert into legacy_default_studies_to_remove (id)
select s.id
from studies s
join users u on u.id = s.user_id
where s.parent_study_id is null
  and (
      binary s.topic = binary '내 학습'
      or binary s.topic = binary 'My Study'
      or binary s.topic = binary 'マイ学習'
  )
  and s.difficulty_level = 2
  and s.interval_minutes = 15
  and s.enabled = true
  and s.active_for_questions = true
  and s.sort_order = 0
  and s.notification_sound = 'default'
  and s.custom_prompt in (
      '짧고 명확하게 질문하세요. 사용자가 답하기 좋은 한 문제만 내세요.',
      'Ask one short, clear study question at a time. Keep it focused so the learner can answer it directly.'
  )
  and s.openai_model = 'gpt-5.4'
  and s.max_history_count = 100
  and s.next_due_at is not null
  and s.schedule_claimed_until is null
  and s.last_sent_at is null
  and s.last_error is null
  and not exists (
      select 1
      from studies child
      where child.parent_study_id = s.id
  )
  and not exists (
      select 1
      from questions q
      where q.study_id = s.id
  )
  and not exists (
      select 1
      from study_question_concepts concept
      where concept.study_id = s.id
  )
  and not exists (
      select 1
      from study_question_coverage coverage
      where coverage.study_id = s.id
  )
  and not exists (
      select 1
      from study_question_jobs job
      where job.study_id = s.id
  )
  and not exists (
      select 1
      from question_embeddings embedding
      where embedding.study_id = s.id
  )
  and not exists (
      select 1
      from question_generation_sagas saga
      where saga.study_id = s.id
         or saga.topic_id = s.id
  );

delete s
from studies s
join legacy_default_studies_to_remove legacy on legacy.id = s.id;

drop temporary table legacy_default_studies_to_remove;
