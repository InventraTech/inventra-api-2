-- ---------------------------------------------------
-- VIEW CREATION
-- ---------------------------------------------------

CREATE OR REPLACE VIEW vw_stock_batch_detail AS
SELECT
    sb.id_batch,
    sb.id_product,
    p.name AS product_name,
    p.brand AS product_brand,
    c.name AS category_name,
    sb.id_kitchen,
    k.name AS kitchen_name,
    sb.id_supplier,
    s.legal_name AS supplier_name,
    sb.batch_number,
    sb.invoice_number,
    mu.symbol AS unit_symbol,
    sb.initial_quantity,
    sb.current_quantity,
    sb.entry_date,
    sb.expiration_date,
    (sb.expiration_date - CURRENT_DATE) AS days_until_expiration,
    CASE
        WHEN sb.expiration_date IS NULL THEN 'NOT_APPLICABLE'
        WHEN sb.expiration_date < CURRENT_DATE THEN 'EXPIRED'
        WHEN sb.expiration_date <= CURRENT_DATE + 3 THEN 'CRITICAL'
        WHEN sb.expiration_date <= CURRENT_DATE + 7 THEN 'WARNING'
        ELSE 'OK'
    END AS expiration_status,
    sb.unit_price,
    (sb.current_quantity * COALESCE(sb.unit_price, 0)) AS batch_value,
    sb.status
FROM tb_stock_batch sb
JOIN tb_product p ON p.id_product = sb.id_product
LEFT JOIN tb_category c ON c.id_category = p.id_category
JOIN tb_measurement_unit mu ON mu.id_unit = p.id_unit
JOIN tb_kitchen k ON k.id_kitchen = sb.id_kitchen
LEFT JOIN tb_supplier s ON s.id_supplier = sb.id_supplier;

CREATE OR REPLACE VIEW vw_product_stock_position AS
SELECT
    pkp.id_product,
    p.name AS product_name,
    p.brand AS product_brand,
    pkp.id_kitchen,
    k.name AS kitchen_name,
    pkp.min_stock,
    pkp.max_stock,
    pkp.average_daily_consumption,
    COALESCE(SUM(sb.current_quantity), 0) AS total_current_quantity,
    COUNT(sb.id_batch) AS batch_count,
    MIN(sb.expiration_date) AS nearest_expiration_date,
    CASE
        WHEN COALESCE(SUM(sb.current_quantity), 0) < pkp.min_stock THEN 'BELOW_MINIMUM'
        WHEN pkp.max_stock IS NOT NULL
             AND COALESCE(SUM(sb.current_quantity), 0) > pkp.max_stock THEN 'ABOVE_MAXIMUM'
        ELSE 'NORMAL'
    END AS stock_status
FROM tb_product_kitchen_parameter pkp
JOIN tb_product p ON p.id_product = pkp.id_product
JOIN tb_kitchen k ON k.id_kitchen = pkp.id_kitchen
LEFT JOIN tb_stock_batch sb
    ON sb.id_product = pkp.id_product
   AND sb.id_kitchen = pkp.id_kitchen
   AND sb.status = 'ACTIVE'
GROUP BY pkp.id_product, p.name, p.brand, pkp.id_kitchen, k.name,
         pkp.min_stock, pkp.max_stock, pkp.average_daily_consumption;

CREATE OR REPLACE VIEW vw_daily_expiration_summary AS
SELECT
    sb.id_kitchen,
    k.name AS kitchen_name,
    sb.expiration_date,
    COUNT(*) AS batch_count,
    SUM(sb.current_quantity) AS total_quantity,
    SUM(sb.current_quantity * COALESCE(sb.unit_price, 0)) AS total_value
FROM tb_stock_batch sb
JOIN tb_kitchen k ON k.id_kitchen = sb.id_kitchen
WHERE sb.status = 'ACTIVE'
  AND sb.expiration_date IS NOT NULL
  AND sb.current_quantity > 0
GROUP BY sb.id_kitchen, k.name, sb.expiration_date;

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
WHERE a.is_read = false;

