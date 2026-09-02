import type { Metadata } from "next";
import Link from "next/link";

export const metadata: Metadata = {
  title: "개인정보처리방침",
  description: "그늘(Geuneul) 개인정보처리방침 — 수집 항목·이용 목적·제3자·보관·문의.",
};

// /privacy — 개인정보처리방침(공개). Google Play 등록 요건 + 앱 내 링크용. 실제 데이터 취급을 사실대로 기술.
// (shell) 밖 독립 라우트라 지도 셸/하단 탭 없음.
export default function PrivacyPage() {
  return (
    <div className="min-h-dvh bg-cream px-6 py-10">
      <div className="mx-auto w-full max-w-[720px]">
        <Link href="/" className="text-[13px] font-semibold text-ink-3 transition-colors hover:text-teal">
          ← 지도로 돌아가기
        </Link>

        <h1 className="mt-6 text-[26px] font-extrabold tracking-[-0.5px] text-ink">개인정보처리방침</h1>
        <p className="mt-2 text-[13px] text-ink-3">
          그늘(Geuneul) · 시행일 2026-07-11 · 개인 개발 프로젝트
        </p>

        <div className="mt-8 space-y-7 text-[14px] leading-relaxed text-ink-2">
          <section>
            <p>
              그늘은 폭염·장마철에 쉬어갈 공공장소(무더위쉼터·화장실·음수대·도서관 등)와 그 “지금 상태”를 지도에서
              보여주는 <b className="text-ink">개인 개발 서비스</b>입니다. 본 방침은 그늘이 어떤 정보를 왜
              수집·이용하는지를 사실대로 설명합니다.
            </p>
          </section>

          <Section title="1. 수집하는 정보">
            <ul className="list-disc space-y-1.5 pl-5">
              <li>
                <b>위치 정보</b> — 브라우저 위치 권한을 허용한 경우, 주변 장소 검색·거리 계산·제보의 방문 인증에
                사용합니다. 위치는 <b>요청 시점에만</b> 사용하며 이동 경로를 지속 추적·저장하지 않습니다. 권한을
                허용하지 않으면 기본 지역(서울 동작구)으로 동작합니다.
              </li>
              <li>
                <b>계정 정보(소셜 로그인 시)</b> — 카카오/구글 로그인을 하면 이메일·닉네임·프로필 이미지·소셜
                식별자를 받아 계정 식별과 세션(JWT)에 사용합니다. <b>로그인 없이도 지도 조회·제보가 가능</b>합니다.
              </li>
              <li>
                <b>이용자 생성 콘텐츠(UGC)</b> — 제보(상태·한 줄 코멘트·선택 사진·좌표), 후기(별점·코멘트·사진),
                북마크, 신고 내용. 제보는 성격상 <b>일정 시간 후 자동 만료</b>됩니다.
              </li>
              <li>
                <b>사진</b> — 제보·후기에 첨부한 이미지(클라우드 스토리지에 저장, 서명된 URL로 열람).
              </li>
              <li>
                <b>서비스 이용 정보</b> — 신뢰도 점수, 알림 설정, 웹 푸시 구독 정보(브라우저가 발급한 엔드포인트),
                오·남용 방지를 위한 최소한의 요청 정보(레이트리밋).
              </li>
            </ul>
          </Section>

          <Section title="2. 이용 목적">
            <ul className="list-disc space-y-1.5 pl-5">
              <li>주변 장소 지도·검색(반경/최근접) 제공 및 “지금 갈만함” 점수 계산</li>
              <li>제보·후기 등 이용자 콘텐츠의 게시·표시와 신뢰도 반영</li>
              <li>관심 장소 상태 변화·주변 제보 급증 등 알림(설정 시)</li>
              <li>스팸·허위·명예훼손 방지를 위한 신고/검수(모더레이션)</li>
            </ul>
          </Section>

          <Section title="3. 제3자 제공·처리 위탁">
            <p className="mb-2">서비스 제공에 필요한 범위에서 다음 외부 서비스를 이용합니다.</p>
            <ul className="list-disc space-y-1.5 pl-5">
              <li><b>카카오</b> — 지도 표시, 주소↔좌표 지오코딩, 소셜 로그인</li>
              <li><b>구글</b> — 소셜 로그인</li>
              <li><b>기상청</b> — 날씨(폭염·강수) 정보</li>
              <li><b>AI 요약 제공자</b> — 최근 제보를 한 줄로 요약할 때 <b>해당 제보 텍스트 일부</b>가 전송됩니다(개인 식별 정보는 보내지 않음)</li>
              <li><b>Amazon Web Services · Vercel</b> — 서버·데이터베이스·스토리지·프론트 호스팅</li>
            </ul>
          </Section>

          <Section title="4. 보관 및 파기">
            <ul className="list-disc space-y-1.5 pl-5">
              <li>제보(휘발성 상태 정보)는 만료 시점 이후 점수·표시에서 제외됩니다.</li>
              <li>계정·후기·사진 등은 서비스 제공 기간 동안 보관하며, 이용자가 삭제를 요청하면 지체 없이 파기합니다.</li>
              <li>법령상 보관 의무가 있는 경우 해당 기간 동안 보관 후 파기합니다.</li>
            </ul>
          </Section>

          <Section title="5. 이용자의 권리">
            <p>
              이용자는 본인 정보의 열람·수정·삭제, 동의 철회(로그아웃·계정 삭제 요청), 위치 권한 해제(브라우저/OS
              설정)를 언제든 요청·수행할 수 있습니다. 요청은 아래 문의처로 접수합니다.
            </p>
          </Section>

          <Section title="6. 아동">
            <p>
              그늘은 만 14세 미만 아동을 대상으로 하지 않으며, 아동의 개인정보를 알면서 수집하지 않습니다.
            </p>
          </Section>

          <Section title="7. 문의처">
            <p>
              개인정보 관련 문의·삭제 요청: <b className="text-ink">akftjdwn@gmail.com</b>
            </p>
          </Section>

          <Section title="8. 변경 고지">
            <p>
              본 방침이 변경되면 본 페이지에 시행일과 함께 게시합니다.
            </p>
          </Section>
        </div>

        <div className="mt-10 border-t border-line-cream pt-6 text-[12px] text-muted">
          그늘은 법률 자문을 제공하지 않는 개인 개발 프로젝트이며, 침수·안전 정보는 참고용입니다.
          <div className="mt-2">
            <Link href="/install" className="font-semibold text-teal">앱 설치</Link>
            {" · "}
            <a href="https://geuneul.vercel.app" className="font-semibold text-teal">geuneul.vercel.app</a>
          </div>
        </div>
      </div>
    </div>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section>
      <h2 className="mb-2 text-[16px] font-extrabold text-ink">{title}</h2>
      {children}
    </section>
  );
}
