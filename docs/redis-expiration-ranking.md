# INV2-26 — Ranking de validade

Implementação do ranking definido no Plano Técnico de Integração Redis v2:
lotes por cozinha, ordenados por validade, com PostgreSQL como fonte de verdade.

## Contrato

`GET /api/stock-batches/ranking?kitchenId=1&limit=10`, com autenticação JWT.
`kitchenId` é obrigatório e positivo; `limit` vai de 1 a 100 e assume 10.
Somente supervisores e estoquistas podem consultar o endpoint (`Roles.STOCK`).
O KitchenAccessGuard restringe cada usuário à própria cozinha.

Exemplo de resposta:

```json
[
  {"position": 1, "batchId": 8, "expirationDate": "2024-10-04", "score": 20000}
]
```

A pontuação é `expirationDate.toEpochDay()`: quantidade de dias desde 1970-01-01.
Menor pontuação aparece primeiro. Empates são resolvidos por ID numérico crescente.
Não há ZINCRBY: movimentar um lote não altera sua validade.

Entram lotes ACTIVE, com saldo positivo e data de validade preenchida.
Lotes ainda ACTIVE cuja data já passou ficam no início, para sinalizar urgência.
WRITTEN_OFF, EXPIRED, CANCELLED, saldo zero e validade ausente são excluídos.
O ranking não muda status de domínio nem descarta lotes automaticamente.

## Sorted Set e sincronização

Chave: `inventra:ranking:expiration:{kitchenId}`.
Membro: ID do lote com padding de 10 posições; score: epoch day.
Um marcador interno com score infinito representa ranking inicializado vazio e nunca
é retornado pela API.

Entrada, baixa, reposição, ajuste e consumo FEFO por produto publicam
StockRankingChanged dentro da transação. O listener AFTER_COMMIT agenda a reconstrução
em executor dedicado (1 worker, fila limitada a 128 tarefas). A requisição de estoque
libera sua conexão sem esperar a reconstrução. Rollback não agenda atualizações.
O ranking converge de forma assíncrona após a confirmação, normalmente logo em seguida.
Saturação do executor gera log e usa recuperação por TTL; nunca executa na thread do commit.

Cada reconstrução usa uma nova transação READ_COMMITTED e um lock pessimista na
linha da cozinha, adquirido antes da leitura do snapshot. Assim, múltiplas
instâncias não publicam snapshots fora de ordem. O snapshot é uma única consulta
de projeção, sem carregar entidades relacionadas. Um script Lua substitui o ZSET
atomicamente através de chave temporária e RENAME. Uma falha de construção não
destrói o ranking publicado. Quem aguardou o lock reavalia o cache antes de reconstruir.

O lock serializa reconstruções apenas da mesma cozinha. Uma reconstrução lê todos
os lotes elegíveis dessa cozinha: custo O(n) por alteração. Para cozinhas muito grandes,
uma evolução possível é outbox e projeção incremental versionada.
O executor usa transações próprias sem manter a requisição aguardando outra conexão.
O lock ainda dura durante a publicação Redis: monitorar latência e tamanho dos snapshots.

## Expiração, falhas e recuperação

TTL padrão: 60 segundos, configurável em REDIS_RANKING_TTL_SECONDS (positivo).
Não é um ranking diário; a expiração serve para reconstrução e limpeza de cache.
O prazo é renovado em cada reconstrução. Cache ausente é reconstruído na consulta;
cozinhas vazias também recebem TTL. Não há dependência de preload no startup.

Falha na projeção após commit gera log com exceção, mas não transforma uma movimentação
já confirmada em erro para o cliente. O cache restante expira e a próxima consulta
reconstrói pelo PostgreSQL. Se os serviços necessários estiverem indisponíveis,
a consulta retorna 503; acesso a outra cozinha retorna 403.

Mudanças feitas diretamente por SQL não disparam o evento Java e são refletidas
na reconstrução seguinte. A consistência entre os dois bancos é eventual nas falhas,
com recuperação após expiração e disponibilidade dos serviços; não há transação
distribuída nem promessa de atualização imediata durante uma indisponibilidade.

## Configuração

- REDIS_RANKING_KEY_PREFIX: inventra:ranking:expiration
- REDIS_RANKING_TTL_SECONDS: 60

O ranking reutiliza StockBatch e Kitchen e não exige migration própria nem novas
credenciais. O schema e as procedures vêm integralmente das migrations oficiais
sincronizadas com o `inventra-database-2`.

## Testes

Suíte padrão:

```powershell
.\mvnw.cmd clean test
```

Com Docker disponível, Testcontainers inicia automaticamente Redis 7 e PostgreSQL 16
descartáveis durante a suíte normal. Sem Docker, somente esses testes integrados são
pulados, seguindo o mesmo padrão de `DatabaseRoutinesIntegrationTest`. Nenhuma variável
de ambiente ou credencial externa é usada pelos testes.

Cobertura: ordem e empates, top N, autenticação existente e autorização por cozinha,
validação de parâmetros, saldo/status/validade, rollback, entrada/ajuste, procedures
de baixa/reposição/FEFO, cache vazio, TTL, reconstrução, falhas, publicação serializada
e leitores concorrentes de snapshots atômicos.

## DevOps

Os testes do ranking fazem parte da suíte normal de CI. A branch deve permanecer
atualizada com a `main`, e migrations PostgreSQL só podem ser adicionadas primeiro no
`inventra-database-2`, que é a fonte da verdade do schema.