CREATE OR REPLACE VIEW vw_stock_value_by_category AS
SELECT
    sb.id_kitchen,
    k.name AS kitchen_name,
    p.id_category,
    COALESCE(c.name, 'UNCATEGORIZED') AS category_name,
    COUNT(*) AS batch_count,
    SUM(sb.current_quantity) AS total_quantity,
    SUM(sb.current_quantity * COALESCE(sb.unit_price, 0)) AS total_value
FROM tb_stock_batch sb
JOIN tb_product p ON p.id_product = sb.id_product
LEFT JOIN tb_category c ON c.id_category = p.id_category
JOIN tb_kitchen k ON k.id_kitchen = sb.id_kitchen
WHERE sb.status = 'ACTIVE'
GROUP BY sb.id_kitchen, k.name, p.id_category, c.name;

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
GROUP BY r.id_requisition, r.requisition_type, r.origin, r.status, r.reason,
         r.id_kitchen, k.name, r.id_requester_user, ru.name, ru.email,
         r.id_approver_user, au.name, r.created_at, r.approved_at;

CREATE OR REPLACE VIEW vw_requisition_pending AS
SELECT *
FROM vw_requisition_summary
WHERE status = 'UNDER_REVIEW';

CREATE OR REPLACE VIEW vw_stock_movement_log AS
SELECT
    l.id_log,
    l.id_batch,
    sb.id_product,
    p.name AS product_name,
    sb.id_kitchen,
    k.name AS kitchen_name,
    l.operation,
    l.db_user,
    l.operation_date,
    (l.previous_data ->> 'current_quantity')::DECIMAL(12,3) AS previous_quantity,
    (l.new_data ->> 'current_quantity')::DECIMAL(12,3) AS new_quantity,
    CASE
        WHEN l.operation = 'UPDATE' THEN
            (l.new_data ->> 'current_quantity')::DECIMAL(12,3)
            - (l.previous_data ->> 'current_quantity')::DECIMAL(12,3)
        ELSE NULL
    END AS quantity_change,
    CASE
        WHEN l.operation = 'INSERT' THEN 'ENTRY'
        WHEN l.operation = 'UPDATE'
             AND (l.new_data ->> 'current_quantity')::DECIMAL(12,3)
                 > (l.previous_data ->> 'current_quantity')::DECIMAL(12,3) THEN 'ENTRY'
        WHEN l.operation = 'UPDATE'
             AND (l.new_data ->> 'current_quantity')::DECIMAL(12,3)
                 < (l.previous_data ->> 'current_quantity')::DECIMAL(12,3) THEN 'WITHDRAWAL'
        WHEN l.operation = 'UPDATE' THEN 'NO_CHANGE'
        ELSE 'OTHER'
    END AS movement_type
FROM tb_log_stock_batch l
LEFT JOIN tb_stock_batch sb ON sb.id_batch = l.id_batch
LEFT JOIN tb_product p ON p.id_product = sb.id_product
LEFT JOIN tb_kitchen k ON k.id_kitchen = sb.id_kitchen
WHERE l.operation IN ('INSERT', 'UPDATE');

CREATE OR REPLACE VIEW vw_inventory_count_divergence AS
SELECT
    ic.id_count,
    ic.id_inventory,
    inv.id_kitchen,
    k.name AS kitchen_name,
    inv.started_at,
    inv.closed_at,
    inv.status AS inventory_status,
    ic.id_batch,
    sb.id_product,
    p.name AS product_name,
    ic.registered_quantity,
    ic.physical_quantity,
    ic.divergence,
    CASE
        WHEN ic.registered_quantity = 0 THEN NULL
        ELSE ROUND((ic.divergence / ic.registered_quantity) * 100, 2)
    END AS divergence_pct,
    ic.note
FROM tb_inventory_count ic
JOIN tb_inventory inv ON inv.id_inventory = ic.id_inventory
JOIN tb_kitchen k ON k.id_kitchen = inv.id_kitchen
JOIN tb_stock_batch sb ON sb.id_batch = ic.id_batch
JOIN tb_product p ON p.id_product = sb.id_product;

