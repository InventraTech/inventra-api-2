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
- **Escopo por cozinha:** todo usuário só acessa dados da própria `kitchen`. Endpoints com `kitchenId` no path/body/query validam contra `user.kitchen` → **403** se divergir; listagens sem `kitchenId` (`/kitchens`, `/stock-batches/low-stock`, `/stock-batches?productId=`, `/requisitions?status=`, `/requisitions?requesterId=`, `/products/{id}/kitchen-parameters`) devolvem só dados da cozinha do usuário (filtrados no banco).
- Endpoints paginados aceitam `?page=0&size=20&sort=name,asc` e respondem no formato `Page<T>` do Spring (`content`, `totalElements`, `totalPages`, `pageable`, ...).
- Todos os endpoints exigem `Authorization: Bearer <token>`, **exceto** `POST /api/auth/login`, `/register`, `/google`, `/refresh`, `/logout`, `GET /swagger-ui/**`, `GET /v3/api-docs/**`, `GET /actuator/health`.
- Token inválido/expirado → **401**. Conta desativada (`active = false`) → **403** no login; tokens já emitidos param de valer na hora (**401**).
- **Papéis (RBAC):** só existem `supervisor`, `estoquista` e `comprador` (`profile.accessType`, authority `ROLE_<ACCESS_TYPE>`). Chamar um endpoint fora do papel → **403**.
  - **Supervisor:** tudo.
  - **Estoquista:** lotes de estoque (`/api/stock-batches`: entrada, baixa, reposição, ajuste e consultas), histórico de movimentações (`/api/stock-movements`), inventários (`/api/inventories`) e o aviso de produto não cadastrado (12.7).
  - **Comprador:** requisições (`/api/requisitions`: criar, itens, submit, cancelar, listar). Aprovar/rejeitar é do supervisor.
  - Qualquer usuário logado: `GET` de catálogo (produtos, categorias, unidades, fornecedores, cozinhas, perfis), descoberta de cozinhas (4.7), consultar e marcar como lidos os **próprios** alertas (12), ver/editar o próprio nome (`/api/users/{seuId}`) e trocar a própria senha.
