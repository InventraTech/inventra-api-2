# INV2-25 Fila de cadastro por código de barras

> **Status:** fluxo interno preservado, sem endpoint HTTP ativo. Os endpoints assíncronos de cadastro por código de barras foram removidos em 26/10/2026 (INV2-206). As classes, o consumer, as chaves e os scripts continuam no projeto, mas nenhum código de produção chama `ProductRegistrationService.enqueue`; o cadastro em uso é o `POST /api/products`.

## Revisão de confiabilidade

Código de barras já cadastrado é rejeitado no enqueue (`BusinessRuleException`). O código de barras é a chave de idempotência do consumer: se ele já existir no processamento, seja numa retomada após commit no PostgreSQL ou num cadastro direto concorrente, o job devolve o `productId` existente, sem cadastrar duas vezes. O produto é gravado na transação normal de cadastro; `tb_product` não tem coluna para o `eventId`.

Falhas transitórias de acesso ao banco e de abertura de transação preservam `pending`, com no máximo cinco execuções que falhem por job. As novas tentativas aguardam 15, 30, 60 e 120 segundos; o polling de 3 segundos pode acrescentar atraso. O contador e o horário da próxima tentativa ficam no JSON Redis e sobrevivem à troca de consumer. A quinta falha vira FAILED/RETRY_LIMIT_EXCEEDED e segue para DLQ. Durante a espera os pedidos posteriores aguardam, preservando FIFO. Jobs antigos sem esses campos assumem zero falhas e execução imediata. Falhas de ACK Redis após sucesso não consomem esse limite. Exceções inesperadas e de infraestrutura são registradas com stack trace em logs restritos.

Payload ausente, JSON inválido ou campos estruturais ausentes são irrecuperáveis: seguem imediatamente para quarentena na DLQ, com status FAILED, eventId, código PAYLOAD_MISSING/INVALID_PAYLOAD e até 4 KiB do payload original. O script exige a concessão atual, remove pending e aplica a mesma retenção limitada da DLQ. Os próximos jobs continuam. Indisponibilidade de conexão Redis não é confundida com payload inválido.

A DLQ retém no máximo 1.000 registros (descarta os mais antigos) e expira após 7 dias sem novas falhas. Esse TTL vale para a lista inteira, não para cada registro. Os resultados individuais continuam com TTL de 7 dias.

Testes de Redis real são opt-in: definir `REDIS_INTEGRATION_TESTS=true` e as variáveis `REDIS_*` de uma instância dedicada a testes. Sem ativação explícita são ignorados; não apontar para produção. O workflow queue-tests.yml executa a suíte com Redis descartável, sem secrets Aiven. Os demais testes usam configurações locais de Redis sem abrir conexões e banco H2.

O `ProductRegistrationService` recebe os dados de um produto revisados pelo usuário e enfileira o cadastro no Redis. Um consumer grava o produto no PostgreSQL e disponibiliza o resultado para consulta. O processo usa Redis List, `LPUSH` para entrada e `BRPOP` para saída, conforme a INV2-25.

## Contrato do cadastro

Para expor o fluxo de novo, um controller precisa chamar `ProductRegistrationService.enqueue` com um `BarcodeRegistrationRequest`:

```json
{
  "name": "Arroz",
  "brand": "Marca revisada",
  "categoryId": 1,
  "unitId": 1,
  "barcode": "7891234567890",
  "supplierId": 7,
  "supplierCode": "SUP-ARROZ-01",
  "referencePrice": 25.90,
  "leadTimeDays": 3
}
```

`name`, `unitId`, `barcode` e `supplierId` são obrigatórios: o produto não nasce sem fornecedor e é gravado já vinculado a ele, como no `POST /api/products`. O fornecedor precisa existir e estar ativo (conferido no enqueue e de novo pelo consumer); `supplierCode`, `referencePrice` e `leadTimeDays` são opcionais. Job enfileirado antes dessa regra, sem `supplierId`, termina `FAILED`/`BUSINESS_RULE` sem cadastrar o produto. Nome tem até 150 caracteres e marca até 80. IDs devem ser positivos; o código aceita de 8 a 14 dígitos. `categoryId` e `brand` são opcionais. Unidade e categoria precisam existir no PostgreSQL quando o consumer processar o cadastro. A foto é enviada separadamente pelo endpoint seguro de upload da API de produtos.

