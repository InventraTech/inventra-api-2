-- ---------------------------------------------------
-- USER: GOOGLE LOGIN (FIREBASE)
-- ---------------------------------------------------

-- conta criada pelo Google não tem senha
ALTER TABLE tb_user
ALTER COLUMN password_hash DROP NOT NULL;

ALTER TABLE tb_user
ADD COLUMN IF NOT EXISTS firebase_uid VARCHAR(128);

DO $$
BEGIN
	IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uq_user_firebase_uid') THEN
		ALTER TABLE tb_user
		ADD CONSTRAINT uq_user_firebase_uid
		UNIQUE (firebase_uid);
	END IF;
END;
$$;

-- ---------------------------------------------------
-- KITCHEN: LOCATION AND ACCESS REQUESTS
-- ---------------------------------------------------

ALTER TABLE tb_kitchen
ADD COLUMN IF NOT EXISTS city VARCHAR(120);

ALTER TABLE tb_kitchen
ADD COLUMN IF NOT EXISTS state CHAR(2);

ALTER TABLE tb_kitchen
ADD COLUMN IF NOT EXISTS latitude DOUBLE PRECISION;

ALTER TABLE tb_kitchen
ADD COLUMN IF NOT EXISTS longitude DOUBLE PRECISION;

ALTER TABLE tb_kitchen
ADD COLUMN IF NOT EXISTS accepting_requests BOOLEAN NOT NULL DEFAULT true;

DO $$
BEGIN
	IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_kitchen_state') THEN
		ALTER TABLE tb_kitchen
		ADD CONSTRAINT ck_kitchen_state
		CHECK (state ~ '^[A-Z]{2}$');
	END IF;
END;
$$;

DO $$
BEGIN
	IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_kitchen_latitude') THEN
		ALTER TABLE tb_kitchen
		ADD CONSTRAINT ck_kitchen_latitude
		CHECK (latitude BETWEEN -90 AND 90);
	END IF;
END;
$$;

DO $$
BEGIN
	IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_kitchen_longitude') THEN
		ALTER TABLE tb_kitchen
		ADD CONSTRAINT ck_kitchen_longitude
		CHECK (longitude BETWEEN -180 AND 180);
	END IF;
END;
$$;

-- pré-filtro por caixa (bounding box) da busca por distância
CREATE INDEX IF NOT EXISTS idx_kitchen_location
ON tb_kitchen (latitude, longitude);