- E-mail não diferencia maiúsculas/minúsculas (login e unicidade); é gravado em minúsculas.
- `password_hash` nunca aparece em respostas — o `User` só expõe `id`, `name`, `email`, `kitchen`, `profile`, `active`, `hasPassword`, `lastLogin`, `createdAt`.

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
  "expiresIn": 1800,
  "refreshToken": "q3b0V0lJ1m...",
  "refreshExpiresIn": 2592000,
  "user": {
    "id": "8f1c9b2a-...",
    "name": "Maria",
    "email": "maria@example.com",
    "kitchen": { "id": 1, "name": "Cozinha Central" },
    "profile": { "id": 2, "accessType": "SUPERVISOR" },
    "active": true,
    "hasPassword": true,
    "lastLogin": "2026-09-24T09:12:33",
    "createdAt": "2026-08-20T14:02:11"
  }
}
```

`expiresIn` está em **segundos** (`JWT_EXPIRATION_MS / 1000`; padrão 30 min). `refreshToken` é um token opaco para renovar a sessão em `POST /api/auth/refresh` (1.3); `refreshExpiresIn` também é em segundos (`JWT_REFRESH_EXPIRATION_MS`, padrão 30 dias). Se o Redis estiver fora do ar no login, `refreshToken` e `refreshExpiresIn` vêm `null`: o `token` continua valendo, só não há renovação até o próximo login. O `accessType` sai sempre em **MAIÚSCULAS** em todas as respostas. `hasPassword` é `false` em conta criada pelo login com Google (1.5) que ainda não definiu uma senha.

**Erros:** **401** se credenciais inválidas; **403** se `active = false` (`Conta desativada. Contate o supervisor da sua cozinha.`); **429** depois de 5 senhas erradas seguidas para o mesmo e-mail a partir do mesmo IP (bloqueio de 15 minutos).

**Sobre o token:** ele carrega o `id` do usuário e uma impressão digital da senha. Desativar a conta ou trocar a senha invalida na hora todos os tokens emitidos antes (inclusive o da própria sessão — é preciso logar de novo). Tokens emitidos antes desta versão da API não têm essa impressão digital e deixam de valer: todos precisam logar de novo após o deploy.

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

**201 Created** — mesmo `LoginResponse` do 1.1 (usuário já autenticado). O usuário é criado **sem cozinha**: ele pede entrada numa cozinha pelo código (4.6) e um supervisor aprova, ou, se for supervisor, cria a própria cozinha (4.1) e já fica vinculado a ela.

**Erros:** **409** se e-mail já cadastrado.

O `Profile` correspondente ao `accessType` (`supervisor`, `estoquista` ou `comprador`, em minúsculas) é criado em `tb_profile` no primeiro cadastro que o usa, caso ainda não exista.

### 1.3 Renovar a sessão

`POST /api/auth/refresh` — **público**

```json
{ "refreshToken": "q3b0V0lJ1m..." }
```

**200 OK** — `LoginResponse` (1.1) com um **novo** `token` **e um novo `refreshToken`**: o refresh token é de uso único (rotação), então o app deve guardar o novo e descartar o antigo.

**Regras:**
- Cada renovação troca o token por um novo da mesma "família" (a cadeia de um mesmo login).
- Reuso do token antigo até **10 s** depois da troca é tolerado, para chamadas simultâneas do app. Depois disso o reuso é tratado como vazamento: a família inteira é revogada e é preciso logar de novo.
- O app deve fazer **uma renovação por vez** (single-flight) e repetir as chamadas que falharam com 401 depois dela.
- Trocar a senha ou desativar a conta impede a renovação.

**Erros:** **401** se o token for desconhecido, expirado, já revogado, ou se a senha mudou / a conta foi desativada; **400** se `refreshToken` vier vazio.

### 1.4 Logout

`POST /api/auth/logout` — **público**

```json
{ "refreshToken": "q3b0V0lJ1m..." }
```

**204 No Content** — revoga a família do refresh token. É idempotente: token desconhecido também responde 204. O access token já emitido segue válido até expirar (no máximo `expiresIn`).

### 1.5 Login e cadastro com Google

`POST /api/auth/google` — **público**

O app autentica no Firebase Authentication e manda o **ID token do Firebase**. A API confere o token (assinatura, expiração e projeto) com o Firebase Admin SDK e devolve a sessão do Inventra: daí em diante o app usa o JWT do backend, `/refresh` e `/logout` como no login por senha. O ID token do Firebase não é aceito em nenhuma outra rota.

```json
{
  "idToken": "eyJhbGciOiJSUzI1NiIs...",
  "accessType": "ESTOQUISTA"
}
```

`accessType` (`SUPERVISOR` | `ESTOQUISTA` | `COMPRADOR`) só é usado no **primeiro acesso**, para criar a conta.

| Situação | Status | Corpo |
|---|---|---|
| Conta já vinculada a essa conta Google | **200** | `LoginResponse` (1.1) |
| Conta com o mesmo e-mail (criada com senha) | **200** | `LoginResponse`; a conta passa a ficar vinculada e a senha continua valendo |
| Usuário novo **com** `accessType` | **200** | `LoginResponse`; conta criada **sem senha** e sem cozinha |
| Usuário novo **sem** `accessType` | **409** | `code: REGISTRATION_REQUIRED`, com `email` e `name` do token |
| Token inválido, expirado ou de outro projeto | **401** | `code: INVALID_GOOGLE_TOKEN` |
| E-mail não verificado no Google | **403** | `code: EMAIL_NOT_VERIFIED` |
| Conta desativada | **403** | igual ao login por senha |
| Login com Google não configurado no ambiente | **503** | `code: GOOGLE_LOGIN_UNAVAILABLE` |

Primeiro acesso, em duas chamadas: o app manda só o `idToken`; se receber **409** com `REGISTRATION_REQUIRED`, pergunta o cargo e chama de novo com `accessType`.

```json
{
  "type": "about:blank",
  "title": "Conflict",
  "status": 409,
  "detail": "Conta ainda não cadastrada: informe o tipo de acesso para concluir o cadastro.",
  "code": "REGISTRATION_REQUIRED",
  "email": "felipe@gmail.com",
  "name": "Felipe Souza"
}
```

**Conta sem senha:** `POST /api/auth/login` com esse e-mail responde o **401** de sempre (não revela que a conta existe). A pessoa pode criar uma senha em 2.5, sem informar a atual.

**Configuração:** a chave da conta de serviço do Firebase vem de `FIREBASE_CREDENTIALS_JSON` (o JSON puro ou em base64) e nunca vai para o repositório.

---

## 2. Usuários

`/api/users` — todos protegidos.

**Regras de acesso:**
- Criar, listar, ativar e desativar são exclusivos do **supervisor**, e só para usuários da **própria cozinha**.
- Usuário ainda **sem cozinha** não pertence a ninguém: o supervisor só pode buscá-lo por id (`GET /api/users/{id}`) e **puxá-lo** pra cozinha dele (`PUT /api/users/{id}` com `kitchenId` = a cozinha do supervisor). Não aparece na listagem e não pode ser desativado nem ter o perfil trocado sem ser vinculado.
- Ninguém vincula usuário a uma cozinha que não seja a sua.
- A cozinha precisa de pelo menos um supervisor ativo: rebaixar o último → **409**.
- Qualquer usuário pode buscar e editar o **próprio nome** e trocar a **própria senha**.

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

**Validações:** `name` obrigatório, até 120; `email` formato válido, até 150, único; `password` 8–100; `profileId` obrigatório e existente; `kitchenId` opcional — se enviado, precisa ser a cozinha do supervisor (senão **403**).

**201 Created** — retorna `UserResponse` com header `Location: /api/users/{id}`.

**Erros:** **409** se e-mail já existe; **404** se `profileId` / `kitchenId` inexistentes.

### 2.2 Listar usuários

`GET /api/users` → array de `UserResponse` (só supervisor; só usuários da cozinha dele).

### 2.3 Buscar por ID

`GET /api/users/{id}` — `id` = UUID.

**200 OK** — `UserResponse`; **404** se não existir; **403** se não for você nem um usuário da cozinha do supervisor. Usuário sem cozinha só enxerga a si mesmo: a entrada numa cozinha é por pedido (4.6).

### 2.4 Atualizar usuário

`PUT /api/users/{id}`

```json
{
  "name": "João Silva",
  "kitchenId": 2,
  "profileId": 3
}
```

Todos os campos são opcionais — só aplica os enviados. Só o próprio usuário ou o supervisor da mesma cozinha altera a conta (**403** caso contrário). `kitchenId`/`profileId` só podem ser alterados por supervisor, e `kitchenId` precisa ser a cozinha do próprio supervisor. O supervisor **não** puxa mais usuário sem cozinha por aqui: esse caminho é o pedido de entrada (4.6). `kitchenId`/`profileId` inexistentes → **404**.

### 2.5 Trocar senha

`PATCH /api/users/{id}/password`

```json
{
  "currentPassword": "senha12345",
  "newPassword": "novaSenhaForte"
}
```

**204 No Content**.

**Validações:** `newPassword` 8–100. `currentPassword` é obrigatório, **exceto** em conta criada pelo Google que ainda não tem senha (`hasPassword = false`): nela a primeira troca cria a senha.

**Erros:** **409** (`Senha atual incorreta.`) se `currentPassword` não bater com o hash armazenado; **403** se `{id}` não for o usuário logado (só dá pra trocar a própria senha). Depois da troca, todos os tokens anteriores param de valer — logue de novo com a senha nova.

### 2.6 Ativar / desativar

`PATCH /api/users/{id}/activate` → **204**
`PATCH /api/users/{id}/deactivate` → **204** — bloqueia login (`DisabledException` → **403** no login) e invalida os tokens já emitidos. Não dá pra desativar a própria conta (**409**).

### 2.7 Usuário logado

`GET /api/users/me` — **200 OK** com o `UserResponse` do usuário do token, lido do banco. Serve para o app atualizar o que mudou no servidor (por exemplo, a cozinha depois que um supervisor aprova o pedido de entrada) sem novo login.

---

## 3. Perfis

`/api/profiles`

Perfis de acesso (roles). O `AuthService` casa `AccessType` do registro com `profile.accessType` em **lowercase** (`supervisor`, `estoquista`, `comprador`) no banco; nas respostas o `accessType` sai em MAIÚSCULAS. Criar/atualizar/deletar perfil é exclusivo do supervisor; atualizar `accessType` para um valor já usado → **409**. Os perfis base (`supervisor`, `estoquista`, `comprador`) sustentam as permissões de todas as cozinhas: não podem ser renomeados nem excluídos (só a descrição muda), e variações do nome (`SUPERVISOR`, ` supervisor`) são recusadas → **409**.

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
{
  "name": "Sabor Filial Sul",
  "address": "Av. das Indústrias, 450",
  "city": "São Paulo",
  "state": "SP",
  "latitude": -23.55052,
  "longitude": -46.633308
}
```

