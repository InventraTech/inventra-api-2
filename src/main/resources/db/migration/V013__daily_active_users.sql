-- ---------------------------------------------------
-- DAILY ACTIVE USERS (DAU)
-- ---------------------------------------------------
-- Registro automático de acessos no banco: a cada login a API grava tb_user.last_login
-- e a trigger trg_register_daily_access soma o acesso do dia em tb_user_daily_access.
-- vw_dau e vw_dau_by_kitchen entregam o DAU pronto para o BI. Detalhes em docs/MONITORING.md.
-- A API não muda para o registro funcionar; para contar quem só renova a sessão
-- (refresh token), ela precisa atualizar last_login também no refresh.

-- ---------------------------------------------------
-- TABLE CREATION
-- ---------------------------------------------------

-- Uma linha por usuário por dia de uso: o DAU de um dia é o COUNT(*) do dia.
-- Gravada só pela trigger trg_register_daily_access (tb_user.last_login).
-- A data fica só em access_date; primeiro e último acesso guardam só a hora
-- (com TIMESTAMP, a data se repetiria e a tabela sairia da FNBC).
CREATE TABLE IF NOT EXISTS tb_user_daily_access (
	id_user UUID NOT NULL,
	access_date DATE NOT NULL,
	first_access_time TIME NOT NULL,
	last_access_time TIME NOT NULL,
	access_count INTEGER NOT NULL DEFAULT 1,
	PRIMARY KEY (id_user, access_date)
);

-- ---------------------------------------------------
-- FOREIGN KEYS
-- ---------------------------------------------------

-- apagar o usuário apaga o histórico de acesso dele (dado pessoal, LGPD)
DO $$
BEGIN
	IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_useraccess_user') THEN
		ALTER TABLE tb_user_daily_access
		ADD CONSTRAINT fk_useraccess_user
		FOREIGN KEY (id_user) REFERENCES tb_user (id_user)
		ON UPDATE NO ACTION ON DELETE CASCADE;
	END IF;
END;
$$;

-- ---------------------------------------------------
-- CHECK CONSTRAINTS
-- ---------------------------------------------------

DO $$
BEGIN
	IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_useraccess_count') THEN
		ALTER TABLE tb_user_daily_access
		ADD CONSTRAINT ck_useraccess_count
		CHECK (access_count > 0);
	END IF;
END;
$$;

-- o primeiro acesso do dia nunca é depois do último
DO $$
BEGIN
	IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_useraccess_period') THEN
		ALTER TABLE tb_user_daily_access
		ADD CONSTRAINT ck_useraccess_period
		CHECK (last_access_time >= first_access_time);
	END IF;
END;
$$;

-- ---------------------------------------------------
-- INDEX CREATION
-- ---------------------------------------------------

-- a PK começa por id_user; as views de DAU agrupam e filtram por dia
CREATE INDEX IF NOT EXISTS idx_useraccess_date
ON tb_user_daily_access (access_date);

-- ---------------------------------------------------
-- FUNCTION CREATION
-- ---------------------------------------------------

-- Registra o acesso do dia a cada login: a API grava tb_user.last_login e a trigger
-- trg_register_daily_access chama esta function. Uma linha por usuário por dia;
-- novo acesso no mesmo dia só soma em access_count.
-- last_login apagado ou voltando no tempo (ex.: sp_restore_from_audit) não é login: ignora.
CREATE OR REPLACE FUNCTION fn_register_daily_access()
RETURNS TRIGGER
LANGUAGE plpgsql
AS
$$
BEGIN

    IF NEW.last_login IS NULL
       OR (OLD.last_login IS NOT NULL AND NEW.last_login <= OLD.last_login) THEN
        RETURN NEW;
    END IF;

    INSERT INTO tb_user_daily_access
    (
        id_user,
        access_date,
        first_access_time,
        last_access_time,
        access_count
    )
    VALUES
    (
        NEW.id_user,
        NEW.last_login::date,
        NEW.last_login::time,
        NEW.last_login::time,
        1
    )
    ON CONFLICT (id_user, access_date) DO UPDATE
    SET first_access_time = LEAST(tb_user_daily_access.first_access_time, EXCLUDED.first_access_time),
        last_access_time = GREATEST(tb_user_daily_access.last_access_time, EXCLUDED.last_access_time),
        access_count = tb_user_daily_access.access_count + 1;

    RETURN NEW;