O enqueue devolve `eventId`, `status: QUEUED` e timestamps, e `ProductRegistrationService.find(eventId)` consulta o resultado.

Apenas o usuário que enviou o pedido pode consultar seu resultado. Os estados são `QUEUED`, `PROCESSING`, `COMPLETED` e `FAILED`. Quando concluído, `productId` identifica o produto no endpoint existente `GET /api/products/{id}`. Em falha, `errorCode` é `REFERENCE_NOT_FOUND`, `BUSINESS_RULE`, `DATABASE_ERROR` ou `PROCESSING_ERROR`. Falhas de conexão Redis e falhas internas do protocolo da fila não afirmam que o pedido foi aceito.

O cadastro cria o produto, seguindo as regras existentes de `ProductService`. Entrada de estoque e cadastro de lotes continuam sendo operações próprias.

A checagem existsByBarcode no enqueue não é uma reserva atômica: dois pedidos concorrentes podem ser aceitos antes do primeiro cadastro. No processamento, o segundo evento encontra o código de barras já gravado e termina `COMPLETED` com o `productId` existente, sem cadastrar duas vezes e sem aplicar o nome e a marca dele. A restrição única do PostgreSQL também impede duplicação numa disputa com o cadastro síncrono (DATABASE_ERROR).

Se o commit PostgreSQL ocorrer e o ACK Redis falhar, o retry encontra o produto pelo código de barras e reutiliza o `productId`. Nada no produto marca o evento de origem: se o código for editado ou o produto for fisicamente excluído antes do retry, ele não é encontrado e o cadastro é tentado de novo. Evitar essas alterações enquanto houver jobs sem confirmação.

## Estruturas Redis e recuperação

Prefixo padrão: `inventra:product-registration`.

| Sufixo da chave | Estrutura | Responsabilidade |
| --- | --- | --- |
| `ready` / `ready:<token>` | List | IDs aguardando consumer / disponíveis à concessão atual; `LPUSH`/`BRPOP` implementam FIFO |
| `job:<eventId>` | String JSON | Payload, proprietário, estado e resultado |
| `pending` | Sorted Set | Índice de pedidos ainda não confirmados, em ordem de entrada |
| `sequence` | String numérica | Sequência monotônica usada pelo índice |
| `consumer-lock` | String com TTL | Concessão exclusiva de consumo, renovada a cada 5 segundos |
| `errors` | List | Dead-letter com o JSON original e código de erro |

Scripts Lua enfileiram payload, índice e ID de forma atômica. O `BRPOP` aguarda até 2 segundos, abaixo do timeout Redis de 10 segundos. O consumer usa uma thread de processamento e uma thread de renovação da concessão de 30 segundos, sem bloquear a requisição HTTP. Instâncias que compartilham o prefixo disputam a mesma concessão.

Cada concessão consome uma lista própria. Um consumer que perdeu sua concessão não pode retirar itens da lista do sucessor. A lista temporária tem TTL renovado com a concessão e é removida ao encerrar esse consumer; o índice `pending` preserva os pedidos ainda não confirmados.

Pedidos pendentes e seu payload não têm TTL. O `BRPOP` remove o ID apenas da lista temporária da concessão; ele permanece no índice durável `pending`. Ao adquirir a concessão, o consumer reconstrói sua lista a partir desse índice, inclusive para itens retirados por `BRPOP` antes de registrar `PROCESSING` ou antes de uma confirmação terminal. Pedidos só saem de `pending` depois da confirmação atômica de sucesso ou envio à DLQ. Falha de confirmação no Redis mantém o pedido pendente para retomada, mesmo que o PostgreSQL já tenha confirmado a gravação.

A entrega é **pelo menos uma vez**: reinícios podem repetir o processamento. O código de barras e sua restrição única impedem cadastro duplicado. Não há transação distribuída entre os dois bancos. O resultado final tem TTL de 7 dias; a DLQ segue a retenção limitada descrita acima. Não há endpoint público de leitura ou reprocessamento da DLQ nesta tarefa.

