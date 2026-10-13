-- ---------------------------------------------------
-- REQUISITION: DRAFT STATUS
-- ---------------------------------------------------

ALTER TABLE tb_requisition
DROP CONSTRAINT IF EXISTS ck_requisition_status;

ALTER TABLE tb_requisition
ADD CONSTRAINT ck_requisition_status
CHECK (status IN ('DRAFT', 'UNDER_REVIEW', 'APPROVED', 'REJECTED', 'CANCELLED'));

ALTER TABLE tb_requisition
ALTER COLUMN status SET DEFAULT 'DRAFT';

-- ---------------------------------------------------
-- PROCEDURE CREATION
-- ---------------------------------------------------

-- p_id_user tem DEFAULT: a chamada antiga, só com requisição e motivo, continua valendo.
-- a assinatura antiga precisa sair antes, senão o CALL com dois argumentos fica ambíguo.
DROP PROCEDURE IF EXISTS sp_cancel_requisition(INTEGER, VARCHAR);

CREATE OR REPLACE PROCEDURE sp_cancel_requisition(
    p_id_requisition INTEGER,
    p_reason VARCHAR(255),
    p_id_user UUID DEFAULT NULL
)
LANGUAGE plpgsql
AS
$$
DECLARE
    v_status VARCHAR(20);
    v_write_off RECORD;
    v_balance DECIMAL(12,3);
BEGIN

    SELECT status
    INTO v_status
    FROM tb_requisition
    WHERE id_requisition = p_id_requisition
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION
            'Requisição % não encontrada.',
            p_id_requisition;
    END IF;

    IF v_status NOT IN ('DRAFT', 'UNDER_REVIEW', 'APPROVED') THEN
        RAISE EXCEPTION
            'Requisição % não pode ser cancelada no status atual.',
            p_id_requisition;
    END IF;

    -- estorno: devolve aos lotes o que a aprovação baixou (movimentações WRITE_OFF da requisição)
    IF v_status = 'APPROVED' THEN

        FOR v_write_off IN
            SELECT id_batch, SUM(quantity) AS quantity
            FROM tb_stock_movement
            WHERE id_requisition = p_id_requisition
              AND type = 'WRITE_OFF'
            GROUP BY id_batch
            ORDER BY id_batch
        LOOP

            UPDATE tb_stock_batch
            SET
                current_quantity = current_quantity + v_write_off.quantity,
                status = CASE WHEN status = 'WRITTEN_OFF' THEN 'ACTIVE' ELSE status END
            WHERE id_batch = v_write_off.id_batch
            RETURNING current_quantity INTO v_balance;

            INSERT INTO tb_stock_movement (id_batch, id_user, id_requisition, type, quantity, balance_after)
            VALUES (v_write_off.id_batch, p_id_user, p_id_requisition, 'ADJUSTMENT', v_write_off.quantity, v_balance);

        END LOOP;

    END IF;

    UPDATE tb_requisition
    SET
        status = 'CANCELLED',
        reason = p_reason
    WHERE id_requisition = p_id_requisition;

END;
$$;

-- ---------------------------------------------------
-- VIEWS
-- ---------------------------------------------------

-- rascunho ainda não foi enviado: fica fora do resumo, do ranking e do Data Mart

CREATE OR REPLACE VIEW vw_requisition_summary AS
SELECT
    r.id_requisition,
    r.requisition_type,
    r.origin,
    r.status,
    r.reason,
    r.id_kitchen,
    k.name AS kitchen_name,
    r.id_requester_user,
    ru.name AS requester_name,
    ru.email AS requester_email,
    r.id_approver_user,
    au.name AS approver_name,
    r.created_at,
    r.approved_at,
    COUNT(ri.id_requisition_item) AS item_count,
    COALESCE(SUM(ri.quantity * COALESCE(ri.estimated_price, 0)), 0) AS total_estimated_value
FROM tb_requisition r
JOIN tb_kitchen k ON k.id_kitchen = r.id_kitchen
JOIN tb_user ru ON ru.id_user = r.id_requester_user
LEFT JOIN tb_user au ON au.id_user = r.id_approver_user
LEFT JOIN tb_requisition_item ri ON ri.id_requisition = r.id_requisition
WHERE r.status <> 'DRAFT'
GROUP BY r.id_requisition, r.requisition_type, r.origin, r.status, r.reason,
         r.id_kitchen, k.name, r.id_requester_user, ru.name, ru.email,
         r.id_approver_user, au.name, r.created_at, r.approved_at;

CREATE OR REPLACE VIEW vw_product_requisition_ranking AS
SELECT
    r.id_kitchen,
    k.name AS kitchen_name,
    ri.id_product,
    p.name AS product_name,
    COUNT(DISTINCT r.id_requisition) AS requisition_count,
    SUM(ri.quantity) AS total_requested_quantity,
    SUM(ri.quantity * COALESCE(ri.estimated_price, 0)) AS total_estimated_value,
    RANK() OVER (
        PARTITION BY r.id_kitchen
        ORDER BY SUM(ri.quantity) DESC
    ) AS demand_rank
FROM tb_requisition_item ri
JOIN tb_requisition r ON r.id_requisition = ri.id_requisition
JOIN tb_product p ON p.id_product = ri.id_product
JOIN tb_kitchen k ON k.id_kitchen = r.id_kitchen
WHERE r.status <> 'DRAFT'
GROUP BY r.id_kitchen, k.name, ri.id_product, p.name;

CREATE OR REPLACE VIEW fact_requisition_item AS
SELECT
    ri.id_requisition_item,
    ri.id_requisition,
    ri.id_product,
    r.id_kitchen,
    ri.id_suggested_supplier AS id_supplier,
    (r.created_at)::DATE AS date_key,
    r.created_at,
    r.status AS requisition_status,
    r.requisition_type,
    ri.quantity,
    ri.estimated_price,
    (ri.quantity * COALESCE(ri.estimated_price, 0)) AS estimated_value
FROM tb_requisition_item ri
JOIN tb_requisition r ON r.id_requisition = ri.id_requisition
WHERE r.status <> 'DRAFT';
