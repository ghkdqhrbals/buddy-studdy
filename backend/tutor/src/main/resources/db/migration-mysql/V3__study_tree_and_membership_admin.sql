alter table studies
    add column parent_study_id bigint null after user_id,
    add column sort_order int not null default 0 after parent_study_id,
    add constraint fk_studies_parent
        foreign key (parent_study_id) references studies(id) on delete cascade,
    add index idx_studies_user_parent_order (user_id, parent_study_id, sort_order, id);

alter table studies
    drop index uq_studies_user_topic,
    add index idx_studies_user_topic (user_id, topic);

alter table user_memberships
    add column monthly_question_limit_override int null after tier,
    add constraint chk_user_memberships_monthly_limit_override
        check (monthly_question_limit_override is null or monthly_question_limit_override >= 0);

alter table user_memberships
    add constraint fk_user_memberships_tier
        foreign key (tier) references user_membership_tiers(tier_code);