CREATE OR REPLACE VIEW vw_kitchen_daily_stock_movement AS
WITH daily AS (
    SELECT
        sb.id_kitchen,
        (l.operation_date)::DATE AS movement_date,
        SUM(
            CASE
                WHEN l.operation = 'INSERT' THEN
                    (l.new_data ->> 'current_quantity')::DECIMAL(12,3)
                WHEN l.operation = 'UPDATE'
                     AND (l.new_data ->> 'current_quantity')::DECIMAL(12,3)
                         > (l.previous_data ->> 'current_quantity')::DECIMAL(12,3)
                THEN
                    (l.new_data ->> 'current_quantity')::DECIMAL(12,3)
                    - (l.previous_data ->> 'current_quantity')::DECIMAL(12,3)
                ELSE 0
            END
        ) AS entry_quantity,
        SUM(
            CASE
                WHEN l.operation = 'UPDATE'
                     AND (l.new_data ->> 'current_quantity')::DECIMAL(12,3)
                         < (l.previous_data ->> 'current_quantity')::DECIMAL(12,3)
                THEN
                    (l.previous_data ->> 'current_quantity')::DECIMAL(12,3)
                    - (l.new_data ->> 'current_quantity')::DECIMAL(12,3)
                ELSE 0
            END
        ) AS withdrawal_quantity
    FROM tb_log_stock_batch l
    LEFT JOIN tb_stock_batch sb ON sb.id_batch = l.id_batch
    WHERE l.operation IN ('INSERT', 'UPDATE')
    GROUP BY sb.id_kitchen, (l.operation_date)::DATE
)
SELECT
    d.id_kitchen,
    k.name AS kitchen_name,
    d.movement_date,
    d.entry_quantity,
    d.withdrawal_quantity,
    (d.entry_quantity - d.withdrawal_quantity) AS net_change,
    SUM(d.entry_quantity - d.withdrawal_quantity) OVER (
        PARTITION BY d.id_kitchen
        ORDER BY d.movement_date
    ) AS running_total_quantity
FROM daily d
LEFT JOIN tb_kitchen k ON k.id_kitchen = d.id_kitchen;

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
GROUP BY r.id_kitchen, k.name, ri.id_product, p.name;

CREATE OR REPLACE VIEW vw_product_supplier_catalog AS
SELECT
    ps.id_product,
    p.name AS product_name,
    p.brand AS product_brand,
    ps.id_supplier,
    s.legal_name AS supplier_name,
    s.rating AS supplier_rating,
    s.active AS supplier_active,
    ps.supplier_code,
    ps.reference_price,
    ps.lead_time_days,
    RANK() OVER (
        PARTITION BY ps.id_product
        ORDER BY ps.reference_price ASC NULLS LAST
    ) AS price_rank
FROM tb_product_supplier ps
JOIN tb_product p ON p.id_product = ps.id_product
JOIN tb_supplier s ON s.id_supplier = ps.id_supplier;

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
    SELECT id_kitchen, COUNT(*) AS active_alert_count
    FROM tb_alert
    WHERE is_read = false
    GROUP BY id_kitchen
) alerts ON alerts.id_kitchen = k.id_kitchen
LEFT JOIN (
    SELECT id_kitchen, COUNT(*) AS pending_requisition_count
    FROM tb_requisition
    WHERE status = 'UNDER_REVIEW'
    GROUP BY id_kitchen
) reqs ON reqs.id_kitchen = k.id_kitchen;

CREATE OR REPLACE VIEW vw_products_below_minimum AS
SELECT *
FROM vw_product_stock_position
WHERE stock_status = 'BELOW_MINIMUM';

CREATE OR REPLACE VIEW vw_batches_needing_attention AS
SELECT *
FROM vw_stock_batch_detail
WHERE expiration_status IN ('EXPIRED', 'CRITICAL')
  AND status = 'ACTIVE';

CREATE OR REPLACE VIEW vw_supplier_profile AS
SELECT
    s.id_supplier,
    s.legal_name AS supplier_name,
    s.cnpj,
    s.email,
    s.whatsapp,
    s.rating,
    s.active,
    COUNT(DISTINCT ps.id_product) AS product_count,
    ROUND(AVG(ps.reference_price), 2) AS avg_reference_price,
    ROUND(AVG(ps.lead_time_days), 1) AS avg_lead_time_days
