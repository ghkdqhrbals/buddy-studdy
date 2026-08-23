alter table user_monthly_question_usage
    add column period_start datetime(6) null after usage_month;

update user_monthly_question_usage
set period_start = str_to_date(concat(usage_month, '-01 00:00:00.000000'), '%Y-%m-%d %H:%i:%s.%f');

update user_monthly_question_usage usage_row
join users user_row on user_row.id = usage_row.user_id
set usage_row.period_start = timestampadd(
    month,
    (
        (year(utc_timestamp(6)) - year(user_row.created_at)) * 12
        + month(utc_timestamp(6)) - month(user_row.created_at)
    ) - if(
        timestampadd(
            month,
            (year(utc_timestamp(6)) - year(user_row.created_at)) * 12
                + month(utc_timestamp(6)) - month(user_row.created_at),
            user_row.created_at
        ) > utc_timestamp(6),
        1,
        0
    ),
    user_row.created_at
)
where usage_row.usage_month = date_format(utc_timestamp(6), '%Y-%m');

alter table user_monthly_question_usage
    modify column period_start datetime(6) not null,
    drop index uq_user_monthly_question_usage_user_month,
    add constraint uq_user_monthly_question_usage_user_period unique (user_id, period_start),
    add index idx_user_monthly_question_usage_period_start (period_start);
