alter table users
    add column if not exists openai_api_key_cipher text;