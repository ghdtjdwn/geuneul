import { describe, it } from "node:test";
import assert from "node:assert/strict";

import { REPORT_GRID, REPORT_META, formatRelativeTime } from "../reports.ts";

const MINUTE = 60_000;
const agoIso = (ms: number): string => new Date(Date.now() - ms).toISOString();

describe("formatRelativeTime", () => {
  it("1분 미만은 방금 전", () => {
    assert.equal(formatRelativeTime(agoIso(0)), "방금 전");
    assert.equal(formatRelativeTime(agoIso(59_000)), "방금 전");
  });

  it("1시간 미만은 분 단위", () => {
    assert.equal(formatRelativeTime(agoIso(MINUTE)), "1분 전");
    assert.equal(formatRelativeTime(agoIso(59 * MINUTE + 30_000)), "59분 전");
  });

  it("하루 미만은 시간 단위", () => {
    assert.equal(formatRelativeTime(agoIso(60 * MINUTE)), "1시간 전");
    assert.equal(formatRelativeTime(agoIso(23 * 60 * MINUTE + 59 * MINUTE)), "23시간 전");
  });

  it("그 이후는 일 단위", () => {
    assert.equal(formatRelativeTime(agoIso(24 * 60 * MINUTE)), "1일 전");
    assert.equal(formatRelativeTime(agoIso(3 * 24 * 60 * MINUTE + 5 * MINUTE)), "3일 전");
  });
});

describe("REPORT_GRID", () => {
  it("그리드의 모든 타입은 REPORT_META에 정의돼 있고 중복이 없다", () => {
    for (const key of REPORT_GRID) assert.ok(REPORT_META[key], `missing meta for ${key}`);
    assert.equal(new Set(REPORT_GRID).size, REPORT_GRID.length);
  });

  it("자리 여유·혼잡 신호가 맨 앞에 온다", () => {
    assert.deepEqual(REPORT_GRID.slice(0, 2), ["SEAT_OK", "CROWDED"]);
  });
});
