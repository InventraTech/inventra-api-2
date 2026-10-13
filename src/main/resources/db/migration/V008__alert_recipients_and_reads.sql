-- ---------------------------------------------------
-- ALERT: RECIPIENT, REFERENCE AND LONGER MESSAGE
-- ---------------------------------------------------

-- id_user e id_target_profile nulos = alerta para toda a cozinha
ALTER TABLE tb_alert
ADD COLUMN IF NOT EXISTS id_user UUID;

ALTER TABLE tb_alert
ADD COLUMN IF NOT EXISTS id_target_profile INTEGER;

-- o que originou o alerta: id da requisição, id do pedido de entrada ou código de barras
ALTER TABLE tb_alert
ADD COLUMN IF NOT EXISTS ref_key VARCHAR(100);

-- vw_active_alerts usa a coluna message e o PostgreSQL não altera o tipo de coluna usada por view:
-- a view sai aqui e é recriada no fim desta migration.
DO $$
BEGIN
	IF EXISTS (
		SELECT 1
		FROM information_schema.columns
		WHERE table_schema = current_schema()
		  AND table_name = 'tb_alert'
		  AND column_name = 'message'
		  AND character_maximum_length < 500
	) THEN
		DROP VIEW IF EXISTS vw_active_alerts;

		ALTER TABLE tb_alert
		ALTER COLUMN message TYPE VARCHAR(500);
	END IF;
END;
$$;

-- ---------------------------------------------------
-- TABLE CREATION
-- ---------------------------------------------------

CREATE TABLE IF NOT EXISTS tb_alert_read (
	id_alert INTEGER NOT NULL,
	id_user UUID NOT NULL,
	read_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
	PRIMARY KEY (id_alert, id_user)
);

-- ---------------------------------------------------
-- FOREIGN KEYS
-- ---------------------------------------------------

DO $$
BEGIN
	IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_alert_user') THEN
		ALTER TABLE tb_alert
		ADD CONSTRAINT fk_alert_user
		FOREIGN KEY (id_user) REFERENCES tb_user (id_user)
		ON UPDATE NO ACTION ON DELETE NO ACTION;
	END IF;
END;
$$;

DO $$
BEGIN
	IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_alert_target_profile') THEN
		ALTER TABLE tb_alert
		ADD CONSTRAINT fk_alert_target_profile
		FOREIGN KEY (id_target_profile) REFERENCES tb_profile (id_profile)
		ON UPDATE NO ACTION ON DELETE NO ACTION;
	END IF;
END;
$$;

DO $$
BEGIN
	IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_alertread_alert') THEN
		ALTER TABLE tb_alert_read
		ADD CONSTRAINT fk_alertread_alert
		FOREIGN KEY (id_alert) REFERENCES tb_alert (id_alert)
		ON UPDATE NO ACTION ON DELETE CASCADE;
	END IF;
END;
$$;

DO $$
BEGIN
	IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_alertread_user') THEN
		ALTER TABLE tb_alert_read
		ADD CONSTRAINT fk_alertread_user
		FOREIGN KEY (id_user) REFERENCES tb_user (id_user)
		ON UPDATE NO ACTION ON DELETE NO ACTION;
	END IF;
END;
$$;

-- ---------------------------------------------------
-- INDEX CREATION
-- ---------------------------------------------------

CREATE INDEX IF NOT EXISTS idx_alert_user
ON tb_alert (id_user);

CREATE INDEX IF NOT EXISTS idx_alert_ref_key
ON tb_alert (ref_key);

CREATE INDEX IF NOT EXISTS idx_alertread_user
ON tb_alert_read (id_user);

-- ---------------------------------------------------
-- FUNCTION CREATION
-- ---------------------------------------------------

-- Alerta "aberto" = ninguém leu ainda: is_read = false (leitura antiga, do alerta inteiro)
-- e nenhuma linha em tb_alert_read (leitura por pessoa).

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

        SELECT a.id_alert
        INTO v_existing_alert
        FROM tb_alert a
        WHERE a.id_product = NEW.id_product
          AND a.id_kitchen = NEW.id_kitchen
          AND a.type = 'STOCK'
          AND a.is_read = false
          AND NOT EXISTS (
              SELECT 1 FROM tb_alert_read ar
              WHERE ar.id_alert = a.id_alert
          )
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

        SELECT a.id_alert
        INTO v_existing_alert
        FROM tb_alert a
        WHERE a.id_batch = NEW.id_batch
          AND a.type = 'EXPIRATION'
          AND a.is_read = false
          AND NOT EXISTS (
              SELECT 1 FROM tb_alert_read ar
              WHERE ar.id_alert = a.id_alert
          )
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
          AND NOT EXISTS (
              SELECT 1 FROM tb_alert_read ar
              WHERE ar.id_alert = a.id_alert
          )
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
          AND NOT EXISTS (
              SELECT 1 FROM tb_alert_read ar
              WHERE ar.id_alert = a.id_alert
          )
    );

    DROP TABLE tmp_expired_batches;

END;
$$;

