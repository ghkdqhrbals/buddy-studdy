alter table feedbacks
    add column status varchar(32) not null default 'NEW' after content,
    add column reviewed_at datetime(6) null after status,
    add column replied_at datetime(6) null after reviewed_at,
    add index idx_feedbacks_status_created (status, created_at, id);
