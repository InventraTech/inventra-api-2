# Inventra API — Documentação

**Base URL:** `http://localhost:8080`
**Formato:** JSON
**Auth:** JWT no header `Authorization: Bearer <token>`

## Sumário

1. [Autenticação](#1-autenticação)
2. [Usuários](#2-usuários)
3. [Perfis](#3-perfis)
4. [Cozinhas](#4-cozinhas)
5. [Categorias](#5-categorias)
6. [Unidades de medida](#6-unidades-de-medida)
7. [Fornecedores](#7-fornecedores)
8. [Produtos](#8-produtos)
9. [Lotes de estoque](#9-lotes-de-estoque)
10. [Inventários](#10-inventários)
11. [Requisições](#11-requisições)
12. [Alertas](#12-alertas)
13. [Enums](#13-enums)
14. [Códigos HTTP e erros](#14-códigos-http-e-erros)

---

## Convenções

- `User.id` é **UUID**. Os demais recursos (`Kitchen`, `Product`, `Category`, `Unit`, `Supplier`, `Alert`, `Inventory`, `InventoryCount`, `StockBatch`, `Requisition`, `RequisitionItem`, `Profile`) usam **Integer**.
- Timestamps em **ISO-8601** (`2026-09-24T14:30:00`). Datas puras (`entryDate`, `expirationDate`) em `yyyy-MM-dd`.
- Números decimais em `BigDecimal`:
  - Quantidades → precisão `(12,3)` (até 3 casas)
  - Preços → precisão `(12,2)`
- **Escopo por cozinha:** todo usuário só acessa dados da própria `kitchen`. Endpoints com `kitchenId` no path/body/query validam contra `user.kitchen` → **403** se divergir; listagens multi-cozinha (`/stock-batches/low-stock`, `/stock-batches?productId=`, `/requisitions?status=`, `/requisitions?requesterId=`) filtram silenciosamente.
- Endpoints paginados aceitam `?page=0&size=20&sort=name,asc` e respondem no formato `Page<T>` do Spring (`content`, `totalElements`, `totalPages`, `pageable`, ...).
- Todos os endpoints exigem `Authorization: Bearer <token>`, **exceto** `POST /api/auth/login`, `GET /swagger-ui/**`, `GET /v3/api-docs/**`, `GET /actuator/health`.
- Token inválido/expirado → **401**. Conta desativada (`active = false`) → **403**.
- `password_hash` nunca aparece em respostas — o `User` só expõe `id`, `name`, `email`, `kitchen`, `profile`, `active`, `lastLogin`, `createdAt`.

---

## 1. Autenticação

`/api/auth`

### 1.1 Login

`POST /api/auth/login` — **público**

```json
{
  "email": "maria@example.com",
  "password": "senhaForte123"
}
```

**200 OK** — `LoginResponse`:

```json
{
  "token": "eyJhbGciOiJIUzI1NiIs...",
  "tokenType": "Bearer",
  "expiresIn": 86400,
  "user": {
    "id": "8f1c9b2a-...",
    "name": "Maria",
    "email": "maria@example.com",
    "kitchen": { "id": 1, "name": "Cozinha Central" },
    "profile": { "id": 2, "accessType": "supervisor" },
    "active": true,
    "lastLogin": "2026-09-24T09:12:33",
    "createdAt": "2026-08-20T14:02:11"
  }
}
```

`expiresIn` está em **segundos** (`JWT_EXPIRATION_MS / 1000`).

**Erros:** **401** se credenciais inválidas; **403** se `active = false` (`Conta desativada. Contate um administrador.`).

### 1.2 Auto-cadastro

`POST /api/auth/register`

```json
{
  "name": "Maria",
  "email": "maria@example.com",
  "password": "senhaForte123",
  "accessType": "SUPERVISOR"
}
```

**Validações:**
- `name` até 120 chars.
- `email` formato válido, até 150 chars, **único**.
- `password` 8–100 chars (armazenado com BCrypt).
- `accessType`: `SUPERVISOR` | `ESTOQUISTA` | `COMPRADOR`. `ADMIN` **não** é aceito aqui.

**201 Created** — mesmo `LoginResponse` do 1.1 (usuário já autenticado). O usuário é criado **sem cozinha**; um admin precisa atribuir depois via `PUT /api/users/{id}`.

**Erros:** **409** se e-mail já cadastrado.

O `Profile` correspondente ao `accessType` (`supervisor`, `estoquista` ou `comprador`, em minúsculas) é criado em `tb_profile` no primeiro cadastro que o usa, caso ainda não exista.

---

## 2. Usuários

`/api/users` — todos protegidos.

### 2.1 Criar usuário

`POST /api/users`

```json
{
  "name": "João",
  "email": "joao@example.com",
  "password": "senha12345",
  "kitchenId": 1,
  "profileId": 2
}
```

**Validações:** `email` único; `profileId` obrigatório e existente; `kitchenId` opcional (usuário fica sem escopo até ser atribuído).

**201 Created** — retorna `UserResponse` com header `Location: /api/users/{id}`.

**Erros:** **409** se e-mail já existe; **404** se `profileId` / `kitchenId` inexistentes.

### 2.2 Listar usuários

`GET /api/users` → array de `UserResponse`.

### 2.3 Buscar por ID

`GET /api/users/{id}` — `id` = UUID.

**200 OK** — `UserResponse`; **404** se não existir.

### 2.4 Atualizar usuário

`PUT /api/users/{id}`

```json
{
  "name": "João Silva",
  "kitchenId": 2,
  "profileId": 3
}
```

Todos os campos são opcionais — só aplica os enviados. `kitchenId`/`profileId` inexistentes → **404**.

### 2.5 Trocar senha

`PATCH /api/users/{id}/password`

```json
{
  "currentPassword": "senha12345",
  "newPassword": "novaSenhaForte"
}
```

**204 No Content**.

**Erros:** **409** (`Senha atual incorreta.`) se `currentPassword` não bater com o hash armazenado.

### 2.6 Ativar / desativar

`PATCH /api/users/{id}/activate` → **204**
`PATCH /api/users/{id}/deactivate` → **204** — bloqueia login (`DisabledException` → **403** no login).

---

## 3. Perfis

`/api/profiles`

Perfis de acesso (roles). O `AuthService` casa `AccessType` do registro com `profile.accessType` em **lowercase** (`admin`, `supervisor`, `estoquista`, `comprador`).

### 3.1 Criar

`POST /api/profiles`

```json
{ "accessType": "supervisor", "description": "Supervisão de cozinha" }
```

**Validações:** `accessType` único, até 50 chars; `description` até 255.

**201 Created** — `ProfileResponse` `{ id, accessType, description }`.

### 3.2 Listar / buscar / atualizar / deletar

- `GET /api/profiles` — array.
- `GET /api/profiles/{id}`.
- `PUT /api/profiles/{id}` — mesmo corpo do POST.
- `DELETE /api/profiles/{id}` → **204**.

---

## 4. Cozinhas

`/api/kitchens`

### 4.1 Criar

`POST /api/kitchens`

```json
{ "name": "Cozinha Central", "code": "CC-001", "address": "Rua X, 123" }
```

**Validações:** `name` até 120; `code` **único**, até 20; `address` até 255.

**201 Created** — `KitchenResponse`:

```json
{
  "id": 1,
  "name": "Cozinha Central",
  "code": "CC-001",
  "address": "Rua X, 123",
  "active": true,
  "createdAt": "2026-09-24T09:00:00"
}
```

### 4.2 Listar ativas

`GET /api/kitchens` → só as ativas.

### 4.3 Buscar

- `GET /api/kitchens/{id}` — por ID.
- `GET /api/kitchens/by-code/{code}` — pelo código único.

### 4.4 Atualizar

`PUT /api/kitchens/{id}`

```json
{ "name": "Novo nome", "address": "Novo endereço" }
```

`code` é **imutável**.

### 4.5 Ativar / desativar

`PATCH /api/kitchens/{id}/activate` → **204**
`PATCH /api/kitchens/{id}/deactivate` → **204**

---

## 5. Categorias

`/api/categories`

### 5.1 Criar

`POST /api/categories`

```json
{ "name": "Secos", "description": "Farinhas, açúcares, etc." }
```

**Validações:** `name` obrigatório, até 80; `description` até 255.

### 5.2 Listar / buscar / atualizar / deletar

- `GET /api/categories`
- `GET /api/categories/{id}`
- `PUT /api/categories/{id}` — ambos os campos opcionais.
- `DELETE /api/categories/{id}` → **204**.

`CategoryResponse`: `{ id, name, description }`.

---

## 6. Unidades de medida

`/api/units`

### 6.1 CRUD

- `POST /api/units` — `{ "symbol": "kg", "description": "Quilograma" }` → **201**
- `GET /api/units` — array
- `GET /api/units/{id}`
- `PUT /api/units/{id}`
- `DELETE /api/units/{id}` → **204**

`UnitResponse`: `{ id, symbol, description }`.

---

## 7. Fornecedores

`/api/suppliers`

### 7.1 Criar

`POST /api/suppliers`

```json
{
  "legalName": "Fornecedor X LTDA",
  "cnpj": "12.345.678/0001-99",
  "email": "contato@fornecedorx.com",
  "whatsapp": "11999999999",
  "rating": 4
}
```

**Validações:** `legalName` obrigatório, até 150; `cnpj` obrigatório, até 18, **único**; `email` formato válido; `rating` entre 1 e 5.

**201 Created** — `SupplierResponse` `{ id, legalName, cnpj, email, whatsapp, rating, active, createdAt }`.

### 7.2 Listar ativos

`GET /api/suppliers` → só os ativos.

### 7.3 Buscar / atualizar

- `GET /api/suppliers/{id}`
- `PUT /api/suppliers/{id}` — mesmo corpo, exceto `cnpj` (imutável).

### 7.4 Ativar / desativar

`PATCH /api/suppliers/{id}/activate` → **204**
`PATCH /api/suppliers/{id}/deactivate` → **204**

---

## 8. Produtos

`/api/products`

### 8.1 Criar

`POST /api/products`

```json
{
  "name": "Farinha de trigo",
  "brand": "Marca X",
  "categoryId": 3,
  "unitId": 1,
  "barcode": "7891234567890",
  "photoUrl": null
}
```

**Validações:** `name` até 150; `brand` até 80; `unitId` obrigatório; `categoryId` opcional; `barcode` **único**, até 50; `photoUrl` opcional (para upload de arquivo, use 8.5).

**Erros:** **409** se `barcode` já existir; **404** se `unitId` / `categoryId` inexistentes.

**201 Created** — `ProductResponse`:

```json
{
  "id": 42,
  "name": "Farinha de trigo",
  "brand": "Marca X",
  "category": { "id": 3, "name": "Secos" },
  "unit": { "id": 1, "symbol": "kg" },
  "barcode": "7891234567890",
  "photoUrl": null,
  "active": true,
  "createdAt": "2026-09-24T09:00:00"
}
```

### 8.2 Buscar

`GET /api/products/{id}` → `ProductResponse`.

### 8.3 Listar (paginado, com filtros)

`GET /api/products?name=&categoryId=&active=&page=0&size=20&sort=name,asc`

Todos os filtros opcionais. Resposta é um `Page<ProductResponse>` do Spring.

### 8.4 Atualizar

`PUT /api/products/{id}`

```json
{
  "name": "Farinha de trigo tipo 1",
  "brand": "Marca Y",
  "categoryId": 3,
  "unitId": 1,
  "barcode": "7891234567890"
}
```

Todos opcionais. Se `barcode` mudar, é revalidado contra duplicidade.

### 8.5 Upload de foto

`PUT /api/products/{id}/photo` — `multipart/form-data`, campo `file`.

```bash
curl -X PUT http://localhost:8080/api/products/42/photo \
     -H "Authorization: Bearer $TOKEN" \
     -F "file=@caminho/produto.jpg"
```

- **Formatos aceitos:** JPEG, PNG, WebP (validado por *magic bytes*, não pelo Content-Type do cliente).
- **Tamanho máximo:** 5 MB.
- **`public_id` no Cloudinary:** `inventra/products/{id}` — sobrescreve versão anterior.

**200 OK** — `ProductResponse` atualizado com `photoUrl`.

**Erros:** **400** formato inválido / arquivo vazio / parte `file` ausente; **413** > 5 MB; **502** falha no Cloudinary.

### 8.6 Remover foto

`DELETE /api/products/{id}/photo` — deleta no Cloudinary e limpa a coluna.

### 8.7 Ativar / desativar

`PATCH /api/products/{id}/activate` → **204**
`PATCH /api/products/{id}/deactivate` → **204**

### 8.8 Vincular fornecedor

`POST /api/products/{id}/suppliers`

```json
{
  "supplierId": 7,
  "supplierCode": "SUP-FARINHA-01",
  "referencePrice": 8.90,
  "leadTimeDays": 3
}
```

**204 No Content**. Insere ou atualiza o vínculo (chave composta `product_id + supplier_id`). `referencePrice` é usado como sugestão em requisições sem preço.

### 8.9 Listar fornecedores vinculados

`GET /api/products/{id}/suppliers` → array:

```json
[
  {
    "supplier": { "id": 7, "legalName": "Fornecedor X" },
    "supplierCode": "SUP-FARINHA-01",
    "referencePrice": 8.90,
    "leadTimeDays": 3
  }
]
```

### 8.10 Definir parâmetros por cozinha

`PUT /api/products/{id}/kitchen-parameters`

```json
{
  "kitchenId": 1,
  "minStock": 10.000,
  "maxStock": 100.000,
  "averageDailyConsumption": 2.500
}
```

**Validações:** `kitchenId` no escopo do usuário; `minStock` default `0` se ausente.

**204 No Content**. Insere ou atualiza (chave composta `product_id + kitchen_id`).

### 8.11 Listar parâmetros por cozinha

`GET /api/products/{id}/kitchen-parameters` — só as cozinhas do usuário. Cada item inclui métricas calculadas em tempo real:

```json
[
  {
    "kitchen": { "id": 1, "name": "Central" },
    "minStock": 10.000,
    "maxStock": 100.000,
    "averageDailyConsumption": 2.500,
    "currentQuantity": 8.000,
    "estimatedDaysUntilStockout": 3.2,
    "lowStock": true,
    "overStock": false
  }
]
```

- `currentQuantity` = soma dos `currentQuantity` dos lotes `ACTIVE`.
- `estimatedDaysUntilStockout` = `currentQuantity / averageDailyConsumption` (`null` se consumo médio ≤ 0).
- `lowStock` = `currentQuantity < minStock`.
- `overStock` = `maxStock != null && currentQuantity > maxStock`.

### 8.12 Consulta por código de barras (Open Food Facts)

`GET /api/products/barcode-lookup?barcode=7891234567890`

Consome `https://world.openfoodfacts.org/api/v2/product/{ean}.json`.

**200 OK**

```json
{
  "name": "Farinha de trigo tipo 1",
  "brand": "Marca X",
  "imageUrl": "https://images.openfoodfacts.org/...",
  "quantity": "1 kg"
}
```

**Erros:** **404** se o barcode não existir no catálogo; **502** se o serviço estiver fora.

---

## 9. Lotes de estoque

`/api/stock-batches`

Cada entrada de nota gera um lote. Consumo e ajustes alteram `currentQuantity` e podem mudar o `status`.

### 9.1 Registrar entrada

`POST /api/stock-batches`

```json
{
  "productId": 42,
  "kitchenId": 1,
  "supplierId": 7,
  "batchNumber": "LOT-2026-001",
  "invoiceNumber": "NF-9821",
  "initialQuantity": 50.000,
  "entryDate": "2026-09-01",
  "expirationDate": "2026-12-31",
  "unitPrice": 8.90
}
```

**Validações:** `productId`, `kitchenId`, `batchNumber`, `initialQuantity`, `entryDate` obrigatórios; `kitchenId` no escopo do usuário; `supplierId`/`invoiceNumber`/`expirationDate`/`unitPrice` opcionais.

**201 Created** — `StockBatchResponse`:

```json
{
  "id": 101,
  "product": { "id": 42, "name": "Farinha de trigo" },
  "kitchen": { "id": 1, "name": "Central" },
  "supplier": { "id": 7, "legalName": "Fornecedor X" },
  "batchNumber": "LOT-2026-001",
  "invoiceNumber": "NF-9821",
  "initialQuantity": 50.000,
  "currentQuantity": 50.000,
  "entryDate": "2026-09-01",
  "expirationDate": "2026-12-31",
  "unitPrice": 8.90,
  "status": "ACTIVE"
}
```

### 9.2 Listar

`GET /api/stock-batches?kitchenId=1`
`GET /api/stock-batches?productId=42`

Informe **apenas um** dos dois filtros. Sem nenhum → **400**.

### 9.3 Lotes vencendo em breve

`GET /api/stock-batches/expiring-soon?kitchenId=1&days=7`

Retorna lotes `ACTIVE` cuja `expirationDate` está entre hoje e `hoje + N dias`.

### 9.4 Produtos abaixo do mínimo

`GET /api/stock-batches/low-stock`

Só considera cozinhas do usuário. Retorna:

```json
[
  { "kitchenId": 1, "productId": 42, "currentQuantity": 8.000, "minStock": 10.000 }
]
```

### 9.5 Consumir lote

`PATCH /api/stock-batches/{id}/consume`

```json
{ "quantity": 5.000 }
```

**Validações:** `quantity > 0`.

**Regras:**
- `quantity > currentQuantity` → **409** (`Quantidade solicitada maior que o saldo do lote.`).
- A baixa é feita pela procedure `sp_write_off_stock`.
- Se `currentQuantity` chegar a 0, o trigger `trg_update_batch_status` muda `status` para `WRITTEN_OFF`.

**200 OK** — `StockBatchResponse` atualizado.

### 9.6 Ajustar quantidade absoluta

`PATCH /api/stock-batches/{id}/adjust`

```json
{ "newQuantity": 0.000 }
```

**Validações:** `newQuantity >= 0`.

**Regras:**
- `newQuantity = 0` → `status = WRITTEN_OFF`.
- Ajustar `> 0` um lote `WRITTEN_OFF` **reativa** (`status = ACTIVE`).

### 9.7 Repor quantidade

`PATCH /api/stock-batches/{id}/restock`

Soma uma quantidade ao saldo de um lote **já existente** (diferente de 9.1, que cria um lote novo). Usa a procedure `sp_register_stock_entry`.

```json
{ "quantity": 5.000 }
```

**Validações:** `quantity > 0`.

**Regras:**
- `currentQuantity = currentQuantity + quantity`.
- O lote volta para `status = ACTIVE`, mesmo que estivesse `WRITTEN_OFF`.

**200 OK** — `StockBatchResponse` atualizado.

---

## 10. Inventários

`/api/inventories`

Contagem física periódica por cozinha. Só existe **um inventário `OPEN` por cozinha**; ao fechar, cada contagem ajusta o lote correspondente.

### 10.1 Abrir inventário

`POST /api/inventories`

```json
{
  "kitchenId": 1,
  "responsibleId": "8f1c9b2a-...",
  "note": "Inventário mensal"
}
```

**Validações:** `kitchenId` e `responsibleId` obrigatórios; `note` até 255.

**Erros:** **409** (`Já existe um inventário em aberto para essa cozinha.`).

**201 Created** — `InventoryResponse`:

```json
{
  "id": 5,
  "kitchen": { "id": 1, "name": "Central" },
  "responsible": { "id": "uuid", "name": "Maria" },
  "startedAt": "2026-09-24T08:00:00",
  "closedAt": null,
  "status": "OPEN",
  "note": "Inventário mensal"
}
```

### 10.2 Listar da cozinha

`GET /api/inventories?kitchenId=1`

### 10.3 Buscar

`GET /api/inventories/{id}`

### 10.4 Registrar contagem

`POST /api/inventories/{id}/counts`

```json
{
  "batchId": 101,
  "physicalQuantity": 40.000,
  "note": "3 pacotes danificados"
}
```

**Validações:** inventário deve estar `OPEN`; `physicalQuantity >= 0`; `note` até 255.

**201 Created** — `InventoryCountResponse`:

```json
{
  "id": 11,
  "inventoryId": 5,
  "batch": { "id": 101, "batchNumber": "LOT-2026-001" },
  "registeredQuantity": 42.500,
  "physicalQuantity": 40.000,
  "divergence": -2.500,
  "note": "3 pacotes danificados"
}
```

- `registeredQuantity` = `batch.currentQuantity` no momento da contagem.
- `divergence` = `physicalQuantity − registeredQuantity` (pode ser negativo).

### 10.5 Listar contagens

`GET /api/inventories/{id}/counts` → array de `InventoryCountResponse`.

### 10.6 Remover contagem

`DELETE /api/inventories/{id}/counts/{countId}` → **200** com `InventoryResponse`. Só permitido enquanto o inventário estiver `OPEN`.

### 10.7 Fechar inventário

`PATCH /api/inventories/{id}/close`

**Regras:**
- Sem contagens → **409** (`Inventário sem contagens não pode ser fechado.`).
- Para cada contagem, chama `stock-batches/{batchId}/adjust` com `newQuantity = physicalQuantity`.
- Depois chama a procedure `sp_close_inventory`, que define `status = CLOSED` e `closedAt = agora`.

**200 OK** — `InventoryResponse`.

### 10.8 Cancelar inventário

`PATCH /api/inventories/{id}/cancel` — **CANCELLED**, sem aplicar nenhuma contagem.

---

## 11. Requisições

`/api/requisitions`

Requisição de compra, transferência ou consumo. Estado inicial já é `UNDER_REVIEW`. **Só é editável enquanto estiver em `UNDER_REVIEW`** — qualquer edição/aprovação/rejeição em outro status retorna **409** (`Requisição não está mais em análise.`). A exceção é o cancelamento (11.9), que também aceita requisições `APPROVED`.

### 11.1 Criar

`POST /api/requisitions`

```json
{
  "type": "PURCHASE",
  "origin": "cozinha",
  "kitchenId": 1,
  "requesterId": "8f1c9b2a-..."
}
```

**Validações:** `type` obrigatório (`PURCHASE` | `TRANSFER` | `CONSUMPTION`); `origin` até 20 chars; `kitchenId` no escopo do usuário; `requesterId` obrigatório.

**201 Created** — `RequisitionResponse`:

```json
{
  "id": 7,
  "type": "PURCHASE",
  "origin": "cozinha",
  "status": "UNDER_REVIEW",
  "reason": null,
  "kitchen": { "id": 1, "name": "Central" },
  "requester": { "id": "uuid", "name": "Maria" },
  "approver": null,
  "createdAt": "2026-09-24T09:00:00",
  "approvedAt": null
}
```

### 11.2 Listar

`GET /api/requisitions?kitchenId=1`
`GET /api/requisitions?status=UNDER_REVIEW`
`GET /api/requisitions?requesterId=<uuid>`

Informe **apenas um** filtro. Sem nenhum → **400**. Os filtros `status` e `requesterId` respeitam o escopo por cozinha silenciosamente.

### 11.3 Adicionar item

`POST /api/requisitions/{id}/items`

```json
{
  "productId": 42,
  "quantity": 10.000,
  "suggestedSupplierId": 7,
  "estimatedPrice": 8.90,
  "note": "Prioridade"
}
```

**Regras:** se `estimatedPrice` for omitido e `suggestedSupplierId` estiver presente, herda do `referencePrice` do vínculo `product↔supplier`.

**200 OK** — `RequisitionResponse` (não retorna os itens; use 11.5).

### 11.4 Remover item

`DELETE /api/requisitions/{id}/items/{itemId}` → **200** com `RequisitionResponse`.

### 11.5 Listar itens

`GET /api/requisitions/{id}/items` → array:

```json
[
  {
    "id": 13,
    "product": { "id": 42, "name": "Farinha de trigo" },
    "quantity": 10.000,
    "estimatedPrice": 8.90,
    "suggestedSupplier": { "id": 7, "legalName": "Fornecedor X" },
    "note": "Prioridade"
  }
]
```

### 11.6 Submit

`PATCH /api/requisitions/{id}/submit` — sanity check "tem itens?". **Não muda o status** (o enum não distingue rascunho de `UNDER_REVIEW`); só rejeita quando a requisição está sem itens (**409**: `Requisição sem itens não pode ser enviada.`).

### 11.7 Aprovar

`PATCH /api/requisitions/{id}/approve`

```json
{ "approverId": "8f1c9b2a-..." }
```

**Efeito:**
- Chama a procedure `sp_approve_requisition`: `status = APPROVED`, `approver = <user>`. O trigger `trg_requisition_approval` preenche `approvedAt = agora`.
- Para cada item: consome do produto na cozinha em **FIFO por validade** (lotes `ACTIVE` ordenados por `expirationDate ASC, entryDate ASC`).
- Estoque insuficiente → **409** (`Estoque insuficiente para atender a quantidade solicitada.`).

### 11.8 Rejeitar

`PATCH /api/requisitions/{id}/reject`

```json
{ "reason": "Fora do orçamento" }
```

**Validações:** `reason` obrigatório, até 255.

**Efeito:** chama a procedure `sp_reject_requisition`: `status = REJECTED`, `reason = <texto>`, `approver = usuário logado`, `approvedAt = agora` (marca o momento da decisão).

### 11.9 Cancelar

`PATCH /api/requisitions/{id}/cancel`

```json
{ "reason": "Pedido duplicado" }
```

**Validações:** `reason` obrigatório, até 255.

**Efeito:** chama a procedure `sp_cancel_requisition`: `status = CANCELLED`, `reason = <texto>`.

**Regras:**
- Aceita requisições `UNDER_REVIEW` ou `APPROVED`. Em outro status → **409** (`Requisition {id} cannot be cancelled in its current status.`).
- Cancelar uma requisição `APPROVED` **não devolve ao estoque** o que foi consumido na aprovação.

**200 OK** — `RequisitionResponse`.

---

## 12. Alertas

`/api/alerts`

Notificações operacionais (validade próxima, estoque baixo, etc.). Podem ser criados manualmente pela API ou automaticamente pelos triggers do banco:

- `trg_stock_alert`: quando o saldo de um lote fica `<= minStock` do produto na cozinha, cria um alerta `type = STOCK`, `severity = HIGH`.
- `trg_expiration_alert`: quando um lote é gravado com `expirationDate` já vencida, cria um alerta `type = EXPIRATION`, `severity = CRITICAL`.

Os dois só criam o alerta se não houver outro igual ainda não lido (`read = false`).

### 12.1 Criar

`POST /api/alerts`

```json
{
  "type": "EXPIRING",
  "severity": "HIGH",
  "batchId": 101,
  "productId": 42,
  "kitchenId": 1,
  "message": "Lote LOT-2026-001 vence em 3 dias"
}
```

**Validações:** `type` obrigatório, até 30; `severity` obrigatório (`LOW` | `MEDIUM` | `HIGH` | `CRITICAL`); `kitchenId` obrigatório no escopo do usuário; `message` obrigatório, até 255; `batchId` e `productId` opcionais.

**201 Created** — `AlertResponse`:

```json
{
  "id": 3,
  "type": "EXPIRING",
  "severity": "HIGH",
  "batch": { "id": 101, "batchNumber": "LOT-2026-001" },
  "product": { "id": 42, "name": "Farinha" },
  "kitchen": { "id": 1, "name": "Central" },
  "message": "Lote LOT-2026-001 vence em 3 dias",
  "read": false,
  "createdAt": "2026-09-24T09:00:00"
}
```

### 12.2 Listar por cozinha

`GET /api/alerts?kitchenId=1&unread=false`

`unread=true` filtra só os não lidos.

### 12.3 Buscar

`GET /api/alerts/{id}` → `AlertResponse`.

### 12.4 Marcar como lido

`PATCH /api/alerts/{id}/read` → **200** com `AlertResponse` (`read = true`).

### 12.5 Deletar

`DELETE /api/alerts/{id}` → **204**.

---

## 13. Enums

### `AccessType` (registro)

- `SUPERVISOR`, `ESTOQUISTA`, `COMPRADOR` (mapeados para `profile.accessType` em lowercase). `ADMIN` não é aceito no registro.

### `RequisitionType`

- `PURCHASE`, `TRANSFER`, `CONSUMPTION`

### `RequisitionStatus`

- `UNDER_REVIEW` — estado inicial e único editável
- `APPROVED` — consome estoque em FIFO por validade
- `REJECTED` — armazena `reason`
- `CANCELLED`

### `InventoryStatus`

- `OPEN` — único aberto por cozinha; único que aceita contagens
- `CLOSED` — aplica ajustes nos lotes
- `CANCELLED`

### `StockBatchStatus`

- `ACTIVE` — participa de FIFO e do cálculo de `low-stock`
- `WRITTEN_OFF` — saldo zerado
- `EXPIRED`
- `CANCELLED`

### `AlertSeverity`

- `LOW`, `MEDIUM`, `HIGH`, `CRITICAL`

---

## 14. Códigos HTTP e erros

| Código | Uso |
|:-------|:----|
| 200 | Sucesso em GET/PUT/PATCH com corpo |
| 201 | Criação (POST) — com header `Location` |
| 204 | Sucesso sem corpo (activate/deactivate, changePassword, delete, link supplier, set parameters) |
| 400 | Payload malformado, filtro obrigatório ausente, formato de imagem inválido, `IOException` de upload |
| 401 | Token ausente/inválido/expirado ou credenciais inválidas no login |
| 403 | Conta desativada ou acesso a recurso fora do escopo da cozinha do usuário |
| 404 | Recurso não encontrado (`ResourceNotFoundException`) |
| 409 | Regra de negócio violada (`BusinessRuleException`), violação de constraint (unique, FK) ou `RAISE EXCEPTION` de uma procedure/trigger do banco (SQLState `P0001`; o `detail` traz a mensagem da procedure, em inglês) |
| 413 | Upload maior que 5 MB |
| 502 | Falha em serviço externo (Cloudinary, Open Food Facts) |
| 500 | Erro inesperado |

**Formato-padrão do corpo de erro** (RFC 7807 `application/problem+json`):

```json
{
  "type": "about:blank",
  "title": "Not Found",
  "status": 404,
  "detail": "Produto não encontrado.",
  "instance": "/api/products/999"
}
```

**Erros de validação** incluem `errors[]` com cada campo violado:

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "Um ou mais campos são inválidos.",
  "instance": "/api/auth/register",
  "errors": [
    { "field": "email",    "message": "must be a well-formed email address" },
    { "field": "password", "message": "size must be between 8 and 100" }
  ]
}
```
