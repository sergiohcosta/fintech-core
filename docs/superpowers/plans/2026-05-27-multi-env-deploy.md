# Deploy Multi-Ambiente — Plano de Implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Separar configuração de desenvolvimento local e produção, centralizar CORS, criar pipeline CI/CD no GitHub Actions e configurar as plataformas Render + Netlify para deploy automático ao fazer push em `main`.

**Architecture:** Spring Profiles (`dev`/`prod`) no backend com variáveis de ambiente para segredos de produção; Angular environments + interceptor HTTP para URL base da API; GitHub Actions como portão de qualidade (testes → deploy via hooks); `render.yaml` e `netlify.toml` como IaC versionado no repositório.

**Tech Stack:** Java 21 / Spring Boot 4.0.1, Angular 21 Zoneless, PostgreSQL (Neon), GitHub Actions, Render (backend), Netlify (frontend static)

---

## Mapa de Arquivos

| Arquivo | Ação | Responsabilidade |
|---|---|---|
| `backend/src/main/resources/application.properties` | Modificar | Apenas defaults comuns + `spring.profiles.active=dev` |
| `backend/src/main/resources/application-dev.properties` | Criar | Config local completa (banco Docker, debug, devtools) |
| `backend/src/main/resources/application-prod.properties` | Criar | Apenas referências a `${VAR}` |
| `backend/.../config/SecurityConfigurations.java` | Modificar | CORS centralizado via `@Value("${cors.allowed-origins}")` |
| `backend/.../controller/AuthController.java` | Modificar | Remover `@CrossOrigin(origins = "*")` |
| `backend/.../controller/AccountController.java` | Modificar | Remover `@CrossOrigin(origins = "*")` |
| `backend/.../controller/CategoryController.java` | Modificar | Remover `@CrossOrigin(origins = "*")` |
| `frontend/src/environments/environment.ts` | Criar | `apiUrl: ''` para dev (proxy assume) |
| `frontend/src/environments/environment.production.ts` | Criar | `apiUrl: 'https://...'` para prod |
| `frontend/src/app/core/interceptors/api-url.interceptor.ts` | Criar | Prepend `apiUrl` em requests `/api/*` e `/auth/*` |
| `frontend/src/app/app.config.ts` | Modificar | Registrar `apiUrlInterceptor` |
| `frontend/angular.json` | Modificar | Adicionar `fileReplacements` na config `production` |
| `.github/workflows/ci-cd.yml` | Criar | Pipeline unificado: testes + deploy condicional em `main` |
| `.github/workflows/backend-ci.yml` | Deletar | Substituído por `ci-cd.yml` |
| `.github/workflows/frontend-ci.yml` | Deletar | Substituído por `ci-cd.yml` |
| `.github/workflows/maven.yml` | Deletar | Substituído por `ci-cd.yml` |
| `render.yaml` | Criar | IaC do serviço backend no Render |
| `netlify.toml` | Criar | IaC do site estático no Netlify |

---

## Task 1: Spring Profiles — Separação de Configuração do Backend

**Contexto:** Atualmente há um único `application.properties` com valores hardcoded (banco, devtools, `show-sql=true`). O CORS é tratado com `@CrossOrigin(origins = "*")` em controllers individuais — inseguro e sem controle por ambiente. Esta task separa tudo em profiles e centraliza o CORS em `SecurityConfigurations.java`.

**Files:**
- Modify: `backend/src/main/resources/application.properties`
- Create: `backend/src/main/resources/application-dev.properties`
- Create: `backend/src/main/resources/application-prod.properties`
- Modify: `backend/src/main/java/com/fintech/api/config/SecurityConfigurations.java`
- Modify: `backend/src/main/java/com/fintech/api/controller/AuthController.java`
- Modify: `backend/src/main/java/com/fintech/api/controller/AccountController.java`
- Modify: `backend/src/main/java/com/fintech/api/controller/CategoryController.java`

---

