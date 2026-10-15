-- ---------------------------------------------------
-- NORMALIZATION INTEGRITY
-- ---------------------------------------------------
-- Fecha as duas redundâncias controladas que não tinham proteção completa
-- (docs/normalization/NORMALIZATION.md, R1 e R4):
--   R1: divergence passa a ser recalculada em qualquer UPDATE, não só quando as quantidades mudam;
--   R4: alerta com lote passa a ter sempre o produto e a cozinha do lote.
-- A API não muda: ela não altera contagens e já valida lote x cozinha x produto ao criar alertas.

-- ---------------------------------------------------
-- R1: DIVERGENCE
-- ---------------------------------------------------

-- antes: BEFORE INSERT OR UPDATE OF registered_quantity, physical_quantity
-- (um UPDATE só de divergence passava sem recálculo)
DROP TRIGGER IF EXISTS trg_calculate_divergence
ON tb_inventory_count;

CREATE TRIGGER trg_calculate_divergence
BEFORE INSERT OR UPDATE
ON tb_inventory_count
FOR EACH ROW
EXECUTE FUNCTION fn_calculate_divergence();

-- corrige o que já estiver errado (a própria trigger recalcula)
UPDATE tb_inventory_count
SET divergence = physical_quantity - registered_quantity
WHERE divergence IS DISTINCT FROM physical_quantity - registered_quantity;

-- ---------------------------------------------------
-- R4: ALERT X BATCH
-- ---------------------------------------------------

-- Alerta com lote: produto e cozinha são os do lote. Vazio é preenchido; diferente é recusado.
CREATE OR REPLACE FUNCTION fn_alert_batch_consistency()
RETURNS TRIGGER
LANGUAGE plpgsql
AS
$$
DECLARE
    v_id_product INTEGER;
    v_id_kitchen INTEGER;
BEGIN

    IF NEW.id_batch IS NULL THEN
        RETURN NEW;
    END IF;

    SELECT id_product, id_kitchen
    INTO v_id_product, v_id_kitchen
    FROM tb_stock_batch
    WHERE id_batch = NEW.id_batch;

    -- lote inexistente: a FK fk_alert_batch recusa com a mensagem dela
    IF NOT FOUND THEN
        RETURN NEW;
    END IF;

    NEW.id_product := COALESCE(NEW.id_product, v_id_product);
    NEW.id_kitchen := COALESCE(NEW.id_kitchen, v_id_kitchen);

    IF NEW.id_product <> v_id_product OR NEW.id_kitchen <> v_id_kitchen THEN
        RAISE EXCEPTION
            'O alerta do lote % precisa ser do produto % e da cozinha %.',
            NEW.id_batch, v_id_product, v_id_kitchen;
    END IF;

    RETURN NEW;

END;
$$;

-- corrige os alertas já gravados antes de ligar a trigger (ex.: lote sem produto)
UPDATE tb_alert a
SET id_product = sb.id_product,
    id_kitchen = sb.id_kitchen
FROM tb_stock_batch sb
WHERE sb.id_batch = a.id_batch
  AND (a.id_product IS DISTINCT FROM sb.id_product OR a.id_kitchen <> sb.id_kitchen);

-- só nas colunas da regra: marcar como lido (is_read) não paga o custo da consulta
DROP TRIGGER IF EXISTS trg_alert_batch_consistency
ON tb_alert;

CREATE TRIGGER trg_alert_batch_consistency
BEFORE INSERT OR UPDATE OF id_batch, id_product, id_kitchen
ON tb_alert
FOR EACH ROW
EXECUTE FUNCTION fn_alert_batch_consistency();

-- ---------------------------------------------------
-- DATA CATALOG
-- ---------------------------------------------------

UPDATE tb_catalog_table
SET business_rules = 'Gerados por trigger e pelo job diário; não duplica enquanto houver alerta aberto do mesmo tipo. Destinatário nulo = cozinha inteira. Com lote, produto e cozinha vêm do lote (trg_alert_batch_consistency). Alterações auditadas em tb_log_alert.',
    updated_at = CURRENT_TIMESTAMP
WHERE table_name = 'tb_alert';

UPDATE tb_catalog_column cc
SET business_rule = v.business_rule,
    updated_at = CURRENT_TIMESTAMP
FROM (VALUES
	('tb_inventory_count', 'divergence', 'Calculada por fn_calculate_divergence (física - registrada) em todo INSERT e UPDATE; valor escrito à mão é sobrescrito.'),
	('tb_alert', 'id_batch', 'Nulo em alerta de estoque mínimo. Com lote, produto e cozinha do alerta são os do lote.'),
	('tb_alert', 'id_product', 'Com lote, é sempre o produto do lote: vazio é preenchido e diferente é recusado (trg_alert_batch_consistency).'),
	('tb_alert', 'id_kitchen', 'Obrigatório. Com lote, é sempre a cozinha do lote (trg_alert_batch_consistency).')
) AS v(table_name, column_name, business_rule)
JOIN tb_catalog_table t ON t.table_name = v.table_name
WHERE cc.id_catalog_table = t.id_catalog_table
  AND cc.column_name = v.column_name;

CALL sp_sync_data_catalog();