END;
$$;

-- ---------------------------------------------------
-- TRIGGER CREATION
-- ---------------------------------------------------

-- só quando last_login muda: editar nome, perfil ou cozinha não conta como acesso
DROP TRIGGER IF EXISTS trg_register_daily_access
ON tb_user;

CREATE TRIGGER trg_register_daily_access
AFTER UPDATE OF last_login
ON tb_user
FOR EACH ROW
WHEN (NEW.last_login IS DISTINCT FROM OLD.last_login)
EXECUTE FUNCTION fn_register_daily_access();

-- ---------------------------------------------------
-- VIEWS
-- ---------------------------------------------------

-- DAU (Daily Active Users): usuários distintos por dia, do primeiro dia registrado até hoje.
-- Dia sem acesso aparece com 0, para a média móvel e a variação não pularem dias.
-- MAU = usuários distintos nos 30 dias que terminam no dia; stickiness = DAU / MAU.
-- O MAU sai numa passada só pela tabela: cada acesso "cobre" o usuário do dia dele até
-- o próximo acesso (LEAD) ou por 30 dias, o que vier antes. As coberturas de um usuário
-- não se sobrepõem, então somar +1 no início e -1 no fim e acumular por dia
-- (SUM() OVER) dá exatamente o COUNT(DISTINCT) da janela de 30 dias.
CREATE OR REPLACE VIEW vw_dau AS
WITH bounds AS (
    SELECT
        MIN(access_date) AS first_day,
        GREATEST(MAX(access_date), CURRENT_DATE) AS last_day
    FROM tb_user_daily_access
),
days AS (
    SELECT generate_series(b.first_day, b.last_day, INTERVAL '1 day')::date AS access_date
    FROM bounds b
    WHERE b.first_day IS NOT NULL
),
daily AS (
    SELECT
        access_date,
        COUNT(*) AS dau,
        SUM(access_count) AS total_accesses
    FROM tb_user_daily_access
    GROUP BY access_date
),
mau_coverage AS (
    SELECT
        access_date AS covered_from,
        LEAST(
            LEAD(access_date) OVER (PARTITION BY id_user ORDER BY access_date),
            access_date + 30
        ) AS covered_until
    FROM tb_user_daily_access
),
mau_delta AS (
    SELECT
        change_date,
        SUM(delta) AS delta
    FROM (
        SELECT covered_from AS change_date, 1 AS delta FROM mau_coverage
        UNION ALL
        SELECT covered_until, -1 FROM mau_coverage
    ) AS changes
    GROUP BY change_date
),
series AS (
    SELECT
        d.access_date,
        COALESCE(dl.dau, 0) AS dau,
        COALESCE(dl.total_accesses, 0) AS total_accesses,
        SUM(COALESCE(md.delta, 0)) OVER (ORDER BY d.access_date)::BIGINT AS mau_30d
    FROM days d
    LEFT JOIN daily dl ON dl.access_date = d.access_date
    LEFT JOIN mau_delta md ON md.change_date = d.access_date
)
SELECT
    s.access_date,
    EXTRACT(ISODOW FROM s.access_date)::INTEGER AS iso_weekday,
    s.dau,
    s.total_accesses,
    s.dau - LAG(s.dau) OVER (ORDER BY s.access_date) AS dau_change_vs_previous_day,
    ROUND(AVG(s.dau) OVER (ORDER BY s.access_date ROWS BETWEEN 6 PRECEDING AND CURRENT ROW), 1) AS dau_moving_avg_7d,
    s.mau_30d,
    ROUND(100.0 * s.dau / NULLIF(s.mau_30d, 0), 1) AS stickiness_pct
FROM series s;

