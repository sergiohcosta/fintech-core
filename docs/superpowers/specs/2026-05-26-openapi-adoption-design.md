# Design: Adoção de OpenAPI (Spec-First) — Fintech SaaS

**Data:** 2026-05-26
**Status:** Aprovado

---

## Contexto

O projeto já possui endpoints REST funcionando (auth, transactions, categories, dashboard). A adoção do OpenAPI resolve três problemas concretos identificados:

1. **Documentação:** sem Swagger UI, explorar e testar endpoints exige Postman ou curl manual.
2. **Geração de cliente:** os services Angular são escritos à mão e podem divergir silenciosamente do backend.
3. **Contrato formal:** desalinhamentos como `TransactionStatus.CONFIRMED` (frontend) vs `PAID` (backend) aconteceram no passado e só foram descobertos em tempo de execução.

---

## Decisões de Design

### Spec-First (não Code-First)

O arquivo `api-spec/openapi.yaml` é a fonte da verdade. Nenhuma mudança de contrato começa no código — começa no YAML. O código gerado é consequência do spec, nunca o contrário.

**Por que não code-first:** anotações `@Operation`, `@Schema` etc. no código Java acoplam documentação à implementação e não garantem que o frontend esteja em sincronia. O spec gerado a partir do código reflete o que foi implementado, não o que foi acordado.

### Ferramentas: OpenAPI Generator (backend) + Orval (frontend)

O `openapi-generator` com gerador `typescript-angular` produz código Angular verboso e incompatível com o modelo Zoneless + Signals do projeto. O Orval gera `Observable<T>` via `HttpClient` de forma idiomática — compatível com o padrão `toSignal() + switchMap` já em uso no dashboard.

---

## Arquitetura

```
api-spec/openapi.yaml          ← fonte da verdade, commitado
       │
       ├─► openapi-generator-maven-plugin  (fase: generate-sources)
       │         └─► target/generated-sources/openapi/
       │                   └─► interfaces Spring (não commitadas)
       │                         └─► controllers implementam as interfaces
       │
       └─► Orval CLI  (npm run api:generate)
                 └─► frontend/src/app/core/api/
                           ├─► services gerados (commitados)
                           └─► modelos TypeScript gerados (commitados)
```

**Localização do spec:** `api-spec/openapi.yaml` na raiz do repositório — visível tanto para `backend/` quanto para `frontend/` sem path relativo complexo.

**Por que commitar o código gerado do frontend:** permite que qualquer dev rode `npm start` após clonar sem precisar executar a geração primeiro. O diff nos PRs inclui o código gerado, mas `.gitattributes` pode marcar esses arquivos como gerados para reduzir o ruído na revisão.

---

## Backend — Configuração

Plugin no `pom.xml`:

```xml
<plugin>
  <groupId>org.openapitools</groupId>
  <artifactId>openapi-generator-maven-plugin</artifactId>
  <version><!-- verificar última versão estável em mvnrepository.com/artifact/org.openapitools/openapi-generator-maven-plugin -->
  <executions>
    <execution>
      <goals><goal>generate</goal></goals>
      <configuration>
        <generatorName>spring</generatorName>
        <inputSpec>${project.basedir}/../api-spec/openapi.yaml</inputSpec>
        <output>${project.build.directory}/generated-sources/openapi</output>
        <configOptions>
          <interfaceOnly>true</interfaceOnly>
          <useSpringBoot3>true</useSpringBoot3>
          <useTags>true</useTags>
          <useJakartaEe>true</useJakartaEe>
        </configOptions>
      </configuration>
    </execution>
  </executions>
</plugin>
```

**`interfaceOnly=true`:** gera apenas a interface (ex: `TransactionsApi`) — sem implementação. O controller existente passa a `implements TransactionsApi`. Se o controller não implementar um método declarado no spec, o compilador quebra. Esse é o mecanismo de sincronização — garantia de compilação, não de runtime.

**O que NÃO é gerado:** Services, Repositories, lógica de negócio. Apenas a assinatura dos endpoints.

---

## Frontend — Configuração

Dependência de desenvolvimento: `orval` (pacote único — inclui suporte Angular).

