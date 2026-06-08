alter table users
    add column if not exists openai_api_key_cipher text;

alter table users
    add column if not exists openai_model varchar(64) not null default 'gpt-5.4';

update users
set openai_model = 'gpt-5.4'
where openai_model is null or openai_model = '';
