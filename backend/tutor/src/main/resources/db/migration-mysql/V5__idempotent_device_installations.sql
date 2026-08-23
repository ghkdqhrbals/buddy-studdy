alter table devices
    add column installation_key_hash varchar(64) null after device_id,
    add constraint uq_devices_installation_key_hash unique (installation_key_hash);

delete u
from users u
where u.status = 'ANONYMOUS'
  and not exists (select 1 from devices d where d.user_id = u.id)
  and not exists (select 1 from user_devices ud where ud.user_id = u.id)
  and not exists (select 1 from studies s where s.user_id = u.id)
  and not exists (select 1 from questions q where q.user_id = u.id)
  and not exists (select 1 from question_likes ql where ql.user_id = u.id)
  and not exists (select 1 from question_comments qc where qc.user_id = u.id)
  and not exists (select 1 from question_embeddings qe where qe.user_id = u.id)
  and not exists (select 1 from study_question_jobs sj where sj.user_id = u.id)
  and not exists (select 1 from question_push_outbox qp where qp.user_id = u.id)
  and not exists (select 1 from app_notifications n where n.user_id = u.id or n.actor_user_id = u.id)
  and not exists (select 1 from reports r where r.reporter_user_id = u.id)
  and not exists (select 1 from user_roles ur where ur.user_id = u.id)
  and not exists (select 1 from user_term_agreements uta where uta.user_id = u.id)
  and not exists (select 1 from notification_preferences np where np.user_id = u.id)
  and not exists (select 1 from user_memberships um where um.user_id = u.id)
  and not exists (select 1 from user_monthly_question_usage uq where uq.user_id = u.id)
  and not exists (select 1 from user_stats us where us.user_id = u.id)
  and not exists (select 1 from user_stats_dirty_keys usd where usd.user_id = u.id)
  and not exists (select 1 from user_avatar_items uai where uai.user_id = u.id);
