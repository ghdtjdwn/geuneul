package com.geuneul.domain.report;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 익명 제보 레이트리밋의 "클라이언트 신원" 해석 — XFF 신뢰경계 문제(코드리뷰 확정)를 해결한다.
 *
 * <p>배경: 우리 앱은 두 경로로 들어온다. ① 브라우저 → Vercel BFF(서버 프록시) → ALB, ② 공격자 → ALB 직접.
 * ALB는 실 접속 IP를 XFF <b>최우측</b>에 append 하므로, 클라이언트가 보낸 XFF 최좌측은 위조 가능하다.
 * 따라서 "최좌측 XFF를 키로 쓰면" 공격자가 XFF를 회전시켜 무한 키를 만들어 리밋을 우회한다.
 *
 * <p>해결(신뢰경계 명시):
 * <ul>
 *   <li><b>BFF가 공유 시크릿({@code X-Proxy-Auth})으로 자신을 증명</b>하면, BFF가 판정한 실제 클라이언트
 *       IP({@code X-Client-Ip})를 신뢰한다 → BFF 경로의 유저별 리밋(다중 유저 정상 동작).</li>
 *   <li>시크릿이 설정돼 있는데 증명이 없으면(=ALB 직접 타격) ALB가 append 한 <b>최우측 XFF</b>(위조 불가)로
 *       키잉 → 직접 남용은 실 IP당 하드 리밋.</li>
 *   <li>시크릿이 미설정이면 전달 헤더를 신뢰하지 않고 TCP 피어로 축약한다. 사용자별 구분은 잃지만
 *       공격자가 XFF를 회전해 리밋을 우회할 수 없는 fail-safe 동작이다.</li>
 * </ul>
 * 순수 오버로드({@link #resolve(String, String, String, String)})로 단위 테스트한다.
 */
@Component
public class ProxyClientResolver {

    private final String proxySecret;

    public ProxyClientResolver(@Value("${geuneul.proxy-secret:}") String proxySecret) {
        this.proxySecret = proxySecret;
    }

    public String resolve(HttpServletRequest http) {
        return resolve(
                http.getHeader("X-Proxy-Auth"),
                http.getHeader("X-Client-Ip"),
                http.getHeader("X-Forwarded-For"),
                http.getRemoteAddr());
    }

    /**
     * @param proxyAuth  BFF가 보낸 공유 시크릿 헤더
     * @param clientIp   BFF가 판정한 실 클라이언트 IP 헤더
     * @param xff        X-Forwarded-For 원문(콤마 구분, ALB가 최우측에 실 접속 IP append)
     * @param remoteAddr TCP 피어(ALB 뒤에선 ALB 노드 IP)
     * @return 레이트리밋 키(네임스페이스 접두사로 신뢰수준 구분)
     */
    String resolve(String proxyAuth, String clientIp, String xff, String remoteAddr) {
        boolean secretConfigured = StringUtils.hasText(proxySecret);

        // ① BFF가 시크릿으로 증명 → BFF가 준 실 클라이언트 IP 신뢰
        if (secretConfigured && proxySecret.equals(proxyAuth) && StringUtils.hasText(clientIp)) {
            return "c:" + clientIp.strip();
        }

        // ② 시크릿이 설정된 운영의 직접 요청은 ALB가 append한 최우측 hop만 신뢰한다.
        if (secretConfigured && StringUtils.hasText(xff)) {
            String[] hops = xff.split(",");
            String token = hops[hops.length - 1];
            if (StringUtils.hasText(token)) {
                return "x:" + token.strip();
            }
        }

        // ③ 시크릿 미설정도 여기로 수렴: 공유 버킷이 되더라도 위조 우회보다 안전하다.
        return "x:" + (remoteAddr == null ? "unknown" : remoteAddr);
    }
}
