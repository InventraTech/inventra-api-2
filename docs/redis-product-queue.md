# INV2-25 Fila de cadastro por código de barras

## Revisão de confiabilidade

Código de barras já cadastrado é rejeitado no enqueue com HTTP 409. O consumer também verifica a origem do produto: somente o mesmo `eventId`, persistido junto ao produto na transação PostgreSQL (migration V010), pode reutilizar o resultado. Duplicatas concorrentes de outros jobs falham, sem descartar nome/marca em silêncio. Produtos anteriores à migration não têm origem e são tratados como duplicatas; drenar a fila antes de atualizar se houver jobs antigos ainda sem confirmação.

Falhas transitórias de acesso ao banco e de abertura de transação preservam `pending`, com no máximo cinco execuções que falhem por job. As novas tentativas aguardam 15, 30, 60 e 120 segundos; o polling de 3 segundos pode acrescentar atraso. O contador e o horário da próxima tentativa ficam no JSON Redis e sobrevivem à troca de consumer. A quinta falha vira FAILED/RETRY_LIMIT_EXCEEDED e segue para DLQ. Durante a espera os pedidos posteriores aguardam, preservando FIFO. Jobs antigos sem esses campos assumem zero falhas e execução imediata. Falhas de ACK Redis após sucesso não consomem esse limite. Exceções inesperadas e de infraestrutura são registradas com stack trace em logs restritos.

Payload ausente, JSON inválido ou campos estruturais ausentes são irrecuperáveis: seguem imediatamente para quarentena na DLQ, com status FAILED, eventId, código PAYLOAD_MISSING/INVALID_PAYLOAD e até 4 KiB do payload original. O script exige a concessão atual, remove pending e aplica a mesma retenção limitada da DLQ. Os próximos jobs continuam. Indisponibilidade de conexão Redis não é confundida com payload inválido.

A DLQ retém no máximo 1.000 registros (descarta os mais antigos) e expira após 7 dias sem novas falhas. Esse TTL vale para a lista inteira, não para cada registro. Os resultados individuais continuam com TTL de 7 dias.

Testes de Redis real são opt-in: definir `REDIS_INTEGRATION_TESTS=true` e as variáveis `REDIS_*` de uma instância dedicada a testes. Sem ativação explícita são ignorados; não apontar para produção. O workflow queue-tests.yml executa a suíte com Redis descartável, sem secrets Aiven. Os demais testes usam configurações locais de Redis sem abrir conexões e banco H2.

A API recebe os dados de um produto revisados pelo usuário, enfileira o cadastro no Redis e retorna `202 Accepted`. Um consumer grava o produto no PostgreSQL e disponibiliza o resultado para consulta. O processo usa Redis List, `LPUSH` para entrada e `BRPOP` para saída, conforme a INV2-25.

## Uso pela aplicação

1. Ler o código de barras. O endpoint existente `GET /api/products/barcode-lookup?barcode=...` pode auxiliar no preenchimento com Open Food Facts.
2. Solicitar que o usuário revise nome, marca, categoria e unidade. Código de barras não informa lote, quantidade disponível ou validade.
3. Enviar os dados confirmados com JWT de ADMIN ou SUPERVISOR. COMPRADOR e ESTOQUISTA recebem 403. A mesma regra protege POST /api/products e POST /api/products/barcode-registrations:

```http
POST /api/products/barcode-registrations
Authorization: Bearer <token>
Content-Type: application/json

{
  "name": "Arroz",
  "brand": "Marca revisada",
  "categoryId": 1,
  "unitId": 1,
  "barcode": "7891234567890"
}
```

`name`, `unitId` e `barcode` são obrigatórios. Nome tem até 150 caracteres e marca até 80. IDs devem ser positivos; o código aceita de 8 a 14 dígitos. `categoryId` e `brand` são opcionais. Unidade e categoria precisam existir no PostgreSQL quando o consumer processar o cadastro. A foto é enviada separadamente pelo endpoint seguro de upload da API de produtos.

