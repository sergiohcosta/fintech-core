-- V11__category_taxonomy_code.sql
ALTER TABLE categories
    ADD COLUMN taxonomy_code VARCHAR(50) NULL;

COMMENT ON COLUMN categories.taxonomy_code IS
    'Código semântico estável para benchmarking cross-tenant. NULL = categoria criada pelo usuário.';