- [ ] **Passo 1: Reescrever `application.properties` com apenas os defaults comuns**

  Substitua o conteúdo inteiro por:

  ```properties
  spring.application.name=fintech-api
  spring.profiles.active=dev
  spring.datasource.driver-class-name=org.postgresql.Driver
  spring.jpa.hibernate.ddl-auto=validate
  springdoc.api-docs.enabled=true
  springdoc.swagger-ui.url=/openapi.yaml
  springdoc.swagger-ui.path=/swagger-ui.html
  ```

  **Por que `spring.profiles.active=dev` aqui?** Este valor é o padrão para qualquer ambiente que não injete a variável de ambiente `SPRING_PROFILES_ACTIVE`. Localmente, nunca precisamos setar essa variável. Em produção, o Render injeta `SPRING_PROFILES_ACTIVE=prod`, que o Spring Boot prioriza sobre o arquivo de propriedades.

- [ ] **Passo 2: Criar `application-dev.properties`**

  ```properties
  # Banco local (Docker Compose)
  spring.datasource.url=jdbc:postgresql://localhost:5432/fintech
  spring.datasource.username=admin
  spring.datasource.password=secret

  # Debug
  spring.jpa.show-sql=true
  spring.jpa.properties.hibernate.format_sql=true

  # DevTools — reinicialização automática em dev
  spring.devtools.restart.enabled=true
  spring.devtools.restart.poll-interval=2s
  spring.devtools.restart.quiet-period=1s

  # JWT (valor fixo apenas para dev — nunca usar em prod)
  api.security.token.secret=dev-secret-key-not-for-production

  # CORS — aceita somente o frontend local
  cors.allowed-origins=http://localhost:4200
  ```

- [ ] **Passo 3: Criar `application-prod.properties`**

  ```properties
  # Banco (Neon) — valores vêm de variáveis de ambiente injetadas pelo Render
  spring.datasource.url=${DATABASE_URL}
  spring.datasource.username=${DB_USERNAME}
  spring.datasource.password=${DB_PASSWORD}

  # Produção: sem SQL no log, sem devtools
  spring.jpa.show-sql=false

  # JWT
  api.security.token.secret=${JWT_SECRET}

  # CORS — URL do site no Netlify, injetada pelo Render
  cors.allowed-origins=${CORS_ALLOWED_ORIGINS}
  ```

- [ ] **Passo 4: Centralizar CORS em `SecurityConfigurations.java`**

  Substitua o conteúdo inteiro do arquivo por:

  ```java
  package com.fintech.api.config;

  import org.springframework.beans.factory.annotation.Autowired;
  import org.springframework.beans.factory.annotation.Value;
  import org.springframework.context.annotation.Bean;
  import org.springframework.context.annotation.Configuration;
  import org.springframework.http.HttpMethod;
  import org.springframework.security.authentication.AuthenticationManager;
  import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
  import org.springframework.security.config.annotation.web.builders.HttpSecurity;
  import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
  import org.springframework.security.config.http.SessionCreationPolicy;
  import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
  import org.springframework.security.crypto.password.PasswordEncoder;
  import org.springframework.security.web.SecurityFilterChain;
  import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
  import org.springframework.web.cors.CorsConfiguration;
  import org.springframework.web.cors.CorsConfigurationSource;
  import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

  import java.util.Arrays;
  import java.util.List;

  @Configuration
  @EnableWebSecurity
  public class SecurityConfigurations {

      @Autowired
      SecurityFilter securityFilter;

      @Value("${cors.allowed-origins}")
      private String allowedOrigins;

      @Bean
      public SecurityFilterChain securityFilterChain(HttpSecurity httpSecurity) throws Exception {
          return httpSecurity
                  .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                  .csrf(csrf -> csrf.disable())
                  .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                  .authorizeHttpRequests(authorize -> authorize
                          .requestMatchers(HttpMethod.POST, "/auth/login").permitAll()
                          .requestMatchers(HttpMethod.POST, "/auth/register").permitAll()
                          .requestMatchers("/openapi.yaml", "/swagger-ui.html", "/swagger-ui/**", "/webjars/**", "/v3/api-docs/**", "/actuator/health").permitAll()
                          .anyRequest().authenticated())
                  .addFilterBefore(securityFilter, UsernamePasswordAuthenticationFilter.class)
                  .build();
      }

      @Bean
      CorsConfigurationSource corsConfigurationSource() {
          CorsConfiguration config = new CorsConfiguration();
          config.setAllowedOrigins(Arrays.asList(allowedOrigins.split(",")));
          config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
          config.setAllowedHeaders(List.of("*"));
          config.setAllowCredentials(true);
          UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
          source.registerCorsConfiguration("/**", config);
          return source;
      }

      @Bean
      public AuthenticationManager authenticationManager(AuthenticationConfiguration authenticationConfiguration)
              throws Exception {
          return authenticationConfiguration.getAuthenticationManager();
      }

      @Bean
      public PasswordEncoder passwordEncoder() {
          return new BCryptPasswordEncoder();
      }
  }
  ```

  **Por que isso?** `@CrossOrigin(origins = "*")` em cada controller é uma solução que permite qualquer origem — inaceitável em produção. Configurar CORS no nível do Spring Security centraliza a política, lê a origin permitida de uma propriedade (que muda por ambiente) e aplica a todos os endpoints, inclusive os que não têm controller próprio.

