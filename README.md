# Inventra API

API RESTful em Java + Spring Boot para gestão de estoque, requisições, inventários e alertas em cozinhas industriais.

- Autenticação JWT stateless + escopo por cozinha (`KitchenAccessGuard`)
- CRUD de cozinhas, categorias, unidades, fornecedores, produtos e usuários
- Lotes de estoque com consumo FIFO por validade, ajuste e detecção de estoque baixo
- Inventário físico com contagem por lote e ajuste automático no fechamento
- Requisições (compra / transferência / consumo) com máquina de estados e aprovação
- Alertas operacionais (validade, estoque baixo, ...)
- Integrações externas: **Cloudinary** (foto de produto) e **Open Food Facts** (barcode lookup)

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
| Spring Boot                 | 4.1.0   |
| Spring Security             | 7.x     |
| Spring Data JPA / Hibernate | 7.x     |
| PostgreSQL                  | 16      |
| Flyway                      | 11.x    |
| JWT (JJWT)                  | 0.12.6  |
| Cloudinary (http5)          | 2.4.0   |
| springdoc-openapi           | 3.0.3   |
| Lombok                      | 1.18    |
| Maven Wrapper               | incluso |

## Pré-requisitos

- **Java 21+** (o repositório inclui `.jdks/` com JDK 24 opcional).
- **PostgreSQL 16** acessível — o projeto usa Aiven em dev, `sslmode=require`.
- **Conta Cloudinary** — necessária para upload de foto de produto (`/api/products/{id}/photo`).
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
JWT_EXPIRATION_MS=86400000

# CORS
CORS_ALLOWED_ORIGINS=http://localhost:3000

# Cloudinary (dashboard > Settings > API Keys)
CLOUDINARY_CLOUD_NAME=
CLOUDINARY_API_KEY=
CLOUDINARY_API_SECRET=

# Login com Google (opcional): chave da conta de serviço do Firebase, o JSON puro ou em base64
FIREBASE_CREDENTIALS_JSON=
```

A lista completa, com os valores padrão, está em [`.env.example`](.env.example). A chave do Firebase **nunca** vai para o repositório; sem ela a API sobe normalmente e só `POST /api/auth/google` responde **503**.

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

A aplicação sobe em `http://localhost:8080`. O Flyway aplica as migrations de `src/main/resources/db/migration/` automaticamente na primeira execução.

