-- Older settings requests created a real "SwiftUI" study when no topic was supplied.
-- Remove only untouched, empty rows that exactly match that generated default.
delete from study_question_jobs
where study_id in (
    select s.id
    from studies s
    where s.topic = 'SwiftUI'
      and s.difficulty_level = 5
      and s.interval_minutes = 15
      and s.custom_prompt = ''
      and s.openai_model = 'gpt-5.4'
      and s.max_history_count = 100
      and s.last_sent_at is null
      and s.last_error is null
      and not exists (select 1 from questions q where q.study_id = s.id)
);

delete from studies s
where s.topic = 'SwiftUI'
  and s.difficulty_level = 5
  and s.interval_minutes = 15
  and s.custom_prompt = ''
  and s.openai_model = 'gpt-5.4'
  and s.max_history_count = 100
  and s.last_sent_at is null
  and s.last_error is null
  and not exists (select 1 from questions q where q.study_id = s.id);
