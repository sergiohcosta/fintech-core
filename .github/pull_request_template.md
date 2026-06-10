## O que faz

<!-- Descrição em 2-3 linhas do que este PR implementa ou corrige. -->

## Issues resolvidas

Closes #

## Escopo de mudanças

- [ ] Backend
- [ ] Frontend
- [ ] Banco de dados / Migration
- [ ] Testes
- [ ] Config / Infra

## Checklist

- [ ] Testes adicionados ou atualizados
- [ ] Migrations são aditivas — sem `DROP` sem nova migration numerada
- [ ] Isolamento de tenant verificado (queries escopadas por tenant)
- [ ] Sem `any` no TypeScript — tipos explícitos ou `unknown` com narrowing
- [ ] Sem `console.log` ou logs de debug esquecidos
- [ ] Sem `Co-Authored-By` nas mensagens de commit
- [ ] Dataset atualizado se nova tabela/coluna/endpoint foi adicionado (`V10__seed_dev.sql`, `seed_base.sql` e/ou `seed-dataset.http`)

## Observações para o revisor

<!-- Algo que merece atenção especial: decisão técnica não óbvia, trade-off, área de risco. -->
