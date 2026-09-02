#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

readonly region="ap-northeast-2"
readonly cluster="geuneul"
readonly service="geuneul"
readonly export_family="geuneul-db-export"
readonly postgres_image="postgres:16.13-bookworm@sha256:472efd9a66f2b2f1a5aeb18b28de74332e6ef88c2b93a1a5d812fb6db67a5f60"
readonly aws_cli_image="public.ecr.aws/aws-cli/aws-cli:2.31.30@sha256:6c9314d8dd18bcfd11c509e00ea39016c4fb978e42e86df1e4dfd17038b10b78"
readonly db_identifier="${1:-}"
readonly output_dir="${2:-}"

fail() {
  printf 'Production database export failed: %s\n' "$1" >&2
  exit 1
}

[[ "${AWS_DB_EXPORT_CONFIRM:-}" == "EXPORT_GEUNEUL_DATABASE" ]] \
  || fail "set AWS_DB_EXPORT_CONFIRM=EXPORT_GEUNEUL_DATABASE after writes are frozen"
[[ "$db_identifier" =~ ^[a-z][a-z0-9-]{0,62}$ ]] \
  || fail "usage: prod-export-db.sh DB_INSTANCE_IDENTIFIER /absolute/empty/output/directory"
[[ "$output_dir" == /* ]] || fail "output directory must be absolute"
[[ ! -e "$output_dir" ]] || fail "output directory must not already exist"

for command in aws jq shasum; do
  command -v "$command" >/dev/null || fail "required command is missing: $command"
done

db_json="$(mktemp)"
service_json="$(mktemp)"
task_definition_json="$(mktemp)"
register_input="$(mktemp)"
run_result="$(mktemp)"
cleanup() {
  rm -f -- "$db_json" "$service_json" "$task_definition_json" "$register_input" "$run_result"
}
trap cleanup EXIT

aws rds describe-db-instances --region "$region" --db-instance-identifier "$db_identifier" >"$db_json"
[[ "$(jq -r '.DBInstances[0].DBInstanceStatus' "$db_json")" == "available" ]] \
  || fail "the source database is not available"
readonly db_host="$(jq -r '.DBInstances[0].Endpoint.Address' "$db_json")"
[[ -n "$db_host" && "$db_host" != "null" ]] || fail "the source database endpoint is missing"

aws ecs describe-services --region "$region" --cluster "$cluster" --services "$service" >"$service_json"
[[ "$(jq -r '.services[0].desiredCount' "$service_json")" == "0" ]] \
  || fail "the application service is not frozen at desired count zero"
[[ "$(jq -r '.services[0].runningCount' "$service_json")" == "0" ]] \
  || fail "the application service still has running tasks"
readonly app_task_definition="$(jq -r '.services[0].taskDefinition' "$service_json")"

aws ecs describe-task-definition --region "$region" --task-definition "$app_task_definition" >"$task_definition_json"
readonly execution_role="$(jq -r '.taskDefinition.executionRoleArn' "$task_definition_json")"
readonly task_role="$(jq -r '.taskDefinition.taskRoleArn' "$task_definition_json")"
readonly password_parameter="$(
  jq -r '.taskDefinition.containerDefinitions[]
    | select(.name == "geuneul")
    | .secrets[]
    | select(.name == "DB_PASSWORD")
    | .valueFrom' "$task_definition_json"
)"
readonly bucket="$(
  jq -r '.taskDefinition.containerDefinitions[]
    | select(.name == "geuneul")
    | .environment[]
    | select(.name == "S3_BUCKET_NAME")
    | .value' "$task_definition_json"
)"
[[ "$execution_role" == arn:aws:iam::*:role/* ]] || fail "execution role is missing"
[[ "$task_role" == arn:aws:iam::*:role/* ]] || fail "task role is missing"
[[ "$password_parameter" == arn:aws:ssm:*:parameter/* ]] || fail "DB password parameter is missing"
[[ "$bucket" =~ ^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$ ]] || fail "source bucket is invalid"

subnet_text="$(
  aws ec2 describe-subnets --region "$region" \
    --filters 'Name=tag:Name,Values=geuneul-public-*' \
    --query 'sort_by(Subnets,&AvailabilityZone)[].SubnetId' --output text
)"
IFS=$'\t' read -r -a subnets <<<"$subnet_text"
[[ "${#subnets[@]}" -ge 1 ]] || fail "no ECS public subnet was found"
readonly security_group="$(
  aws ec2 describe-security-groups --region "$region" \
    --filters 'Name=group-name,Values=geuneul-ecs-sg' \
    --query 'SecurityGroups[0].GroupId' --output text
)"
[[ "$security_group" == sg-* ]] || fail "the ECS security group was not found"

readonly timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
readonly prefix="migration/database/${timestamp}"
readonly exporter_script='pg_dump --host="$DB_HOST" --port=5432 --username="$DB_USERNAME" --dbname="$DB_NAME" --format=custom --compress=9 --no-owner --no-acl --file=/export/geuneul.dump
test -s /export/geuneul.dump
pg_restore --list /export/geuneul.dump >/dev/null
sha256sum /export/geuneul.dump >/export/geuneul.dump.sha256
psql --host="$DB_HOST" --port=5432 --username="$DB_USERNAME" --dbname="$DB_NAME" --no-align --tuples-only --field-separator="\t" --set ON_ERROR_STOP=1 > /export/source.table-counts.tsv <<'"'"'SQL'"'"'
SELECT format(
  '"'"'SELECT %L AS table_name, count(*) AS row_count FROM %I.%I;'"'"',
  table_name, table_schema, table_name
)
FROM information_schema.tables
WHERE table_schema = '"'"'public'"'"' AND table_type = '"'"'BASE TABLE'"'"'
ORDER BY table_name
\gexec
SQL
test -s /export/source.table-counts.tsv'
readonly uploader_script='set -eu
for file in geuneul.dump geuneul.dump.sha256 source.table-counts.tsv; do
  aws s3 cp "/export/${file}" "s3://${EXPORT_BUCKET}/${EXPORT_PREFIX}/${file}" --only-show-errors
done
local_size=$(stat -c %s /export/geuneul.dump)
remote_size=$(aws s3api head-object --bucket "$EXPORT_BUCKET" --key "$EXPORT_PREFIX/geuneul.dump" --query ContentLength --output text)
test "$local_size" = "$remote_size"
printf "Database export uploaded and size-verified (%s bytes).\n" "$local_size"'

jq -n \
  --arg family "$export_family" \
  --arg executionRoleArn "$execution_role" \
  --arg taskRoleArn "$task_role" \
  --arg postgresImage "$postgres_image" \
  --arg awsCliImage "$aws_cli_image" \
  --arg dbHost "$db_host" \
  --arg passwordParameter "$password_parameter" \
  --arg bucket "$bucket" \
  --arg prefix "$prefix" \
  --arg exporterScript "$exporter_script" \
  --arg uploaderScript "$uploader_script" \
  '{
    family:$family,
    executionRoleArn:$executionRoleArn,
    taskRoleArn:$taskRoleArn,
    networkMode:"awsvpc",
    requiresCompatibilities:["FARGATE"],
    cpu:"512",
    memory:"1024",
    runtimePlatform:{operatingSystemFamily:"LINUX",cpuArchitecture:"X86_64"},
    volumes:[{name:"export-data"}],
    containerDefinitions:[
      {
        name:"exporter",
        image:$postgresImage,
        essential:false,
        command:["bash","-euo","pipefail","-c",$exporterScript],
        environment:[
          {name:"DB_HOST",value:$dbHost},
          {name:"DB_PORT",value:"5432"},
          {name:"DB_NAME",value:"geuneul"},
          {name:"DB_USERNAME",value:"geuneul"}
        ],
        secrets:[{name:"PGPASSWORD",valueFrom:$passwordParameter}],
        mountPoints:[{sourceVolume:"export-data",containerPath:"/export",readOnly:false}],
        logConfiguration:{logDriver:"awslogs",options:{"awslogs-group":"/ecs/geuneul","awslogs-region":"ap-northeast-2","awslogs-stream-prefix":"migration"}}
      },
      {
        name:"uploader",
        image:$awsCliImage,
        essential:true,
        entryPoint:["/bin/sh","-c"],
        command:[$uploaderScript],
        dependsOn:[{containerName:"exporter",condition:"SUCCESS"}],
        environment:[
          {name:"AWS_DEFAULT_REGION",value:"ap-northeast-2"},
          {name:"EXPORT_BUCKET",value:$bucket},
          {name:"EXPORT_PREFIX",value:$prefix}
        ],
        mountPoints:[{sourceVolume:"export-data",containerPath:"/export",readOnly:true}],
        logConfiguration:{logDriver:"awslogs",options:{"awslogs-group":"/ecs/geuneul","awslogs-region":"ap-northeast-2","awslogs-stream-prefix":"migration"}}
      }
    ]
  }' >"$register_input"

readonly export_task_definition="$(
  aws ecs register-task-definition --region "$region" --cli-input-json "file://${register_input}" \
    --query 'taskDefinition.taskDefinitionArn' --output text
)"
readonly subnet_csv="$(IFS=,; printf '%s' "${subnets[*]}")"
aws ecs run-task --region "$region" \
  --cluster "$cluster" \
  --task-definition "$export_task_definition" \
  --launch-type FARGATE \
  --platform-version LATEST \
  --network-configuration "awsvpcConfiguration={subnets=[${subnet_csv}],securityGroups=[${security_group}],assignPublicIp=ENABLED}" \
  --tags key=Purpose,value=oci-migration \
  >"$run_result"

readonly task_arn="$(jq -r '.tasks[0].taskArn // empty' "$run_result")"
[[ "$task_arn" == arn:aws:ecs:*:task/* ]] || fail "ECS did not start the export task"
[[ "$(jq -r '.failures | length' "$run_result")" == "0" ]] || fail "ECS reported a task launch failure"

printf 'Waiting for one-off database export task %s...\n' "${task_arn##*/}"
aws ecs wait tasks-stopped --region "$region" --cluster "$cluster" --tasks "$task_arn"

task_result="$(aws ecs describe-tasks --region "$region" --cluster "$cluster" --tasks "$task_arn")"
exporter_exit="$(jq -r '.tasks[0].containers[] | select(.name == "exporter") | .exitCode // -1' <<<"$task_result")"
uploader_exit="$(jq -r '.tasks[0].containers[] | select(.name == "uploader") | .exitCode // -1' <<<"$task_result")"
[[ "$exporter_exit" == "0" && "$uploader_exit" == "0" ]] \
  || fail "export task failed; inspect the migration CloudWatch log stream"

install -d -m 0700 "$output_dir"
for file in geuneul.dump geuneul.dump.sha256 source.table-counts.tsv; do
  aws s3 cp "s3://${bucket}/${prefix}/${file}" "${output_dir}/${file}" --only-show-errors
done
chmod 0600 "$output_dir"/*
(
  cd "$output_dir"
  shasum -a 256 --check geuneul.dump.sha256
)

if command -v pg_restore >/dev/null; then
  pg_restore --list "$output_dir/geuneul.dump" >/dev/null
else
  docker run --rm --volume "${output_dir}:/export:ro" "$postgres_image" \
    pg_restore --list /export/geuneul.dump >/dev/null
fi

jq -n \
  --arg exportedAt "$timestamp" \
  --arg dbIdentifier "$db_identifier" \
  --arg s3Prefix "$prefix" \
  --arg taskId "${task_arn##*/}" \
  '{exportedAt:$exportedAt,dbIdentifier:$dbIdentifier,s3Prefix:$s3Prefix,taskId:$taskId}' \
  >"$output_dir/export-metadata.json"
chmod 0600 "$output_dir/export-metadata.json"

printf 'Production database export downloaded and verified in %s.\n' "$output_dir"