**Validações:** `name` obrigatório, até 120; `address` até 255; `city` até 120; `state` com 2 letras (gravado em maiúsculas); `latitude` de -90 a 90 e `longitude` de -180 a 180, sempre **juntas**. Os campos de localização são opcionais. O `code` é **gerado pela API** (6 caracteres, sem `O/0/I/1`, único) e devolvido na resposta; um `code` enviado no corpo é ignorado.

**Regras:** só supervisor. Quem cria a cozinha é **vinculado a ela** automaticamente — cada usuário tem uma cozinha só, então quem já tem cozinha recebe **409** (`Você já está vinculado a uma cozinha.`). Atualizar/ativar/desativar também é só do supervisor, e só da própria cozinha.

**201 Created** — `KitchenResponse`:

```json
{
  "id": 1,
  "name": "Cozinha Central",
  "code": "K7M2QX",
  "address": "Av. das Indústrias, 450",
  "city": "São Paulo",
  "state": "SP",
  "latitude": -23.55052,
  "longitude": -46.633308,
  "acceptingRequests": true,
  "active": true,
  "createdAt": "2026-09-24T09:00:00"
}
```

**Localização:** o caminho principal é o app mandar `latitude`, `longitude`, `city` e `state` prontos (GPS ou geocodificação no aparelho). Quando a cozinha chega **sem coordenadas, mas com endereço**, a API busca as coordenadas no Nominatim (OpenStreetMap) em segundo plano, depois de salvar; quando chega com coordenadas e sem cidade/UF, busca a cidade e a UF. O `POST`/`PUT` não espera essa consulta: os campos aparecem preenchidos numa leitura posterior. Se o serviço não encontrar o endereço, a cozinha fica **sem pin** (aparece na lista da descoberta, mas não no mapa). Desligável com `GEOCODING_ENABLED=false`.

### 4.2 Listar ativas

`GET /api/kitchens` → a cozinha do usuário, se estiver ativa (lista vazia caso contrário).

### 4.3 Buscar

- `GET /api/kitchens/{id}` — por ID.
- `GET /api/kitchens/by-code/{code}` — pelo código (sem diferenciar maiúsculas de minúsculas). Aberto a **qualquer usuário autenticado**, inclusive quem ainda não tem cozinha, para confirmar a cozinha antes de pedir entrada. Devolve só `{ "id": 1, "name": "Cozinha Central" }`. Cozinha desativada ou inexistente → **404**. Limitado por usuário (padrão 10 consultas por hora, `KITCHEN_CODE_LOOKUP_MAX_PER_HOUR`); acima disso → **429**.

### 4.4 Atualizar

`PUT /api/kitchens/{id}`

```json
{
  "name": "Novo nome",
  "address": "Novo endereço",
  "city": "São Paulo",
  "state": "SP",
  "latitude": -23.55052,
  "longitude": -46.633308,
  "acceptingRequests": false
}
```

Todos os campos são opcionais — só aplica os enviados. `code` é **imutável**. Mudar o `address` sem mandar coordenadas novas apaga as antigas (o pin apontaria para o lugar errado) e dispara a geocodificação de novo. `acceptingRequests = false` tira a cozinha da descoberta (4.7): só entra quem tiver o código de convite.

### 4.5 Ativar / desativar

`PATCH /api/kitchens/{id}/activate` → **204**
`PATCH /api/kitchens/{id}/deactivate` → **204**

### 4.6 Pedido de entrada na cozinha

`/api/kitchen-access-requests` — guardado no Redis. Pedido pendente expira sozinho em **7 dias** (`KITCHEN_ACCESS_REQUEST_TTL`); depois de aprovado ou recusado, o registro permanece como histórico (quem decidiu e quando).

| Endpoint | Quem | O que faz |
|---|---|---|
| `POST /api/kitchen-access-requests` `{ "kitchenId": 7 }` ou `{ "code": "K7M2QX" }` | qualquer usuário **sem cozinha** | cria o pedido pendente (**201**). Exatamente um dos dois campos: `kitchenId` (cozinha escolhida na descoberta) ou `code` (convite) |
| `GET /api/kitchen-access-requests/mine` | o próprio usuário | pedido mais recente; **404** se não houver (ou se o pendente expirou) |
| `GET /api/kitchen-access-requests?status=PENDING` | supervisor | pedidos da própria cozinha; sem `status` traz também o histórico |
| `POST /api/kitchen-access-requests/{id}/approve` | supervisor da cozinha | aprova e **vincula o usuário à cozinha** |
| `POST /api/kitchen-access-requests/{id}/reject` `{ "reason": "..." }` | supervisor da cozinha | recusa; `reason` opcional |

**200/201** — `KitchenAccessRequestResponse`:

```json
{
  "id": "4c1f...",
  "status": "PENDING",
  "kitchen": { "id": 1, "name": "Cozinha Central" },
  "user": { "id": "8f1c9b2a-...", "name": "João", "email": "joao@example.com" },
  "createdAt": "2026-10-09T18:55:02Z",
  "expiresAt": "2026-10-16T18:55:02Z",
  "decidedAt": null,
  "decidedBy": null,
  "reason": null
}
```