- [ ] **Passo 5: Remover `@CrossOrigin(origins = "*")` dos controllers**

  Em `AuthController.java`, `AccountController.java` e `CategoryController.java`:
  - Remova a linha `@CrossOrigin(origins = "*")`
  - Remova o import `import org.springframework.web.bind.annotation.CrossOrigin;` se não for usado em mais nada

- [ ] **Passo 6: Verificar que o backend sobe corretamente no profile dev**

  ```bash
  cd backend
  ./mvnw spring-boot:run
  ```

  Espere ver nos logs:
  ```
  The following 1 profile is active: "dev"
  ...
  Started FintechApiApplication in X.XXX seconds
  ```

  Teste rápido:
  ```bash
  curl http://localhost:8080/actuator/health
  ```
  Esperado: `{"status":"UP"}`

- [ ] **Passo 7: Rodar testes do backend**

  ```bash
  cd backend
  ./mvnw test
  ```

  Esperado: `BUILD SUCCESS` com todos os testes passando (19/19).

- [ ] **Passo 8: Commit**

  ```bash
  git add backend/src/main/resources/application.properties \
          backend/src/main/resources/application-dev.properties \
          backend/src/main/resources/application-prod.properties \
          backend/src/main/java/com/fintech/api/config/SecurityConfigurations.java \
          backend/src/main/java/com/fintech/api/controller/AuthController.java \
          backend/src/main/java/com/fintech/api/controller/AccountController.java \
          backend/src/main/java/com/fintech/api/controller/CategoryController.java

  git commit -m "feat: separa configuração em Spring Profiles dev/prod e centraliza CORS"
  ```

---

## Task 2: Angular Environments + Interceptor de URL Base

**Contexto:** Os services gerados pelo Orval (e o `AuthService` manual) usam caminhos relativos como `/api/accounts` e `/auth/login`. Em desenvolvimento, o `proxy.conf.json` intercepta esses caminhos e redireciona para `localhost:8080`. Em produção no Netlify, não há proxy — as requisições precisam de URL absoluta apontando para o Render. A solução é um interceptor HTTP que prepend `environment.apiUrl` quando não estiver vazio.

**Files:**
- Create: `frontend/src/environments/environment.ts`
- Create: `frontend/src/environments/environment.production.ts`
- Create: `frontend/src/app/core/interceptors/api-url.interceptor.ts`
- Modify: `frontend/angular.json`
- Modify: `frontend/src/app/app.config.ts`

---

- [ ] **Passo 1: Criar a pasta `environments` e o arquivo de dev**

  Crie `frontend/src/environments/environment.ts`:

  ```typescript
  export const environment = {
    production: false,
    apiUrl: ''
  };
  ```

  `apiUrl` vazio em dev significa que o interceptor não vai agir — o proxy do Angular CLI continua redirecionando `/api/*` e `/auth/*` para `localhost:8080` normalmente.

- [ ] **Passo 2: Criar o arquivo de produção**

  Crie `frontend/src/environments/environment.production.ts`:

  ```typescript
  export const environment = {
    production: true,
    apiUrl: 'https://fintech-api.onrender.com'
  };
  ```

  **Atenção:** esta URL deve ser atualizada com o endereço real gerado pelo Render após criar o serviço na Task 5. Por enquanto, use esse valor como placeholder — o build vai compilar normalmente.

