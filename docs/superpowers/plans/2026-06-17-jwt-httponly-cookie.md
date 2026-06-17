# JWT httpOnly Cookie — Plano de Execução

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrar autenticação de `localStorage` + header `Authorization` para cookie `httpOnly` + endpoint `/auth/me`, eliminando o vetor XSS.

**Architecture:** Backend seta cookie `auth_token` (httpOnly, Secure, SameSite=Strict, 2h) no login e no accept-invite. `SecurityFilter` lê o token do cookie primeiro, Bearer como fallback para Swagger. Frontend chama `GET /auth/me` no boot e após login para hidratar o signal `currentUser`.

**Tech Stack:** Java 21 / Spring Boot 4 / `org.springframework.http.ResponseCookie` · Angular 21 Zoneless / Signals / `withCredentials: true`

## Global Constraints

- Nunca commitar `Co-Authored-By` nas mensagens de commit
- Testes backend: `@SpringBootTest` + `MockMvc` + `@MockitoBean` (não `@MockBean`)
- Testes unitários: `@ExtendWith(MockitoExtension.class)` + AssertJ
- Role enum: `UserRole.ADMIN` e `UserRole.USER` (não MEMBER)
- Cookie name: `"auth_token"` (consistente com o nome atual em `localStorage`)
- Sem `@Autowired` em campo — sempre via construtor (`@RequiredArgsConstructor`)

---

### Task 1: OpenAPI spec — novos schemas e endpoints de auth

**Files:**
- Modify: `api-spec/openapi.yaml`

**Interfaces:**
- Produces: schema `UserProfileResponseDTO`, endpoints `/auth/me` e `/auth/logout` disponíveis para codegen

- [ ] **Step 1: Substituir schema `LoginResponseDTO` por `UserProfileResponseDTO`**

Localizar a seção `LoginResponseDTO` (linha ~52) e substituir:

```yaml
    UserProfileResponseDTO:
      type: object
      properties:
        email:
          type: string
        name:
          type: string
        role:
          type: string
          enum: [ADMIN, USER]
        tenantId:
          type: string
```

- [ ] **Step 2: Atualizar response do `/auth/login`**

Localizar `/auth/login` (linha ~976). Atualizar a response `'200'`:

```yaml
        '200':
          description: Autenticado — cookie auth_token setado no response
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/UserProfileResponseDTO'
```

- [ ] **Step 3: Adicionar `/auth/accept-invite` (ausente na spec) e atualizar sua response**

Após o bloco `/auth/register`, adicionar:

```yaml
  /auth/accept-invite:
    post:
      tags: [auth]
      operationId: acceptInvite
      security: []
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/AcceptInviteDTO'
      responses:
        '200':
          description: Convite aceito — cookie auth_token setado no response
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/UserProfileResponseDTO'
        '400':
          description: Token inválido, expirado ou senha fraca
        '410':
          description: Convite já utilizado
```

Verificar que `AcceptInviteDTO` já existe na seção `schemas`. Se não existir, adicionar:

```yaml
    AcceptInviteDTO:
      type: object
      required: [token, name, password]
      properties:
        token:
          type: string
        name:
          type: string
        password:
          type: string
          minLength: 8
          maxLength: 72
          pattern: '^(?=.*[a-z])(?=.*[A-Z])(?=.*\d).{8,}$'
```

- [ ] **Step 4: Adicionar `/auth/me` e `/auth/logout`**

Após o bloco `/auth/accept-invite`:

```yaml
  /auth/me:
    get:
      tags: [auth]
      operationId: getMe
      summary: Retorna perfil do usuário autenticado via cookie
      responses:
        '200':
          description: Perfil do usuário
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/UserProfileResponseDTO'
        '401':
          description: Cookie ausente ou expirado

  /auth/logout:
    post:
      tags: [auth]
      operationId: logout
      security: []
      summary: Expira o cookie auth_token
      responses:
        '204':
          description: Cookie expirado com sucesso
```

- [ ] **Step 5: Copiar spec para resources do backend**

```bash
cp api-spec/openapi.yaml backend/src/main/resources/static/openapi.yaml
```

- [ ] **Step 6: Commit**

```bash
git add api-spec/openapi.yaml backend/src/main/resources/static/openapi.yaml
git commit -m "feat(spec): migra auth para cookie httpOnly — UserProfileResponseDTO, /auth/me, /auth/logout"
```

---

### Task 2: Backend — DTO e regeneração de interfaces

**Files:**
- Create: `backend/src/main/java/com/fintech/api/dto/UserProfileResponseDTO.java`
- Delete: `backend/src/main/java/com/fintech/api/dto/LoginResponseDTO.java`

**Interfaces:**
- Produces: `UserProfileResponseDTO(email, name, role, tenantId)` disponível para `AuthController`

- [ ] **Step 1: Criar `UserProfileResponseDTO`**