A resposta inclui `eventId`, `status: QUEUED`, timestamps e um header `Location`:

```http
GET /api/products/barcode-registrations/<eventId>
Authorization: Bearer <token>
```

Apenas o usuário que enviou o pedido pode consultar seu resultado. Os estados são `QUEUED`, `PROCESSING`, `COMPLETED` e `FAILED`. Quando concluído, `productId` identifica o produto no endpoint existente `GET /api/products/{id}`. Em falha, `errorCode` é `REFERENCE_NOT_FOUND`, `BUSINESS_RULE`, `DATABASE_ERROR` ou `PROCESSING_ERROR`. Falhas de conexão Redis e falhas internas do protocolo da fila retornam `503` sem afirmar que o pedido foi aceito.

O cadastro cria o produto, seguindo as regras existentes de `ProductService`. Entrada de estoque e cadastro de lotes continuam sendo operações próprias. O reuso do ID é permitido somente para retomadas do mesmo evento.

A checagem existsByBarcode no enqueue não é uma reserva atômica: dois pedidos concorrentes podem receber 202 antes do primeiro cadastro. No processamento, o segundo evento termina FAILED/BUSINESS_RULE se o primeiro já gravou; a restrição única do PostgreSQL também impede duplicação numa disputa com o cadastro síncrono (DATABASE_ERROR).

Se o commit PostgreSQL ocorrer e o ACK Redis falhar, o retry consulta registration_event_id antes de barcode, preservando o resultado mesmo após editar o código de barras. Se o produto for fisicamente excluído, essa marca também desaparece: o retry pode recriar o produto ou falhar se o barcode tiver sido reutilizado por outro evento. Esta PR não mantém histórico durável de produtos excluídos; evitar exclusões físicas enquanto houver jobs sem confirmação. A persistência adicional planejada no INV2-26 não está incluída nesta correção isolada.

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

A entrega é **pelo menos uma vez**: reinícios podem repetir o processamento. A origem `eventId` e a restrição única de código de barras distinguem retomadas de duplicatas. Não há transação distribuída entre os dois bancos. O resultado final tem TTL de 7 dias; a DLQ segue a retenção limitada descrita acima. Não há endpoint público de leitura ou reprocessamento da DLQ nesta tarefa.

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
| Proteção do resultado e validação | Testes do serviço e dos endpoints |
| Confirmação Redis perdida | Pedido permanece pendente e não vira DLQ indevidamente |

Os testes Redis usam prefixos `inventra:test:product-queue:<UUID>` e removem apenas suas próprias chaves. O processamento relacional dos testes usa H2, sem alterar o PostgreSQL compartilhado. Os relatórios Maven ficam em `target/surefire-reports`.

Uma checagem DNS em 06/10/2026 resolveu o host Aiven citado na review. Isso não comprova autenticação/TLS nem os valores dos secrets de CI/deploy. O .env real não é versionado nem alterado pelos testes.

V010__product_registration_event.sql substitui os nomes V2 e V4 usados anteriormente nesta PR. A V2 histórica remove_user_role_column da Aiven permanece intacta; não executar Flyway repair nem editar seu checksum. O teste ProductRegistrationMigrationTest simula uma V2 externa e aplica V010 usando H2; não equivale a validar o banco compartilhado. Um ambiente de desenvolvimento que já aplicou uma versão anterior desta migration precisa de reconciliação específica de histórico antes da atualização.

Referências técnicas: [LPUSH](https://redis.io/docs/latest/commands/lpush/), [BRPOP](https://redis.io/docs/latest/commands/brpop/) e [scripts no Spring Data Redis](https://docs.spring.io/spring-data/redis/reference/redis/scripting.html).

## DevOps

Branch: `feat/fila-processamento-redis`.

Commit sugerido: `fix(redis): corrige migration, autorizacao e recuperacao da fila`.

Título da PR: `INV2-25: Implementar fila de processamento com Redis`.

Vincular à tarefa os testes, exemplos de requisição/resposta e evidências de FIFO/DLQ. Não versionar `.env` nem credenciais.