- [ ] **Passo 3: Criar o interceptor de URL base**

  Crie `frontend/src/app/core/interceptors/api-url.interceptor.ts`:

  ```typescript
  import { HttpInterceptorFn } from '@angular/common/http';
  import { environment } from '../../../environments/environment';

  export const apiUrlInterceptor: HttpInterceptorFn = (req, next) => {
    const isApiRequest = req.url.startsWith('/api') || req.url.startsWith('/auth');
    if (environment.apiUrl && isApiRequest) {
      return next(req.clone({ url: `${environment.apiUrl}${req.url}` }));
    }
    return next(req);
  };
  ```

  **Por que não modificar os services gerados?** Os services do Orval são regenerados a cada `npm run api:generate` — qualquer modificação manual seria sobrescrita. O interceptor resolve o problema em um único lugar, de forma transparente para todos os services atuais e futuros.

- [ ] **Passo 4: Registrar o interceptor em `app.config.ts`**

  Adicione `apiUrlInterceptor` ao array de interceptors em `frontend/src/app/app.config.ts`:

  ```typescript
  import { ApplicationConfig, LOCALE_ID, provideZonelessChangeDetection } from '@angular/core';
  import { provideRouter } from '@angular/router';
  import { provideHttpClient, withFetch, withInterceptors } from '@angular/common/http';
  import { provideAnimationsAsync } from '@angular/platform-browser/animations/async';
  import { registerLocaleData } from '@angular/common';
  import localePtBr from '@angular/common/locales/pt';
  import { MAT_DATE_LOCALE } from '@angular/material/core';

  import { routes } from './app.routes';
  import { authInterceptor } from './core/interceptors/auth.interceptor';
  import { apiUrlInterceptor } from './core/interceptors/api-url.interceptor';

  registerLocaleData(localePtBr);

  export const appConfig: ApplicationConfig = {
    providers: [
      provideZonelessChangeDetection(),
      provideRouter(routes),
      provideHttpClient(
        withFetch(),
        withInterceptors([apiUrlInterceptor, authInterceptor])
      ),
      provideAnimationsAsync(),
      { provide: LOCALE_ID, useValue: 'pt-BR' },
      { provide: MAT_DATE_LOCALE, useValue: 'pt-BR' }
    ]
  };
  ```

  **Por que `apiUrlInterceptor` antes de `authInterceptor`?** Os interceptors são executados na ordem do array. O `apiUrlInterceptor` precisa modificar a URL antes que o `authInterceptor` adicione o header de autorização — caso contrário o header seria adicionado à URL relativa que seria então modificada (sem impacto prático, mas a ordem semântica está correta).

- [ ] **Passo 5: Adicionar `fileReplacements` no `angular.json`**

  No `angular.json`, localize a configuração `production` dentro de `projects.frontend.architect.build.configurations`. Adicione a chave `fileReplacements` logo antes de `budgets`:

  ```json
  "production": {
    "fileReplacements": [
      {
        "replace": "src/environments/environment.ts",
        "with": "src/environments/environment.production.ts"
      }
    ],
    "budgets": [
      ...
    ],
    "outputHashing": "all"
  }
  ```

  **O que isso faz?** Durante o build de produção (`ng build --configuration=production`), o compilador Angular substitui automaticamente `environment.ts` por `environment.production.ts`. O interceptor que importa `environment` passa a usar `apiUrl: 'https://fintech-api.onrender.com'` em vez de `''`.

- [ ] **Passo 6: Verificar que o app inicia normalmente em dev**

  ```bash
  cd frontend
  npm start
  ```

  Abra `http://localhost:4200`, faça login e navegue pelas telas. O comportamento deve ser idêntico ao de antes.

- [ ] **Passo 7: Verificar que o build de produção compila sem erros**

  ```bash
  cd frontend
  npm run build -- --configuration=production
  ```

  Esperado: build completo sem erros em `dist/frontend/browser/`.

- [ ] **Passo 8: Commit**

  ```bash
  git add frontend/src/environments/ \
          frontend/src/app/core/interceptors/api-url.interceptor.ts \
          frontend/src/app/app.config.ts \
          frontend/angular.json

  git commit -m "feat: adiciona Angular environments e interceptor de URL base para produção"
  ```

---

## Task 3: GitHub Actions — Consolidação do Pipeline CI/CD

**Contexto:** Existem três workflows sobrepostos (`backend-ci.yml`, `frontend-ci.yml`, `maven.yml`) que só fazem CI. O novo `ci-cd.yml` unifica tudo e adiciona o job de deploy condicional: só roda em push para `main` e só após os dois jobs de teste passarem.

