-- Garante que as procedures de movimentacao possam omitir created_at.
-- A tabela pode existir antes da V007, portanto o CREATE TABLE IF NOT EXISTS
-- dessa migracao nao aplica o DEFAULT em bancos que ja possuem a coluna.
ALTER TABLE tb_stock_movement
    ALTER COLUMN created_at SET DEFAULT CURRENT_TIMESTAMP;
