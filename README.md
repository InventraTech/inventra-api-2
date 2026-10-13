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
```

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
| Autenticação       | `/api/auth`            | `POST /login` e `POST /register` públicos                          |
| Usuários           | `/api/users`           | Autenticado                                                        |
| Perfis             | `/api/profiles`        | Autenticado                                                        |
| Cozinhas           | `/api/kitchens`        | Autenticado                                                        |
| Categorias         | `/api/categories`      | Autenticado                                                        |
| Unidades de medida | `/api/units`           | Autenticado                                                        |
| Fornecedores       | `/api/suppliers`       | Autenticado                                                        |
| Produtos           | `/api/products`        | Autenticado — parâmetros e listagens filtrados pela cozinha        |
| Lotes de estoque   | `/api/stock-batches`   | Autenticado — escopo por cozinha                                   |
| Inventários        | `/api/inventories`     | Autenticado — escopo por cozinha                                   |
| Requisições        | `/api/requisitions`    | Autenticado — escopo por cozinha                                   |
| Alertas            | `/api/alerts`          | Autenticado — escopo por cozinha                                   |
| Swagger / OpenAPI  | `/swagger-ui/**`, `/v3/api-docs/**` | Público                                               |
| Health             | `/actuator/health`     | Público                                                            |

Todos os endpoints protegidos exigem o header:

```
Authorization: Bearer <token>
```

**Regras de autorização:**

- Toda requisição (fora dos endpoints públicos acima) exige JWT válido; token inválido/expirado → **401**. Conta desativada (`active = false`) → **403** no login.
- **Escopo por cozinha (`KitchenAccessGuard`):** todo usuário só enxerga dados da própria `user.kitchen`. Endpoints que recebem `kitchenId` validam e retornam **403** se divergir; listagens multi-cozinha filtram silenciosamente.
- Usuários **sem cozinha atribuída** (recém-cadastrados via `/api/auth/register`) só conseguem usar endpoints administrativos sem `kitchenId` (perfis, cozinhas, categorias, unidades, fornecedores, usuários).
- `POST /api/auth/register` não aceita `ADMIN` como `accessType` — contas admin não são criadas por essa rota.
- `password_hash` nunca aparece em respostas: o `User` só expõe `id`, `name`, `email`, `kitchen`, `profile`, `active`, `lastLogin`, `createdAt`.
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
└── db/migration/       # Flyway (V1__init_database.sql, ...)
```

## Coleção Bruno

O diretório [`bruno/`](bruno/) contém uma coleção pronta para o [Bruno](https://www.usebruno.com/), organizada por recurso (`auth`, `users`, `kitchens`, `products`, `stock-batches`, `inventories`, `requisitions`, `alerts`, ...) com environments em `bruno/environments/`.

Basta abrir a pasta no Bruno, escolher um environment, rodar `auth/Login` para popular o token e disparar as demais requisições — o header `Authorization` é preenchido pela variável do environment.
