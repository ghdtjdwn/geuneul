# RDS PostgreSQL (PostGIS는 Flyway V1의 CREATE EXTENSION으로 활성화).
# 최소급 구성: db.t3.micro, 20GB gp3, Single-AZ. 이 계정에서는 사용액을 Free plan 크레딧으로 상쇄한다.

resource "aws_db_subnet_group" "db" {
  name       = "${var.project}-db"
  subnet_ids = aws_subnet.private[*].id
  tags       = { Name = "${var.project}-db-subnet" }
}

resource "aws_db_instance" "postgres" {
  identifier        = "${var.project}-db"
  engine            = "postgres"
  engine_version    = "16"
  instance_class    = "db.t3.micro" # 최소급 구성. 컴퓨트 사용액은 Free plan 크레딧에서 차감된다.
  allocated_storage = 20
  storage_type      = "gp3"
  storage_encrypted = true # 저장 암호화(KMS). 라이브 DB는 암호화 스냅샷에서 복원해 적용(아래 snapshot_identifier·TS-035).
  # 기존 데이터를 무손실로 암호화하기 위해, 미암호화 인스턴스의 스냅샷을 KMS로 암호화 복사한 뒤
  # 그 스냅샷에서 복원한다(수동: create-db-snapshot → copy-db-snapshot --kms-key-id → 이 값 참조).
  snapshot_identifier    = "geuneul-db-encrypted"
  db_name                = var.db_name
  username               = var.db_username
  password               = var.db_password
  db_subnet_group_name   = aws_db_subnet_group.db.name
  vpc_security_group_ids = [aws_security_group.rds.id]
  publicly_accessible    = false
  multi_az               = false
  # 자동 백업(PITR) — 이 계정의 Free plan API 제한은 retention 최대 1일(7일은 FreeTierRestrictionError, 실측 TS-035).
  # 1일이라도 자동 스냅샷 + 1일 PITR을 확보한다(유료 전환 시 7일 검토). DB 삭제 뒤 남은 수동 스냅샷은 과금될 수 있다.
  backup_retention_period   = 1
  backup_window             = "18:00-18:30" # UTC = 03:00 KST(트래픽 낮은 새벽)
  copy_tags_to_snapshot     = true
  skip_final_snapshot       = false
  final_snapshot_identifier = "geuneul-db-final"
  deletion_protection       = true # 복원·암호화 검증 완료 후 실수 삭제 방지.
  apply_immediately         = true
  tags                      = { Name = "${var.project}-db" }

  # snapshot_identifier는 최초 복원에만 쓰이는 create-time 속성 — 이후 변경/제거가 또 replace를 유발하지
  # 않도록 고정한다(ignore). 자동백업이 켜졌으니 이후 장애 복구는 PITR/자동 스냅샷으로 한다.
  lifecycle {
    ignore_changes = [snapshot_identifier]
  }
}
