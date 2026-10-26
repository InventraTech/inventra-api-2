package com.inventra.api.infrastructure.repository;

import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.inventra.api.core.service.uiclick.UiClickPlatform;
import com.inventra.api.core.service.uiclick.UiClickRanking;

import lombok.RequiredArgsConstructor;

// Contador de cliques criado em V014__ui_click_counter.sql: uma linha só (id_counter = 1) e uma coluna
// BIGINT por elemento clicável. Sem entidade JPA (seriam 300 campos): acesso direto por JDBC.
@Repository
@RequiredArgsConstructor
public class UiClickCounterRepository {

    private static final RowMapper<UiClickRanking> RANKING_MAPPER = (rs, rowNum) -> new UiClickRanking(
            rs.getString("column_name"),
            UiClickPlatform.valueOf(rs.getString("platform")),
            rs.getString("type_code"),
            rs.getString("description"),
            rs.getLong("clicks"),
            rs.getBigDecimal("share_pct"),
            rs.getLong("rank_in_platform"));

    private final JdbcTemplate jdbc;

    // Colunas contadoras que existem hoje no banco: todas menos a chave id_counter.
    public Set<String> findCounterColumns() {
        return Set.copyOf(jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = current_schema()
                  AND table_name = 'tb_ui_click_counter'
                  AND column_name <> 'id_counter'
                """, String.class));
    }

    // Soma os cliques de todas as colunas num UPDATE só (um lock na linha por lote, não por clique).
    // Os nomes de coluna entram no SQL, então quem chama tem que passar só nomes de findCounterColumns().
    public void increment(SortedMap<String, Long> clicksByColumn) {
        String assignments = clicksByColumn.keySet().stream()
                .map(column -> column + " = " + column + " + ?")
                .collect(Collectors.joining(", "));
        jdbc.update("UPDATE tb_ui_click_counter SET " + assignments + " WHERE id_counter = 1",
                clicksByColumn.values().toArray());
    }

    // View criada em V014: uma linha por elemento, com ranking dentro da plataforma.
    public List<UiClickRanking> findRanking(UiClickPlatform platform) {
        String select = "SELECT column_name, platform, type_code, description, clicks, share_pct, rank_in_platform "
                + "FROM vw_ui_click_ranking ";
        String order = "ORDER BY platform, rank_in_platform, column_name";
        if (platform == null) {
            return jdbc.query(select + order, RANKING_MAPPER);
        }
        return jdbc.query(select + "WHERE platform = ? " + order, RANKING_MAPPER, platform.name());
    }
}
