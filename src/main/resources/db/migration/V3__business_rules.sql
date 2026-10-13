-- Copiado de arte-e-modelagem-de-dados/postgre/09_migrations/V002__business_rules.sql (disciplina de Modelagem de Dados).
-- As procedures sp_* sao chamadas pela API via CALL nos repositories; as functions fn_* rodam como triggers.
-- E V3 (nao V2) porque o banco compartilhado (Aiven) ja tem uma V2 aplicada: V2__remove_user_role_column, nunca commitada.

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
            'The batch current quantity cannot be negative. Batch: %',
            NEW.id_batch;
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

    IF NEW.current_quantity <= v_min_stock THEN

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
                'Product below minimum stock.'
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
                'Batch expired. Check the product expiration date.'
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
            'Requisition % not found.',
            p_id_requisition;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM tb_requisition
        WHERE id_requisition = p_id_requisition
          AND status = 'UNDER_REVIEW'
    ) THEN
        RAISE EXCEPTION
            'Requisition % is not under review.',
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
            'Requisition % not found.',
            p_id_requisition;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM tb_requisition
        WHERE id_requisition = p_id_requisition
          AND status = 'UNDER_REVIEW'
    ) THEN
        RAISE EXCEPTION
            'Requisition % is not under review.',
            p_id_requisition;
    END IF;

    UPDATE tb_requisition
    SET
        status = 'REJECTED',
        id_approver_user = p_id_approver_user,
        reason = p_reason,
        approved_at = CURRENT_TIMESTAMP
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
            'Requisition % not found.',
            p_id_requisition;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM tb_requisition
        WHERE id_requisition = p_id_requisition
          AND status IN ('UNDER_REVIEW', 'APPROVED')
    ) THEN
        RAISE EXCEPTION
            'Requisition % cannot be cancelled in its current status.',
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
BEGIN

    IF p_quantity <= 0 THEN
        RAISE EXCEPTION
            'Entry quantity must be greater than zero.';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM tb_stock_batch
        WHERE id_batch = p_id_batch
    ) THEN
        RAISE EXCEPTION
            'Batch % not found.',
            p_id_batch;
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
BEGIN

    IF p_quantity <= 0 THEN
        RAISE EXCEPTION
            'Write-off quantity must be greater than zero.';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM tb_stock_batch
        WHERE id_batch = p_id_batch
    ) THEN
        RAISE EXCEPTION
            'Batch % not found.',
            p_id_batch;
    END IF;

    IF (
        SELECT current_quantity
        FROM tb_stock_batch
        WHERE id_batch = p_id_batch
    ) < p_quantity THEN
        RAISE EXCEPTION
            'Insufficient stock to write off batch %.',
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
            'Inventory % not found.',
            p_id_inventory;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM tb_inventory
        WHERE id_inventory = p_id_inventory
          AND status = 'OPEN'
    ) THEN
        RAISE EXCEPTION
            'Inventory % is not open.',
            p_id_inventory;
    END IF;

    UPDATE tb_inventory
    SET
        status = 'CLOSED',
        closed_at = CURRENT_TIMESTAMP
    WHERE id_inventory = p_id_inventory;

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
