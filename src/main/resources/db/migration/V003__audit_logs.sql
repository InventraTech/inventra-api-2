-- ---------------------------------------------------
-- LOG TABLE CREATION
-- ---------------------------------------------------

CREATE SEQUENCE IF NOT EXISTS seq_log_id;

CREATE TABLE IF NOT EXISTS tb_log_base (
    id_log INTEGER NOT NULL DEFAULT nextval('seq_log_id'),
    operation VARCHAR(10) NOT NULL,
    db_user VARCHAR(100) NOT NULL,
    operation_date TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    previous_data JSONB,
    new_data JSONB,
    PRIMARY KEY (id_log)
);

ALTER SEQUENCE seq_log_id OWNED BY tb_log_base.id_log;

CREATE TABLE IF NOT EXISTS tb_log_user (
    id_user UUID
) INHERITS (tb_log_base);

CREATE TABLE IF NOT EXISTS tb_log_product (
    id_product INTEGER
) INHERITS (tb_log_base);

CREATE TABLE IF NOT EXISTS tb_log_supplier (
    id_supplier INTEGER
) INHERITS (tb_log_base);

CREATE TABLE IF NOT EXISTS tb_log_stock_batch (
    id_batch INTEGER
) INHERITS (tb_log_base);

CREATE TABLE IF NOT EXISTS tb_log_requisition (
    id_requisition INTEGER
) INHERITS (tb_log_base);

CREATE TABLE IF NOT EXISTS tb_log_inventory (
    id_inventory INTEGER
) INHERITS (tb_log_base);

CREATE TABLE IF NOT EXISTS tb_log_alert (
    id_alert INTEGER
) INHERITS (tb_log_base);

-- ---------------------------------------------------
-- LOG TABLE INDEXES CREATION
-- ---------------------------------------------------

CREATE INDEX IF NOT EXISTS idx_log_stock_batch_id_batch
ON tb_log_stock_batch (id_batch);

-- ---------------------------------------------------
-- LOG FUNCTION CREATION
-- ---------------------------------------------------

CREATE OR REPLACE FUNCTION fn_log_user()
RETURNS TRIGGER
LANGUAGE plpgsql
AS
$$
BEGIN
    IF TG_OP = 'INSERT' THEN
        INSERT INTO tb_log_user
        (id_user, operation, db_user, previous_data, new_data)
        VALUES
        (NEW.id_user, TG_OP, CURRENT_USER, NULL, to_jsonb(NEW));

        RETURN NEW;  

    ELSIF TG_OP = 'UPDATE' THEN

        INSERT INTO tb_log_user
        (id_user, operation, db_user, previous_data, new_data)
        VALUES
        (NEW.id_user, TG_OP, CURRENT_USER, to_jsonb(OLD), to_jsonb(NEW));

        RETURN NEW;

    ELSIF TG_OP = 'DELETE' THEN

        INSERT INTO tb_log_user
        (id_user, operation, db_user, previous_data, new_data)
        VALUES
        (OLD.id_user, TG_OP, CURRENT_USER, to_jsonb(OLD), NULL);

        RETURN OLD;

    END IF;
END;
$$;

CREATE OR REPLACE FUNCTION fn_log_product()
RETURNS TRIGGER
LANGUAGE plpgsql
AS
$$
BEGIN
    IF TG_OP = 'INSERT' THEN

        INSERT INTO tb_log_product
        (id_product, operation, db_user, previous_data, new_data)
        VALUES
        (NEW.id_product, TG_OP, CURRENT_USER, NULL, to_jsonb(NEW));

        RETURN NEW;

    ELSIF TG_OP = 'UPDATE' THEN

        INSERT INTO tb_log_product
        (id_product, operation, db_user, previous_data, new_data)
        VALUES
        (NEW.id_product, TG_OP, CURRENT_USER, to_jsonb(OLD), to_jsonb(NEW));

        RETURN NEW;

    ELSIF TG_OP = 'DELETE' THEN

        INSERT INTO tb_log_product
        (id_product, operation, db_user, previous_data, new_data)
        VALUES
        (OLD.id_product, TG_OP, CURRENT_USER, to_jsonb(OLD), NULL);

        RETURN OLD;

    END IF;
