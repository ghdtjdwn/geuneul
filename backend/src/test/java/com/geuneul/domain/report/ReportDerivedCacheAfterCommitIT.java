package com.geuneul.domain.report;

import com.geuneul.AbstractIntegrationTest;
import com.geuneul.domain.place.Place;
import com.geuneul.domain.place.PlaceCategory;
import com.geuneul.domain.place.PlaceRepository;
import com.geuneul.global.geo.GeoUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class ReportDerivedCacheAfterCommitIT extends AbstractIntegrationTest {

    @Autowired ReportDerivedCacheService service;
    @Autowired ReportDerivedCacheGenerationStore generations;
    @Autowired PlaceRepository places;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean CacheManager cacheManager;

    private Cache aiSummary;
    private Cache popularTimes;
    private long placeId;

    @BeforeEach
    void configureCaches() {
        aiSummary = new ConcurrentMapCache("aiSummary");
        popularTimes = new ConcurrentMapCache("popularTimes");
        when(cacheManager.getCache("aiSummary")).thenReturn(aiSummary);
        when(cacheManager.getCache("popularTimes")).thenReturn(popularTimes);
        placeId = places.save(Place.of("cache-fence-" + System.nanoTime(), PlaceCategory.COOLING_SHELTER, null,
                GeoUtils.point(37.5, 127.0), "test", "cache-fence-" + System.nanoTime())).getId();
    }

    @Test
    @DisplayName("commit→evict 후 끝난 stale reader는 예전 generation에도 put하지 않고 최신 generation을 재조회한다")
    void committedGenerationFencesLateStaleReader() throws Exception {
        CountDownLatch staleLoadEntered = new CountDownLatch(1);
        CountDownLatch releaseStaleLoad = new CountDownLatch(1);
        AtomicInteger loads = new AtomicInteger();

        try (var executor = Executors.newSingleThreadExecutor()) {
            var read = executor.submit(() -> service.cached("aiSummary", placeId, () -> {
                if (loads.incrementAndGet() == 1) {
                    staleLoadEntered.countDown();
                    try {
                        if (!releaseStaleLoad.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("test timeout");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                    return "stale";
                }
                return "fresh";
            }, value -> true));

            assertThat(staleLoadEntered.await(5, TimeUnit.SECONDS)).isTrue();
            new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                    service.evictAfterCommit(placeId));
            releaseStaleLoad.countDown();

            assertThat(read.get(5, TimeUnit.SECONDS)).isEqualTo("fresh");
        } finally {
            releaseStaleLoad.countDown();
        }

        assertThat(loads).hasValue(2);
        assertThat(generations.currentOrZero(placeId)).isEqualTo(1);
        assertThat(aiSummary.get(placeId + ":0")).isNull();
        assertThat(aiSummary.get(placeId + ":1", String.class)).isEqualTo("fresh");
    }

    @Test
    @DisplayName("write transaction rollback은 generation과 기존 파생 캐시를 모두 그대로 둔다")
    void rollbackDoesNotAdvanceGenerationOrEvictCache() {
        aiSummary.put(placeId + ":0", "current");
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);

        transactions.executeWithoutResult(status -> {
            service.evictAfterCommit(placeId);
            status.setRollbackOnly();
        });

        assertThat(generations.currentOrZero(placeId)).isZero();
        assertThat(aiSummary.get(placeId + ":0", String.class)).isEqualTo("current");
    }
}