`status`: `PENDING` | `APPROVED` | `REJECTED`. `expiresAt` só vem enquanto o pedido está pendente.

**Erros:** **400** se vierem `kitchenId` e `code` juntos ou nenhum dos dois; **409** se o usuário já tem cozinha, já tem pedido pendente (em qualquer cozinha), se a cozinha escolhida pelo `kitchenId` não está aceitando pedidos, ou se o pedido já foi decidido; **404** para código/id inexistente, cozinha desativada ou pedido inexistente/expirado; **403** para quem não é supervisor da cozinha do pedido. Depois da aprovação o app chama `GET /api/users/me` para ver a cozinha; o token continua o mesmo.

**Regras:** depois de um pedido recusado, o usuário pode pedir de novo para a mesma cozinha, sem intervalo. Cada pedido gera um alerta `ACCESS_REQUEST` para os supervisores da cozinha (12), que deixa de aparecer como não lido quando o pedido é aprovado ou recusado.

### 4.7 Descoberta de cozinhas

`GET /api/kitchens/discover` — qualquer usuário autenticado, inclusive quem ainda não tem cozinha.

Lista **resumida** das cozinhas ativas que aceitam pedidos de entrada, para a lista, o mapa e o bottom sheet da tela "Selecionar cozinha".

| Parâmetro | Obrigatório | Descrição |
|---|---|---|
| `q` | não | busca por nome (contém, sem diferenciar maiúsculas) |
| `lat`, `lng` | não | posição do usuário, sempre juntos; ordena por distância e preenche `distanceKm` |
| `radiusKm` | não | com `lat`/`lng`, limita ao raio (padrão 50) |
| `page`, `size` | não | paginação; `size` padrão 20, máximo 50 |

**200 OK**

```json
{
  "content": [
    {
      "id": 7,
      "name": "Sabor Filial Sul",
      "city": "São Paulo",
      "state": "SP",
      "latitude": -23.5505,
      "longitude": -46.6333,
      "distanceKm": 3.2,
      "myRequestStatus": "PENDING"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1
}
```

- `latitude`/`longitude` vêm arredondadas para 4 casas decimais (~11 m) e são `null` em cozinha sem pin.
- `distanceKm` (1 casa decimal) só vem quando `lat`/`lng` são enviados. Cozinha sem coordenadas não tem distância e aparece **no fim** da lista.
- `myRequestStatus` é o pedido mais recente **do usuário logado** para aquela cozinha: `PENDING`, `REJECTED` ou `null`.
- Não expõe `code`, endereço completo, membros nem estoque. Sem `lat`/`lng` a lista vem por nome.

**Erros:** **400** para `size` fora de 1–50, `page` negativo, `lat` sem `lng` (ou o contrário), coordenadas fora da faixa ou `radiusKm <= 0`; **429** acima de 30 buscas por minuto por usuário (`KITCHEN_DISCOVERY_MAX_PER_MINUTE`).

### 4.8 Resumo do estoque da cozinha

`GET /api/kitchens/{kitchenId}/stock` — supervisor e estoquista. Filtros opcionais: `categoryId` e `name`.

Produtos ativos ligados à cozinha (com lote em qualquer status ou com parâmetro de estoque), ordenados por nome, com o saldo dos lotes `ACTIVE` e dentro da validade:

```json
[
  {
    "product": { "id": 5013, "name": "Leite Integral", "brand": "Italac", "photoUrl": null },
    "category": { "id": 2, "name": "Laticínios" },
    "unit": { "id": 3, "symbol": "L" },
    "totalQuantity": 18.000,
    "activeBatches": 2,
    "nextExpirationDate": "2026-10-12",
    "minStock": 10.000,
    "lowStock": false
  }
]
```

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

