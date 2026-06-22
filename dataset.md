### Dataset de Testes — Família Costa

O projeto mantém um dataset realista (`V13__seed_dev.sql` + `V16__seed_dev_budget.sql`) que deve ser tratado como **artefato vivo** — parte da especificação do sistema. Mantê-lo desatualizado equivale a ter documentação errada.

**Regra inviolável:** toda alteração que envolva banco de dados **deve** atualizar o dataset de testes para contemplar as mudanças realizadas. Não existe "vou atualizar depois" — a atualização faz parte da entrega, não é opcional.

**Artefatos:**
- `backend/src/main/resources/db/seed/V13__seed_dev.sql` — seed Flyway, perfil `dev` (dados gerais: contas, categorias, transações, faturas)
- `backend/src/main/resources/db/seed/V16__seed_dev_budget.sql` — seed Flyway, perfil `dev` (planejamento mensal: budget cycles/items)
- `backend/src/test/resources/sql/seed_base.sql` / `cleanup.sql` — fixture para Testcontainers
- `docs/http/seed-dataset.http` — HTTP collection IntelliJ/VS Code
- Spec completa: `docs/superpowers/specs/2026-06-09-test-dataset-design.md`

**Regra para SDD e TDD — ao implementar qualquer feature:**

| Situação | Ação obrigatória |
|----------|-----------------|
| Nova tabela de negócio adicionada | Inserir dados representativos no seed (`V13`, ou `V16` se for de planejamento) |
| Nova coluna relevante em tabela existente | Atualizar os INSERTs do seed correspondente |
| Nova coluna adicionada por migration | Atualizar os INSERTs existentes do seed para incluir o novo campo |
| Nova entidade necessária para setup mínimo de testes | Atualizar `seed_base.sql` |
| Novo endpoint ou novo parâmetro de endpoint | Adicionar request em `docs/http/seed-dataset.http` |
| Feature puramente de frontend / refatoração sem schema | Nenhuma atualização necessária |

**Ao atualizar o seed:** manter o padrão de UUIDs predefinidos. Novas entidades recebem UUIDs na série correspondente (ver spec). Nunca usar `gen_random_uuid()` para entidades que precisam de cross-reference.

**Atenção — posição dos arquivos seed:** cada seed precisa ter versão maior que as migrations das tabelas que ele popula, para rodar depois delas. Hoje há dois seeds: `V13` (dados gerais, após o schema base) e `V16` (planejamento, após `V14`/`V15`). Ao popular uma tabela criada numa nova migration, coloque o INSERT no seed de versão posterior a ela (renomeando ou criando um novo seed se necessário).

**Credenciais:**
- Dev (banco com seed): `carlos@costa.com` / `costa123`
- Testes de integração (`seed_base.sql`): `admin@test.com` / `admin123`
