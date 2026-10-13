-- ---------------------------------------------------
-- FUNCTION CREATION
-- ---------------------------------------------------

CREATE OR REPLACE FUNCTION fn_validate_stock()
RETURNS TRIGGER
LANGUAGE plpgsql
AS
$$
BEGIN

    IF NEW.current_quantity < 0 THEN
        RAISE EXCEPTION
            'O saldo do lote não pode ficar negativo.';
    END IF;

    RETURN NEW;

END;
$$;

CREATE OR REPLACE FUNCTION fn_update_batch_status()
RETURNS TRIGGER
LANGUAGE plpgsql
AS
$$
BEGIN

    IF NEW.current_quantity = 0
       AND NEW.status = 'ACTIVE' THEN

        NEW.status := 'WRITTEN_OFF';

    END IF;

    RETURN NEW;

END;
$$;

CREATE OR REPLACE FUNCTION fn_calculate_divergence()
RETURNS TRIGGER
LANGUAGE plpgsql
AS
$$
BEGIN

    NEW.divergence :=
        NEW.physical_quantity - NEW.registered_quantity;

    RETURN NEW;

END;
$$;

CREATE OR REPLACE FUNCTION fn_requisition_approval()
RETURNS TRIGGER
LANGUAGE plpgsql
AS
$$
BEGIN

    IF NEW.status = 'APPROVED'
       AND OLD.status IS DISTINCT FROM 'APPROVED' THEN

        NEW.approved_at := CURRENT_TIMESTAMP;

    END IF;

    RETURN NEW;

END;
$$;

CREATE OR REPLACE FUNCTION fn_stock_alert()
RETURNS TRIGGER
LANGUAGE plpgsql
AS
$$
DECLARE
    v_min_stock DECIMAL(12,3);
    v_total_quantity DECIMAL(12,3);
    v_existing_alert INTEGER;
BEGIN

    SELECT min_stock
    INTO v_min_stock
    FROM tb_product_kitchen_parameter
    WHERE id_product = NEW.id_product
      AND id_kitchen = NEW.id_kitchen;

    IF v_min_stock IS NULL THEN
        RETURN NEW;
    END IF;

    -- trigger AFTER: a linha alterada já aparece com o valor novo nessa soma; lote vencido não conta
    SELECT COALESCE(SUM(current_quantity), 0)
    INTO v_total_quantity
    FROM tb_stock_batch
    WHERE id_product = NEW.id_product
      AND id_kitchen = NEW.id_kitchen
      AND status = 'ACTIVE'
      AND (expiration_date IS NULL OR expiration_date >= CURRENT_DATE);

    IF v_total_quantity < v_min_stock THEN

        SELECT id_alert
        INTO v_existing_alert
        FROM tb_alert
        WHERE id_product = NEW.id_product
          AND id_kitchen = NEW.id_kitchen
          AND type = 'STOCK'
          AND is_read = false
        LIMIT 1;

        IF v_existing_alert IS NULL THEN

            INSERT INTO tb_alert
            (
                type,
                severity,
                id_batch,
                id_product,
                id_kitchen,
                message
            )
            VALUES
            (
                'STOCK',
                'HIGH',
                NEW.id_batch,
                NEW.id_product,
                NEW.id_kitchen,
                'Produto abaixo do estoque mínimo.'
            );

        END IF;

    END IF;

    RETURN NEW;

END;
$$;

CREATE OR REPLACE FUNCTION fn_expiration_alert()
RETURNS TRIGGER
LANGUAGE plpgsql
AS
$$
DECLARE
    v_existing_alert INTEGER;
BEGIN

    IF NEW.expiration_date IS NULL THEN
        RETURN NEW;
    END IF;

    IF NEW.expiration_date < CURRENT_DATE THEN

        SELECT id_alert
        INTO v_existing_alert
        FROM tb_alert
        WHERE id_batch = NEW.id_batch
          AND type = 'EXPIRATION'
          AND is_read = false
        LIMIT 1;

        IF v_existing_alert IS NULL THEN

            INSERT INTO tb_alert
            (
                type,
                severity,
                id_batch,
                id_product,
                id_kitchen,
                message
            )
            VALUES
            (
                'EXPIRATION',
                'CRITICAL',
                NEW.id_batch,
                NEW.id_product,
                NEW.id_kitchen,
                'Lote vencido. Verifique a validade do produto.'
            );

        END IF;

    END IF;

    RETURN NEW;

END;
$$;

-- ---------------------------------------------------
-- PROCEDURE CREATION
-- ---------------------------------------------------

