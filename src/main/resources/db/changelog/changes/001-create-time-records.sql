--liquibase formatted sql

-- Создание таблицы для хранения временных меток
create table time_records
(
    id          bigint generated always as identity,
    recorded_at timestamp not null,
    constraint time_records_pkey primary key (id)
);

--rollback drop table time_records;
