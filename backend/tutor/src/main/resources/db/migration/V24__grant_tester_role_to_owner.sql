insert into user_roles (user_id, role_id, created_at, updated_at)
select u.id, r.id, now(), now()
from users u
join roles r on r.code = 'TESTER'
where lower(u.email) = 'ghkdqhrbals@gmail.com'
  and not exists (
      select 1
      from user_roles ur
      where ur.user_id = u.id
        and ur.role_id = r.id
  );
