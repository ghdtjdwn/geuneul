package com.geuneul.domain.report;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.ApplicationEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.function.Predicate;
import java.util.function.Supplier;

/** One invalidation boundary for every cache derived from mutable report rows. */
@Service
public class ReportDerivedCacheService {

    private static final Logger log = LoggerFactory.getLogger(ReportDerivedCacheService.class);
    private final ApplicationEventPublisher events;
    private final CacheManager caches;
    private final ReportDerivedCacheGenerationStore generations;

    public ReportDerivedCacheService(ApplicationEventPublisher events, CacheManager caches,
                                     ReportDerivedCacheGenerationStore generations) {
        this.events = events;
        this.caches = caches;
        this.generations = generations;
    }

    /** Must be called inside the report/flag write transaction; rollback intentionally drops the event. */
    public void evictAfterCommit(long placeId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("report cache invalidation requires an active transaction");
        }
        long newGeneration = generations.increment(placeId);
        events.publishEvent(new InvalidationRequested(placeId, newGeneration - 1));
    }

    /**
     * A loader started before a mutation may finish after AFTER_COMMIT eviction. Generation-key fencing makes that
     * delayed value unreachable, while the post-load check retries once to return/cache the committed generation.
     */
    @SuppressWarnings("unchecked")
    public <T> T cached(String cacheName, long placeId, Supplier<T> loader, Predicate<T> cacheable) {
        Cache cache = cache(cacheName);
        for (int attempt = 0; attempt < 2; attempt++) {
            long generation = generations.currentOrZero(placeId);
            String key = cacheKey(placeId, generation);
            if (cache != null) {
                try {
                    Cache.ValueWrapper hit = cache.get(key);
                    if (hit != null) {
                        T value = (T) hit.get();
                        if (generations.currentOrZero(placeId) == generation) return value;
                        continue;
                    }
                } catch (RuntimeException unavailable) {
                    log.warn("[report-cache] get bypass(name={}, errorCode={})",
                            cacheName, unavailable.getClass().getSimpleName());
                }
            }
            T value = loader.get();
            if (generations.currentOrZero(placeId) != generation) continue;
            if (cache != null && cacheable.test(value)) {
                try {
                    cache.put(key, value);
                } catch (RuntimeException unavailable) {
                    log.warn("[report-cache] put bypass(name={}, errorCode={})",
                            cacheName, unavailable.getClass().getSimpleName());
                }
            }
            return value;
        }
        // Continuous mutations are rare; preserve availability without caching when both fenced attempts raced.
        return loader.get();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCommitted(InvalidationRequested event) {
        evict("aiSummary", event.placeId(), event.previousGeneration());
        evict("popularTimes", event.placeId(), event.previousGeneration());
    }

    private void evict(String cacheName, long placeId, long generation) {
        try {
            Cache cache = cache(cacheName);
            if (cache == null) {
                return;
            }
            cache.evict(cacheKey(placeId, generation));
            cache.evict(placeId); // one-release compatibility cleanup for the former unversioned key
        } catch (RuntimeException unavailable) {
            // The incremented DB generation already makes the old key unreachable; eviction only reclaims memory.
            log.warn("[report-cache] after-commit eviction failed(name={}, errorCode={})",
                    cacheName, unavailable.getClass().getSimpleName());
        }
    }

    private Cache cache(String cacheName) {
        Cache cache = caches.getCache(cacheName);
        if (cache == null) log.warn("[report-cache] configured cache missing(name={})", cacheName);
        return cache;
    }

    static String cacheKey(long placeId, long generation) {
        return placeId + ":" + generation;
    }

    record InvalidationRequested(long placeId, long previousGeneration) {}
}