**Files:**
- Create: `.github/workflows/ci-cd.yml`
- Delete: `.github/workflows/backend-ci.yml`
- Delete: `.github/workflows/frontend-ci.yml`
- Delete: `.github/workflows/maven.yml`

---

- [ ] **Passo 1: Criar `.github/workflows/ci-cd.yml`**

  ```yaml
  name: CI/CD

  on:
    push:
      branches: [main, develop]
    pull_request:
      branches: [main, develop]

  jobs:
    test-backend:
      name: Testes Backend
      runs-on: ubuntu-latest
      defaults:
        run:
          working-directory: ./backend
      steps:
        - uses: actions/checkout@v4
        - name: Configura JDK 21
          uses: actions/setup-java@v4
          with:
            java-version: '21'
            distribution: 'temurin'
            cache: maven
        - name: Roda testes e empacota
          run: ./mvnw verify

    test-frontend:
      name: Testes Frontend
      runs-on: ubuntu-latest
      defaults:
        run:
          working-directory: ./frontend
      steps:
        - uses: actions/checkout@v4
        - name: Configura Node.js 22
          uses: actions/setup-node@v4
          with:
            node-version: '22'
            cache: 'npm'
            cache-dependency-path: frontend/package-lock.json
        - name: Instala dependências
          run: npm ci
        - name: Roda testes
          run: npm test
        - name: Build de produção
          run: npm run build -- --configuration=production

    deploy:
      name: Deploy para Produção
      runs-on: ubuntu-latest
      needs: [test-backend, test-frontend]
      if: github.ref == 'refs/heads/main' && github.event_name == 'push'
      steps:
        - name: Aciona deploy do backend (Render)
          run: curl -X POST "${{ secrets.RENDER_DEPLOY_HOOK_URL }}"
        - name: Aciona deploy do frontend (Netlify)
          run: curl -X POST "${{ secrets.NETLIFY_DEPLOY_HOOK_URL }}"
  ```

- [ ] **Passo 2: Deletar os três workflows antigos**

  ```bash
  git rm .github/workflows/backend-ci.yml \
         .github/workflows/frontend-ci.yml \
         .github/workflows/maven.yml
  ```

- [ ] **Passo 3: Commit**

  ```bash
  git add .github/workflows/ci-cd.yml

  git commit -m "ci: substitui workflows separados por pipeline unificado com portão de deploy"
  ```

- [ ] **Passo 4: Push para `develop` e verificar CI no GitHub**

  ```bash
  git push origin develop
  ```

  Vá em `github.com/<seu-usuario>/fintech-core` → aba **Actions**. Você verá o workflow `CI/CD` rodando com os jobs `Testes Backend` e `Testes Frontend`. O job `Deploy para Produção` **não aparece** — só dispara em push para `main`.

  Espere os dois jobs ficarem verdes (✓). Se algum falhar, leia o log do passo que falhou.

---

## Task 4: Infrastructure as Code — `render.yaml` e `netlify.toml`

**Contexto:** Versionar a configuração da infra junto com o código garante que qualquer pessoa que clonar o repositório consiga replicar o ambiente de produção sem depender de cliques no dashboard. O `render.yaml` declara o serviço backend; o `netlify.toml` declara o build e o redirect crítico para SPA.

**Files:**
- Create: `render.yaml`
- Create: `netlify.toml`

---

- [ ] **Passo 1: Criar `render.yaml` na raiz do repositório**

  ```yaml
  services:
    - type: web
      name: fintech-api
      runtime: java
      buildCommand: cd backend && ./mvnw package -DskipTests
      startCommand: java -jar backend/target/api-0.0.1-SNAPSHOT.jar
      envVars:
        - key: SPRING_PROFILES_ACTIVE
          value: prod
        - key: JWT_SECRET
          sync: false
        - key: DATABASE_URL
          sync: false
        - key: DB_USERNAME
          sync: false
        - key: DB_PASSWORD
          sync: false
        - key: CORS_ALLOWED_ORIGINS
          sync: false
  ```

  **`sync: false`:** indica ao Render que esse valor não está no arquivo — deve ser preenchido manualmente no dashboard. Sem `sync: false`, o Render exigiria o valor no YAML (que iria para o repositório público — um vazamento de segredo).

  **`-DskipTests` no buildCommand:** os testes já rodaram no Actions antes do deploy ser acionado. Rodá-los de novo no Render seria redundante e lento.

