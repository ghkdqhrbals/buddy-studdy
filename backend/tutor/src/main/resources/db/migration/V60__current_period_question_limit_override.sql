alter table user_monthly_question_usage
    add column current_period_question_limit_override integer null
        check (current_period_question_limit_override is null or current_period_question_limit_override >= 0);
