# ADR-0029 — RDS 저장 암호화 + 자동 백업 (스냅샷 복원 무손실 마이그레이션)

- 상태: AWS 운영 이력. 암호화 RDS/PITR을 migration source로 사용했고 2026-09-03 무결성 이전 뒤 RDS와 snapshot을 삭제했다.
- 날짜: 2026-07-12
- 관련: ADR-0028(보안 트레이드오프에서 "남겨 둠"으로 기록했던 항목을 해소), TS-035

## 문제(Context)

라이브 RDS(PostgreSQL+PostGIS)가 **저장 암호화 없이**, **자동 백업 없이**(retention 0) 돌고 있었다.
`storage_encrypted`는 기존 인스턴스에서 바꿀 수 없는(immutable) 속성이라 `ignore_changes`로 고정해 두었고,
ADR-0028에서 "실서비스 전환 시 재검토"할 트레이드오프로 남겨 두었다. 이번에 실제로 해소한다.

## 결정(Decision)

**스냅샷 경유 무손실 암호화** — 미암호화 인스턴스를 지우고 새로 만들되 데이터는 스냅샷에서 복원한다.

1. 현재 인스턴스의 수동 스냅샷 생성(`geuneul-db-pre-encrypt-v1`, 원본 안전망).
2. 그 스냅샷을 **KMS(alias/aws/rds)로 암호화 복사**(`copy-db-snapshot --kms-key-id`) → `geuneul-db-encrypted`.
3. `rds.tf`에 `storage_encrypted = true` + `snapshot_identifier = "geuneul-db-encrypted"`를 넣어 apply →
   terraform이 인스턴스를 replace하며 **암호화 스냅샷에서 복원**(데이터 보존). `snapshot_identifier`는 create-time
   속성이라 이후 `ignore_changes`로 고정.
4. **자동 백업(PITR) 활성** — `backup_retention_period = 1`, `deletion_protection = true`, `final_snapshot_identifier` 지정.

검증(실측):
- 새 인스턴스 `StorageEncrypted=true`, `BackupRetentionPeriod=1`, `DeletionProtection=true`.
- **RDS 엔드포인트는 identifier 기반이라 replace 후에도 동일**(`geuneul-db.<...>.rds.amazonaws.com`) →
  앱의 `DB_HOST`(SSM/env) 재배선 불필요. replace 직후 `/actuator/health` UP, 반경검색 데이터 복원 확인.

## 검토한 대안

| 대안 | 기각 사유 |
|---|---|
| `storage_encrypted`를 in-place로 true | RDS는 기존 인스턴스 암호화를 지원하지 않음(immutable) → 불가 |
| 새 암호화 인스턴스 + 데이터 재적재 | 공공데이터는 재적재 가능하나 UGC(제보·후기)가 유실. 스냅샷 복원이 무손실 |
| `backup_retention_period = 7` | 이 계정 프리티어가 `FreeTierRestrictionError`로 거부(최대 1일, TS-035). 1일로 확보 |
| deletion_protection을 replace와 동시에 true | replace의 destroy 단계가 막힘 → replace 시 false, 복원 검증 후 in-place로 true(2단계) |

## 결과(Consequences)

- 저장 데이터 암호화(KMS) + 1일 PITR/자동 스냅샷 + 실수 삭제 방지 확보. 무중단은 아니고 replace 동안
  ~15분 DB 다운타임이 있었으나(필드테스트 단계라 감수), 데이터는 두 스냅샷으로 이중 안전망.
- **한계**: 프리티어라 retention 1일. 유료 전환 시 `backup_retention_period`만 7일로 올리면 된다(코드 한 줄).
  이제 rds.tf에 트레이드오프가 아니라 실제 설정으로 반영돼 있다.
