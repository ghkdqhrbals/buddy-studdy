alter table users
    add column if not exists avatar_mode varchar(32) not null default 'BUILDER',
    add column if not exists avatar_config text;

create table if not exists avatar_categories (
    category_key varchar(64) primary key,
    title_ko varchar(120) not null,
    title_en varchar(120) not null,
    slot varchar(64) not null,
    required boolean not null default false,
    single_select boolean not null default true,
    z_index integer not null default 0,
    sort_order integer not null default 0,
    active boolean not null default true,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table if not exists avatar_items (
    item_key varchar(96) primary key,
    category_key varchar(64) not null references avatar_categories(category_key),
    slot varchar(64) not null,
    display_name_ko varchar(120) not null,
    display_name_en varchar(120) not null,
    asset_name varchar(160) not null,
    color_hex varchar(16) not null default '#8B5CF6',
    default_grant boolean not null default false,
    compatible_bases text not null default '[]',
    z_index integer not null default 0,
    sort_order integer not null default 0,
    active boolean not null default true,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create index if not exists idx_avatar_items_category on avatar_items(category_key, sort_order);
create index if not exists idx_avatar_items_slot on avatar_items(slot);

create table if not exists user_avatar_items (
    id bigserial primary key,
    user_id bigint not null references users(id) on delete cascade,
    item_key varchar(96) not null references avatar_items(item_key),
    granted_source varchar(64) not null default 'SYSTEM',
    created_at timestamptz not null default now(),
    constraint uq_user_avatar_items_user_item unique (user_id, item_key)
);

create index if not exists idx_user_avatar_items_user on user_avatar_items(user_id);

insert into avatar_categories(category_key, title_ko, title_en, slot, required, single_select, z_index, sort_order)
values
    ('bases', '베이스', 'Base', 'base', true, true, 10, 10),
    ('backgrounds', '배경', 'Background', 'background', true, true, 0, 20),
    ('tops', '상의', 'Tops', 'top', false, true, 30, 30),
    ('bottoms', '하의', 'Bottoms', 'bottom', false, true, 25, 40),
    ('shoes', '신발', 'Shoes', 'shoes', false, true, 35, 50),
    ('hats', '모자', 'Hats', 'hat', false, true, 50, 60),
    ('items', '소품', 'Items', 'item', false, true, 60, 70)
on conflict (category_key) do update
   set title_ko = excluded.title_ko,
       title_en = excluded.title_en,
       slot = excluded.slot,
       required = excluded.required,
       single_select = excluded.single_select,
       z_index = excluded.z_index,
       sort_order = excluded.sort_order,
       active = true,
       updated_at = now();

insert into avatar_items(item_key, category_key, slot, display_name_ko, display_name_en, asset_name, color_hex, default_grant, compatible_bases, z_index, sort_order)
values
    ('base-cat', 'bases', 'base', '고양이', 'Cat', 'ProfileAvatarCatLaptop', '#20A6B8', true, '["base-cat"]', 10, 10),
    ('base-fox', 'bases', 'base', '여우', 'Fox', 'ProfileAvatarFoxScholar', '#F59E0B', true, '["base-fox"]', 10, 20),
    ('base-rabbit', 'bases', 'base', '토끼', 'Rabbit', 'ProfileAvatarRabbitPencil', '#E879F9', true, '["base-rabbit"]', 10, 30),
    ('base-dog', 'bases', 'base', '강아지', 'Dog', 'ProfileAvatarDogCorgiReader', '#A16207', true, '["base-dog"]', 10, 40),
    ('background-teal', 'backgrounds', 'background', '틸', 'Teal', 'avatar-background-teal', '#14B8A6', true, '["base-cat","base-fox","base-rabbit","base-dog"]', 0, 10),
    ('background-indigo', 'backgrounds', 'background', '인디고', 'Indigo', 'avatar-background-indigo', '#6366F1', true, '["base-cat","base-fox","base-rabbit","base-dog"]', 0, 20),
    ('background-slate', 'backgrounds', 'background', '슬레이트', 'Slate', 'avatar-background-slate', '#475569', true, '["base-cat","base-fox","base-rabbit","base-dog"]', 0, 30),
    ('top-hoodie-blue', 'tops', 'top', '파란 후디', 'Blue Hoodie', 'avatar-top-hoodie-blue', '#2563EB', true, '["base-cat","base-fox","base-rabbit","base-dog"]', 30, 10),
    ('top-varsity-green', 'tops', 'top', '초록 재킷', 'Green Varsity', 'avatar-top-varsity-green', '#16A34A', true, '["base-cat","base-fox","base-rabbit","base-dog"]', 30, 20),
    ('top-sweater-rose', 'tops', 'top', '로즈 스웨터', 'Rose Sweater', 'avatar-top-sweater-rose', '#E11D48', true, '["base-cat","base-fox","base-rabbit","base-dog"]', 30, 30),
    ('bottom-denim-pants', 'bottoms', 'bottom', '데님 바지', 'Denim Pants', 'avatar-bottom-denim-pants', '#1D4ED8', true, '["base-cat","base-fox","base-rabbit","base-dog"]', 25, 10),
    ('bottom-jogger-black', 'bottoms', 'bottom', '블랙 조거', 'Black Joggers', 'avatar-bottom-jogger-black', '#27272A', true, '["base-cat","base-fox","base-rabbit","base-dog"]', 25, 20),
    ('bottom-shorts-tan', 'bottoms', 'bottom', '탄 쇼츠', 'Tan Shorts', 'avatar-bottom-shorts-tan', '#B45309', true, '["base-cat","base-fox","base-rabbit","base-dog"]', 25, 30),
    ('shoes-white-sneakers', 'shoes', 'shoes', '흰색 스니커즈', 'White Sneakers', 'avatar-shoes-white-sneakers', '#F8FAFC', true, '["base-cat","base-fox","base-rabbit","base-dog"]', 35, 10),
    ('shoes-brown-loafers', 'shoes', 'shoes', '브라운 로퍼', 'Brown Loafers', 'avatar-shoes-brown-loafers', '#92400E', true, '["base-cat","base-fox","base-rabbit","base-dog"]', 35, 20),
    ('shoes-blue-boots', 'shoes', 'shoes', '블루 부츠', 'Blue Boots', 'avatar-shoes-blue-boots', '#1E40AF', true, '["base-cat","base-fox","base-rabbit","base-dog"]', 35, 30),
    ('hat-beanie-navy', 'hats', 'hat', '네이비 비니', 'Navy Beanie', 'avatar-hat-beanie-navy', '#1E3A8A', true, '["base-cat","base-fox","base-rabbit","base-dog"]', 50, 10),
    ('hat-cap-orange', 'hats', 'hat', '오렌지 캡', 'Orange Cap', 'avatar-hat-cap-orange', '#F97316', true, '["base-cat","base-fox","base-rabbit","base-dog"]', 50, 20),
    ('hat-grad-black', 'hats', 'hat', '졸업 모자', 'Graduation Cap', 'avatar-hat-grad-black', '#18181B', true, '["base-cat","base-fox","base-rabbit","base-dog"]', 50, 30),
    ('item-laptop', 'items', 'item', '노트북', 'Laptop', 'avatar-item-laptop', '#64748B', true, '["base-cat","base-fox","base-rabbit","base-dog"]', 60, 10),
    ('item-book', 'items', 'item', '책', 'Book', 'avatar-item-book', '#7C3AED', true, '["base-cat","base-fox","base-rabbit","base-dog"]', 60, 20),
    ('item-pencil', 'items', 'item', '연필', 'Pencil', 'avatar-item-pencil', '#FACC15', true, '["base-cat","base-fox","base-rabbit","base-dog"]', 60, 30)
on conflict (item_key) do update
   set category_key = excluded.category_key,
       slot = excluded.slot,
       display_name_ko = excluded.display_name_ko,
       display_name_en = excluded.display_name_en,
       asset_name = excluded.asset_name,
       color_hex = excluded.color_hex,
       default_grant = excluded.default_grant,
       compatible_bases = excluded.compatible_bases,
       z_index = excluded.z_index,
       sort_order = excluded.sort_order,
       active = true,
       updated_at = now();

update users
   set avatar_config = coalesce(
       avatar_config,
       '{"base":"base-cat","background":"background-teal","top":"top-hoodie-blue","bottom":"bottom-denim-pants","shoes":"shoes-white-sneakers","hat":"hat-beanie-navy","item":"item-laptop"}'
   )
 where avatar_mode = 'BUILDER';