Com `?kitchenId=1`, devolve só os fornecedores ativos que já entregaram algum lote naquela cozinha, ordenados por razão social (**403** se a cozinha não for a do usuário).

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
  "barcode": "7891234567890"
}
```

**Validações:** `name` até 150; `brand` até 80; `unitId` obrigatório; `categoryId` opcional; `barcode` **único**, até 50. A foto **não** vem aqui: envie o arquivo pelo upload (8.5), que valida a imagem e gera a URL.

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

**Validações:** `kitchenId` no escopo do usuário; `minStock` default `0` se ausente; valores `>= 0`; `maxStock` não pode ser menor que `minStock` (**400**).

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

### 8.13 Consulta do scan (catálogo + Open Food Facts)

`GET /api/products/by-barcode/{barcode}?kitchenId=1` — qualquer usuário autenticado.

É a única chamada do app depois de ler o código de barras. A API procura no **catálogo** e, se não achar, no **Open Food Facts**.

**200 OK** — produto do catálogo (`source = CATALOG`):

```json
{
  "source": "CATALOG",
  "barcode": "7898080640611",
  "product": {
    "id": 5013,
    "name": "Leite Integral",
    "brand": "Italac",
    "category": { "id": 2, "name": "Laticínios" },
    "unit": { "id": 3, "symbol": "L" },
    "photoUrl": null,
    "active": true
  },
  "kitchenStock": {
    "totalQuantity": 18.000,
    "activeBatches": 2,
    "nextExpirationDate": "2026-10-12"
  }
}
```

**200 OK** — produto que só o Open Food Facts conhece (`source = OPEN_FOOD_FACTS`), para preencher o cadastro:

```json
{
  "source": "OPEN_FOOD_FACTS",
  "barcode": "7898080640611",
  "suggestion": {
    "name": "Leite Integral Italac 1L",
    "brand": "Italac",
    "imageUrl": "https://images.openfoodfacts.org/...",
    "quantity": "1 L"
  }
}
```

**Regras:**
- `kitchenId` é opcional. Quando vem, valida o acesso à cozinha (**403**) e preenche `kitchenStock` com os lotes `ACTIVE` e dentro da validade, o mesmo critério do resumo de estoque (4.8). Sem ele, `kitchenStock` não aparece.
- Os campos que não se aplicam ao `source` não aparecem no JSON.
- Open Food Facts fora do ar ou lento conta como "não encontrado": responde **404**, não 502, para o app seguir para o cadastro manual.

**Erros:** **404** se nem o catálogo nem o Open Food Facts conhecem o código; **400** se o código não tiver só dígitos.

---

## 9. Lotes de estoque

`/api/stock-batches`

Cada entrada de nota gera um lote. Consumo e ajustes alteram `currentQuantity` e podem mudar o `status`. Toda entrada, baixa e ajuste fica gravada no histórico de movimentações (9.9), com quem fez, quando, quanto e por quê.

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
`GET /api/stock-batches?kitchenId=1&productId=42`

Os dois filtros combinam. Só com `productId`, vale a cozinha do usuário. Sem nenhum → **400**.

### 9.3 Lotes vencendo em breve

`GET /api/stock-batches/expiring-soon?kitchenId=1&days=7`

Retorna lotes `ACTIVE` cuja `expirationDate` está entre hoje e `hoje + N dias`.

### 9.4 Produtos abaixo do mínimo

`GET /api/stock-batches/low-stock`

Só considera a cozinha do usuário. O saldo conta apenas lotes `ACTIVE` dentro da validade (lote vencido não é estoque). Retorna:

```json
[
  {
    "kitchenId": 1,
    "productId": 42,
    "product": { "id": 42, "name": "Arroz", "unit": { "id": 3, "symbol": "kg" } },
    "currentQuantity": 8.000,
    "minStock": 10.000
  }
]
```

### 9.5 Consumir lote

`PATCH /api/stock-batches/{id}/consume`

```json
{ "quantity": 5.000, "reason": "USAGE" }
```

**Validações:** `quantity > 0`. `reason` é opcional: `USAGE` (padrão), `LOSS` ou `EXPIRED`. `REQUISITION` é reservado à aprovação de requisição → **400**.

**Regras:**
- Lote com a validade passada não pode ser consumido com `USAGE` → **409** (`Lote vencido não pode ser consumido.`), mas pode ser descartado com `LOSS` ou `EXPIRED` enquanto ainda estiver `ACTIVE`.
- Lote fora de `ACTIVE` → **409** (`Só é possível dar baixa em lote ativo.`).
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
- Lote `WRITTEN_OFF` volta para `status = ACTIVE`.
- Lote `EXPIRED` ou `CANCELLED` não aceita reposição → **409** (`Não é possível dar entrada em lote vencido ou cancelado.`).

**Cozinha e produto desativados:** cozinha desativada não movimenta estoque (entrada, baixa, reposição, ajuste) nem abre inventário ou requisição → **409**; produto desativado não recebe entrada nem pode ser requisitado → **409**.

**Vencimento:** um job diário (00:05, horário de Brasília, e também na subida da API) marca como `EXPIRED` todo lote `ACTIVE` com validade passada e gera os alertas de vencimento e de estoque mínimo (procedure `sp_expire_batches`). O mesmo job avisa **antes de vencer**: gera o alerta `EXPIRING` para os lotes `ACTIVE` que vencem em até `ALERTS_EXPIRING_DAYS` dias (padrão 3; procedure `sp_alert_expiring_batches`). Mesmo antes do job rodar, lote vencido nunca é usado nem conta como estoque. Desligável com `BATCH_EXPIRATION_JOB_ENABLED=false`.

**200 OK** — `StockBatchResponse` atualizado.

### 9.8 Baixa por produto (scan)

`POST /api/stock-batches/consume`

Dá baixa de um produto sem escolher o lote: tira dos lotes que **vencem primeiro** (`ACTIVE` e dentro da validade, por `expirationDate ASC, entryDate ASC`).

```json
{
  "kitchenId": 1,
  "productId": 5013,
  "quantity": 6.000,
  "reason": "USAGE"
}
```

**Validações:** `kitchenId` (no escopo do usuário), `productId` e `quantity > 0` obrigatórios; `reason` opcional, como em 9.5.

**200 OK** — os lotes afetados, com o saldo novo:

```json
[
  {
    "batch": { "id": 5011, "batchNumber": "QA-1011-01", "expirationDate": "2026-10-13" },
    "consumedQuantity": 4.000,
    "remainingQuantity": 0.000,
    "status": "WRITTEN_OFF"
  },
  {
    "batch": { "id": 5012, "batchNumber": "QA-1011-02", "expirationDate": "2026-11-02" },
    "consumedQuantity": 2.000,
    "remainingQuantity": 10.000,
    "status": "ACTIVE"
  }
]
```

**Regras:** se o saldo da cozinha não cobre a quantidade → **409** (`Estoque insuficiente para atender a quantidade solicitada.`) e **nada é baixado**. Os lotes são lidos com lock: duas baixas simultâneas do mesmo produto esperam uma à outra.

**Erros:** **404** para cozinha ou produto inexistente; **409** para cozinha desativada.

### 9.9 Histórico de movimentações

`GET /api/stock-movements?kitchenId=1&productId=5013&type=WRITE_OFF` — supervisor e estoquista.

| Parâmetro | Obrigatório | Descrição |
|---|---|---|
| `kitchenId` | sim | cozinha (no escopo do usuário) |
| `productId` | não | só as movimentações dos lotes desse produto |
| `type` | não | `ENTRY`, `WRITE_OFF` ou `ADJUSTMENT` |
| `limit` | não | quantas trazer; padrão 200, máximo 500 |

**200 OK** — da mais recente para a mais antiga:

```json
[
  {
    "id": 1,
    "type": "WRITE_OFF",
    "reason": "USAGE",
    "quantity": 6.000,
    "balanceAfter": 12.000,
    "batch": { "id": 5011, "batchNumber": "QA-1011-01" },
    "product": { "id": 5013, "name": "Leite Integral" },
    "user": { "id": "8f1c9b2a-...", "name": "Davi Arakaki" },
    "requisitionId": null,
    "createdAt": "2026-10-11T13:40:00"
  }
]
```

| `type` | Quando nasce | `quantity` | `reason` |
|---|---|---|---|
| `ENTRY` | criação do lote (9.1) e reposição (9.7) | quantidade que entrou | sempre `null` |
| `WRITE_OFF` | baixa por lote (9.5), por produto (9.8) e aprovação de requisição (11.7) | quantidade baixada | `USAGE`, `LOSS`, `EXPIRED` ou `REQUISITION` |
| `ADJUSTMENT` | ajuste (9.6) e estorno do cancelamento de requisição aprovada (11.9) | **diferença, com sinal** | sempre `null` |

`balanceAfter` é o saldo do lote logo depois da movimentação. `requisitionId` vem preenchido nas baixas de uma aprovação e no estorno dela. Ajuste para o mesmo saldo não gera movimentação. Movimentações anteriores a esta versão não existem: o histórico começa no deploy.

---

## 10. Inventários

`/api/inventories`

Contagem física periódica por cozinha. Só existe **um inventário `OPEN` por cozinha**; ao fechar, a divergência de cada contagem é aplicada ao lote correspondente. Inventário é do supervisor e do estoquista.

### 10.1 Abrir inventário

`POST /api/inventories`

```json
{
  "kitchenId": 1,
  "responsibleId": "8f1c9b2a-...",
  "note": "Inventário mensal"
}
```

**Validações:** `kitchenId` obrigatório; `responsibleId` opcional — sem ele, o responsável é o usuário logado; se enviado, precisa ser um usuário da mesma cozinha (**409** caso contrário); `note` até 255.

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

**Validações:** inventário deve estar `OPEN`; o lote precisa ser da cozinha do inventário (**409**); cada lote só pode ser contado uma vez por inventário — pra recontar, remova a contagem anterior (**409**); `physicalQuantity >= 0`; `note` até 255.

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
- Para cada contagem, ajusta o lote para `saldo atual + divergence` (mínimo 0). Assim, consumos e entradas feitos entre a contagem e o fechamento não se perdem.
- Depois chama a procedure `sp_close_inventory`, que define `status = CLOSED` e `closedAt = agora`.

**200 OK** — `InventoryResponse`.

### 10.8 Cancelar inventário

`PATCH /api/inventories/{id}/cancel` — **CANCELLED**, sem aplicar nenhuma contagem.

---

## 11. Requisições

`/api/requisitions`

Requisição de compra, transferência ou consumo. Nasce como rascunho (`DRAFT`) e segue `DRAFT` → `UNDER_REVIEW` (submit, 11.6) → `APPROVED` ou `REJECTED`. **Os itens só mudam enquanto ela é rascunho** (**409** depois: `Requisição já foi enviada e não pode mais ser alterada.`), e **só é aprovada ou rejeitada em `UNDER_REVIEW`** (**409**: `Requisição ainda não foi enviada para análise.` ou `Requisição não está mais em análise.`). O rascunho só aparece para quem o criou. O cancelamento (11.9) aceita `DRAFT`, `UNDER_REVIEW` e `APPROVED`.

### 11.1 Criar

`POST /api/requisitions`

```json
{
  "type": "PURCHASE",
  "origin": "cozinha",
  "kitchenId": 1
}
```

**Validações:** `type` obrigatório (`PURCHASE` | `TRANSFER` | `CONSUMPTION`); `origin` obrigatório, até 20 chars; `kitchenId` obrigatório, no escopo do usuário. O solicitante é sempre o **usuário logado**.

**201 Created** — `RequisitionResponse`:

```json
{
  "id": 7,
  "type": "PURCHASE",
  "origin": "cozinha",
  "status": "DRAFT",
  "reason": null,
  "kitchen": { "id": 1, "name": "Central" },
  "requester": { "id": "uuid", "name": "Maria" },
  "approver": null,
  "createdAt": "2026-09-24T09:00:00",
  "approvedAt": null,
  "itemCount": 4,
  "estimatedTotal": 131.76,
  "itemNames": ["Leite Integral", "Arroz Camil", "Feijão Carioca"]
}
```

O resumo dos itens vem em **toda** `RequisitionResponse`, para o card da listagem não precisar de uma chamada de itens por requisição: `itemCount` (0 na criação), `estimatedTotal` (soma de `quantity × estimatedPrice`; item sem preço conta zero) e `itemNames` (os **3 primeiros**, na ordem em que foram adicionados).

### 11.2 Listar

`GET /api/requisitions?kitchenId=1&status=UNDER_REVIEW&requesterId=<uuid>`

Os três filtros são opcionais e **combinam**; informe pelo menos um (sem nenhum → **400**). Sem `kitchenId`, vale a cozinha do usuário logado. Rascunho (`DRAFT`) de outra pessoa nunca aparece, nem para o supervisor.

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

**Validações:** `productId` obrigatório (produto ativo); `quantity` obrigatório e `> 0`; `estimatedPrice >= 0`; `note` até 255. Comprador só edita (itens, submit, cancelar) as **próprias** requisições; o supervisor edita qualquer uma da cozinha → **403** caso contrário.

**Regras:** se `estimatedPrice` for omitido e `suggestedSupplierId` estiver presente, herda do `referencePrice` do vínculo `product↔supplier`.

**200 OK** — `RequisitionResponse`, com o resumo dos itens atualizado (a lista completa fica em 11.5). Só em requisição `DRAFT`.

### 11.4 Remover item

`DELETE /api/requisitions/{id}/items/{itemId}` → **200** com `RequisitionResponse`. Só em requisição `DRAFT`.

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

`PATCH /api/requisitions/{id}/submit` — envia o rascunho para análise: `DRAFT` → `UNDER_REVIEW`. Exige pelo menos um item (**409**: `Requisição sem itens não pode ser enviada.`) e gera o alerta `REQUISITION_SUBMITTED` para os supervisores da cozinha (`Nova requisição #12 para análise.`). Requisição que já saiu do rascunho → **409**.