```java
// backend/src/main/java/com/fintech/api/dto/UserProfileResponseDTO.java
package com.fintech.api.dto;

public record UserProfileResponseDTO(
        String email,
        String name,
        String role,
        String tenantId
) {}
```

- [ ] **Step 2: Deletar `LoginResponseDTO`**

```bash
rm backend/src/main/java/com/fintech/api/dto/LoginResponseDTO.java
```

- [ ] **Step 3: Regenerar interfaces OpenAPI**

```bash
cd backend && ./mvnw generate-sources
```

Esperado: BUILD SUCCESS. O codegen recria `target/generated-sources/openapi/` com as novas interfaces. Verificar que `AuthApi` agora tem `getMe()` e `logout()`.

- [ ] **Step 4: Regenerar client frontend**

```bash
cd frontend && npm run api:generate
```

- [ ] **Step 5: Verificar se há outros arquivos referenciando `LoginResponseDTO` além de `AuthController`**

```bash
grep -r "LoginResponseDTO" backend/src/main/java --include="*.java" | grep -v AuthController
```

Esperado: nenhum resultado. Se aparecer algum arquivo diferente de `AuthController`, corrigi-lo agora.
`AuthController.java` ainda vai falhar compile até o Task 5 — isso é esperado. Não rodar `./mvnw compile` aqui.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/fintech/api/dto/UserProfileResponseDTO.java
git rm backend/src/main/java/com/fintech/api/dto/LoginResponseDTO.java
git commit -m "feat(dto): substitui LoginResponseDTO por UserProfileResponseDTO"
```

---

### Task 3: Backend — `SecurityFilter` lê token do cookie

**Files:**
- Modify: `backend/src/main/java/com/fintech/api/config/SecurityFilter.java`
- Modify: `backend/src/test/java/com/fintech/api/config/SecurityFilterTest.java`

**Interfaces:**
- Produces: `recoverToken()` lê cookie `auth_token` primeiro, Bearer como fallback

- [ ] **Step 1: Escrever o teste que falha**

Em `SecurityFilterTest.java`, adicionar após o teste `doFilterInternal_inactiveUser_doesNotSetAuthentication`:

```java
@Test
@DisplayName("autentica via cookie auth_token quando Authorization header está ausente")
void doFilterInternal_cookieToken_setsAuthentication() throws Exception {
    User user = buildUser(true);
    jakarta.servlet.http.Cookie cookie =
            new jakarta.servlet.http.Cookie("auth_token", "cookie-token");
    when(request.getCookies()).thenReturn(new jakarta.servlet.http.Cookie[]{ cookie });
    when(request.getHeader("Authorization")).thenReturn(null);
    when(tokenService.validateToken("cookie-token")).thenReturn(user.getEmail());
    when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));

    securityFilter.doFilterInternal(request, response, filterChain);

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    verify(filterChain).doFilter(request, response);
}

@Test
@DisplayName("não autentica quando não há cookie nem Authorization header")
void doFilterInternal_noCookieNoHeader_noAuthentication() throws Exception {
    when(request.getCookies()).thenReturn(null);
    when(request.getHeader("Authorization")).thenReturn(null);

    securityFilter.doFilterInternal(request, response, filterChain);

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    verify(filterChain).doFilter(request, response);
}
```

- [ ] **Step 2: Executar testes para confirmar falha**

```bash
cd backend && ./mvnw test -pl . -Dtest=SecurityFilterTest -q 2>&1 | tail -20
```

Esperado: FAIL — `doFilterInternal_cookieToken_setsAuthentication` falha pois `recoverToken` ainda lê só o header.

- [ ] **Step 3: Implementar leitura de cookie em `SecurityFilter`**

Substituir o método `recoverToken` em `SecurityFilter.java`:

```java
private String recoverToken(HttpServletRequest request) {
    if (request.getCookies() != null)
        for (jakarta.servlet.http.Cookie c : request.getCookies())
            if ("auth_token".equals(c.getName())) return c.getValue();
    var header = request.getHeader("Authorization");
    return header != null ? header.replace("Bearer ", "") : null;
}
```

- [ ] **Step 4: Executar testes**

```bash
cd backend && ./mvnw test -pl . -Dtest=SecurityFilterTest -q 2>&1 | tail -10
```

Esperado: `Tests run: 4, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/fintech/api/config/SecurityFilter.java \
        backend/src/test/java/com/fintech/api/config/SecurityFilterTest.java
