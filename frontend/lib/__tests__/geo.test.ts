import { describe, it } from "node:test";
import assert from "node:assert/strict";

import { boundsParam, formatDistance, haversineMeters, radiusLabel, walkMinutes } from "../geo.ts";

describe("formatDistance", () => {
  it("1km 미만은 반올림한 m, 이상은 소수 1자리 km", () => {
    assert.equal(formatDistance(0), "0m");
    assert.equal(formatDistance(349.6), "350m");
    assert.equal(formatDistance(999), "999m");
    assert.equal(formatDistance(1000), "1.0km");
    assert.equal(formatDistance(1250), "1.3km");
  });

  it("거리가 없으면 null", () => {
    assert.equal(formatDistance(null), null);
    assert.equal(formatDistance(undefined), null);
  });
});

describe("radiusLabel", () => {
  it("딱 떨어지는 km는 정수, 아니면 소수 1자리", () => {
    assert.equal(radiusLabel(800), "800m");
    assert.equal(radiusLabel(1000), "1km");
    assert.equal(radiusLabel(1500), "1.5km");
    assert.equal(radiusLabel(5000), "5km");
  });
});

describe("walkMinutes", () => {
  it("67m/분으로 반올림하고 최소 1분", () => {
    assert.equal(walkMinutes(0), 1);
    assert.equal(walkMinutes(30), 1);
    assert.equal(walkMinutes(67), 1);
    assert.equal(walkMinutes(670), 10);
    assert.equal(walkMinutes(1005), 15);
  });

  it("거리가 없으면 null", () => {
    assert.equal(walkMinutes(null), null);
    assert.equal(walkMinutes(undefined), null);
  });
});

describe("haversineMeters", () => {
  it("같은 점은 0", () => {
    const p = { lat: 37.5024, lng: 126.9438 };
    assert.equal(haversineMeters(p, p), 0);
  });

  it("위도 0.01도 차이는 약 1.11km", () => {
    const d = haversineMeters({ lat: 37.5, lng: 126.95 }, { lat: 37.51, lng: 126.95 });
    assert.ok(d > 1100 && d < 1120, `got ${d}`);
  });

  it("순서를 바꿔도 같은 거리", () => {
    const a = { lat: 37.4963, lng: 126.9575 };
    const b = { lat: 37.5024, lng: 126.9438 };
    assert.ok(Math.abs(haversineMeters(a, b) - haversineMeters(b, a)) < 1e-6);
  });
});

describe("boundsParam", () => {
  it("west,south,east,north 순서로 직렬화한다", () => {
    assert.equal(
      boundsParam({ south: 37.49, west: 126.94, north: 37.51, east: 126.96 }),
      "126.94,37.49,126.96,37.51",
    );
  });
});
