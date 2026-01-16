# 🚀 Projeto Fintech SaaS - Sumário da Jornada

Este documento detalha a evolução da arquitetura Fullstack desenvolvida, as decisões técnicas e os desafios superados desde o início.

---

## 🏛️ 1. Fundação da Arquitetura
O objetivo central é criar uma estrutura **SaaS Multi-Tenant**, onde uma única instância do sistema atende múltiplos clientes de forma isolada e segura.

* **Backend:** Spring Boot 3.4+ com Java 21.
* **Frontend:** Angular 21 (Utilizando o novo padrão **Zoneless** para alta performance).
* **Banco de Dados:** PostgreSQL 16.
* **Versionamento de Banco:** Flyway (Migrations).
* **Comunicação:** REST API.

---

## 💾 2. Modelagem de Dados e Backend
Iniciamos definindo a hierarquia de dados e o isolamento entre contas:
1.  **Tenant:** Representa o cliente (Empresa ou Família). Cada Tenant possui seu próprio identificador único (UUID).
2.  **User:** Indivíduos pertencentes a um Tenant específico.
3.  **Segurança de IDs:** Implementação de UUIDs para evitar ataques de enumeração de IDs sequenciais.

### Desafios Superados:
* **Flyway Migrations:** Configuração de scripts SQL para garantir que a estrutura do banco seja versionada junto com o código.
* **DTOs (Data Transfer Objects):** Criação de contratos de entrada e saída para proteger as entidades de domínio e garantir validações rigorosas com `Bean Validation`.

---

## 🎨 3. Interface e Experiência do Usuário (Frontend)
Utilizamos o **Angular Material** para implementar o Material Design 3.

* **Zoneless Change Detection:** Implementação do `provideZonelessChangeDetection()`, eliminando a necessidade da `zone.js`.
* **Signals:** Uso de `signal`, `computed` e `effect` para gerenciamento de estado reativo e eficiente.
* **Componentização:** Criação do fluxo de cadastro (`RegisterComponent`) integrando o registro do Tenant e do Usuário Administrador em uma única transação de UI.

---

## 🔐 4. Segurança e Autenticação (JWT)
Este foi o marco de proteção do sistema, saindo do modelo de sessão para o modelo **Stateless**.

### Pilares da Segurança implementados:
* **Password Hashing:** Uso de `BCrypt` para garantir que senhas nunca sejam armazenadas em texto puro.
* **JWT (JSON Web Token):** Implementação de geração e validação de tokens assinados com chave secreta.
* **Security Filter:** Um filtro customizado que intercepta cada requisição HTTP para validar o token no Header `Authorization`.
* **RBAC (Role-Based Access Control):** Configuração inicial de permissões baseadas em funções (ex: `ROLE_USER`).

---

## 🛠️ 5. Resolução de Conflitos e Debugging
Principais correções realizadas durante o desenvolvimento:
* **TS2724/TS2322:** Correção de tipos e nomes de métodos que mudaram na versão estável do Angular 21.
* **Erro 403 Forbidden:** Diagnóstico de erros de autorização no Spring Security. Descobrimos que o Spring bloqueava rotas inexistentes (404) transformando-as em 403.
* **CORS Configuration:** Ajuste fino para permitir que o Frontend (porta 4200) se comunique com o Backend (porta 8080) com segurança.

---

## 📅 Status Atual
- [x] Estrutura de Pastas e Projetos.
- [x] Banco de Dados e Migrations Iniciais.
- [x] Cadastro de Tenant/Usuário (Fullstack).
- [x] Infraestrutura de Segurança JWT (Backend).
- [x] Implementação da Tela de Login (Frontend).
- [x] Persistência de Sessão e Interceptadores de Requisição.
- [ ] Gestão de Transações Financeiras.