CREATE OR REPLACE PROCEDURE sp_approve_requisition(
    p_id_requisition INTEGER,
    p_id_approver_user UUID
)
LANGUAGE plpgsql
AS
$$
BEGIN

    IF NOT EXISTS (
        SELECT 1
        FROM tb_requisition
        WHERE id_requisition = p_id_requisition
    ) THEN
        RAISE EXCEPTION
            'Requisição % não encontrada.',
            p_id_requisition;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM tb_requisition
        WHERE id_requisition = p_id_requisition
          AND status = 'UNDER_REVIEW'
    ) THEN
        RAISE EXCEPTION
            'Requisição % não está mais em análise.',
            p_id_requisition;
    END IF;

    UPDATE tb_requisition
    SET
        status = 'APPROVED',
        id_approver_user = p_id_approver_user
    WHERE id_requisition = p_id_requisition;

END;
$$;

CREATE OR REPLACE PROCEDURE sp_reject_requisition(
    p_id_requisition INTEGER,
    p_id_approver_user UUID,
    p_reason VARCHAR(255)
)
LANGUAGE plpgsql
AS
$$
BEGIN

    IF NOT EXISTS (
        SELECT 1
        FROM tb_requisition
        WHERE id_requisition = p_id_requisition
    ) THEN
        RAISE EXCEPTION
            'Requisição % não encontrada.',
            p_id_requisition;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM tb_requisition
        WHERE id_requisition = p_id_requisition
          AND status = 'UNDER_REVIEW'
    ) THEN
        RAISE EXCEPTION
            'Requisição % não está mais em análise.',
            p_id_requisition;
    END IF;

    UPDATE tb_requisition
    SET
        status = 'REJECTED',
        id_approver_user = p_id_approver_user,
        reason = p_reason
    WHERE id_requisition = p_id_requisition;

END;
$$;

CREATE OR REPLACE PROCEDURE sp_cancel_requisition(
    p_id_requisition INTEGER,
    p_reason VARCHAR(255)
)
LANGUAGE plpgsql
AS
$$
BEGIN

    IF NOT EXISTS (
        SELECT 1
        FROM tb_requisition
        WHERE id_requisition = p_id_requisition
    ) THEN
        RAISE EXCEPTION
            'Requisição % não encontrada.',
            p_id_requisition;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM tb_requisition
        WHERE id_requisition = p_id_requisition
          AND status IN ('UNDER_REVIEW', 'APPROVED')
    ) THEN
        RAISE EXCEPTION
            'Requisição % não pode ser cancelada no status atual.',
            p_id_requisition;
    END IF;

    UPDATE tb_requisition
    SET
        status = 'CANCELLED',
        reason = p_reason
    WHERE id_requisition = p_id_requisition;

END;
$$;

CREATE OR REPLACE PROCEDURE sp_register_stock_entry(
    p_id_batch INTEGER,
    p_quantity DECIMAL(12,3)
)
LANGUAGE plpgsql
AS
$$
DECLARE
    v_status VARCHAR(20);
BEGIN

    IF p_quantity <= 0 THEN
        RAISE EXCEPTION
            'A quantidade de entrada deve ser maior que zero.';
    END IF;

    SELECT status
    INTO v_status
    FROM tb_stock_batch
    WHERE id_batch = p_id_batch;

    IF NOT FOUND THEN
        RAISE EXCEPTION
            'Lote % não encontrado.',
            p_id_batch;
    END IF;

    IF v_status IN ('EXPIRED', 'CANCELLED') THEN
        RAISE EXCEPTION
            'Não é possível dar entrada em lote vencido ou cancelado.';
    END IF;

    UPDATE tb_stock_batch
    SET
        current_quantity = current_quantity + p_quantity,
        status = 'ACTIVE'
    WHERE id_batch = p_id_batch;

END;
$$;

CREATE OR REPLACE PROCEDURE sp_write_off_stock(
    p_id_batch INTEGER,
    p_quantity DECIMAL(12,3)
)
LANGUAGE plpgsql
AS
$$
DECLARE
    v_status VARCHAR(20);
    v_current_quantity DECIMAL(12,3);
BEGIN

    IF p_quantity <= 0 THEN
        RAISE EXCEPTION
            'A quantidade de baixa deve ser maior que zero.';
    END IF;

    SELECT status, current_quantity
    INTO v_status, v_current_quantity
    FROM tb_stock_batch
    WHERE id_batch = p_id_batch
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION
            'Lote % não encontrado.',
            p_id_batch;
    END IF;

    IF v_status <> 'ACTIVE' THEN
        RAISE EXCEPTION
            'Só é possível dar baixa em lote ativo.';
    END IF;

    IF v_current_quantity < p_quantity THEN
        RAISE EXCEPTION
            'Saldo insuficiente para dar baixa no lote %.',
            p_id_batch;
    END IF;

    UPDATE tb_stock_batch
    SET
        current_quantity = current_quantity - p_quantity
    WHERE id_batch = p_id_batch;

END;
$$;