- [ ] **Passo 2: Criar `netlify.toml` na raiz do repositório**

  ```toml
  [build]
    base    = "frontend"
    command = "npm ci && npm run build -- --configuration=production"
    publish = "dist/frontend/browser"

  [[redirects]]
    from   = "/*"
    to     = "/index.html"
    status = 200
  ```

  **Por que o `[[redirects]]` é obrigatório?** O Angular é uma SPA — existe apenas um arquivo `index.html`. Quando o usuário acessa diretamente `https://seu-app.netlify.app/accounts/123`, o Netlify procura um arquivo físico em `accounts/123/index.html` e não encontra: retorna 404. A regra de redirect captura qualquer rota não encontrada e serve o `index.html`, deixando o Angular Router assumir o controle.

- [ ] **Passo 3: Commit**

  ```bash
  git add render.yaml netlify.toml

  git commit -m "feat: adiciona render.yaml e netlify.toml como Infrastructure as Code"
  ```

---

## Task 5: Configuração das Plataformas Remotas

**Contexto:** Esta task é predominantemente manual — envolve criar os serviços nas plataformas, preencher segredos e fazer o primeiro deploy. Ao fim, qualquer push para `main` dispara o pipeline completo automaticamente.

**Pré-requisito:** Tasks 1–4 commitadas e `develop` atualizado no remote.

---

### Parte A: Configurar o Render (backend)

