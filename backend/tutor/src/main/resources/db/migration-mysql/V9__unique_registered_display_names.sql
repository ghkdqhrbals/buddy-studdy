update users duplicate_user
join (
    select ranked.id
    from (
        select
            id,
            row_number() over (
                partition by lower(trim(display_name))
                order by id
            ) as duplicate_rank
        from users
        where provider <> 'ANONYMOUS'
    ) ranked
    where ranked.duplicate_rank > 1
) duplicates on duplicates.id = duplicate_user.id
set duplicate_user.display_name = concat(
    'Migrated-User-',
    duplicate_user.id,
    '-',
    substring(replace(uuid(), '-', ''), 1, 12)
);

alter table users
    add column display_name_key varchar(120)
        generated always as (
            case
                when provider <> 'ANONYMOUS' then lower(trim(display_name))
                else null
            end
        ) stored,
    add constraint uq_users_display_name_key unique (display_name_key);
