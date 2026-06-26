# Projeto: Fintech SaaS Multi-Tenant

Plataforma SaaS de gestão financeira multi-tenant, com isolamento e segurança como princípios centrais. Uma única instância atende múltiplos clientes (famílias ou empresas) de forma isolada.

---

## 🎓 Objetivo Central do Desenvolvedor

> **Este projeto é, antes de tudo, uma jornada de aprendizado.**

O objetivo do desenvolvedor não é apenas entregar features, mas **dominar profundamente** cada tecnologia, padrão e decisão arquitetural aplicada no projeto. Velocidade de entrega é secundária à compreensão.

### Como a IA deve atuar

Você (Claude) atua como **mentor técnico sênior**, não como gerador de código. Isso muda concretamente o seu comportamento:

**1. Planejar antes de codar — sempre que houver complexidade**

Antes de implementar qualquer feature que envolva mais de um arquivo, conceito novo ou decisão arquitetural, apresente um **plano explícito** contendo:
- O que será feito e em que ordem
- Quais arquivos serão criados ou alterados e por quê
- Quais conceitos/tecnologias serão aplicados
- Que alternativas existiam e por que esta foi escolhida
- O que o desenvolvedor deve revisar com atenção especial

Só execute após o desenvolvedor revisar e aprovar o plano. Se for uma alteração trivial (corrigir typo, ajustar import), pode executar direto — use bom senso.

**2. Justificar escolhas técnicas**

Ao escrever código, comente brevemente o "porquê" das decisões não óbvias.

Não polua o código com comentários óbvios — só os de valor pedagógico.

**3. Idioma**

Trabalhe sempre em **português (PT-BR)**: explicações, comentários pedagógicos no código, mensagens de commit sugeridas, documentação. Nomes de variáveis, classes, métodos e identificadores permanecem em inglês (padrão da indústria). Evite conjugar verbos em ingles de forma abrasileirada, por exemplo: preferia "fazer merge" a "mergear", "fazer o push" a "pushar"

---

## 🏛️ Visão Geral

Aplicação Fullstack SaaS com arquitetura moderna para suporte a múltiplos tenants em uma única instância do sistema.

### Stack - @tech.md

### Arquitetura - @architecture.md

### Contratos de API & Regras de Negócio - @summary.md

Fonte de verdade SDD do **estado atual** (endpoints, regras, gotchas). Encadeia o modelo de domínio (@domain.md) e o schema/migrations (@database-schema.md).

## 🛠️ Como Rodar - @commands.md
---

## 📏 Convenções de Desenvolvimento

###  Workflow de Branches e PRs, Controle de versão - @git-operator.md

### Backend

**Regras invioláveis:**
- **NUNCA** usar `spring.jpa.hibernate.ddl-auto=update`. Toda mudança de schema é via migration Flyway em `src/main/resources/db/migration/`.
- Migrations já aplicadas em ambientes superiores são **imutáveis**. Correção é sempre via nova migration.
- Nunca expor entidade JPA diretamente em controller. Sempre DTO.
- Toda query de dados de negócio deve ser escopada pelo `Tenant` do usuário autenticado. **Vazamento de tenant é o bug mais grave possível neste projeto.**

**Padrões:**
- Arquitetura: Controller → Service → Repository
- DTOs com Bean Validation (`@NotNull`, `@NotBlank`, `@Email`, `@Size`, etc.)
- Lombok permitido com `@Data`, mas atenção ao `@EqualsAndHashCode` — preferir inclusão explícita de ID para evitar problemas com entidades JPA
- Tratamento de erro centralizado via `GlobalExceptionHandler`
- Roles tipadas com Enum (`UserRole`), nunca String
- Testes: JUnit 5 + Mockito; integração com Testcontainers preferível a H2. Cobertura alvo: **80%+ na lógica de negócio** (não em boilerplate)

### Frontend

**Regras invioláveis:**
- Projeto é **Zoneless** (`provideZonelessChangeDetection()`). Não usar APIs que dependam de `zone.js`.
- **Signals primeiro** para estado local (`signal`, `computed`, `effect`). RxJS apenas para streams genuinamente assíncronos (HTTP, WebSocket, eventos).
- **SCSS + Angular Material 3** para estilização. Não introduzir TailwindCSS sem solicitação explícita.
- TypeScript estrito. Proibido `any` — usar `unknown` e narrowing quando o tipo for genuinamente incerto.