-- DAU por cozinha, com adoção (DAU / usuários ativos da cozinha) e ranking do dia.
-- A cozinha é a atual do usuário; usuário sem cozinha entra só em vw_dau.
CREATE OR REPLACE VIEW vw_dau_by_kitchen AS
WITH kitchen_users AS (
    SELECT
        id_kitchen,
        COUNT(*) FILTER (WHERE active) AS active_users
    FROM tb_user
    WHERE id_kitchen IS NOT NULL
    GROUP BY id_kitchen
),
daily AS (
    SELECT
        a.access_date,
        u.id_kitchen,
        COUNT(*) AS dau,
        SUM(a.access_count) AS total_accesses
    FROM tb_user_daily_access a
    JOIN tb_user u ON u.id_user = a.id_user
    WHERE u.id_kitchen IS NOT NULL
    GROUP BY a.access_date, u.id_kitchen
)
SELECT
    d.access_date,
    d.id_kitchen,
    k.name AS kitchen_name,
    d.dau,
    d.total_accesses,
    ku.active_users,
    ROUND(100.0 * d.dau / NULLIF(ku.active_users, 0), 1) AS adoption_pct,
    RANK() OVER (PARTITION BY d.access_date ORDER BY d.dau DESC) AS kitchen_rank_in_day
FROM daily d
JOIN tb_kitchen k ON k.id_kitchen = d.id_kitchen
LEFT JOIN kitchen_users ku ON ku.id_kitchen = d.id_kitchen;

-- ---------------------------------------------------
-- DATA CATALOG
-- ---------------------------------------------------

INSERT INTO tb_catalog_table (table_name, domain, description, business_rules, id_access_level, data_owner)
SELECT
	'tb_user_daily_access', 'EVENT',
	'Acessos de cada usuário por dia, base do DAU (Daily Active Users).',
	'Uma linha por usuário por dia (PK composta). Gravada só pela trigger trg_register_daily_access quando a API atualiza tb_user.last_login; novo acesso no mesmo dia soma em access_count. Consultada por vw_dau e vw_dau_by_kitchen.',
	al.id_access_level, 'Administração do sistema'
FROM tb_catalog_access_level al
WHERE al.code = 'RESTRICTED'
ON CONFLICT (table_name) DO UPDATE
SET domain = EXCLUDED.domain,
    description = EXCLUDED.description,
    business_rules = EXCLUDED.business_rules,
    id_access_level = EXCLUDED.id_access_level,
    data_owner = EXCLUDED.data_owner,
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO tb_catalog_column (id_catalog_table, column_name, description, business_rule, id_access_level, is_personal_data)
SELECT t.id_catalog_table, v.column_name, v.description, v.business_rule, NULL, v.is_personal_data
FROM (VALUES
	('id_user', 'Usuário que acessou.', 'Parte da PK composta; cascata ao apagar o usuário.', false),
	('access_date', 'Dia do acesso.', 'Parte da PK composta.', false),
	('first_access_time', 'Hora do primeiro acesso do usuário no dia.', 'Nunca depois do último (ck_useraccess_period).', true),
	('last_access_time', 'Hora do último acesso do usuário no dia.', 'Hora do last_login mais recente do dia.', true),
	('access_count', 'Quantos logins o usuário fez no dia.', 'Maior que zero (ck_useraccess_count).', false)
) AS v(column_name, description, business_rule, is_personal_data)
JOIN tb_catalog_table t ON t.table_name = 'tb_user_daily_access'
ON CONFLICT (id_catalog_table, column_name) DO UPDATE
SET description = EXCLUDED.description,
    business_rule = EXCLUDED.business_rule,
    id_access_level = EXCLUDED.id_access_level,
    is_personal_data = EXCLUDED.is_personal_data,
    updated_at = CURRENT_TIMESTAMP;

UPDATE tb_catalog_column cc
SET business_rule = 'Gravada pela API a cada login; cada avanço registra o acesso do dia em tb_user_daily_access (trg_register_daily_access).',
    updated_at = CURRENT_TIMESTAMP
FROM tb_catalog_table t
WHERE t.id_catalog_table = cc.id_catalog_table
  AND t.table_name = 'tb_user'
  AND cc.column_name = 'last_login';

CALL sp_sync_data_catalog();
