package com.geuneul.domain.report;

import com.geuneul.global.web.RedisFixedWindowRateLimiter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 익명 제보 남용 방어 — 클라이언트(IP)별 고정창(fixed-window) 2단 리밋.
 * 분당 {@value #PER_MINUTE}(버스트 억제) + 시간당 {@value #PER_HOUR}(지속 남용 억제).
 *
 * <p>판정+증가 전체를 한 클라이언트 엔트리의 단일 {@code compute()} 람다 안에서 수행한다 →
 * ConcurrentHashMap이 키별 상호배제를 보장하므로 별도 락/AtomicInteger 없이 원자적이다
 * (분·시간 카운트를 각각 다른 맵에서 check-then-act 하던 이전 구조의 TOCTOU를 제거).
 *
 * <p>정상 경로는 Redis Lua 공유 카운터라 ECS scale-out에서도 한도가 유지된다. Redis 장애 시에는
 * 이 bounded in-memory 구현으로 폴백해 가용성을 보존하며, 폴백 동안에는 인스턴스별 한도라는 경계를 감수한다.
 */
@Component
public class ReportRateLimiter {

    static final int PER_MINUTE = 3;
    static final int PER_HOUR = 10;
    /** 맵 폭주 방지 상한. 초과 시 만료 창 정리 → 그래도 넘으면 전체 비움(OOM 방지). */
    static final int MAX_TRACKED_CLIENTS = 10_000;

    private final Clock clock;
    private final Map<String, ClientWindow> windows = new ConcurrentHashMap<>();
    private RedisFixedWindowRateLimiter distributed;

    public ReportRateLimiter(Clock clock) {
        this.clock = clock;
    }

    @Autowired(required = false)
    void setDistributed(RedisFixedWindowRateLimiter distributed) {
        this.distributed = distributed;
    }

    /** 허용되면 true(카운트 소모), 초과면 false. 분·시간 창 모두 여유가 있어야 허용된다. */
    public boolean tryAcquire(String clientKey) {
        if (distributed != null) {
            var shared = distributed.tryAcquire("reports", clientKey, PER_MINUTE, PER_HOUR);
            if (shared.isPresent()) return shared.get();
        }
        long epochSecond = clock.instant().getEpochSecond();
        long minuteBucket = epochSecond / 60;
        long hourBucket = epochSecond / 3_600;

        evictIfOversized(minuteBucket);

        boolean[] allowed = {false};
        windows.compute(clientKey, (key, existing) -> {
            ClientWindow w = (existing == null) ? new ClientWindow() : existing;
            w.rollIfNeeded(minuteBucket, hourBucket);
            if (w.minuteCount < PER_MINUTE && w.hourCount < PER_HOUR) {
                w.minuteCount++;
                w.hourCount++;
                allowed[0] = true;
            }
            return w;
        });
        return allowed[0];
    }

    /** 상한 초과 시: 이번 분에 활동 없는 창 정리 → 그래도 넘으면(동일 분 고유키 폭주 = 위조 XFF 회전) 전체 비움. */
    private void evictIfOversized(long currentMinuteBucket) {
        if (windows.size() <= MAX_TRACKED_CLIENTS) {
            return;
        }
        windows.values().removeIf(w -> w.minuteBucket != currentMinuteBucket);
        if (windows.size() > MAX_TRACKED_CLIENTS) {
            windows.clear();
        }
    }

    /** 테스트용 — 추적 중인 클라이언트 창 수(맵 상한 검증). */
    int trackedWindows() {
        return windows.size();
    }

    /** 한 클라이언트의 분·시간 고정창. 변경은 compute() 람다 내부(키별 배타)에서만 일어나므로 필드는 plain. */
    private static final class ClientWindow {
        private long minuteBucket = Long.MIN_VALUE;
        private long hourBucket = Long.MIN_VALUE;
        private int minuteCount;
        private int hourCount;

        void rollIfNeeded(long minuteBucket, long hourBucket) {
            if (this.minuteBucket != minuteBucket) {
                this.minuteBucket = minuteBucket;
                this.minuteCount = 0;
            }
            if (this.hourBucket != hourBucket) {
                this.hourBucket = hourBucket;
                this.hourCount = 0;
            }
        }
    }
}