FROM tb_supplier s
LEFT JOIN tb_product_supplier ps ON ps.id_supplier = s.id_supplier
GROUP BY s.id_supplier, s.legal_name, s.cnpj, s.email, s.whatsapp, s.rating, s.active;

CREATE OR REPLACE VIEW vw_monthly_waste_proxy_kpi AS
WITH movement AS (
    SELECT
        sb.id_kitchen,
        DATE_TRUNC('month', l.operation_date)::DATE AS reference_month,
        CASE
            WHEN l.operation = 'INSERT' THEN
                (l.new_data ->> 'current_quantity')::DECIMAL(12,3)
            WHEN l.operation = 'UPDATE'
                 AND (l.new_data ->> 'current_quantity')::DECIMAL(12,3)
                     > (l.previous_data ->> 'current_quantity')::DECIMAL(12,3)
            THEN
                (l.new_data ->> 'current_quantity')::DECIMAL(12,3)
                - (l.previous_data ->> 'current_quantity')::DECIMAL(12,3)
            ELSE 0
        END AS entry_quantity,
        CASE
            WHEN l.operation = 'UPDATE'
                 AND (l.new_data ->> 'current_quantity')::DECIMAL(12,3)
                     < (l.previous_data ->> 'current_quantity')::DECIMAL(12,3)
            THEN
                (l.previous_data ->> 'current_quantity')::DECIMAL(12,3)
                - (l.new_data ->> 'current_quantity')::DECIMAL(12,3)
            ELSE 0
        END AS withdrawal_quantity,
        CASE
            WHEN l.operation = 'UPDATE'
                 AND (l.new_data ->> 'current_quantity')::DECIMAL(12,3)
                     < (l.previous_data ->> 'current_quantity')::DECIMAL(12,3)
                 AND (l.new_data ->> 'expiration_date') IS NOT NULL
                 AND (l.new_data ->> 'expiration_date')::DATE < l.operation_date::DATE
            THEN
                (l.previous_data ->> 'current_quantity')::DECIMAL(12,3)
                - (l.new_data ->> 'current_quantity')::DECIMAL(12,3)
            ELSE 0
        END AS withdrawal_after_expiration_quantity
    FROM tb_log_stock_batch l
    LEFT JOIN tb_stock_batch sb ON sb.id_batch = l.id_batch
    WHERE l.operation IN ('INSERT', 'UPDATE')
)
SELECT
    m.id_kitchen,
    k.name AS kitchen_name,
    m.reference_month,
    SUM(m.entry_quantity) AS total_entry_quantity,
    SUM(m.withdrawal_quantity) AS total_withdrawal_quantity,
    SUM(m.withdrawal_after_expiration_quantity) AS waste_proxy_quantity,
    CASE
        WHEN SUM(m.entry_quantity) = 0 THEN NULL
        ELSE ROUND(
            SUM(m.withdrawal_after_expiration_quantity) / SUM(m.entry_quantity) * 100,
            2
        )
    END AS waste_proxy_rate_pct
FROM movement m
LEFT JOIN tb_kitchen k ON k.id_kitchen = m.id_kitchen
GROUP BY m.id_kitchen, k.name, m.reference_month;

-- ---------------------------------------------------
-- DATA MART VIEWS — STAR SCHEMA
-- ---------------------------------------------------

CREATE OR REPLACE VIEW dim_product AS
SELECT
    p.id_product,
    p.name AS product_name,
    p.brand AS product_brand,
    COALESCE(c.name, 'UNCATEGORIZED') AS category_name,
    mu.symbol AS unit_symbol,
    mu.description AS unit_description,
    p.barcode,
    p.active AS product_active
FROM tb_product p
LEFT JOIN tb_category c ON c.id_category = p.id_category
JOIN tb_measurement_unit mu ON mu.id_unit = p.id_unit;

