package com.geuneul;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 통합테스트 공통 베이스 — 실 PostGIS로 검증(H2가 놓치는 공간 dialect·타입 drift를 잡는다).
 * (Redis는 메인 코드에서 아직 미사용 — P3 캐시 도입 시 여기에 컨테이너를 다시 추가한다.)
 *
 * ⚠️ 싱글턴 컨테이너 패턴 — @Container를 일부러 쓰지 않는다 (TS-002).
 * @Container(static)는 "각 테스트 클래스 종료 시 컨테이너 중지"인데, Spring TestContext는
 * 컨텍스트를 JVM 전체에 캐싱한다. 그 결과 두 번째 IT 클래스부터 캐시된 컨텍스트가
 * 죽은(또는 재시작되어 포트가 바뀐) 컨테이너로 접속 → ConnectException.
 * 수동 start() 싱글턴은 JVM 수명과 함께 살고, 종료 시 Testcontainers Ryuk이 정리한다.
 *
 * Docker 데몬이 없으면 @Testcontainers(disabledWithoutDocker)로 자동 skip —
 * static 블록도 Docker 가용 시에만 start하므로 오프라인 로컬 빌드가 깨지지 않는다.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
// 실시간 급증 리스너(ADR-0016)는 IT에서 끈다 — 백그라운드 LISTEN 스레드가 컨텍스트 캐시 수명 내내
// 떠 있을 필요가 없고, 급증 감지 SQL·NOTIFY 트리거는 리스너 없이 직접(서비스/JDBC) 검증한다.
//
// 커넥션 풀 캡(TS-030): 각 IT가 고유 프로퍼티(jwt.secret 등)로 별도 Spring 컨텍스트를 만들고, 컨텍스트마다
// HikariCP 풀이 캐시 수명 내내 살아있어(TestContext 캐시) 단일 Testcontainers Postgres의 max_connections(기본
// 100)를 소진한다 → 임계 근처에서 컨텍스트를 하나 더 추가하면 새 컨텍스트의 Flyway 접속이 "too many clients"로
// 실패한다(N6 IT 추가 때 ActuatorPrometheusOptInIT에서 표면화). IT는 순차 MockMvc라 풀이 작아도 충분하므로
// 컨텍스트 수와 무관하게 접속 총량을 억제하도록 풀을 작게 캡한다(프로덕션 풀은 무영향 — 테스트 프로퍼티만).
@org.springframework.test.context.TestPropertySource(properties = {
        "geuneul.realtime.enabled=false",
        "geuneul.photo-cleanup.enabled=false",
        "spring.datasource.hikari.maximum-pool-size=4"
})
@org.springframework.context.annotation.Import({
        com.geuneul.domain.ingest.geocode.FakeGeocodingConfig.class,
        com.geuneul.domain.ingest.openapi.FakeLibraryApiConfig.class
})
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:16-3.4")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("geuneul");

    static {
        if (DockerClientFactory.instance().isDockerAvailable()) {
            POSTGRES.start();
        }
    }
}
