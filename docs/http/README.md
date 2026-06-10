# HTTP Collection — Dataset Família Costa

Arquivo: `seed-dataset.http`

Compatível com **IntelliJ HTTP Client** e **VS Code REST Client** (extensão REST Client).

## Pré-requisitos

- Backend rodando em `http://localhost:8080`
- Banco de dados limpo (sem dados prévios)

## Ordem de execução

Execute os 9 blocos na sequência. Variáveis são capturadas automaticamente via `client.global.set`.

| Bloco | Descrição | Requests |
|-------|-----------|----------|
| 1 | Auth (register + login) | 2 |
| 2 | Contas (5 contas) | 5 |
| 3 | Categorias (33 categorias + archive) | 34 |
| 4 | Membros (convites + aceites) | 5 |
| 5 | Transações recorrentes + cenários especiais | 18+ |
| 6 | Parcelamentos (2 grupos) | 2 |
| 7 | Transferências (6 meses) | 6 |
| 8 | Ciclo de faturas (pay + close) | 10 |
| 9 | Verificações (GETs) | 6 |

## Alternativa: SQL seed

Para dev com reset completo, use `V10__seed_dev.sql` via Flyway:

```bash
# Reset banco
docker exec -it $(docker ps -qf "name=postgres") psql -U admin -d postgres -c "DROP DATABASE fintech; CREATE DATABASE fintech;"
# Subir app (aplica V10 automaticamente)
cd backend && SPRING_PROFILES_ACTIVE=dev ./mvnw spring-boot:run
```

Credenciais: `carlos@costa.com` / `costa123`