CREATE OR REPLACE PROCEDURE sp_alert_expiring_batches(
    p_days INTEGER DEFAULT 3
)
LANGUAGE plpgsql
AS
$$
BEGIN

    IF p_days IS NULL OR p_days < 0 THEN
        RAISE EXCEPTION
            'A quantidade de dias para o aviso de validade não pode ser negativa.';
    END IF;

    INSERT INTO tb_alert (type, severity, id_batch, id_product, id_kitchen, message)
    SELECT
        'EXPIRING',
        CASE
            WHEN sb.expiration_date <= CURRENT_DATE + 1 THEN 'HIGH'
            ELSE 'MEDIUM'
        END,
        sb.id_batch,
        sb.id_product,
        sb.id_kitchen,
        'Lote ' || sb.batch_number ||
        CASE
            WHEN sb.expiration_date = CURRENT_DATE THEN ' vence hoje'
            WHEN sb.expiration_date = CURRENT_DATE + 1 THEN ' vence amanhã'
            ELSE ' vence em ' || (sb.expiration_date - CURRENT_DATE) || ' dias'
        END ||
        ' (' || TO_CHAR(sb.expiration_date, 'DD/MM') || ').'
    FROM tb_stock_batch sb
    WHERE sb.status = 'ACTIVE'
      AND sb.expiration_date BETWEEN CURRENT_DATE AND CURRENT_DATE + p_days
      AND NOT EXISTS (
        SELECT 1 FROM tb_alert a
        WHERE a.id_batch = sb.id_batch
          AND a.type = 'EXPIRING'
          AND a.is_read = false
          AND NOT EXISTS (
              SELECT 1 FROM tb_alert_read ar
              WHERE ar.id_alert = a.id_alert
          )
    );

END;
$$;

-- ---------------------------------------------------
-- VIEWS
-- ---------------------------------------------------

-- mesmas colunas da V004 (só o filtro muda): reaplicar a V004 por cima continua funcionando

CREATE OR REPLACE VIEW vw_active_alerts AS
SELECT
    a.id_alert,
    a.type,
    a.severity,
    CASE a.severity
        WHEN 'CRITICAL' THEN 1
        WHEN 'HIGH' THEN 2
        WHEN 'MEDIUM' THEN 3
        WHEN 'LOW' THEN 4
        ELSE 5
    END AS severity_rank,
    a.id_kitchen,
    k.name AS kitchen_name,
    a.id_product,
    p.name AS product_name,
    a.id_batch,
    sb.batch_number,
    sb.expiration_date,
    a.message,
    a.created_at,
    (CURRENT_TIMESTAMP - a.created_at) AS alert_age
FROM tb_alert a
JOIN tb_kitchen k ON k.id_kitchen = a.id_kitchen
LEFT JOIN tb_product p ON p.id_product = a.id_product
LEFT JOIN tb_stock_batch sb ON sb.id_batch = a.id_batch
WHERE a.is_read = false
  AND NOT EXISTS (
      SELECT 1 FROM tb_alert_read ar
      WHERE ar.id_alert = a.id_alert
  );

CREATE OR REPLACE VIEW vw_kitchen_dashboard_kpi AS
SELECT
    k.id_kitchen,
    k.name AS kitchen_name,
    COALESCE(stock.active_batch_count, 0) AS active_batch_count,
    COALESCE(stock.distinct_product_count, 0) AS distinct_product_count,
    COALESCE(stock.total_stock_value, 0) AS total_stock_value,
    COALESCE(stock.expired_batch_count, 0) AS expired_batch_count,
    COALESCE(stock.critical_batch_count, 0) AS critical_batch_count,
    COALESCE(alerts.active_alert_count, 0) AS active_alert_count,
    COALESCE(reqs.pending_requisition_count, 0) AS pending_requisition_count
FROM tb_kitchen k
LEFT JOIN (
    SELECT
        id_kitchen,
        COUNT(*) AS active_batch_count,
        COUNT(DISTINCT id_product) AS distinct_product_count,
        SUM(current_quantity * COALESCE(unit_price, 0)) AS total_stock_value,
        COUNT(*) FILTER (WHERE expiration_date < CURRENT_DATE) AS expired_batch_count,
        COUNT(*) FILTER (
            WHERE expiration_date >= CURRENT_DATE
              AND expiration_date <= CURRENT_DATE + 3
        ) AS critical_batch_count
    FROM tb_stock_batch
    WHERE status = 'ACTIVE'
    GROUP BY id_kitchen
) stock ON stock.id_kitchen = k.id_kitchen
LEFT JOIN (
    SELECT a.id_kitchen, COUNT(*) AS active_alert_count
    FROM tb_alert a
    WHERE a.is_read = false
      AND NOT EXISTS (
          SELECT 1 FROM tb_alert_read ar
          WHERE ar.id_alert = a.id_alert
      )
    GROUP BY a.id_kitchen
) alerts ON alerts.id_kitchen = k.id_kitchen
LEFT JOIN (
    SELECT id_kitchen, COUNT(*) AS pending_requisition_count
    FROM tb_requisition
    WHERE status = 'UNDER_REVIEW'
    GROUP BY id_kitchen
) reqs ON reqs.id_kitchen = k.id_kitchen;