### 11.7 Aprovar

`PATCH /api/requisitions/{id}/approve` — **sem corpo**; só supervisor.

**Efeito:**
- Chama a procedure `sp_approve_requisition`: `status = APPROVED`, `approver = usuário logado`. O trigger `trg_requisition_approval` preenche `approvedAt = agora`.
- `CONSUMPTION` e `TRANSFER`: para cada item, consome do produto na cozinha em **FIFO por validade** (lotes `ACTIVE` ordenados por `expirationDate ASC, entryDate ASC`).
- `PURCHASE`: **não mexe no estoque** — a entrada acontece pelo `POST /api/stock-batches` (9.1) quando a mercadoria chega.
- `TRANSFER` é a **saída** da cozinha da requisição; o destino é texto livre (`origin`), sem cozinha vinculada — a cozinha que recebe registra a própria entrada pelo 9.1.
- Requisição sem itens → **409** (`Requisição sem itens não pode ser aprovada.`).
- Estoque insuficiente → **409** (`Estoque insuficiente para atender a quantidade solicitada.`).
- Cada baixa fica no histórico de movimentações (9.9) como `WRITE_OFF` com `reason = REQUISITION` e o `requisitionId`.
- Quem pediu recebe o alerta `REQUISITION_APPROVED` (`Sua requisição #12 foi aprovada.`), a não ser que tenha aprovado a própria requisição. O alerta de "nova requisição" dos supervisores deixa de aparecer como não lido.