- [ ] **Passo 1: Criar conta e conectar repositório no Render**

  1. Acesse [render.com](https://render.com) e crie uma conta (ou faça login)
  2. Clique em **New** → **Blueprint**
  3. Conecte sua conta GitHub e selecione o repositório `fintech-core`
  4. O Render detectará o `render.yaml` automaticamente e mostrará o serviço `fintech-api`
  5. Clique em **Apply** — o Render criará o serviço mas o build ainda vai falhar (os segredos estão vazios)

- [ ] **Passo 2: Preencher as variáveis de ambiente no Render**

  No dashboard do Render, acesse o serviço `fintech-api` → **Environment**.
  
  Preencha cada variável (as que têm `sync: false` aparecem como campos para preencher):

  | Variável | Valor |
  |---|---|
  | `JWT_SECRET` | Gere um segredo forte: `openssl rand -base64 64` (rode esse comando localmente e use o resultado) |
  | `DATABASE_URL` | Connection string do Neon no formato `postgresql://user:password@ep-xxx.neon.tech/fintech?sslmode=require` |
  | `DB_USERNAME` | Usuário do Neon (visível no dashboard do Neon em **Connection Details**) |
  | `DB_PASSWORD` | Senha do Neon |
  | `CORS_ALLOWED_ORIGINS` | Por enquanto use `https://placeholder.netlify.app` — atualize após criar o site no Netlify |

  Após preencher, clique em **Save Changes** → **Manual Deploy** → **Deploy latest commit**.

- [ ] **Passo 3: Verificar que o backend subiu**

  Aguarde o build finalizar (2–5 min). Quando aparecer "Live", anote a URL do serviço (ex: `https://fintech-api.onrender.com`).

  Teste:
  ```bash
  curl https://fintech-api.onrender.com/actuator/health
  ```
  Esperado: `{"status":"UP"}`

- [ ] **Passo 4: Atualizar a URL do backend no `environment.production.ts`**

  Edite `frontend/src/environments/environment.production.ts` com a URL real:

  ```typescript
  export const environment = {
    production: true,
    apiUrl: 'https://fintech-api.onrender.com'  // substitua pela URL real do Render
  };
  ```

  ```bash
  git add frontend/src/environments/environment.production.ts
  git commit -m "feat: atualiza apiUrl de produção com URL real do Render"
  ```

- [ ] **Passo 5: Gerar o Deploy Hook do Render**

  No dashboard do Render → serviço `fintech-api` → **Settings** → **Deploy Hook** → **Generate**.
  
  Copie a URL gerada (ex: `https://api.render.com/deploy/srv-xxx?key=yyy`). Ela será usada como secret no GitHub.

---

### Parte B: Configurar o Netlify (frontend)

- [ ] **Passo 6: Criar site no Netlify**

  1. Acesse [netlify.com](https://netlify.com) e crie uma conta (ou faça login)
  2. Clique em **Add new site** → **Import an existing project** → **GitHub**
  3. Selecione o repositório `fintech-core`
  4. O Netlify detectará o `netlify.toml` automaticamente — as configurações de build são preenchidas automaticamente
  5. Clique em **Deploy site**

  Aguarde o primeiro deploy. Anote a URL gerada (ex: `https://fintech-app.netlify.app`).

- [ ] **Passo 7: Atualizar `CORS_ALLOWED_ORIGINS` no Render**

  Agora que você tem a URL do Netlify, volte ao dashboard do Render → `fintech-api` → **Environment** e atualize:

  | Variável | Valor |
  |---|---|
  | `CORS_ALLOWED_ORIGINS` | `https://fintech-app.netlify.app` (URL real do Netlify) |

  Salve e faça um novo deploy manual no Render para o CORS entrar em vigor.

- [ ] **Passo 8: Gerar o Deploy Hook do Netlify**

  No dashboard do Netlify → seu site → **Site configuration** → **Build & deploy** → **Build hooks** → **Add build hook**.

  Nomeie como `github-actions` e escolha a branch `main`. Copie a URL gerada (ex: `https://api.netlify.com/build_hooks/xxx`).

---

### Parte C: Configurar Secrets no GitHub

- [ ] **Passo 9: Adicionar os Deploy Hooks como secrets no GitHub**

  Acesse o repositório no GitHub → **Settings** → **Secrets and variables** → **Actions** → **New repository secret**.

  Crie dois secrets:

  | Nome | Valor |
  |---|---|
  | `RENDER_DEPLOY_HOOK_URL` | URL do Deploy Hook do Render (Passo 5) |
  | `NETLIFY_DEPLOY_HOOK_URL` | URL do Build Hook do Netlify (Passo 8) |

---

### Parte D: Primeiro Deploy via Pipeline Completo

- [ ] **Passo 10: Fazer merge de `develop` em `main` e observar o pipeline**

  ```bash
  git checkout main
  git merge develop
  git push origin main
  ```

  Vá em GitHub → **Actions**. Você verá o workflow `CI/CD` com três jobs:
  1. `Testes Backend` — roda `./mvnw verify`
  2. `Testes Frontend` — roda `npm test` + `npm run build`
  3. `Deploy para Produção` — só inicia após os dois acima passarem

  Aguarde o job de deploy (✓). Em seguida:
  - Render inicia um novo deploy do backend automaticamente
  - Netlify inicia um novo build do frontend automaticamente

- [ ] **Passo 11: Validar o sistema em produção**

  Acesse a URL do Netlify (ex: `https://fintech-app.netlify.app`) e realize os seguintes testes:

  - [ ] A tela de login carrega
  - [ ] Login com um usuário existente funciona (o token retorna do Render)
  - [ ] A listagem de contas carrega corretamente
  - [ ] Criar uma nova conta funciona e aparece na lista
  - [ ] Fazer refresh direto em `/accounts` retorna a SPA (não um 404)
  - [ ] Abrir o DevTools → Network: requisições vão para `https://fintech-api.onrender.com/api/*` (não para `/api/*`)

- [ ] **Passo 12: Commit final com ajustes eventuais**

  Se algum valor precisou ser ajustado (URL, secret, CORS), commit as mudanças:

  ```bash
  git add -p  # adicione apenas o que foi modificado
  git commit -m "fix: ajusta configurações de produção após validação"
  git push origin main  # dispara novo deploy automático
  ```

---

## Verificação Final do Fluxo Completo

Após concluir todas as tasks, o fluxo de trabalho esperado é:

```
1. Você faz alterações em uma branch feature/*
2. Abre PR para develop → Actions roda CI (testes apenas)
3. Merge em develop → Actions roda CI (sem deploy)
4. Abre PR develop → main → Actions roda CI
5. Merge em main → Actions: CI passou → dispara Deploy Hooks
6. Render builda e sobe novo backend automaticamente
7. Netlify builda e publica novo frontend automaticamente
8. Flyway aplica migrations novas no startup do Spring Boot
```

Código com testes quebrados nunca chega a produção.