`frontend/orval.config.ts`:

```typescript
import { defineConfig } from 'orval';

export default defineConfig({
  fintechApi: {
    input: '../api-spec/openapi.yaml',
    output: {
      mode: 'tags-split',
      target: 'src/app/core/api',
      client: 'angular',
      override: {
        angular: { provideIn: 'root' }
      }
    }
  }
});
```

**`mode: 'tags-split'`:** gera um arquivo por tag do spec — `transactions.service.ts`, `categories.service.ts` etc. Evita um único arquivo gigante.

**Script no `package.json`:**
```json
"api:generate": "orval --config orval.config.ts"
```

**Integração com Zoneless + Signals:** o Orval gera métodos que retornam `Observable<T>` via `HttpClient`. Os componentes continuam aplicando `toSignal()` e `switchMap` exatamente como já fazem — o Orval não impõe padrão de consumo.

**Substituição dos services manuais:** após a geração, os services em `features/*/services/` são removidos. Os componentes passam a importar de `core/api/`.

---

## Estratégia de Migração

A migração dos endpoints existentes ocorre em quatro fases sem quebrar o funcionamento do sistema:

### Fase 1 — Escrever o spec (sem gerar código)

Mapear todos os endpoints existentes para o `openapi.yaml`. O spec age apenas como documentação nessa fase. Endpoints a cobrir:

| Tag | Endpoints |
|---|---|
| `auth` | `POST /auth/login`, `POST /auth/register` |
| `transactions` | `GET /api/transactions`, `POST /api/transactions`, `GET /api/transactions/{id}`, `PUT /api/transactions/{id}`, `DELETE /api/transactions/{id}` |
| `categories` | `GET /api/categories`, `POST /api/categories`, `GET /api/categories/{id}`, `PUT /api/categories/{id}`, `DELETE /api/categories/{id}` |
| `dashboard` | `GET /api/dashboard/summary` |

O spec deve incluir: esquemas de request/response, enums (`TransactionType`, `TransactionStatus`, `UserRole`), autenticação via `Bearer` (JWT), e parâmetros de query onde aplicável (`month` no dashboard).

### Fase 2 — Ligar o gerador do backend e corrigir divergências

Ativar o plugin no `pom.xml` e rodar `mvn compile`. Cada erro de compilação indica uma divergência entre o spec e o controller. Corrigir no spec (se o contrato estava errado) ou no controller (se a implementação estava errada).

### Fase 3 — Gerar o frontend e substituir os services

Rodar `npm run api:generate`. Atualizar as importações nos componentes — de `features/*/services/` para `core/api/`. Subir o servidor e verificar os fluxos principais no browser.

### Fase 4 — Remover os services e modelos manuais

Após verificação, deletar os services e modelos manuais substituídos. Commitar spec + código gerado + remoções juntos.

**Critério de conclusão:** `mvn compile` passa, `npm start` sobe sem erros TypeScript, fluxos de login / transações / categorias / dashboard funcionam no browser.

---

## Fluxo de Trabalho Pós-Migração

Para qualquer mudança de API:

```
1. Editar api-spec/openapi.yaml
2. mvn compile  →  interface Spring atualizada
   npm run api:generate  →  service Angular atualizado
3. Corrigir erros de compilação (feedback imediato de divergências)
4. Implementar lógica (Service, Repository, componente)
5. Commitar spec + código no mesmo commit
```

**Swagger UI em dev:** disponível em `http://localhost:8080/swagger-ui.html` via springdoc-openapi — permite explorar e testar endpoints com JWT sem Postman.

**Regra de ouro:** se o controller foi editado sem passar pelo YAML primeiro, o processo foi violado. O compilador pode não reclamar, mas o contrato mudou sem registro.

---

## O Que Este Design Não Cobre

- **Versionamento de API** (`/v1/`, `/v2/`) — não há necessidade atual; pode ser adicionado quando surgir.
- **Geração de testes a partir do spec** — possível com ferramentas como Schemathesis, fora do escopo desta adoção.
- **Publicação do spec** em portal de documentação externo — irrelevante enquanto o sistema for interno.