Logs registram início e fim com `eventId`, estado, código de erro e timestamp. Falhas inesperadas incluem stack trace para diagnóstico em logs restritos; a resposta HTTP e o payload da DLQ contêm somente códigos de erro.

## Configuração e operação

As credenciais Redis continuam vindo das variáveis `REDIS_HOST`, `REDIS_PORT`, `REDIS_USERNAME`, `REDIS_PASSWORD`, `REDIS_DATABASE` e `REDIS_SSL`. A Aiven é o serviço principal; manter SSL habilitado nela.

Variáveis opcionais adicionadas ao `.env.example`:

```dotenv
REDIS_PRODUCT_QUEUE_CONSUMER_ENABLED=true
REDIS_PRODUCT_QUEUE_KEY_PREFIX=inventra:product-registration
```

Desabilitar o consumer mantém o producer disponível, permitindo enfileirar enquanto o processamento estiver pausado. Ambientes independentes devem usar prefixos diferentes. A implementação atende Redis/Valkey standalone, como o ambiente atual; Redis Cluster não é coberto nesta entrega.

Reiniciar apenas a API não remove as chaves Redis. Sobreviver a reinícios ou perdas do próprio servidor Redis exige persistência configurada no serviço (Aiven ou AOF + volume no Docker local). Nenhum teste desta tarefa reinicia o serviço Aiven compartilhado.

## Testes e evidências

Usar Java 21. A suíte padrão não precisa de credenciais Redis: os testes reais ficam desativados. Para ativá-los, fornecer REDIS_INTEGRATION_TESTS=true e as variáveis Redis de uma instância dedicada ao processo Maven, não somente ao arquivo .env.

```bash
./mvnw -B -ntp test
```

No Windows, usar `mvnw.cmd` ou a instalação Maven já disponível. O workflow queue-tests.yml usa um serviço Redis de teste; o CI/deploy compartilhado continua dependendo de suas configurações próprias.

Se a execução Java no Windows apresentar `Unable to establish loopback connection` em `UnixDomainSockets`, foi validado o fallback TCP somente no comando de testes: `-DargLine=-Djdk.net.unixdomain.tmpdir=target/java-nio-tcp`, indicando um diretório inexistente. Não é necessário alterar a configuração da aplicação nem usar esse ajuste no CI Linux.

| Critério da INV2-25 | Evidência automatizada |
| --- | --- |
| Producer enfileira corretamente | JSON preservado e IDs na lista com Redis real |
| Consumer processa FIFO | Múltiplos eventos com resultados em ordem |
| Falhas vão para dead-letter | Primeiro evento falha, próximo conclui e payload permanece na DLQ |
| Logs de início e fim | Captura dos logs com timestamps e eventId |
| Reinício da aplicação preserva pedidos | Nova instância recupera evento retirado antes de `PROCESSING` e evento em processamento, ambos na ordem original |
| Sem cadastro duplicado em retomadas | Processamento real com H2 e repetição do mesmo código |
| Proteção do resultado e validação | Testes do serviço |
| Confirmação Redis perdida | Pedido permanece pendente e não vira DLQ indevidamente |

Os testes Redis usam prefixos `inventra:test:product-queue:<UUID>` e removem apenas suas próprias chaves. O processamento relacional dos testes usa H2, sem alterar o PostgreSQL compartilhado. Os relatórios Maven ficam em `target/surefire-reports`.

Uma checagem DNS em 06/10/2026 resolveu o host Aiven citado na review. Isso não comprova autenticação/TLS nem os valores dos secrets de CI/deploy. O .env real não é versionado nem alterado pelos testes.

Referências técnicas: [LPUSH](https://redis.io/docs/latest/commands/lpush/), [BRPOP](https://redis.io/docs/latest/commands/brpop/) e [scripts no Spring Data Redis](https://docs.spring.io/spring-data/redis/reference/redis/scripting.html).

## DevOps

Branch: `feat/fila-processamento-redis`.

Commit sugerido: `fix(redis): corrige migration, autorizacao e recuperacao da fila`.

Título da PR: `INV2-25: Implementar fila de processamento com Redis`.

Vincular à tarefa os testes, exemplos de requisição/resposta e evidências de FIFO/DLQ. Não versionar `.env` nem credenciais.
