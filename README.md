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
- [Coleção Bruno](#coleção-bruno)

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

## Coleção Bruno

O diretório [`bruno/`](bruno/) contém uma coleção pronta para o [Bruno](https://www.usebruno.com/), organizada por recurso (`auth`, `users`, `kitchens`, `products`, `stock-batches`, `inventories`, `requisitions`, `alerts`, ...) com environments em `bruno/environments/`.

Basta abrir a pasta no Bruno, escolher um environment, rodar `auth/Login` para popular o token e disparar as demais requisições — o header `Authorization` é preenchido pela variável do environment.
