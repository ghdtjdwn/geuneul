"use client";

import { createContext, useCallback, useContext, useEffect, useRef, useState } from "react";
import { FALLBACK_CENTER } from "@/lib/geo";

type GeoStatus = "idle" | "locating" | "granted" | "fallback";

const LEGACY_LAST_LOCATION_KEY = "geuneul:last-location:v1";

function clearLegacyStoredCoordinates() {
  try {
    window.localStorage.removeItem(LEGACY_LAST_LOCATION_KEY);
  } catch {
    // 비공개 모드 등 저장소를 읽을 수 없어도 메모리 기반 위치 기능은 정상 동작한다.
  }
}

interface GeoState {
  lat: number;
  lng: number;
  accuracy: number | null;
  isFallback: boolean;
  status: GeoStatus;
  /** 다시 현재 위치로(FAB). 위치를 받았는지 반환한다. */
  locate: () => Promise<boolean>;
}

const GeoCtx = createContext<GeoState | null>(null);

// 지도/급해요가 같은 메모리 위치 상태를 공유한다. 정확한 좌표는 브라우저 저장소에 남기지 않고,
// 자동 요청은 이미 허용된 권한에만 한다.
export function GeoProvider({ children }: { children: React.ReactNode }) {
  const [coords, setCoords] = useState<{ lat: number; lng: number }>(() => ({
    lat: FALLBACK_CENTER.lat,
    lng: FALLBACK_CENTER.lng,
  }));
  const [accuracy, setAccuracy] = useState<number | null>(null);
  const [status, setStatus] = useState<GeoStatus>("idle");
  const requested = useRef(false);

  const locate = useCallback((): Promise<boolean> => {
    if (typeof navigator === "undefined" || !navigator.geolocation) {
      setStatus("fallback");
      return Promise.resolve(false);
    }
    setStatus("locating");
    return new Promise((resolve) => {
      const succeed = (pos: GeolocationPosition) => {
        setCoords({ lat: pos.coords.latitude, lng: pos.coords.longitude });
        setAccuracy(pos.coords.accuracy);
        setStatus("granted");
        resolve(true);
      };
      const fail = (error: GeolocationPositionError) => {
        // 고정밀 GPS가 실내·절전 모드에서 늦거나 실패하는 경우, 이미 허용된 권한으로 네트워크 위치를 한 번 더 시도한다.
        // 권한 거부는 재시도해도 프롬프트만 반복될 수 있어 즉시 끝낸다.
        if (error.code === error.PERMISSION_DENIED) {
          setStatus("fallback");
          resolve(false);
          return;
        }
        navigator.geolocation.getCurrentPosition(
          succeed,
          () => {
            setStatus("fallback");
            resolve(false);
          },
          { enableHighAccuracy: false, timeout: 5_000, maximumAge: 5 * 60_000 },
        );
      };
      navigator.geolocation.getCurrentPosition(succeed, fail, {
        enableHighAccuracy: true,
        timeout: 8_000,
        maximumAge: 30_000,
      });
    });
  }, []);

  useEffect(() => {
    if (requested.current) return;
    requested.current = true;
    clearLegacyStoredCoordinates();
    if (typeof navigator === "undefined") return;

    // 브라우저가 이미 허용한 경우에만 자동 갱신한다. 아직 선택하지 않은 사용자에게는
    // 진입 직후 권한창을 띄우지 않고, 현재 위치 버튼을 눌렀을 때 한 번만 묻는다.
    if (!navigator.permissions?.query) return;
    let permission: PermissionStatus | null = null;
    navigator.permissions
      .query({ name: "geolocation" })
      .then((result) => {
        permission = result;
        if (result.state === "granted") void locate();
        result.onchange = () => {
          if (result.state === "granted") void locate();
          else if (result.state === "denied") setStatus("fallback");
        };
      })
      .catch(() => {
        // Permissions API가 없는 브라우저는 버튼을 눌렀을 때만 Geolocation API를 호출한다.
      });
    return () => {
      if (permission) permission.onchange = null;
    };
  }, [locate]);

  return (
    <GeoCtx.Provider
      value={{
        lat: coords.lat,
        lng: coords.lng,
        accuracy,
        isFallback: status !== "granted",
        status,
        locate,
      }}
    >
      {children}
    </GeoCtx.Provider>
  );
}

export function useGeo(): GeoState {
  const ctx = useContext(GeoCtx);
  if (!ctx) throw new Error("useGeo must be used within GeoProvider");
  return ctx;
}
