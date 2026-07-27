package com.geuneul.domain.report;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Database generation is committed atomically with report mutations and fences delayed cache writers. */
@Repository
public class ReportDerivedCacheGenerationStore {

    private final JdbcTemplate jdbc;

    public ReportDerivedCacheGenerationStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long currentOrZero(long placeId) {
        Long generation = jdbc.queryForObject("""
                SELECT COALESCE((SELECT generation FROM report_cache_generations WHERE place_id = ?), 0)
                """, Long.class, placeId);
        return generation == null ? 0L : generation;
    }

    public long increment(long placeId) {
        Long generation = jdbc.queryForObject("""
                INSERT INTO report_cache_generations(place_id, generation)
                VALUES (?, 1)
                ON CONFLICT (place_id) DO UPDATE
                SET generation = report_cache_generations.generation + 1
                RETURNING generation
                """, Long.class, placeId);
        if (generation == null) throw new IllegalStateException("report cache generation increment returned no row");
        return generation;
    }
}
