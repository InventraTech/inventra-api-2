# INV2-25 — Entrega isolada da revisão

Alterações preparadas na branch local `fix/inv2-25-review`, baseada em
`origin/feat/fila-processamento-redis` (8b7f2d2), em uma cópia separada:
`C:/Users/eduardoqueiroz-ieg/..Inventra/inventra-inv2-25-review`.
O trabalho não commitado do INV2-26 permanece na pasta original.

## Comentários atendidos

- Migration renumerada para V010, preservando a V2 histórica da Aiven e as migrations V006-V009 da main.
- Payload ausente ou inválido vai para quarentena com FAILED e sai de pending.
- Falhas transitórias têm contador persistido, cinco execuções e backoff de
  15/30/60/120 segundos. Falha de ACK após sucesso não conta como falha de processamento.
- POST síncrono e assíncrono exigem ADMIN/SUPERVISOR; comprador/estoquista recebem 403.
- Concorrência na checagem de barcode documentada, sem prometer reserva no enqueue.
- Regressões de 404/405/415; repasse de ErrorResponse preserva também headers.
- Imports da implementação normalizados; logs de infraestrutura mantêm stack trace.
- Retry encontra produto por eventId mesmo após editar barcode. Exclusão física
  permanece uma limitação documentada; não há journal PostgreSQL nesta PR isolada.
- DLQ continua limitada a 1.000 itens e sete dias de inatividade; testes Redis
  continuam exigindo ativação explícita e ambiente dedicado.
- Workflow queue-tests.yml adicionado com Redis descartável, sem secrets Aiven.

## Estado do merge e infraestrutura

Validação local final em 13/10/2026: BUILD SUCCESS; 440 testes contabilizados,
427 executados com sucesso, zero falhas/erros e 13 ignorados por dependerem de
Redis real. Testes incluíram RBAC com JWT, 404/405/415, Flyway com V2 externa
simulada, retry limitado, backoff, ACK perdido e idempotência após editar barcode.
Os scripts novos de quarentena ainda precisam da execução do workflow Redis.
`git diff --check` e a verificação do diff staged passaram.

O Redis descartável do workflow não usa autenticação. REDIS_HOST e REDIS_PORT
continuam obrigatórios quando a integração é habilitada; REDIS_USERNAME e
REDIS_PASSWORD são opcionais para aceitar Redis local sem ACL e continuam sendo
usados quando preenchidos para serviços autenticados.

Comando executado (Java 21, REDIS_INTEGRATION_TESTS=false):

```powershell
.\mvnw.cmd -B -ntp '-DargLine=-Djdk.net.unixdomain.tmpdir=target/java-nio-tcp' '-Dspring.jpa.show-sql=false' test
```

A main 754c055 foi integrada e os conflitos foram resolvidos preservando as
correções da fila e as mudanças mobile, de autenticação e de migrations da main.

O último CI publicado da PR (commit 8b7f2d2) está verde:
[Build da aplicação](https://github.com/InventraTech/inventra-development-spring-redis-neo4j-2/actions/runs/37043822220/job/110960183364).
Isso não valida as alterações novas, ainda não publicadas. O CI compartilhado
não define REDIS_INTEGRATION_TESTS, portanto seu sucesso não prova conexão Aiven.

O host citado resolveu por DNS em 06/10/2026. Valores secretos do GitHub/deploy
não foram lidos nem alterados; autenticação, TLS e histórico real do Flyway ainda
precisam de validação no ambiente apropriado. O Docker local não iniciou.
Aprovações e políticas de merge continuam sendo controladas pelo GitHub/equipe.

## Publicação na PR existente

Após conferir o diff e os resultados, execute na cópia isolada:

```powershell
git add .
git diff --cached --check
git commit -m "fix(redis): corrige migration, autorizacao e recuperacao da fila"
git push origin HEAD:feat/fila-processamento-redis
```

Esse push atualiza a PR #16 existente; não abrir uma PR do ranking para substituí-la.
Se o remoto tiver avançado, o push normal recusará: integrar as novas alterações
antes de tentar novamente, sem force push. Marcar o checklist de main atualizada
apenas após o commit/publicação e confirmar que a main não avançou novamente.
Atualizar evidências no template com o resultado do novo CI.

Ao integrar posteriormente o INV2-26, sua migration durável deve usar uma versão
posterior a V010, pois V010 passa a pertencer ao registration_event_id.
Não aplicar a antiga V2 da PR no banco compartilhado nem executar Flyway repair.