### 11.8 Rejeitar

`PATCH /api/requisitions/{id}/reject`

```json
{ "reason": "Fora do orçamento" }
```

**Validações:** `reason` obrigatório, até 255.

**Efeito:** chama a procedure `sp_reject_requisition`: `status = REJECTED`, `reason = <texto>`, `approver = usuário logado`. Só supervisor. `approvedAt` continua `null` (a requisição não foi aprovada). Quem pediu recebe o alerta `REQUISITION_REJECTED` (`Sua requisição #12 foi recusada: Fora do orçamento`).

### 11.9 Cancelar

`PATCH /api/requisitions/{id}/cancel`

```json
{ "reason": "Pedido duplicado" }
```

**Validações:** `reason` obrigatório, até 255.

**Efeito:** chama a procedure `sp_cancel_requisition`: `status = CANCELLED`, `reason = <texto>`.

**Regras:**
- Aceita requisições `DRAFT`, `UNDER_REVIEW` ou `APPROVED`. Em outro status → **409** (`Requisição {id} não pode ser cancelada no status atual.`).
- Comprador só cancela as **próprias** requisições, e só enquanto `DRAFT` ou `UNDER_REVIEW`; cancelar uma `APPROVED` é do supervisor → **403**.
- Cancelar uma requisição `APPROVED` **estorna o estoque**: a procedure devolve a cada lote o que a aprovação tinha baixado dele (pelas movimentações `WRITE_OFF` da requisição), reativa o lote que tinha zerado e grava um `ADJUSTMENT` por lote no histórico (9.9). Só há o que estornar em aprovações feitas a partir desta versão, que é quando as baixas passaram a guardar a requisição.

**200 OK** — `RequisitionResponse`.

### 11.10 Buscar por ID

`GET /api/requisitions/{id}` → **200** com `RequisitionResponse`. O comprador só abre as **próprias** requisições; o supervisor, qualquer uma da cozinha (**403** caso contrário). **404** se não existir.

---

## 12. Alertas

`/api/alerts`

Notificações da cozinha. O `type` é texto livre; hoje existem:

| `type` | `severity` | Quando nasce | Para quem |
|---|---|---|---|
| `STOCK` | `HIGH` | saldo dos lotes `ACTIVE` e dentro da validade fica `< minStock` (trigger `trg_stock_alert`) | supervisores e estoquistas |
| `EXPIRATION` | `CRITICAL` | lote vencido (trigger `trg_expiration_alert` e job diário) | supervisores e estoquistas |
| `EXPIRING` | `HIGH` se vence hoje ou amanhã, `MEDIUM` nos demais | job diário, para lotes `ACTIVE` que vencem em até `ALERTS_EXPIRING_DAYS` dias (padrão 3). Ex.: `Lote QA-1011-01 vence em 2 dias (13/10).` | supervisores e estoquistas |
| `ACCESS_REQUEST` | `MEDIUM` | pedido de entrada na cozinha (4.6). `refKey` = id do pedido | supervisores |
| `REQUISITION_SUBMITTED` | `MEDIUM` | requisição enviada para análise (11.6). `refKey` = id da requisição | supervisores |
| `REQUISITION_APPROVED` / `REQUISITION_REJECTED` | `MEDIUM` | requisição decidida (11.7, 11.8). `refKey` = id da requisição | quem pediu |
| `UNREGISTERED_PRODUCT` | `MEDIUM` | estoquista escaneou um produto fora do catálogo (12.7). `refKey` = código de barras | supervisores |

Os alertas automáticos não se repetem enquanto houver outro igual que ninguém leu.

**Quem vê o quê:** as consultas (12.2, 12.3) e as leituras (12.4, 12.6) são abertas a **qualquer usuário da cozinha**, mas cada um só enxerga os alertas que são para ele: os endereçados à pessoa, os do perfil dela e — para supervisor e estoquista — os da cozinha inteira. O comprador só recebe os das próprias requisições. Alerta de outra pessoa → **403**.

**Lido é por pessoa:** `read` é a leitura de **quem está chamando**. Um estoquista ler um alerta não o tira de "não lido" do supervisor. A exceção são os alertas `ACCESS_REQUEST` e `REQUISITION_SUBMITTED`, que passam a `read = true` para todos quando o pedido ou a requisição é decidido.

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

