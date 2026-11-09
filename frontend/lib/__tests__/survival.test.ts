import { describe, it } from "node:test";
import assert from "node:assert/strict";

import { GRADE_META, gradeOf, statusLabel } from "../survival.ts";
import type { Survival, SurvivalGrade } from "../../types/place.ts";

const survival = (score: number, grade: SurvivalGrade): Survival => ({
  score,
  grade,
  distanceScore: null,
  comfortScore: 0,
  freshnessScore: 0,
  riskScore: 0,
  reportCount: 0,
});

describe("gradeOf", () => {
  it("survival이 없으면 UNKNOWN으로 취급한다", () => {
    assert.equal(gradeOf({ survival: null }), "UNKNOWN");
    assert.equal(gradeOf({ survival: undefined }), "UNKNOWN");
  });

  it("응답의 grade를 그대로 돌려준다", () => {
    assert.equal(gradeOf({ survival: survival(82, "GOOD") }), "GOOD");
    assert.equal(gradeOf({ survival: survival(55, "OKAY") }), "OKAY");
  });
});

describe("statusLabel", () => {
  it("GOOD/OKAY는 라벨 뒤에 점수를 붙인다", () => {
    assert.deepEqual(statusLabel(survival(82, "GOOD")), {
      label: "지금 좋음 · 82",
      color: GRADE_META.GOOD.color,
    });
    assert.deepEqual(statusLabel(survival(55, "OKAY")), {
      label: "보통 · 55",
      color: GRADE_META.OKAY.color,
    });
  });

  it("UNKNOWN은 점수 없이 라벨만 보여준다", () => {
    assert.deepEqual(statusLabel(survival(0, "UNKNOWN")), {
      label: GRADE_META.UNKNOWN.label,
      color: GRADE_META.UNKNOWN.color,
    });
    assert.deepEqual(statusLabel(null), statusLabel(undefined));
    assert.equal(statusLabel(null).label, "정보 부족");
  });
});
