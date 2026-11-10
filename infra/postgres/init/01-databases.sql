-- 由 docker-entrypoint-initdb.d 在首次初始化时执行
--   ygo     : 业务表（card 卡面事实 / combo_record 实战连招录入）
--   ygo_rag : LightRAG 的 KV / 向量 / 文档状态表

\set ON_ERROR_STOP on

select 'create database ygo_rag'
where not exists (select 1 from pg_database where datname = 'ygo_rag')\gexec

\c ygo
create extension if not exists vector;

\c ygo_rag
create extension if not exists vector;
