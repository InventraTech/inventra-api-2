-- ---------------------------------------------------
-- USER: PROFILE PHOTO
-- ---------------------------------------------------
-- Foto de perfil do usuário, no mesmo padrão de tb_product.photo_url: o arquivo fica no Cloudinary
-- (public_id inventra/users/{id_user}) e o banco guarda só a URL. Nula = sem foto (o app mostra as
-- iniciais). Na criação da conta pelo login Google, a API pode preencher com a foto do Google.

ALTER TABLE tb_user
ADD COLUMN IF NOT EXISTS photo_url VARCHAR(255);

-- ---------------------------------------------------
-- DATA CATALOG
-- ---------------------------------------------------

INSERT INTO tb_catalog_column (id_catalog_table, column_name, description, business_rule, id_access_level, is_personal_data)
SELECT t.id_catalog_table, 'photo_url', 'URL da foto de perfil do usuário.',
	'Nula quando o usuário não tem foto. secure_url do Cloudinary com a versão (public_id inventra/users/{id_user}), trocada a cada envio, ou a foto do Google gravada na criação da conta.',
	NULL, true
FROM tb_catalog_table t
WHERE t.table_name = 'tb_user'
ON CONFLICT (id_catalog_table, column_name) DO UPDATE
SET description = EXCLUDED.description,
    business_rule = EXCLUDED.business_rule,
    id_access_level = EXCLUDED.id_access_level,
    is_personal_data = EXCLUDED.is_personal_data,
    updated_at = CURRENT_TIMESTAMP;

CALL sp_sync_data_catalog();
