import { readFileSync } from "node:fs";

const source = readFileSync(new URL("../lib/context/geo.tsx", import.meta.url), "utf8");
const forbidden = [
  ["persistent coordinate write", /localStorage\.setItem|sessionStorage\.setItem/],
  ["persistent coordinate read", /localStorage\.getItem|sessionStorage\.getItem/],
  ["serialized coordinate cache", /SavedLocation|savedAt|LAST_LOCATION_MAX_AGE/],
  ["persistent cache state", /["']cached["']/],
];

for (const [label, pattern] of forbidden) {
  if (pattern.test(source)) {
    throw new Error(`geo privacy regression: ${label}`);
  }
}

if (!source.includes("localStorage.removeItem(LEGACY_LAST_LOCATION_KEY)")) {
  throw new Error("geo privacy regression: legacy persisted coordinates are not removed");
}
if (!source.includes("maximumAge:")) {
  throw new Error("geo privacy regression: browser-managed Geolocation caching is missing");
}

console.log("location privacy verified: memory-only coordinates with legacy storage cleanup");
