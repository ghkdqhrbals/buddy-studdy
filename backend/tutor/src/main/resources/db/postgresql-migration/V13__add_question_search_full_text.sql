alter table question_search
    add column if not exists search_vector tsvector generated always as (
        setweight(to_tsvector('simple', coalesce(topic, '')), 'A') ||
        setweight(to_tsvector('simple', coalesce(question, '')), 'A') ||
        setweight(to_tsvector('simple', coalesce(answer, '')), 'B') ||
        setweight(to_tsvector('simple', coalesce(feedback, '')), 'C') ||
        setweight(to_tsvector('simple', coalesce(explanation, '')), 'C') ||
        setweight(to_tsvector('simple', coalesce(author_display_name, '')), 'B')
    ) stored;

create index if not exists idx_question_search_vector on question_search using gin (search_vector);
