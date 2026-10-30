-- 由 docker-entrypoint-initdb.d 在首次初始化时执行
-- 目的：建立两个 database，并各自启用 pgvector
--   ygo     : 决策服务业务表（match / decision / cards）
--   ygo_rag : LightRAG 的 KV / 向量 / 文档状态表

\set ON_ERROR_STOP on

select 'create database ygo_rag'
where not exists (select 1 from pg_database where datname = 'ygo_rag')\gexec

\c ygo
create extension if not exists vector;

\c ygo_rag
create extension if not exists vector;
