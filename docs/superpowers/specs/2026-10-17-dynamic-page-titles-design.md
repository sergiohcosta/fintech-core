# Especificação: Título Dinâmico das Páginas (Issue #211)

**Data:** 2026-10-17  
**Issue:** #211 (`feat: title de cada pagina mostrar onde estamos por exemplo NossoDinheirinho - Contas`)  
**Status:** Aprovado para Implementação  
**Escopo:** Frontend Angular 21 (Router TitleStrategy)  
**Branch Alvo:** `develop` (via worktree `.worktrees/page-titles`)

---

## 1. Contexto & Diagnóstico

Atualmente, ao navegar pelo sistema, todas as telas mantêm o título estático padrão configurado no `index.html`. 
Isso traz atritos para o usuário:
- Ao abrir múltiplas abas no navegador, não é possível distinguir qual aba contém o Dashboard, Contas, Transações ou Faturas.
- No histórico de navegação e em favoritos, todas as páginas aparecem com o mesmo nome.
- No aplicativo instalado como PWA, a barra de título não contextualiza onde o usuário está.

---

## 2. Decisão Técnica & Arquitetura

O Angular Router oferece suporte nativo a estratégias customizadas de títulos através de `TitleStrategy`:

1. **`PageTitleStrategy` (`frontend/src/app/core/services/page-title.strategy.ts`):**
   - Estende `TitleStrategy` de `@angular/router`.
   - Implementa `override updateTitle(routerState: RouterStateSnapshot): void`.
   - Obtém o título da rota ativa via `this.buildTitle(routerState)`.
   - Se houver título definido: formata como `Nosso Dinheirinho - ${title}`.
   - Se a rota não tiver título (fallback): define apenas `Nosso Dinheirinho`.
   - Usa `Title` de `@angular/platform-browser` para atualizar a tag `<title>`.

2. **Registro em `app.config.ts`:**
   - Adicionar `{ provide: TitleStrategy, useClass: PageTitleStrategy }` na lista de `providers`.

3. **Configuração de Rotas em `app.routes.ts`:**
   Adicionar a propriedade `title` em cada rota filha e de autenticação:

   | Rota | Título | Resultado no Navegador |
   |---|---|---|
   | `/login` | `Entrar` | `Nosso Dinheirinho - Entrar` |
   | `/register` | `Criar Conta` | `Nosso Dinheirinho - Criar Conta` |
   | `/accept-invite` | `Aceitar Convite` | `Nosso Dinheirinho - Aceitar Convite` |
   | `/forgot-password` | `Recuperar Senha` | `Nosso Dinheirinho - Recuperar Senha` |
   | `/reset-password` | `Redefinir Senha` | `Nosso Dinheirinho - Redefinir Senha` |
   | `/dashboard` | `Início` | `Nosso Dinheirinho - Início` |
   | `/accounts` | `Minhas Contas` | `Nosso Dinheirinho - Minhas Contas` |
   | `/accounts/new` | `Nova Conta` | `Nosso Dinheirinho - Nova Conta` |
   | `/accounts/:id` | `Editar Conta` | `Nosso Dinheirinho - Editar Conta` |
   | `/categories` | `Categorias` | `Nosso Dinheirinho - Categorias` |
   | `/categories/new` | `Nova Categoria` | `Nosso Dinheirinho - Nova Categoria` |
   | `/categories/taxonomy` | `Mapeamento de Categorias` | `Nosso Dinheirinho - Mapeamento de Categorias` |
   | `/categories/:id` | `Editar Categoria` | `Nosso Dinheirinho - Editar Categoria` |
   | `/transactions` | `Transações` | `Nosso Dinheirinho - Transações` |
   | `/transactions/timeline` | `Linha do Tempo` | `Nosso Dinheirinho - Linha do Tempo` |
   | `/transactions/new` | `Nova Transação` | `Nosso Dinheirinho - Nova Transação` |
   | `/transactions/:id` | `Editar Transação` | `Nosso Dinheirinho - Editar Transação` |
   | `/invoices` | `Faturas de Cartão` | `Nosso Dinheirinho - Faturas de Cartão` |
   | `/invoices/:id` | `Detalhe da Fatura` | `Nosso Dinheirinho - Detalhe da Fatura` |
   | `/planning` | `Planejamento Mensal` | `Nosso Dinheirinho - Planejamento Mensal` |
   | `/recurrences` | `Recorrências` | `Nosso Dinheirinho - Recorrências` |
   | `/import` | `Importar Extrato` | `Nosso Dinheirinho - Importar Extrato` |
   | `/imports` | `Histórico de Importações` | `Nosso Dinheirinho - Histórico de Importações` |
   | `/team` | `Equipe` | `Nosso Dinheirinho - Equipe` |
   | `/profile` | `Meu Perfil` | `Nosso Dinheirinho - Meu Perfil` |

---

## 3. Arquivos a Criar e Modificar

| Arquivo | Tipo | Ação |
|---|---|---|
| `frontend/src/app/core/services/page-title.strategy.ts` | Novo | Implementação da classe `PageTitleStrategy` |
| `frontend/src/app/core/services/page-title.strategy.spec.ts` | Novo | Teste unitário da estratégia de título |
| `frontend/src/app/app.routes.ts` | Modificar | Adicionar `title` nas definições de rota |
| `frontend/src/app/app.config.ts` | Modificar | Registrar `{ provide: TitleStrategy, useClass: PageTitleStrategy }` |

---

## 4. Critérios de Aceite

1. [ ] A navegação para qualquer rota atualiza o `<title>` com o prefixo `Nosso Dinheirinho - [Título da Rota]`.
2. [ ] Rotas sem `title` explicitado mantêm o fallback `Nosso Dinheirinho`.
3. [ ] Testes unitários em `page-title.strategy.spec.ts` validam a formatação com e sem título.
4. [ ] Todos os testes passam via `npm test -- --watch=false`.