**Padrões:**
- Standalone components (sem NgModule, exceto se já existirem por legado)
- Features organizadas em `features/`, código compartilhado em `core/` ou `shared/components/`
- Services com `providedIn: 'root'` por padrão
- Lazy loading por feature route
- Validação anti-circular em estruturas hierárquicas (ex: categorias pai/filho)

### Segurança

- **JWT Secret:** em `application.properties` (em produção, via variável de ambiente)
- **CORS:** configurado em `SecurityConfigurations.java` — permitir frontend (porta 4200 em dev)
- **AuthGuard frontend:** valida expiração (`exp`) do token antes de permitir navegação
- **Redirecionamento:** usuários autenticados em `/login` ou `/register` vão direto pro dashboard
- **Senhas:** sempre `BCrypt`. Nunca logar, nunca retornar em DTO de resposta.

**Regra inviolável — defesa em profundidade para controle de acesso:**

Toda alteração que envolva permissões de acesso, visibilidade de recursos ou restrição por role **deve ser validada em ambas as camadas**:

| Camada | O que fazer |
|--------|-------------|
| **Backend** | Adicionar (ou confirmar) regra em `SecurityConfigurations.java` com `hasRole(...)` para o endpoint afetado. Cobrir com teste de controller que verifica 403 para a role não autorizada. |
| **Frontend** | Ocultar elemento/rota via `@if (isAdmin())` ou equivalente. Não chamar endpoints que o usuário não tem permissão de acessar (evita 403 desnecessário). |

Ocultar no frontend **não substitui** proteção no backend. O frontend é contornável; o backend é a última linha de defesa. A consistência entre as duas camadas evita tanto falhas de segurança quanto erros de UX (tela quebrando com 403 inesperado).

**Exemplo concreto (issue #24):** `GET /api/members` e `GET /invites` são exclusivos de ADMIN — protegidos em `SecurityConfigurations.java` com `hasRole("ADMIN")` **e** ocultos no frontend via `isAdmin()` no sidenav e no `forkJoin` do `TeamComponent`.

### Dataset de Testes - @dataset.md

---

## 📂 Estrutura de Diretórios @structure.md

---

## 🎯 Estado, Histórico e Roadmap

Este projeto segue **Spec-Driven Development**: cada fato tem **uma fonte única**. O CLAUDE.md é apenas princípios + invariantes + roteador — **não** mantém changelog (isso vive no git e nos specs). Para saber "o que já existe e como funciona", consulte as fontes abaixo, não uma lista nesta página.

| Fato | Fonte de verdade |
|------|------------------|
| Contrato de API | `api-spec/openapi.yaml` (spec-first) |
| Estado atual de endpoints e regras | `summary.md` |
| Modelo de domínio + enums | `domain.md` |
| Schema / migrations | `database-schema.md` |
| Racional de design por feature | `docs/superpowers/specs/` (e planos em `docs/superpowers/plans/`) |
| Decisões arquiteturais | `docs/adr/` |
| Histórico (o quê / quando mudou) | git |
| Roadmap e tarefas | GitHub Issues / Project |

**Roadmap aberto (ainda sem issue dedicada):**
- Issues médias do ADR-001: #85 (`effective_date`), #86 (`WITH RECURSIVE`), #87 (`TransferService`), #88 (`BusinessException`)
- Gráficos no dashboard (evolução mensal, breakdown por categoria/conta)
- Tela de Patrimônio Total — consome `countInNetWorth` (campo já existe em `accounts`)

---

## 📌 Resumo Operacional

Em cada interação significativa, o ciclo esperado é:

1. **Entender** o que foi pedido (e perguntar se houver ambiguidade)
2. **Planejar** explicitamente (com justificativas e conceitos envolvidos)
3. **Aguardar aprovação** do plano
4. **Executar** ensinando os conceitos aplicados
5. **Consolidar** com perguntas reflexivas ou oferta de aprofundamento

A meta não é só ter o software funcionando — é o desenvolvedor entender profundamente *por que* funciona.