CREATE OR REPLACE PROCEDURE sp_close_inventory(
    p_id_inventory INTEGER
)
LANGUAGE plpgsql
AS
$$
BEGIN

    IF NOT EXISTS (
        SELECT 1
        FROM tb_inventory
        WHERE id_inventory = p_id_inventory
    ) THEN
        RAISE EXCEPTION
            'Inventário % não encontrado.',
            p_id_inventory;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM tb_inventory
        WHERE id_inventory = p_id_inventory
          AND status = 'OPEN'
    ) THEN
        RAISE EXCEPTION
            'Inventário % não está mais aberto.',
            p_id_inventory;
    END IF;

    UPDATE tb_inventory
    SET
        status = 'CLOSED',
        closed_at = CURRENT_TIMESTAMP
    WHERE id_inventory = p_id_inventory;

END;
$$;

CREATE OR REPLACE PROCEDURE sp_expire_batches()
LANGUAGE plpgsql
AS
$$
BEGIN

    CREATE TEMP TABLE tmp_expired_batches ON COMMIT DROP AS
    SELECT id_batch, id_product, id_kitchen
    FROM tb_stock_batch
    WHERE status = 'ACTIVE'
      AND expiration_date < CURRENT_DATE;

    UPDATE tb_stock_batch sb
    SET status = 'EXPIRED'
    FROM tmp_expired_batches e
    WHERE sb.id_batch = e.id_batch;

    INSERT INTO tb_alert (type, severity, id_batch, id_product, id_kitchen, message)
    SELECT 'EXPIRATION', 'CRITICAL', e.id_batch, e.id_product, e.id_kitchen,
           'Lote vencido. Verifique a validade do produto.'
    FROM tmp_expired_batches e
    WHERE NOT EXISTS (
        SELECT 1 FROM tb_alert a
        WHERE a.id_batch = e.id_batch
          AND a.type = 'EXPIRATION'
          AND a.is_read = false
    );

    INSERT INTO tb_alert (type, severity, id_product, id_kitchen, message)
    SELECT DISTINCT 'STOCK', 'HIGH', p.id_product, p.id_kitchen, 'Produto abaixo do estoque mínimo.'
    FROM tmp_expired_batches e
    JOIN tb_product_kitchen_parameter p
      ON p.id_product = e.id_product AND p.id_kitchen = e.id_kitchen
    WHERE p.min_stock > (
        SELECT COALESCE(SUM(sb.current_quantity), 0)
        FROM tb_stock_batch sb
        WHERE sb.id_product = p.id_product
          AND sb.id_kitchen = p.id_kitchen
          AND sb.status = 'ACTIVE'
          AND (sb.expiration_date IS NULL OR sb.expiration_date >= CURRENT_DATE)
    )
      AND NOT EXISTS (
        SELECT 1 FROM tb_alert a
        WHERE a.id_product = p.id_product
          AND a.id_kitchen = p.id_kitchen
          AND a.type = 'STOCK'
          AND a.is_read = false
    );

    DROP TABLE tmp_expired_batches;

END;
$$;

-- ---------------------------------------------------
-- TRIGGER CREATION
-- ---------------------------------------------------

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'trg_validate_stock') THEN
        CREATE TRIGGER trg_validate_stock
        BEFORE INSERT OR UPDATE OF current_quantity
        ON tb_stock_batch
        FOR EACH ROW
        EXECUTE FUNCTION fn_validate_stock();
    END IF;
END;
$$;        

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'trg_update_batch_status') THEN
        CREATE TRIGGER trg_update_batch_status
        BEFORE INSERT OR UPDATE OF current_quantity, status
        ON tb_stock_batch
        FOR EACH ROW
        EXECUTE FUNCTION fn_update_batch_status();
    END IF;
END;
$$;        

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'trg_calculate_divergence') THEN
        CREATE TRIGGER trg_calculate_divergence
        BEFORE INSERT OR UPDATE OF registered_quantity, physical_quantity
        ON tb_inventory_count
        FOR EACH ROW
        EXECUTE FUNCTION fn_calculate_divergence();
    END IF;
END;
$$;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'trg_requisition_approval') THEN
        CREATE TRIGGER trg_requisition_approval
        BEFORE UPDATE OF status
        ON tb_requisition
        FOR EACH ROW
        EXECUTE FUNCTION fn_requisition_approval();
    END IF;
END;
$$;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'trg_stock_alert') THEN
        CREATE TRIGGER trg_stock_alert
        AFTER INSERT OR UPDATE OF current_quantity
        ON tb_stock_batch
        FOR EACH ROW
        EXECUTE FUNCTION fn_stock_alert();
    END IF;
END;
$$;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'trg_expiration_alert') THEN
        CREATE TRIGGER trg_expiration_alert
        AFTER INSERT OR UPDATE OF expiration_date
        ON tb_stock_batch
        FOR EACH ROW
        EXECUTE FUNCTION fn_expiration_alert();
    END IF;
END;
$$;