**Validações:** `type` obrigatório, até 30; `severity` obrigatório (`LOW` | `MEDIUM` | `HIGH` | `CRITICAL`); `kitchenId` obrigatório no escopo do usuário; `message` obrigatório, até 255; `batchId` e `productId` opcionais — se `batchId` vier, o lote precisa ser da mesma cozinha (e do `productId`, se informado) → **409**.

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
  "refKey": null,
  "createdAt": "2026-09-24T09:00:00"
}
```

`refKey` diz o que originou o alerta (id da requisição, id do pedido de entrada ou código de barras; `null` nos de estoque e validade): o app usa para saber aonde levar a pessoa quando ela toca na notificação. Só supervisor cria alerta por aqui.

### 12.2 Listar por cozinha

`GET /api/alerts?kitchenId=1&unread=false`

Os alertas que o usuário logado enxerga naquela cozinha, do mais recente para o mais antigo. `unread=true` filtra só os que **ele** ainda não leu (é o contador do sininho).

### 12.3 Buscar

`GET /api/alerts/{id}` → `AlertResponse`.

### 12.4 Marcar como lido

`PATCH /api/alerts/{id}/read` → **200** com `AlertResponse` (`read = true`). Grava a leitura só para o usuário logado; repetir a chamada não tem efeito.

### 12.5 Deletar

`DELETE /api/alerts/{id}` → **204**. Só supervisor.

### 12.6 Marcar todos como lidos

`PATCH /api/alerts/read-all?kitchenId=1` → **204**. Marca como lidos, para o usuário logado, todos os alertas não lidos que ele enxerga na cozinha.

### 12.7 Produto não cadastrado

`POST /api/alerts/unregistered-product` — supervisor e estoquista.

O estoquista escaneou um produto que não está no catálogo e não pode cadastrá-lo: avisa o supervisor com um toque.

```json
{ "kitchenId": 1, "barcode": "7898080640611" }
```

**Validações:** `kitchenId` obrigatório, no escopo do usuário; `barcode` obrigatório, só dígitos (até 50).

**201 Created** — `AlertResponse` com `type = UNREGISTERED_PRODUCT`, `severity = MEDIUM`, sem produto, `refKey` = código de barras e a mensagem montada pela API: `Produto não cadastrado: Leite Integral Italac 1L (7898080640611)`. O nome vem do Open Food Facts; se ele não conhecer o código ou estiver fora do ar, a mensagem sai só com o código.

**200 OK** — se já existe um aviso aberto (que ninguém leu) para o mesmo código na mesma cozinha, devolve o existente em vez de criar outro.

**Erros:** **409** se o código já está cadastrado no catálogo (`Esse produto já está cadastrado no catálogo.`).

---

## 13. Enums

### `AccessType` (registro)

- `SUPERVISOR`, `ESTOQUISTA`, `COMPRADOR` (mapeados para `profile.accessType` em lowercase). `ADMIN` não é aceito no registro.

### `RequisitionType`

- `PURCHASE`, `TRANSFER`, `CONSUMPTION`

### `RequisitionStatus`

- `DRAFT` — estado inicial; único em que os itens mudam; só o solicitante a vê
- `UNDER_REVIEW` — enviada para análise (submit); único que pode ser aprovado ou rejeitado
- `APPROVED` — `CONSUMPTION`/`TRANSFER` consomem estoque em FIFO por validade; `PURCHASE` não mexe no estoque
- `REJECTED` — armazena `reason`
- `CANCELLED` — cancelar uma `APPROVED` estorna o estoque

### `InventoryStatus`

- `OPEN` — único aberto por cozinha; único que aceita contagens
- `CLOSED` — aplica ajustes nos lotes
- `CANCELLED`

### `StockBatchStatus`

- `ACTIVE` — participa de FIFO e do cálculo de `low-stock` (enquanto estiver dentro da validade)
- `WRITTEN_OFF` — saldo zerado
- `EXPIRED` — validade passada (marcado pelo job diário); não é consumido nem reposto
- `CANCELLED`

### `AlertSeverity`

- `LOW`, `MEDIUM`, `HIGH`, `CRITICAL`

### `StockMovementType`

- `ENTRY`, `WRITE_OFF`, `ADJUSTMENT`

### `StockMovementReason` (só em `WRITE_OFF`)

- `USAGE` — uso normal (padrão)
- `LOSS` — perda
- `EXPIRED` — descarte por vencimento
- `REQUISITION` — baixa da aprovação de uma requisição; não é aceito nos endpoints de baixa

### `KitchenAccessRequestStatus`

- `PENDING`, `APPROVED`, `REJECTED`

---

## 14. Códigos HTTP e erros

| Código | Uso |
|:-------|:----|
| 200 | Sucesso em GET/PUT/PATCH com corpo |
| 201 | Criação (POST) — com header `Location` |
| 204 | Sucesso sem corpo (activate/deactivate, changePassword, delete, link supplier, set parameters) |
| 400 | JSON malformado, campo inválido, parâmetro obrigatório ausente ou de tipo errado (ex.: UUID inválido), `sort` inválido, formato de imagem inválido, `IOException` de upload |
| 401 | Token ausente/inválido/expirado ou credenciais inválidas no login |
| 403 | Conta desativada, endpoint fora do papel do usuário (RBAC) ou recurso fora do escopo da cozinha do usuário |
| 404 | Recurso não encontrado (`ResourceNotFoundException`) ou rota inexistente |
| 405 | Método HTTP não suportado no endpoint |
| 415 | `Content-Type` não suportado |
| 409 | Regra de negócio violada (`BusinessRuleException`), violação de constraint (unique, FK) ou `RAISE EXCEPTION` de uma procedure/trigger do banco (SQLState `P0001`; o `detail` traz a mensagem da procedure, em português) |
| 413 | Upload maior que 5 MB |
| 502 | Falha em serviço externo (Cloudinary, Open Food Facts) |
| 429 | Muitas tentativas de login seguidas (bloqueio de 15 minutos por e-mail + IP), de buscas de cozinha por código ou de buscas na descoberta de cozinhas |
| 500 | Erro inesperado |
| 503 | Banco indisponível ao validar o token, ou login com Google não configurado no ambiente |

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

**Erros com `code`:** alguns erros trazem um campo `code`, para o app tratar o caso sem depender do texto de `detail`. Hoje só o login com Google usa (1.5): `REGISTRATION_REQUIRED`, `INVALID_GOOGLE_TOKEN`, `EMAIL_NOT_VERIFIED` e `GOOGLE_LOGIN_UNAVAILABLE`.

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