git commit -m "feat(security): SecurityFilter lê JWT do cookie auth_token com fallback Bearer"
```

---

### Task 4: Backend — `InvitationService.accept()` retorna `User`

**Files:**
- Modify: `backend/src/main/java/com/fintech/api/service/InvitationService.java`
- Modify: `backend/src/test/java/com/fintech/api/service/InvitationServiceTest.java`

**Interfaces:**
- Produces: `InvitationService.accept(AcceptInviteDTO dto): User` — responsabilidade de gerar token e cookie move para `AuthController`

- [ ] **Step 1: Atualizar o teste `accept_happyPath`**

Em `InvitationServiceTest.java`, localizar `accept_happyPath` e substituir:

```java
@Test
@DisplayName("accept cria usuário USER, marca convite como usado e retorna o User salvo")
void accept_happyPath() {
    Invitation inv = buildInvitation(false, LocalDateTime.now().plusDays(1));
    when(invitationRepository.findByToken("valid-token")).thenReturn(Optional.of(inv));
    when(userRepository.existsByEmail("convidado@silva.com")).thenReturn(false);
    when(passwordEncoder.encode("senha123")).thenReturn("hashed");
    when(userRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    when(invitationRepository.save(any())).thenAnswer(i -> i.getArgument(0));

    AcceptInviteDTO dto = new AcceptInviteDTO("valid-token", "João Silva", "senha123");
    User result = service.accept(dto);

    assertThat(result.getEmail()).isEqualTo("convidado@silva.com");
    assertThat(result.getRole()).isEqualTo(UserRole.USER);
    assertThat(result.getTenant()).isEqualTo(tenant);
    assertThat(inv.isUsed()).isTrue();

    ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
    verify(userRepository).save(userCaptor.capture());
    User saved = userCaptor.getValue();
    assertThat(saved.getPasswordHash()).isEqualTo("hashed");
}
```

Remover também o `@Mock TokenService tokenService` da classe (não será mais usado em `accept()`).

- [ ] **Step 2: Executar testes para confirmar falha**

```bash
cd backend && ./mvnw test -pl . -Dtest=InvitationServiceTest -q 2>&1 | tail -20
```

Esperado: erro de compilação ou FAIL em `accept_happyPath`.

- [ ] **Step 3: Modificar `InvitationService.accept()`**

Em `InvitationService.java`:

1. Remover o import de `TokenService` e o campo da injeção:
```java
// REMOVER do construtor/campo: TokenService tokenService
```

2. Alterar o método `accept()` — trocar return type de `String` para `User` e remover a chamada ao `tokenService`:

```java
@Transactional
public User accept(AcceptInviteDTO dto) {
    Invitation invitation = findValidInvitation(dto.token());

    if (userRepository.existsByEmail(invitation.getEmail())) {
        throw new BusinessConflictException("Este email já possui uma conta");
    }

    User user = new User();
    user.setName(dto.name());
    user.setEmail(invitation.getEmail());
    user.setPasswordHash(passwordEncoder.encode(dto.password()));
    user.setRole(UserRole.USER);
    user.setTenant(invitation.getTenant());
    userRepository.save(user);

    invitation.setUsed(true);
    invitationRepository.save(invitation);

    return user;
}
```

- [ ] **Step 4: Executar testes**

```bash
cd backend && ./mvnw test -pl . -Dtest=InvitationServiceTest -q 2>&1 | tail -10
```

Esperado: `Tests run: X, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/fintech/api/service/InvitationService.java \
        backend/src/test/java/com/fintech/api/service/InvitationServiceTest.java
git commit -m "refactor(invitation): accept() retorna User — geração de token move para AuthController"
```

---

### Task 5: Backend — `AuthController` + `SecurityConfigurations`

**Files:**
- Modify: `backend/src/main/java/com/fintech/api/controller/AuthController.java`
- Modify: `backend/src/main/java/com/fintech/api/config/SecurityConfigurations.java`
- Modify: `backend/src/test/java/com/fintech/api/controller/AuthControllerTest.java`

**Interfaces:**
- Consumes: `UserProfileResponseDTO`, `InvitationService.accept(): User`, `TokenService.generateToken(User): String`
- Produces: endpoints `/auth/login`, `/auth/accept-invite`, `/auth/me`, `/auth/logout` funcionando com cookie

- [ ] **Step 1: Escrever testes atualizados em `AuthControllerTest`**

Substituir o conteúdo de `AuthControllerTest.java` por:

```java
package com.fintech.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.api.config.LoginRateLimiter;
import com.fintech.api.config.TokenService;
import com.fintech.api.domain.enums.UserRole;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.AcceptInviteDTO;
import com.fintech.api.dto.LoginDTO;
import com.fintech.api.dto.TenantRegistrationDTO;
import com.fintech.api.repository.UserRepository;
import com.fintech.api.service.InvitationService;
import com.fintech.api.service.TenantRegistrationService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
class AuthControllerTest {

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext context;

    @MockitoBean private TenantRegistrationService registrationService;
    @MockitoBean private UserRepository userRepository;
    @MockitoBean private PasswordEncoder passwordEncoder;
    @MockitoBean private TokenService tokenService;
    @MockitoBean private InvitationService invitationService;
    @MockitoBean private LoginRateLimiter loginRateLimiter;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    private User buildUser() {
        Tenant tenant = new Tenant();
        tenant.setId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        tenant.setName("Tenant Test");

        User user = new User();
        user.setId(UUID.fromString("22222222-2222-2222-2222-222222222222"));
        user.setEmail("admin@test.com");
        user.setName("Test Admin");
        user.setPasswordHash("encoded_password");
        user.setRole(UserRole.ADMIN);
        user.setTenant(tenant);
        user.setActive(true);
        return user;
    }

    // --- register ---

    @Test
    @DisplayName("POST /auth/register cria tenant com sucesso")
    void shouldRegisterTenant() throws Exception {
        TenantRegistrationDTO dto = new TenantRegistrationDTO(
                "My Tenant", "Admin", "admin@email.com", "Senha123");
        Tenant tenant = new Tenant();
        tenant.setId(UUID.randomUUID());
        tenant.setName("My Tenant");
        when(registrationService.register(any())).thenReturn(tenant);

        mockMvc.perform(post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("My Tenant"));
    }

    @Test
    @DisplayName("POST /auth/register retorna 400 com senha fraca")
    void shouldFailRegisterWithWeakPassword() throws Exception {
        TenantRegistrationDTO dto = new TenantRegistrationDTO(
                "My Tenant", "Admin", "admin@email.com", "12345678");

        mockMvc.perform(post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /auth/register retorna 400 com senha acima de 72 caracteres")
    void shouldFailRegisterWithTooLongPassword() throws Exception {
        String tooLong = "Senha123" + "a".repeat(70);
        TenantRegistrationDTO dto = new TenantRegistrationDTO(
                "My Tenant", "Admin", "admin@email.com", tooLong);

        mockMvc.perform(post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isBadRequest());
    }

    // --- login ---

    @Test
    @DisplayName("POST /auth/login seta cookie e retorna perfil")
    void shouldLoginSuccessfully() throws Exception {
        User user = buildUser();
        when(userRepository.findByEmail("admin@test.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password", "encoded_password")).thenReturn(true);
        when(tokenService.generateToken(user)).thenReturn("valid-token");

        mockMvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new LoginDTO("admin@test.com", "password"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("admin@test.com"))
                .andExpect(jsonPath("$.name").value("Test Admin"))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.tenantId").value("11111111-1111-1111-1111-111111111111"))
                .andExpect(header().string("Set-Cookie", containsString("auth_token=valid-token")))
                .andExpect(header().string("Set-Cookie", containsString("HttpOnly")));
    }

    @Test
    @DisplayName("POST /auth/login retorna 401 com credenciais inválidas")
    void shouldFailLogin() throws Exception {
        User user = buildUser();
        when(userRepository.findByEmail("admin@test.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "encoded_password")).thenReturn(false);

        mockMvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new LoginDTO("admin@test.com", "wrong"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /auth/login retorna 401 genérico quando usuário não existe")
    void shouldFailLoginWhenUserNotFound() throws Exception {
        when(userRepository.findByEmail("naoexiste@test.com")).thenReturn(Optional.empty());

        mockMvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new LoginDTO("naoexiste@test.com", "qualquer"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /auth/login retorna 401 quando usuário está inativo")
    void shouldFailLoginWhenUserInactive() throws Exception {
        User user = buildUser();
        user.setActive(false);
        when(userRepository.findByEmail("admin@test.com")).thenReturn(Optional.of(user));

        mockMvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new LoginDTO("admin@test.com", "password"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /auth/login retorna 429 quando rate limit excedido")
    void shouldReturn429WhenRateLimited() throws Exception {
        when(loginRateLimiter.isBlocked("admin@test.com")).thenReturn(true);

        mockMvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new LoginDTO("admin@test.com", "password"))))
                .andExpect(status().isTooManyRequests());

        verify(userRepository, never()).findByEmail(any());
    }

    // --- accept-invite ---

    @Test
    @DisplayName("POST /auth/accept-invite seta cookie e retorna perfil")
    void shouldAcceptInviteSuccessfully() throws Exception {
        User user = buildUser();
        when(invitationService.accept(any(AcceptInviteDTO.class))).thenReturn(user);
        when(tokenService.generateToken(user)).thenReturn("invite-token");

        mockMvc.perform(post("/auth/accept-invite")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        new AcceptInviteDTO("valid-token", "Test Admin", "Senha123"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("admin@test.com"))
                .andExpect(header().string("Set-Cookie", containsString("auth_token=invite-token")));
    }

    @Test
    @DisplayName("POST /auth/accept-invite retorna 400 com senha fraca")
    void shouldFailAcceptInviteWithWeakPassword() throws Exception {
        mockMvc.perform(post("/auth/accept-invite")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        new AcceptInviteDTO("valid-token", "João", "12345678"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /auth/accept-invite retorna 400 com campos obrigatórios ausentes")
    void shouldFailAcceptInviteWhenMissingFields() throws Exception {
        mockMvc.perform(post("/auth/accept-invite")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isBadRequest());
    }

    // --- /auth/me ---

    @Test
    @DisplayName("GET /auth/me retorna perfil quando cookie válido")
    void shouldReturnUserProfile() throws Exception {
        User user = buildUser();
        when(tokenService.validateToken("valid-token")).thenReturn(user.getEmail());
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));

        mockMvc.perform(get("/auth/me")
                .cookie(new Cookie("auth_token", "valid-token")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("admin@test.com"))
                .andExpect(jsonPath("$.name").value("Test Admin"))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.tenantId").value("11111111-1111-1111-1111-111111111111"));
    }

    @Test
    @DisplayName("GET /auth/me retorna 401 sem cookie")
    void shouldReturn401WithoutCookie() throws Exception {
        mockMvc.perform(get("/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    // --- logout ---

    @Test
    @DisplayName("POST /auth/logout expira o cookie auth_token")
    void shouldLogout() throws Exception {
        mockMvc.perform(post("/auth/logout"))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Set-Cookie", containsString("auth_token=")))
                .andExpect(header().string("Set-Cookie", containsString("Max-Age=0")));
    }
}
```

- [ ] **Step 2: Executar testes para confirmar falha**

```bash
cd backend && ./mvnw test -pl . -Dtest=AuthControllerTest -q 2>&1 | tail -30
```

Esperado: erros de compilação ou falhas — `LoginResponseDTO` removido, novos endpoints ausentes.

- [ ] **Step 3: Implementar `AuthController` completo**

Substituir o conteúdo de `AuthController.java` por:

```java
package com.fintech.api.controller;

import com.fintech.api.config.LoginRateLimiter;
import com.fintech.api.config.TokenService;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.AcceptInviteDTO;
import com.fintech.api.dto.LoginDTO;
import com.fintech.api.dto.TenantRegistrationDTO;
import com.fintech.api.dto.UserProfileResponseDTO;
import com.fintech.api.dto.RegisterResponseDTO;
import com.fintech.api.repository.UserRepository;
import com.fintech.api.service.InvitationService;
import com.fintech.api.service.TenantRegistrationService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final TenantRegistrationService registrationService;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final InvitationService invitationService;
    private final LoginRateLimiter loginRateLimiter;

    @PostMapping("/register")
    public ResponseEntity<RegisterResponseDTO> register(@RequestBody @Valid TenantRegistrationDTO dto) {
        Tenant newTenant = registrationService.register(dto);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new RegisterResponseDTO(newTenant.getId(), newTenant.getName()));
    }

    @PostMapping("/login")
    public ResponseEntity<UserProfileResponseDTO> login(
            @RequestBody @Valid LoginDTO data, HttpServletResponse response) {
        if (loginRateLimiter.isBlocked(data.email())) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }

        var userOpt = userRepository.findByEmail(data.email());
        boolean authenticated = userOpt.isPresent()
                && userOpt.get().isEnabled()
                && passwordEncoder.matches(data.password(), userOpt.get().getPasswordHash());

        if (!authenticated) {
            loginRateLimiter.registerFailure(data.email());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        loginRateLimiter.registerSuccess(data.email());
        User user = userOpt.get();
        String token = tokenService.generateToken(user);
        addAuthCookie(response, token);
        return ResponseEntity.ok(toProfileDTO(user));
    }

    @PostMapping("/accept-invite")
    public ResponseEntity<UserProfileResponseDTO> acceptInvite(
            @RequestBody @Valid AcceptInviteDTO dto, HttpServletResponse response) {
        User user = invitationService.accept(dto);
        String token = tokenService.generateToken(user);
        addAuthCookie(response, token);
        return ResponseEntity.ok(toProfileDTO(user));
    }

    @GetMapping("/me")
    public ResponseEntity<UserProfileResponseDTO> me(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(toProfileDTO(user));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletResponse response) {
        clearAuthCookie(response);
        return ResponseEntity.noContent().build();
    }

    private void addAuthCookie(HttpServletResponse response, String token) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from("auth_token", token)
                .httpOnly(true)
                .secure(true)
                .path("/")
                .maxAge(Duration.ofHours(2))
                .sameSite("Strict")
                .build()
                .toString());
    }

    private void clearAuthCookie(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from("auth_token", "")
                .httpOnly(true)
                .secure(true)
                .path("/")
                .maxAge(0)
                .sameSite("Strict")
                .build()
                .toString());
    }

    private UserProfileResponseDTO toProfileDTO(User user) {
        return new UserProfileResponseDTO(
                user.getEmail(),
                user.getName(),
                user.getRole().name(),
                user.getTenant().getId().toString()
        );
    }
}
```

**Nota:** O `AuthController` não implementa mais `AuthApi` (a interface gerada ainda referencia `LoginResponseDTO` em alguns operationIds — se houver conflito, remover o `implements AuthApi`).

- [ ] **Step 4: Adicionar `/auth/logout` a `permitAll` em `SecurityConfigurations`**

Em `SecurityConfigurations.java`, dentro do bloco `authorizeHttpRequests`, adicionar:

```java
.requestMatchers(HttpMethod.POST, "/auth/logout").permitAll()
```

Deve ficar junto com os outros `.permitAll()` de auth:

```java
.requestMatchers(HttpMethod.POST, "/auth/login").permitAll()
.requestMatchers(HttpMethod.POST, "/auth/register").permitAll()
.requestMatchers(HttpMethod.POST, "/auth/accept-invite").permitAll()
.requestMatchers(HttpMethod.POST, "/auth/logout").permitAll()
.requestMatchers(HttpMethod.GET, "/invites/*").permitAll()
```

- [ ] **Step 5: Executar todos os testes do backend**

```bash
cd backend && ./mvnw test -q 2>&1 | tail -20
```

Esperado: BUILD SUCCESS. Se algum teste falhar por referência a `LoginResponseDTO` em outros testes, corrigi-los removendo as referências.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/fintech/api/controller/AuthController.java \
        backend/src/main/java/com/fintech/api/config/SecurityConfigurations.java \
        backend/src/test/java/com/fintech/api/controller/AuthControllerTest.java
git commit -m "feat(auth): cookie httpOnly no login/accept-invite, endpoints /auth/me e /auth/logout"
```

---

### Task 6: Frontend — `AuthService` sem `localStorage`

**Files:**
- Modify: `frontend/src/app/core/services/auth.ts`
- Modify: `frontend/src/app/core/services/auth.spec.ts`
- Modify: `frontend/package.json` (remover `jwt-decode`)

**Interfaces:**
- Produces: `AuthService` com `currentUser: Signal<UserProfile | null>`, `isAdmin: Signal<boolean>`, `login()`, `logout()`, `loadCurrentUser()`, `isAuthenticated()`

- [ ] **Step 1: Escrever testes atualizados em `auth.spec.ts`**

```typescript
// frontend/src/app/core/services/auth.spec.ts
import { TestBed } from '@angular/core/testing';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { AuthService, UserProfile } from './auth';

const mockProfile: UserProfile = {
  email: 'admin@test.com',
  name: 'Test Admin',
  role: 'ADMIN',
  tenantId: '11111111-1111-1111-1111-111111111111',
};

describe('AuthService', () => {
  let service: AuthService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
      ],
    });
    // Prevent the constructor's loadCurrentUser() from running during setup
    httpMock = TestBed.inject(HttpTestingController);
    service = TestBed.inject(AuthService);
    // Flush the automatic /auth/me call from constructor
    const req = httpMock.expectOne('/auth/me');
    req.flush(null, { status: 401, statusText: 'Unauthorized' });
  });

  afterEach(() => httpMock.verify());

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  it('isAdmin retorna false quando não há usuário logado', () => {
    service.currentUser.set(null);
    expect(service.isAdmin()).toBe(false);
  });

  it('isAdmin retorna true para role ADMIN', () => {
    service.currentUser.set(mockProfile);
    expect(service.isAdmin()).toBe(true);
  });

  it('isAdmin retorna false para role USER', () => {
    service.currentUser.set({ ...mockProfile, role: 'USER' });
    expect(service.isAdmin()).toBe(false);
  });

  it('login hidrata currentUser com perfil retornado pela API', () => {
    service.login({ email: 'admin@test.com', password: 'Senha123' }).subscribe();

    const req = httpMock.expectOne('/auth/login');
    expect(req.request.withCredentials).toBeTrue();
    req.flush(mockProfile);

    expect(service.currentUser()).toEqual(mockProfile);
    expect(localStorage.getItem('auth_token')).toBeNull();
  });

  it('loadCurrentUser define currentUser null quando API retorna 401', () => {
    service.loadCurrentUser().subscribe();

    const req = httpMock.expectOne('/auth/me');
    req.flush(null, { status: 401, statusText: 'Unauthorized' });

    expect(service.currentUser()).toBeNull();
  });

  it('loadCurrentUser hidrata currentUser quando API retorna perfil', () => {
    service.loadCurrentUser().subscribe();

    const req = httpMock.expectOne('/auth/me');
    req.flush(mockProfile);

    expect(service.currentUser()).toEqual(mockProfile);
  });

  it('isAuthenticated retorna false quando currentUser é null', () => {
    service.currentUser.set(null);
    expect(service.isAuthenticated()).toBe(false);
  });

  it('isAuthenticated retorna true quando currentUser está definido', () => {
    service.currentUser.set(mockProfile);
    expect(service.isAuthenticated()).toBe(true);
  });
});
```

- [ ] **Step 2: Executar testes para confirmar falha**

```bash
cd frontend && npm test -- --run 2>&1 | grep -A5 "auth.spec"
```

Esperado: falhas — `UserProfile` não existe com os novos campos, métodos ausentes.

- [ ] **Step 3: Implementar o novo `auth.ts`**

```typescript
// frontend/src/app/core/services/auth.ts
import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { Observable, catchError, of, tap } from 'rxjs';

export interface UserProfile {
  email: string;
  name: string;
  role: 'ADMIN' | 'USER';
  tenantId: string;
}

@Injectable({ providedIn: 'root' })
export class AuthService {
  private http = inject(HttpClient);
  private router = inject(Router);

  currentUser = signal<UserProfile | null>(null);
  isAdmin = computed(() => this.currentUser()?.role === 'ADMIN');

  constructor() {
    this.loadCurrentUser().subscribe();
  }

  login(credentials: { email: string; password: string }): Observable<UserProfile> {
    return this.http
      .post<UserProfile>('/auth/login', credentials, { withCredentials: true })
      .pipe(tap(profile => this.currentUser.set(profile)));
  }

  logout(): void {
    this.http.post('/auth/logout', {}, { withCredentials: true }).subscribe();
    this.currentUser.set(null);
    this.router.navigate(['/login']);
  }

  loadCurrentUser(): Observable<UserProfile | null> {
    return this.http.get<UserProfile>('/auth/me', { withCredentials: true }).pipe(
      tap(profile => this.currentUser.set(profile)),
      catchError(() => {
        this.currentUser.set(null);
        return of(null);
      }),
    );
  }

  isAuthenticated(): boolean {
    return this.currentUser() !== null;
  }
}
```

- [ ] **Step 4: Executar testes**

```bash
cd frontend && npm test -- --run 2>&1 | grep -E "PASS|FAIL|auth\.spec"
```

Esperado: `auth.spec.ts` — todos passando.

- [ ] **Step 5: Remover `jwt-decode` do `package.json`**

```bash
cd frontend && npm uninstall jwt-decode
```

Confirmar que não há mais imports de `jwt-decode`:

```bash
grep -r "jwt-decode\|jwtDecode" frontend/src --include="*.ts"
```

Esperado: sem resultado.

- [ ] **Step 6: Executar todos os testes frontend**

```bash
cd frontend && npm test -- --run 2>&1 | tail -20
```

Esperado: sem falhas causadas pela remoção de `jwt-decode`.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/app/core/services/auth.ts \
        frontend/src/app/core/services/auth.spec.ts \
        frontend/package.json \
        frontend/package-lock.json
git commit -m "feat(frontend): AuthService migra para cookie httpOnly — remove localStorage e jwtDecode"
```

---

### Task 7: Frontend — interceptor, `AuthGuard` e `AcceptInviteComponent`

**Files:**
- Modify: `frontend/src/app/core/interceptors/auth.interceptor.ts`
- Modify: `frontend/src/app/core/guards/auth-guard.ts`
- Modify: `frontend/src/app/core/guards/auth-guard.spec.ts`
- Modify: `frontend/src/app/core/services/invitation.ts`
- Modify: `frontend/src/app/features/auth/accept-invite/accept-invite.ts`

**Interfaces:**
- Consumes: `AuthService.loadCurrentUser(): Observable<UserProfile | null>`, `AuthService.currentUser: Signal`
- Produces: todas as requests têm `withCredentials: true`; guard aguarda `/auth/me` antes de decidir

- [ ] **Step 1: Simplificar `auth.interceptor.ts`**

```typescript
// frontend/src/app/core/interceptors/auth.interceptor.ts
import { HttpInterceptorFn } from '@angular/common/http';

export const authInterceptor: HttpInterceptorFn = (req, next) =>
  next(req.clone({ withCredentials: true }));
```

- [ ] **Step 2: Implementar `auth-guard.ts` assíncrono**

```typescript
// frontend/src/app/core/guards/auth-guard.ts
import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { map } from 'rxjs';
import { AuthService } from '../services/auth';

export const authGuard: CanActivateFn = () => {
  const authService = inject(AuthService);
  const router = inject(Router);

  if (authService.currentUser() !== null) return true;

  return authService.loadCurrentUser().pipe(
    map(user => (user !== null ? true : router.createUrlTree(['/login']))),
  );
};
```

- [ ] **Step 3: Atualizar `auth-guard.spec.ts`**

```typescript
// frontend/src/app/core/guards/auth-guard.spec.ts
import { TestBed } from '@angular/core/testing';
import { CanActivateFn, Router, RouterStateSnapshot, ActivatedRouteSnapshot } from '@angular/router';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { authGuard } from './auth-guard';
import { AuthService, UserProfile } from '../services/auth';

const mockProfile: UserProfile = {
  email: 'a@test.com',
  name: 'A',
  role: 'ADMIN',
  tenantId: 'tid',
};

describe('authGuard', () => {
  let authService: AuthService;
  let httpMock: HttpTestingController;

  const executeGuard: CanActivateFn = (...params) =>
    TestBed.runInInjectionContext(() => authGuard(...params));

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
      ],
    });
    httpMock = TestBed.inject(HttpTestingController);
    authService = TestBed.inject(AuthService);
    // Flush constructor call
    httpMock.expectOne('/auth/me').flush(null, { status: 401, statusText: 'Unauthorized' });
  });

  afterEach(() => httpMock.verify());

  it('retorna true síncronamente quando currentUser já está carregado', () => {
    authService.currentUser.set(mockProfile);
    const result = executeGuard(
      {} as ActivatedRouteSnapshot,
      {} as RouterStateSnapshot,
    );
    expect(result).toBe(true);
  });

  it('aguarda /auth/me e retorna true quando autenticado', done => {
    authService.currentUser.set(null);
    const result = executeGuard(
      {} as ActivatedRouteSnapshot,
      {} as RouterStateSnapshot,
    ) as ReturnType<typeof authService.loadCurrentUser>;

    (result as any).subscribe((value: any) => {
      expect(value).toBe(true);
      done();
    });

    httpMock.expectOne('/auth/me').flush(mockProfile);
  });

  it('redireciona para /login quando não autenticado', done => {
    authService.currentUser.set(null);
    const router = TestBed.inject(Router);
    const result = executeGuard(
      {} as ActivatedRouteSnapshot,
      {} as RouterStateSnapshot,
    );

    (result as any).subscribe((value: any) => {
      expect(value).toEqual(router.createUrlTree(['/login']));
      done();
    });

    httpMock.expectOne('/auth/me').flush(null, { status: 401, statusText: 'Unauthorized' });
  });
});
```

- [ ] **Step 4: Atualizar `InvitationService.acceptInvite` — novo tipo de retorno**

Em `frontend/src/app/core/services/invitation.ts`, alterar:

```typescript
import { UserProfile } from './auth';

// ...

acceptInvite(dto: AcceptInviteRequest): Observable<UserProfile> {
  return this.http.post<UserProfile>('/auth/accept-invite', dto);
}
```

Remover a interface `{ token: string }` que não é mais usada pelo `acceptInvite`.

- [ ] **Step 5: Atualizar `AcceptInviteComponent.onSubmit()`**

Em `frontend/src/app/features/auth/accept-invite/accept-invite.ts`, substituir o bloco `next` do `subscribe`:

```typescript
next: (profile) => {
  this.authService.currentUser.set(profile);
  this.router.navigate(['/dashboard']);
},
```

- [ ] **Step 6: Executar todos os testes frontend**

```bash
cd frontend && npm test -- --run 2>&1 | tail -20
```

Esperado: BUILD SUCCESS, zero falhas.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/app/core/interceptors/auth.interceptor.ts \
        frontend/src/app/core/guards/auth-guard.ts \
        frontend/src/app/core/guards/auth-guard.spec.ts \
        frontend/src/app/core/services/invitation.ts \
        frontend/src/app/features/auth/accept-invite/accept-invite.ts
git commit -m "feat(frontend): interceptor withCredentials, AuthGuard assíncrono, AcceptInvite sem setToken"
```

---

### Task 8: Smoke test end-to-end

**Files:** nenhum arquivo novo — verificação manual

- [ ] **Step 1: Subir backend e frontend**

```bash
docker compose up -d
cd backend && ./mvnw spring-boot:run &
cd frontend && npm start &
```

- [ ] **Step 2: Verificar login**

Acessar `http://localhost:4200/login`. Logar com `faria@limer.com` / `limer123`.

Esperado:
- Rede: `POST /auth/login` retorna `{ email, name, role, tenantId }` com header `Set-Cookie: auth_token=...; HttpOnly; SameSite=Strict`
- `localStorage` não contém `auth_token`
- Redireciona para dashboard

- [ ] **Step 3: Verificar boot (F5)**

Com sessão ativa, dar F5. Esperado:
- `GET /auth/me` chamado automaticamente
- Usuário permanece logado, sem flash de tela de login

- [ ] **Step 4: Verificar logout**

Clicar em logout. Esperado:
- `POST /auth/logout` chamado com `Set-Cookie: auth_token=; Max-Age=0`
- Redireciona para `/login`
- F5 na tela protegida → redireciona para login

- [ ] **Step 5: Verificar acesso direto por URL sem sessão**

Em aba anônima, acessar `http://localhost:4200/dashboard`. Esperado: redireciona para `/login`.

- [ ] **Step 6: Commit final se houver ajustes**

```bash
git add -p  # staged apenas o que mudou no smoke test
git commit -m "fix(auth): ajustes pós smoke test de cookie httpOnly"
```