END;
$$;


CREATE OR REPLACE FUNCTION fn_log_supplier()
RETURNS TRIGGER
LANGUAGE plpgsql
AS
$$
BEGIN
    IF TG_OP = 'INSERT' THEN

        INSERT INTO tb_log_supplier
        (id_supplier, operation, db_user, previous_data, new_data)
        VALUES
        (NEW.id_supplier, TG_OP, CURRENT_USER, NULL, to_jsonb(NEW));

        RETURN NEW;

    ELSIF TG_OP = 'UPDATE' THEN

        INSERT INTO tb_log_supplier
        (id_supplier, operation, db_user, previous_data, new_data)
        VALUES
        (NEW.id_supplier, TG_OP, CURRENT_USER, to_jsonb(OLD), to_jsonb(NEW));

        RETURN NEW;

    ELSIF TG_OP = 'DELETE' THEN

        INSERT INTO tb_log_supplier
        (id_supplier, operation, db_user, previous_data, new_data)
        VALUES
        (OLD.id_supplier, TG_OP, CURRENT_USER, to_jsonb(OLD), NULL);

        RETURN OLD;

    END IF;
END;
$$;


CREATE OR REPLACE FUNCTION fn_log_stock_batch()
RETURNS TRIGGER
LANGUAGE plpgsql
AS
$$
BEGIN
    IF TG_OP = 'INSERT' THEN

        INSERT INTO tb_log_stock_batch
        (id_batch, operation, db_user, previous_data, new_data)
        VALUES
        (NEW.id_batch, TG_OP, CURRENT_USER, NULL, to_jsonb(NEW));

        RETURN NEW;

    ELSIF TG_OP = 'UPDATE' THEN

        INSERT INTO tb_log_stock_batch
        (id_batch, operation, db_user, previous_data, new_data)
        VALUES
        (NEW.id_batch, TG_OP, CURRENT_USER, to_jsonb(OLD), to_jsonb(NEW));

        RETURN NEW;

    ELSIF TG_OP = 'DELETE' THEN

        INSERT INTO tb_log_stock_batch
        (id_batch, operation, db_user, previous_data, new_data)
        VALUES
        (OLD.id_batch, TG_OP, CURRENT_USER, to_jsonb(OLD), NULL);

        RETURN OLD;

    END IF;
END;
$$;


CREATE OR REPLACE FUNCTION fn_log_requisition()
RETURNS TRIGGER
LANGUAGE plpgsql
AS
$$
BEGIN
    IF TG_OP = 'INSERT' THEN

        INSERT INTO tb_log_requisition
        (id_requisition, operation, db_user, previous_data, new_data)
        VALUES
        (NEW.id_requisition, TG_OP, CURRENT_USER, NULL, to_jsonb(NEW));

        RETURN NEW;

    ELSIF TG_OP = 'UPDATE' THEN

        INSERT INTO tb_log_requisition
        (id_requisition, operation, db_user, previous_data, new_data)
        VALUES
        (NEW.id_requisition, TG_OP, CURRENT_USER, to_jsonb(OLD), to_jsonb(NEW));

        RETURN NEW;

    ELSIF TG_OP = 'DELETE' THEN

        INSERT INTO tb_log_requisition
        (id_requisition, operation, db_user, previous_data, new_data)
        VALUES
        (OLD.id_requisition, TG_OP, CURRENT_USER, to_jsonb(OLD), NULL);

        RETURN OLD;

    END IF;
END;
$$;