CREATE OR REPLACE VIEW dim_kitchen AS
SELECT
    k.id_kitchen,
    k.name AS kitchen_name,
    k.code AS kitchen_code,
    k.address AS kitchen_address,
    k.active AS kitchen_active
FROM tb_kitchen k;

CREATE OR REPLACE VIEW dim_supplier AS
SELECT
    s.id_supplier,
    s.legal_name AS supplier_name,
    s.cnpj,
    s.rating,
    s.active AS supplier_active
FROM tb_supplier s;

CREATE OR REPLACE VIEW dim_date AS
WITH calendar AS (
    SELECT generate_series(
        DATE_TRUNC('year', CURRENT_DATE - INTERVAL '10 years'),
        DATE_TRUNC('year', CURRENT_DATE + INTERVAL '10 years') + INTERVAL '1 year' - INTERVAL '1 day',
        INTERVAL '1 day'
    )::DATE AS date_key
)
SELECT
    date_key,
    EXTRACT(YEAR FROM date_key)::INT AS year_number,
    EXTRACT(MONTH FROM date_key)::INT AS month_number,
    (ARRAY['January','February','March','April','May','June','July',
           'August','September','October','November','December']
    )[EXTRACT(MONTH FROM date_key)::INT] AS month_name,
    EXTRACT(DAY FROM date_key)::INT AS day_number,
    EXTRACT(QUARTER FROM date_key)::INT AS quarter_number,
    EXTRACT(ISODOW FROM date_key)::INT AS day_of_week_number,
    (ARRAY['Monday','Tuesday','Wednesday','Thursday','Friday','Saturday','Sunday']
    )[EXTRACT(ISODOW FROM date_key)::INT] AS day_of_week_name,
    (EXTRACT(ISODOW FROM date_key) IN (6, 7)) AS is_weekend
FROM calendar;

CREATE OR REPLACE VIEW fact_stock_movement AS
SELECT
    l.id_log,
    sb.id_product,
    sb.id_kitchen,
    sb.id_supplier,
    (l.operation_date)::DATE AS date_key,
    l.operation_date,
    CASE
        WHEN l.operation = 'INSERT' THEN 'ENTRY'
        WHEN l.operation = 'UPDATE'
             AND (l.new_data ->> 'current_quantity')::DECIMAL(12,3)
                 > (l.previous_data ->> 'current_quantity')::DECIMAL(12,3) THEN 'ENTRY'
        WHEN l.operation = 'UPDATE'
             AND (l.new_data ->> 'current_quantity')::DECIMAL(12,3)
                 < (l.previous_data ->> 'current_quantity')::DECIMAL(12,3) THEN 'WITHDRAWAL'
        ELSE 'NO_CHANGE'
    END AS movement_type,
    ABS(
        COALESCE((l.new_data ->> 'current_quantity')::DECIMAL(12,3), 0)
        - COALESCE((l.previous_data ->> 'current_quantity')::DECIMAL(12,3), 0)
    ) AS quantity_change,
    ABS(
        COALESCE((l.new_data ->> 'current_quantity')::DECIMAL(12,3), 0)
        - COALESCE((l.previous_data ->> 'current_quantity')::DECIMAL(12,3), 0)
    ) * COALESCE(sb.unit_price, 0) AS movement_value
FROM tb_log_stock_batch l
LEFT JOIN tb_stock_batch sb ON sb.id_batch = l.id_batch
WHERE l.operation IN ('INSERT', 'UPDATE');

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
JOIN tb_requisition r ON r.id_requisition = ri.id_requisition;

CREATE OR REPLACE VIEW fact_inventory_count AS
SELECT
    ic.id_count,
    ic.id_inventory,
    inv.id_kitchen,
    sb.id_product,
    (inv.started_at)::DATE AS date_key,
    inv.started_at,
    inv.closed_at,
    ic.registered_quantity,
    ic.physical_quantity,
    ic.divergence
FROM tb_inventory_count ic
JOIN tb_inventory inv ON inv.id_inventory = ic.id_inventory
JOIN tb_stock_batch sb ON sb.id_batch = ic.id_batch;