> **Migrations não são editadas aqui.** O repositório [inventra-data-modeling-2](https://github.com/InventraTech/inventra-data-modeling-2) é a fonte da verdade do schema: toda mudança nasce lá (`postgre/migrations`) e é copiada byte a byte para `db/migration`, mantendo os mesmos nomes (`V001__...`, `V002__...`).

- **Swagger UI:** `http://localhost:8080/swagger-ui.html` — para chamar endpoints protegidos, faça login (ou registre-se), clique em **Authorize** e cole o `token` (sem o prefixo `Bearer`)
- **OpenAPI JSON:** `http://localhost:8080/v3/api-docs`
- **Health check:** `http://localhost:8080/actuator/health`

Rodar testes:

```bash
./mvnw test
```

## Endpoints

Documentação completa com payloads e exemplos em [`docs/docs.md`](docs/docs.md).

Resumo:

| Recurso            | Base path              | Acesso                                                             |
|:-------------------|:-----------------------|:-------------------------------------------------------------------|
| Autenticação       | `/api/auth`            | `POST /login`, `/register`, `/google`, `/refresh` e `/logout` públicos |
| Usuários           | `/api/users`           | Supervisor (criar/listar/ativar/desativar); cada um edita o próprio nome e senha |
| Perfis             | `/api/profiles`        | Leitura: autenticado — escrita: supervisor                         |
| Cozinhas           | `/api/kitchens`        | Leitura: autenticado — escrita: supervisor; escopo por cozinha. `GET /discover` lista as cozinhas abertas a pedidos de entrada (qualquer usuário autenticado) |
| Acesso à cozinha   | `/api/kitchen-access-requests` | Pedir entrada (por `kitchenId` ou `code`): autenticado sem cozinha — decidir: supervisor |
| Categorias         | `/api/categories`      | Leitura: autenticado — escrita: supervisor                         |
| Unidades de medida | `/api/units`           | Leitura: autenticado — escrita: supervisor                         |
| Fornecedores       | `/api/suppliers`       | Leitura: autenticado — escrita: supervisor                         |
| Produtos           | `/api/products`        | Leitura: autenticado — escrita: supervisor; parâmetros filtrados pela cozinha. `GET /by-barcode/{barcode}` consulta o catálogo e depois o OpenFoodFacts |
| Lotes de estoque   | `/api/stock-batches`   | Supervisor e estoquista — escopo por cozinha. `POST /consume` dá baixa por produto, nos lotes que vencem primeiro |
| Movimentações      | `/api/stock-movements` | Supervisor e estoquista — histórico de entradas, baixas e ajustes  |
| Inventários        | `/api/inventories`     | Supervisor e estoquista — escopo por cozinha                       |
| Requisições        | `/api/requisitions`    | Supervisor e comprador (aprovar/rejeitar: supervisor) — escopo por cozinha. Nasce `DRAFT`; `PATCH /{id}/submit` envia para análise |
| Alertas            | `/api/alerts`          | Consultar e marcar como lido: qualquer usuário da cozinha, só os alertas dele — criar/deletar: supervisor — `POST /unregistered-product`: supervisor e estoquista |
| Swagger / OpenAPI  | `/swagger-ui/**`, `/v3/api-docs/**` | Público                                               |
| Health             | `/actuator/health`     | Público                                                            |

Todos os endpoints protegidos exigem o header:

```
Authorization: Bearer <token>
```

**Regras de autorização:**

- Toda requisição (fora dos endpoints públicos acima) exige JWT válido; token inválido/expirado → **401**. Conta desativada (`active = false`) → **403** no login, e os tokens já emitidos param de valer na hora.
- **Papéis:** só existem `supervisor` (faz tudo), `estoquista` (fluxos de entrada e baixa de estoque) e `comprador` (requisições). Endpoint fora do papel → **403** (`@PreAuthorize` com as expressões de `infrastructure/security/Roles`).
- **Escopo por cozinha (`KitchenAccessGuard`):** todo usuário só enxerga dados da própria `user.kitchen`. Endpoints que recebem `kitchenId` validam e retornam **403** se divergir; listagens multi-cozinha filtram silenciosamente.
- Usuários **sem cozinha atribuída** (recém-cadastrados via `/api/auth/register`) não acessam nada com `kitchenId`. Um supervisor vincula o usuário à cozinha dele, ou o supervisor recém-cadastrado cria a própria cozinha (`POST /api/kitchens`) e já fica vinculado a ela. Ninguém vincula usuário a uma cozinha que não seja a sua.
- `POST /api/auth/register` aceita só `SUPERVISOR`, `ESTOQUISTA` e `COMPRADOR` como `accessType`.
- `password_hash` nunca aparece em respostas: o `User` só expõe `id`, `name`, `email`, `kitchen`, `profile`, `active`, `lastLogin`, `createdAt`.
- Desativar a conta ou trocar a senha invalida na hora os tokens já emitidos (o token carrega o `id` do usuário e uma impressão digital da senha). Login com 5 senhas erradas seguidas (mesmo e-mail + IP) bloqueia por 15 minutos (**429**).
- Os perfis base `supervisor`, `estoquista` e `comprador` não podem ser renomeados nem excluídos.
- Erros seguem o padrão **`application/problem+json`** (RFC 7807) — detalhes por status em [`docs/docs.md` §14](docs/docs.md#14-códigos-http-e-erros).

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
    ├── security/       # JwtService, JwtAuthenticationFilter, UserPrincipal, KitchenAccessGuard
    ├── config/         # SecurityConfig, CloudinaryConfig
    ├── client/         # Integrações externas (Cloudinary, OpenFoodFacts)
    └── exception/      # GlobalExceptionHandler + BusinessRuleException / ResourceNotFoundException / ImageStorageException

src/main/resources/
├── application.properties
└── db/migration/       # Flyway (V001..V009): copia exata de inventra-data-modeling-2/postgre/migrations
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
| CRUD completo via API | [INV2-15](https://inventraoficial.atlassian.net/browse/INV2-15) (+ INV2-181, 183, 184, 185, 186, 187, 188) | ✅ Feito | CRUD de cozinhas, categorias, unidades, fornecedores, produtos, usuários, perfis, lotes, inventários, requisições e alertas, além dos fluxos de negócio (consumo FIFO, contagem de inventário, aprovação de requisição, ciclo de vida da conta) | `core/service/`, `infrastructure/controller/` |
| Divisão de responsabilidades (padrão MVC em pacotes) | [INV2-13](https://inventraoficial.atlassian.net/browse/INV2-13) | ✅ Feito | `controller` → `service` (UseCase + impl) → `repository` → `domain`, com DTOs separados das entidades | ver [Estrutura](#estrutura) |
| Validação das entradas | [INV2-16](https://inventraoficial.atlassian.net/browse/INV2-16) | ✅ Feito | Bean Validation (`@Valid`, `@NotBlank`, `@Size`, ...) nos DTOs de request, mais regras de negócio nos services | `core/service/*/` (DTOs) |
| Tratamento de exceções centralizado | [INV2-17](https://inventraoficial.atlassian.net/browse/INV2-17) | ✅ Feito | `@RestControllerAdvice` converte validação, regra de negócio, recurso inexistente e erro de banco em respostas `application/problem+json` (RFC 7807) com o status HTTP adequado | `infrastructure/exception/GlobalExceptionHandler` |

### Extras

| Requisito | Ticket | Status | Como foi feito | Onde |
|:----------|:-------|:-------|:---------------|:-----|
| Swagger / OpenAPI | [INV2-18](https://inventraoficial.atlassian.net/browse/INV2-18) | ✅ Feito | springdoc-openapi com `@Operation` nos endpoints e esquema Bearer para o botão **Authorize** | `/swagger-ui.html`, `/v3/api-docs` |
| Procedures e Functions da disciplina Modelagem de Dados | [INV2-21](https://inventraoficial.atlassian.net/browse/INV2-21) | ✅ Feito | Procedures `sp_*` vêm da migration de regras de negócio (copiada do repo de modelagem) e são acionadas por `CALL` nativo nos repositories: aprovar/rejeitar/cancelar requisição, baixa e entrada de estoque, fechar inventário, expirar lotes e alertar vencimento (esta última por job diário) | `infrastructure/repository/`, `infrastructure/job/BatchExpirationJob`, `db/migration/` |
| Autenticação e autorização com Spring Security | [INV2-19](https://inventraoficial.atlassian.net/browse/INV2-19) | ✅ Feito | JWT stateless com refresh token, login com Google, RBAC por papel (`@PreAuthorize`), escopo por cozinha (`KitchenAccessGuard`) e bloqueio por tentativas de login | `infrastructure/security/`, `infrastructure/config/SecurityConfig` |
| Segunda API REST sobre banco NoSQL | [INV2-20](https://inventraoficial.atlassian.net/browse/INV2-20) | ❌ Não feito (cancelado) | Não foi criada uma segunda API. O mais próximo é o uso de **Redis** *dentro* desta API, como apoio a refresh tokens e pedidos de entrada na cozinha, e não como API REST independente | `infrastructure/redis/` |

### Entregas adicionais (fora do escopo da disciplina)

| Entrega | Ticket | Status |
|:--------|:-------|:-------|
| Fotos de produto no Cloudinary | [INV2-195](https://inventraoficial.atlassian.net/browse/INV2-195) | ✅ Feito |
| Testes unitários para todos os services | [INV2-199](https://inventraoficial.atlassian.net/browse/INV2-199) | ✅ Feito (`src/test/`) |
