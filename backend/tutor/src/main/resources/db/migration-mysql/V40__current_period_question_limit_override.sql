alter table user_monthly_question_usage
    add column current_period_question_limit_override int null
        comment 'Total question allowance override for this exact quota period'
        after system_question_count,
    add constraint chk_user_monthly_question_usage_current_period_limit
        check (current_period_question_limit_override is null or current_period_question_limit_override >= 0);
