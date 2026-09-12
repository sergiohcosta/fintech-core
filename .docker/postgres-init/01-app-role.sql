-- Role de runtime da aplicação, SEM superuser (#116, ADR-006).
--
-- Postgres: SUPERUSER bypassa Row-Level Security incondicionalmente, mesmo com
-- FORCE ROW LEVEL SECURITY (comportamento hardcoded, não configurável). O usuário
-- POSTGRES_USER do docker-compose (admin) é o bootstrap user da imagem oficial postgres —
-- e é superuser por padrão. Rodar a aplicação com esse mesmo usuário torna qualquer policy
-- RLS inútil localmente. Este script cria um role separado, comum (sem SUPERUSER/BYPASSRLS),
-- que passa a ser o usuário de runtime; "admin" continua só para migrations (dono das tabelas).
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'fintech_app') THEN
        CREATE ROLE fintech_app LOGIN PASSWORD 'fintech_app_secret';
    END IF;
END
$$;

GRANT USAGE ON SCHEMA public TO fintech_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO fintech_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO fintech_app;

-- Migrations futuras (rodadas como admin, dono) criam tabelas novas — sem isso, fintech_app
-- não enxergaria nenhuma tabela adicionada depois deste script.
ALTER DEFAULT PRIVILEGES FOR ROLE admin IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO fintech_app;
ALTER DEFAULT PRIVILEGES FOR ROLE admin IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO fintech_app;
