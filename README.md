# Inventra API

API RESTful em Java + Spring Boot para gestão de estoque, requisições, inventários e alertas em cozinhas industriais.

- Autenticação JWT stateless com refresh token, login com Google, redefinição de senha por e-mail e escopo por cozinha (`KitchenAccessGuard`)
- CRUD de cozinhas, categorias, unidades, fornecedores, produtos e usuários
- Entrada na cozinha por pedido aprovado pelo supervisor, descoberta de cozinhas e saída/remoção de membros
- Lotes de estoque com consumo FIFO por validade, ajuste, detecção de estoque baixo e ranking de validade
- Inventário físico com contagem por lote e ajuste automático no fechamento
- Requisições (compra / transferência / consumo) com máquina de estados e aprovação
- Pré-listas de compra (somente leitura) e contador de cliques da interface
- Alertas operacionais (validade, estoque baixo, pedidos de entrada, requisições, ...)
- Integrações externas: **Cloudinary** (fotos de produto, fornecedor e usuário), **Open Food Facts** (barcode lookup), **Firebase** (login com Google), **SendGrid** (e-mail) e **Nominatim** (geocodificação de cozinhas)

## Sumário

- [Stack](#stack)
- [Pré-requisitos](#pré-requisitos)
- [Configuração](#configuração)
- [Rodando](#rodando)
- [Endpoints](#endpoints)
- [Estrutura](#estrutura)
- [Board no Jira](#board-no-jira)
- [Demandas de Desenvolvimento 2](#demandas-de-desenvolvimento-2)

## Stack

| Tecnologia                  | Versão  |
|:----------------------------|:--------|
| Java                        | 21      |
| Spring Boot                 | 4.1.1   |
| Spring Security             | 7.1     |
| Spring Data JPA / Hibernate | 7.4     |
| PostgreSQL                  | 16      |
| Flyway                      | 12.x    |
| Redis (Spring Data Redis)   | 7       |
| JWT (JJWT)                  | 0.12.6  |
| Firebase Admin              | 9.5.0   |
| Cloudinary (http5)          | 2.4.0   |
| springdoc-openapi           | 3.0.3   |
| Testcontainers              | 2.x     |
| Lombok                      | 1.18    |
| Maven Wrapper               | incluso |

## Pré-requisitos

- **Java 21+**.
- **PostgreSQL 16** acessível com SSL — o projeto usa Aiven em dev e a URL de conexão fixa `sslmode=require`.
- **Redis** acessível — guarda refresh tokens, pedidos de entrada na cozinha, redefinição de senha, fila de cadastro de produtos e ranking de validade.
- **Conta Cloudinary** — necessária para upload de fotos (`/api/products/{id}/photo`, `/api/suppliers/{id}/photo`, `/api/users/me/photo`).
- **Docker** (opcional) — usado pelos testes de integração com Testcontainers; sem ele, esses testes são ignorados.
- Arquivo `.env` na raiz do projeto com as credenciais (template em `.env.example`).

## Configuração

O `application.properties` lê variáveis do `.env` via `spring-dotenv` (`DotenvApplicationInitializer`).

**`.env` mínimo:**

```dotenv
# PostgreSQL
DB_HOST=localhost
DB_PORT=5432
DB_NAME=inventra
DB_USER=inventra_user
DB_PASSWORD=troque_isso

# JWT — gere com: openssl rand -base64 32
JWT_SECRET=uma_string_com_no_minimo_32_caracteres_aleatorios
# Access token de 30 min; o app renova com o refresh token de 30 dias
JWT_EXPIRATION_MS=1800000
JWT_REFRESH_EXPIRATION_MS=2592000000

# CORS
CORS_ALLOWED_ORIGINS=http://localhost:3000

# Cloudinary (dashboard > Settings > API Keys)
CLOUDINARY_CLOUD_NAME=
CLOUDINARY_API_KEY=
CLOUDINARY_API_SECRET=

# Redis
REDIS_HOST=
REDIS_PORT=
REDIS_USERNAME=
REDIS_PASSWORD=
REDIS_SSL=true

# Login com Google (opcional): chave da conta de serviço do Firebase, o JSON puro ou em base64
FIREBASE_CREDENTIALS_JSON=

# E-mail da redefinição de senha (opcional em dev): SendGrid e remetente verificado nele
SENDGRID_API_KEY=
MAIL_FROM=
PASSWORD_RESET_WEB_URL=http://localhost:3000/redefinir-senha
```

A lista completa, com os valores padrão, está em [`.env.example`](.env.example). A chave do Firebase **nunca** vai para o repositório; sem ela a API sobe normalmente e só `POST /api/auth/google` responde **503**. O mesmo vale para a chave do SendGrid: sem ela, os e-mails de redefinição de senha não são enviados, só aparecem no log (com o código), o que basta para testar localmente.

Uploads são limitados a **5 MB** por arquivo/request (`spring.servlet.multipart.max-file-size`).

## Rodando

**Windows (PowerShell):**

```powershell
.\mvnw.cmd spring-boot:run
```

**Linux / macOS:**

```bash
./mvnw spring-boot:run
```

A aplicação sobe em `http://localhost:8080` (ou na porta da variável `PORT`). O Flyway aplica as migrations pendentes de `src/main/resources/db/migration/` automaticamente ao subir.

> **Migrations não são editadas aqui.** O repositório [inventra-database-2](https://github.com/InventraTech/inventra-database-2) é a fonte da verdade do schema: toda mudança nasce lá (`postgre/migrations`) e é copiada byte a byte para `db/migration`, mantendo os mesmos nomes (`V001__...`, `V002__...`).

- **Swagger UI:** `http://localhost:8080/swagger-ui.html` — para chamar endpoints protegidos, faça login (ou registre-se), clique em **Authorize** e cole o `token` (sem o prefixo `Bearer`). Em produção, `SWAGGER_ENABLED=false` esconde o Swagger e o OpenAPI
- **OpenAPI JSON:** `http://localhost:8080/v3/api-docs`
- **Health check:** `http://localhost:8080/actuator/health` (também `/actuator/health/liveness` e `/actuator/health/readiness`)

Rodar testes:

```bash
./mvnw test
```

Os testes usam H2 e, com Docker disponível, PostgreSQL e Redis reais via Testcontainers (procedures, triggers e ranking). Os testes da fila de cadastro contra Redis real só rodam com `REDIS_INTEGRATION_TESTS=true` e as variáveis `REDIS_*` de uma instância dedicada a testes.

## Endpoints

Documentação completa com payloads e exemplos em [`docs/docs.md`](docs/docs.md).

Resumo:

| Recurso            | Base path              | Acesso                                                             |
|:-------------------|:-----------------------|:-------------------------------------------------------------------|
| Autenticação       | `/api/auth`            | `POST /login`, `/register`, `/google`, `/refresh`, `/logout` e `/password/forgot`, `/password/verify`, `/password/reset` públicos |
| Usuários           | `/api/users`           | Supervisor (criar/listar/ativar/desativar); cada um edita o próprio nome e senha. `GET /me`, `PUT`/`DELETE /me/photo` e `POST /me/leave-kitchen`: qualquer usuário autenticado |
| Perfis             | `/api/profiles`        | Só listagem (`GET`): autenticado                                   |
| Cozinhas           | `/api/kitchens`        | Leitura: autenticado — escrita: supervisor; escopo por cozinha. `GET /discover` lista as cozinhas abertas a pedidos de entrada e `GET /by-code/{code}` busca pelo código de convite (qualquer usuário autenticado). `GET /{kitchenId}/stock`: supervisor e estoquista. `DELETE /{kitchenId}/members/{userId}`: supervisor |
| Acesso à cozinha   | `/api/kitchen-access-requests` | Pedir entrada (por `kitchenId` ou `code`): autenticado sem cozinha — decidir: supervisor |
| Categorias         | `/api/categories`      | Leitura: autenticado — escrita: supervisor                         |
| Unidades de medida | `/api/units`           | Leitura: autenticado — escrita: supervisor                         |
| Fornecedores       | `/api/suppliers`       | Leitura: autenticado (`?active=false` só supervisor; `?kitchenId=` filtra pela própria cozinha) — escrita e foto: supervisor |
| Produtos           | `/api/products`        | Leitura: autenticado — escrita e foto: supervisor; parâmetros filtrados pela cozinha. `GET /by-barcode/{barcode}` consulta o catálogo e depois o OpenFoodFacts |
| Lotes de estoque   | `/api/stock-batches`   | Supervisor e estoquista — escopo por cozinha. `POST /consume` dá baixa por produto, nos lotes que vencem primeiro. `GET /ranking` traz os lotes por validade (Redis) |
| Movimentações      | `/api/stock-movements` | Supervisor e estoquista — histórico de entradas, baixas e ajustes  |
| Inventários        | `/api/inventories`     | Supervisor e estoquista — escopo por cozinha                       |
| Requisições        | `/api/requisitions`    | Supervisor e comprador (aprovar/rejeitar: supervisor) — escopo por cozinha. Nasce `DRAFT`; `PATCH /{id}/submit` envia para análise |
| Pré-listas         | `/api/pre-lists`       | Supervisor e comprador — somente leitura, escopo por cozinha       |
| Alertas            | `/api/alerts`          | Consultar e marcar como lido: qualquer usuário da cozinha, só os alertas dele — `POST /unregistered-product`: supervisor e estoquista. Os demais alertas nascem da própria API e do banco |
| Eventos de clique  | `/api/events/clicks`   | Enviar lote de cliques: qualquer usuário autenticado (o app guarda os cliques de antes do login e envia depois) — `GET /ranking`: supervisor; contador global, não por cozinha |
| Swagger / OpenAPI  | `/swagger-ui/**`, `/v3/api-docs/**` | Público                                               |
| Health             | `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness` | Público     |

Todos os endpoints protegidos exigem o header:

```
Authorization: Bearer <token>
```

**Regras de autorização:**

- Toda requisição (fora dos endpoints públicos acima) exige JWT válido; token inválido/expirado → **401**. Conta desativada (`active = false`) → **403** no login, e os tokens já emitidos param de valer na hora.
- **Papéis:** só existem `supervisor` (faz tudo), `estoquista` (fluxos de entrada e baixa de estoque) e `comprador` (requisições). Endpoint fora do papel → **403** (`@PreAuthorize` com as expressões de `infrastructure/security/Roles`).
- **Escopo por cozinha (`KitchenAccessGuard`):** todo usuário só enxerga dados da própria `user.kitchen`. Endpoints que recebem `kitchenId` validam e retornam **403** se divergir; listagens multi-cozinha filtram silenciosamente.
- Usuários **sem cozinha atribuída** (recém-cadastrados via `/api/auth/register`) não acessam nada com `kitchenId`. O usuário pede entrada em `/api/kitchen-access-requests` e um supervisor da cozinha aprova, ou o supervisor recém-cadastrado cria a própria cozinha (`POST /api/kitchens`) e já fica vinculado a ela. Ninguém vincula usuário a uma cozinha que não seja a sua.
- Sair da cozinha (`POST /api/users/me/leave-kitchen`) ou ser removido dela desfaz só o vínculo: a conta continua ativa e os rascunhos de requisição do usuário na cozinha são cancelados. O último supervisor de uma cozinha ativa não pode sair (**409**).
- `POST /api/auth/register` aceita só `SUPERVISOR`, `ESTOQUISTA` e `COMPRADOR` como `accessType`.
- `password_hash` nunca aparece em respostas: o `User` só expõe `id`, `name`, `email`, `photoUrl`, `kitchen`, `profile`, `active`, `hasPassword`, `lastLogin`, `createdAt`.
- Desativar a conta ou trocar a senha invalida na hora os tokens já emitidos (o token carrega o `id` do usuário e uma impressão digital da senha). Login com 5 senhas erradas seguidas (mesmo e-mail + IP) bloqueia por 15 minutos (**429**).
- Erros seguem o padrão **`application/problem+json`** (RFC 7807) — detalhes por status em [`docs/docs.md` §15](docs/docs.md#15-códigos-http-e-erros).

## Estrutura

```
src/main/java/com/inventra/api/
├── InventraApiApplication.java
├── core/
│   ├── domain/         # Entidades JPA (Kitchen, Product, StockBatch, ...) + enums
│   └── service/        # UseCase (interface) + Service (impl) + DTOs de request/response
└── infrastructure/
    ├── controller/     # REST controllers em /api/*
    ├── repository/     # Spring Data JPA
    ├── security/       # JwtService, JwtAuthenticationFilter, UserPrincipal, KitchenAccessGuard, Roles, limitadores
    ├── config/         # SecurityConfig, CloudinaryConfig, RedisConfig, EmailConfig, OpenApiConfig, ...
    ├── client/         # Integrações externas (Cloudinary, Firebase, Nominatim, OpenFoodFacts)
    ├── email/          # Envio de e-mail: SendGrid ou só log, sem a chave configurada
    ├── redis/          # Refresh tokens, pedidos de entrada, redefinição de senha, fila e ranking
    ├── job/            # BatchExpirationJob (lotes vencidos e alertas de validade, diário)
    └── exception/      # GlobalExceptionHandler + BusinessRuleException / ResourceNotFoundException / ImageStorageException / ...

src/main/resources/
├── application.properties
├── redis/              # Scripts Lua da fila de cadastro e do ranking
└── db/migration/       # Flyway (V001..V023): cópia exata de inventra-database-2/postgre/migrations
```

## Board no Jira

O planejamento e o acompanhamento do trabalho ficam no board do projeto **INV2**, com o épico **INV2-4 — Desenvolvimento 2** agrupando todas as demandas desta API:

👉 [Board INV2 no Jira](https://inventraoficial.atlassian.net/jira/software/projects/INV2/boards/81?filter=&groupBy=custom&selectedIssue=INV2-4)

## Demandas de Desenvolvimento 2

Checklist da disciplina e onde cada item foi entregue.

### Mínimo para o 7,0

| Requisito | Ticket | Status | Como foi feito | Onde |
|:----------|:-------|:-------|:---------------|:-----|
| API REST em Java com Spring MVC sobre PostgreSQL | [INV2-13](https://inventraoficial.atlassian.net/browse/INV2-13) | ✅ Feito | Projeto Spring Boot com controllers `@RestController` em `/api/*`, banco PostgreSQL versionado com Flyway | `infrastructure/controller/`, `application.properties` |
| Spring Data JPA integrado ao Postgres | [INV2-14](https://inventraoficial.atlassian.net/browse/INV2-14) | ✅ Feito | Entidades JPA e repositories `JpaRepository`, conexão configurada por variáveis do `.env` | `core/domain/`, `infrastructure/repository/` |
| CRUD completo via API | [INV2-15](https://inventraoficial.atlassian.net/browse/INV2-15) (+ INV2-181, 183, 184, 185, 186, 187, 188) | ✅ Feito | CRUD de cozinhas, categorias, unidades, fornecedores, produtos, usuários, lotes, inventários e requisições, além dos fluxos de negócio (consumo FIFO, contagem de inventário, aprovação de requisição, ciclo de vida da conta). Perfis e alertas mantêm só as rotas usadas pelos clientes web e mobile ([INV2-206](https://inventraoficial.atlassian.net/browse/INV2-206)) | `core/service/`, `infrastructure/controller/` |
| Divisão de responsabilidades (padrão MVC em pacotes) | [INV2-13](https://inventraoficial.atlassian.net/browse/INV2-13) | ✅ Feito | `controller` → `service` (UseCase + impl) → `repository` → `domain`, com DTOs separados das entidades | ver [Estrutura](#estrutura) |
| Validação das entradas | [INV2-16](https://inventraoficial.atlassian.net/browse/INV2-16) | ✅ Feito | Bean Validation (`@Valid`, `@NotBlank`, `@Size`, ...) nos DTOs de request, mais regras de negócio nos services | `core/service/*/` (DTOs) |
| Tratamento de exceções centralizado | [INV2-17](https://inventraoficial.atlassian.net/browse/INV2-17) | ✅ Feito | `@RestControllerAdvice` converte validação, regra de negócio, recurso inexistente e erro de banco em respostas `application/problem+json` (RFC 7807) com o status HTTP adequado | `infrastructure/exception/GlobalExceptionHandler` |

### Extras

| Requisito | Ticket | Status | Como foi feito | Onde |
|:----------|:-------|:-------|:---------------|:-----|
| Swagger / OpenAPI | [INV2-18](https://inventraoficial.atlassian.net/browse/INV2-18) | ✅ Feito | springdoc-openapi com `@Operation` nos endpoints e esquema Bearer para o botão **Authorize** | `/swagger-ui.html`, `/v3/api-docs` |
| Procedures e Functions da disciplina Modelagem de Dados | [INV2-21](https://inventraoficial.atlassian.net/browse/INV2-21) | ✅ Feito | Procedures `sp_*` vêm da migration de regras de negócio (copiada do repo de modelagem) e são acionadas por `CALL` nativo nos repositories: aprovar/rejeitar/cancelar requisição, baixa e entrada de estoque, fechar inventário, expirar lotes e alertar vencimento (esta última por job diário) | `infrastructure/repository/`, `infrastructure/job/BatchExpirationJob`, `db/migration/` |
| Autenticação e autorização com Spring Security | [INV2-19](https://inventraoficial.atlassian.net/browse/INV2-19) | ✅ Feito | JWT stateless com refresh token, login com Google, RBAC por papel (`@PreAuthorize`), escopo por cozinha (`KitchenAccessGuard`) e bloqueio por tentativas de login | `infrastructure/security/`, `infrastructure/config/SecurityConfig` |
| Segunda API REST sobre banco NoSQL | [INV2-20](https://inventraoficial.atlassian.net/browse/INV2-20) | ❌ Não feito (cancelado) | Não foi criada uma segunda API. O mais próximo é o uso de **Redis** *dentro* desta API, como apoio a refresh tokens, pedidos de entrada na cozinha, redefinição de senha, fila de cadastro e ranking de validade, e não como API REST independente | `infrastructure/redis/` |

### Entregas adicionais (fora do escopo da disciplina)

| Entrega | Ticket | Status |
|:--------|:-------|:-------|
| Fotos de produto no Cloudinary | [INV2-195](https://inventraoficial.atlassian.net/browse/INV2-195) | ✅ Feito |
| Testes unitários para todos os services | [INV2-199](https://inventraoficial.atlassian.net/browse/INV2-199) | ✅ Feito (`src/test/`) |
| Configuração e integração do Redis | [INV2-24](https://inventraoficial.atlassian.net/browse/INV2-24) | ✅ Feito |
| Fila de cadastro de produtos no Redis | [INV2-25](https://inventraoficial.atlassian.net/browse/INV2-25) | ✅ Feito (rotas HTTP removidas na INV2-206; o consumer segue na API) |
| Ranking de validade no Redis | [INV2-26](https://inventraoficial.atlassian.net/browse/INV2-26) | ✅ Feito |
| Pré-listas de compra | [INV2-138](https://inventraoficial.atlassian.net/browse/INV2-138) | ✅ Feito |
| Contador de cliques da interface | [INV2-201](https://inventraoficial.atlassian.net/browse/INV2-201) | ✅ Feito |
| Redefinição de senha por e-mail | [INV2-203](https://inventraoficial.atlassian.net/browse/INV2-203) | ✅ Feito |
| Remoção de rotas sem uso pelos clientes | [INV2-206](https://inventraoficial.atlassian.net/browse/INV2-206) | ✅ Feito |
| Saída da cozinha e remoção de membros | [INV2-207](https://inventraoficial.atlassian.net/browse/INV2-207) | ✅ Feito |
| Fornecedores da cozinha pelos vínculos com produtos | [INV2-210](https://inventraoficial.atlassian.net/browse/INV2-210) | ✅ Feito |
