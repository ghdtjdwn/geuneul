package com.geuneul;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;

class FlywayMigrationChecksumTest {

    @Test
    void appliedVersionedMigrationsRemainImmutable() throws IOException {
        Map<String, Integer> productionChecksums = Map.ofEntries(
                Map.entry("V1__enable_postgis.sql", 391800219),
                Map.entry("V2__create_core_tables.sql", -1257003608),
                Map.entry("V3__geography_functional_index.sql", 839718942),
                Map.entry("V4__place_report_signals_view.sql", -740269826),
                Map.entry("V5__place_commercial_softdelete.sql", 1996058876),
                Map.entry("V6__place_report_signals_trust_weight.sql", 1565383154),
                Map.entry("V7__flags.sql", 1364284529),
                Map.entry("V8__reports_expires_index.sql", -400055358),
                Map.entry("V9__report_surge_notify_trigger.sql", 1327241324),
                Map.entry("V10__reports_verified_visit.sql", -353993788),
                Map.entry("V11__review_comments_reactions.sql", -830738828),
                Map.entry("V12__moderation_hidden.sql", -920268146),
                Map.entry("V13__place_feature_signals_view.sql", -834343775),
                Map.entry("V14__bookmarks.sql", 976053986),
                Map.entry("V15__notifications.sql", 378210843),
                Map.entry("V16__push_subscriptions.sql", 1940459801),
                Map.entry("V17__follows.sql", 319618315),
                Map.entry("V18__me_activity_indexes.sql", 1357550114),
                Map.entry("V19__integrity_indexes.sql", -247915055),
                Map.entry("V20__ingest_operational_ledger.sql", -1111508383),
                Map.entry("V21__security_session_and_photo_upload_claims.sql", -1887863230)
        );

        productionChecksums.forEach((filename, expected) ->
                assertThat(calculateFlywayChecksum(filename))
                        .as("production Flyway checksum for %s", filename)
                        .isEqualTo(expected)
        );
    }

    private int calculateFlywayChecksum(String filename) {
        CRC32 checksum = new CRC32();
        String resource = "/db/migration/" + filename;
        try (var input = getClass().getResourceAsStream(resource)) {
            assertThat(input).as("migration resource %s", resource).isNotNull();
            try (var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    checksum.update(line.getBytes(StandardCharsets.UTF_8));
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to calculate checksum for " + resource, exception);
        }
        return (int) checksum.getValue();
    }
}