CREATE OR REPLACE FUNCTION fn_log_inventory()
RETURNS TRIGGER
LANGUAGE plpgsql
AS
$$
BEGIN
    IF TG_OP = 'INSERT' THEN

        INSERT INTO tb_log_inventory
        (id_inventory, operation, db_user, previous_data, new_data)
        VALUES
        (NEW.id_inventory, TG_OP, CURRENT_USER, NULL, to_jsonb(NEW));

        RETURN NEW;

    ELSIF TG_OP = 'UPDATE' THEN

        INSERT INTO tb_log_inventory
        (id_inventory, operation, db_user, previous_data, new_data)
        VALUES
        (NEW.id_inventory, TG_OP, CURRENT_USER, to_jsonb(OLD), to_jsonb(NEW));

        RETURN NEW;

    ELSIF TG_OP = 'DELETE' THEN

        INSERT INTO tb_log_inventory
        (id_inventory, operation, db_user, previous_data, new_data)
        VALUES
        (OLD.id_inventory, TG_OP, CURRENT_USER, to_jsonb(OLD), NULL);

        RETURN OLD;

    END IF;
END;
$$;


CREATE OR REPLACE FUNCTION fn_log_alert()
RETURNS TRIGGER
LANGUAGE plpgsql
AS
$$
BEGIN
    IF TG_OP = 'INSERT' THEN

        INSERT INTO tb_log_alert
        (id_alert, operation, db_user, previous_data, new_data)
        VALUES
        (NEW.id_alert, TG_OP, CURRENT_USER, NULL, to_jsonb(NEW));

        RETURN NEW;

    ELSIF TG_OP = 'UPDATE' THEN

        INSERT INTO tb_log_alert
        (id_alert, operation, db_user, previous_data, new_data)
        VALUES
        (NEW.id_alert, TG_OP, CURRENT_USER, to_jsonb(OLD), to_jsonb(NEW));

        RETURN NEW;

    ELSIF TG_OP = 'DELETE' THEN

        INSERT INTO tb_log_alert
        (id_alert, operation, db_user, previous_data, new_data)
        VALUES
        (OLD.id_alert, TG_OP, CURRENT_USER, to_jsonb(OLD), NULL);

        RETURN OLD;

    END IF;
END;
$$;

-- ---------------------------------------------------
-- LOG TRIGGER CREATION
-- ---------------------------------------------------

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'trg_log_user') THEN
        CREATE TRIGGER trg_log_user
        AFTER INSERT OR UPDATE OR DELETE
        ON tb_user
        FOR EACH ROW EXECUTE FUNCTION fn_log_user();
    END IF;
END;
$$;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'trg_log_product') THEN
        CREATE TRIGGER trg_log_product
        AFTER INSERT OR UPDATE OR DELETE
        ON tb_product
        FOR EACH ROW EXECUTE FUNCTION fn_log_product();
    END IF;
END;
$$;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'trg_log_supplier') THEN
        CREATE TRIGGER trg_log_supplier
        AFTER INSERT OR UPDATE OR DELETE
        ON tb_supplier
        FOR EACH ROW EXECUTE FUNCTION fn_log_supplier();
    END IF;
END;
$$;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'trg_log_stock_batch') THEN
        CREATE TRIGGER trg_log_stock_batch
        AFTER INSERT OR UPDATE OR DELETE
        ON tb_stock_batch
        FOR EACH ROW EXECUTE FUNCTION fn_log_stock_batch();
    END IF;
END;
$$;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'trg_log_requisition') THEN
        CREATE TRIGGER trg_log_requisition
        AFTER INSERT OR UPDATE OR DELETE
        ON tb_requisition
        FOR EACH ROW EXECUTE FUNCTION fn_log_requisition();
    END IF;
END;
$$;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'trg_log_inventory') THEN
        CREATE TRIGGER trg_log_inventory
        AFTER INSERT OR UPDATE OR DELETE
        ON tb_inventory
        FOR EACH ROW EXECUTE FUNCTION fn_log_inventory();
    END IF;
END;
$$;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'trg_log_alert') THEN
        CREATE TRIGGER trg_log_alert
        AFTER INSERT OR UPDATE OR DELETE
        ON tb_alert
        FOR EACH ROW EXECUTE FUNCTION fn_log_alert();
    END IF;
END;
$$;