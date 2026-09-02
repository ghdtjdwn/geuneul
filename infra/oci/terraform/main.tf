provider "oci" {
  region = var.region
}

data "oci_objectstorage_namespace" "current" {
  compartment_id = var.compartment_ocid
}

resource "oci_objectstorage_bucket" "photos" {
  compartment_id = var.compartment_ocid
  namespace      = data.oci_objectstorage_namespace.current.namespace
  name           = var.photos_bucket_name
  access_type    = "NoPublicAccess"
  storage_tier   = "Standard"
  versioning     = "Enabled"

  object_events_enabled = false

  freeform_tags = {
    application = "geuneul"
    managed-by  = "terraform"
    data-class  = "user-content"
  }

  lifecycle {
    prevent_destroy = true
  }
}

resource "oci_objectstorage_bucket" "backups" {
  compartment_id = var.compartment_ocid
  namespace      = data.oci_objectstorage_namespace.current.namespace
  name           = var.backups_bucket_name
  access_type    = "NoPublicAccess"
  storage_tier   = "Standard"
  versioning     = "Disabled"

  object_events_enabled = false

  freeform_tags = {
    application = "geuneul"
    managed-by  = "terraform"
    data-class  = "database-backup"
  }

  lifecycle {
    prevent_destroy = true
  }
}

resource "oci_objectstorage_object_lifecycle_policy" "photos" {
  namespace = oci_objectstorage_bucket.photos.namespace
  bucket    = oci_objectstorage_bucket.photos.name

  depends_on = [oci_identity_policy.object_storage_runtime]

  rules {
    action      = "DELETE"
    is_enabled  = true
    name        = "delete-old-photo-versions"
    time_amount = 30
    time_unit   = "DAYS"
    target      = "previous-object-versions"
  }

  rules {
    action      = "ABORT"
    is_enabled  = true
    name        = "abort-stale-photo-multipart-uploads"
    time_amount = 7
    time_unit   = "DAYS"
    target      = "multipart-uploads"
  }
}

resource "oci_objectstorage_object_lifecycle_policy" "backups" {
  namespace = oci_objectstorage_bucket.backups.namespace
  bucket    = oci_objectstorage_bucket.backups.name

  depends_on = [oci_identity_policy.object_storage_runtime]

  rules {
    action      = "DELETE"
    is_enabled  = true
    name        = "delete-expired-logical-backups"
    time_amount = var.backup_retention_days
    time_unit   = "DAYS"
    target      = "objects"
  }

  rules {
    action      = "ABORT"
    is_enabled  = true
    name        = "abort-stale-backup-multipart-uploads"
    time_amount = 7
    time_unit   = "DAYS"
    target      = "multipart-uploads"
  }
}

resource "oci_identity_user" "photo_app" {
  compartment_id = var.tenancy_ocid
  name           = "${var.iam_name_prefix}-photo-app"
  description    = "Non-interactive Geuneul application identity restricted to the photos bucket."
  email          = var.photo_app_email
}

resource "oci_identity_user_capabilities_management" "photo_app" {
  user_id                      = oci_identity_user.photo_app.id
  can_use_api_keys             = false
  can_use_auth_tokens          = false
  can_use_console_password     = false
  can_use_customer_secret_keys = true
  can_use_smtp_credentials     = false
}

resource "oci_identity_group" "photo_app" {
  compartment_id = var.tenancy_ocid
  name           = "${var.iam_name_prefix}-photo-app"
  description    = "Least-privilege access to Geuneul photo objects only."
}

resource "oci_identity_user_group_membership" "photo_app" {
  compartment_id = var.tenancy_ocid
  user_id        = oci_identity_user.photo_app.id
  group_id       = oci_identity_group.photo_app.id
}

resource "oci_identity_user" "backup_writer" {
  compartment_id = var.tenancy_ocid
  name           = "${var.iam_name_prefix}-backup-writer"
  description    = "Non-interactive append-oriented writer for Geuneul logical database backups."
  email          = var.backup_writer_email
}

resource "oci_identity_user_capabilities_management" "backup_writer" {
  user_id                      = oci_identity_user.backup_writer.id
  can_use_api_keys             = false
  can_use_auth_tokens          = false
  can_use_console_password     = false
  can_use_customer_secret_keys = true
  can_use_smtp_credentials     = false
}

resource "oci_identity_group" "backup_writer" {
  compartment_id = var.tenancy_ocid
  name           = "${var.iam_name_prefix}-backup-writer"
  description    = "Write and inspect Geuneul backups without object delete permission."
}

resource "oci_identity_user_group_membership" "backup_writer" {
  compartment_id = var.tenancy_ocid
  user_id        = oci_identity_user.backup_writer.id
  group_id       = oci_identity_group.backup_writer.id
}

resource "oci_identity_policy" "object_storage_runtime" {
  compartment_id = var.tenancy_ocid
  name           = "${var.iam_name_prefix}-object-storage-runtime"
  description    = "Separate Geuneul photo runtime and append-oriented backup writer permissions."

  statements = [
    "Allow group ${oci_identity_group.photo_app.name} to read buckets in compartment id ${var.compartment_ocid} where target.bucket.name='${oci_objectstorage_bucket.photos.name}'",
    "Allow group ${oci_identity_group.photo_app.name} to manage objects in compartment id ${var.compartment_ocid} where target.bucket.name='${oci_objectstorage_bucket.photos.name}'",
    "Allow group ${oci_identity_group.backup_writer.name} to read buckets in compartment id ${var.compartment_ocid} where target.bucket.name='${oci_objectstorage_bucket.backups.name}'",
    "Allow group ${oci_identity_group.backup_writer.name} to manage objects in compartment id ${var.compartment_ocid} where all {target.bucket.name='${oci_objectstorage_bucket.backups.name}', request.permission!='OBJECT_DELETE', request.permission!='OBJECT_VERSION_DELETE'}",
    "Allow service objectstorage-${var.region} to manage object-family in compartment id ${var.compartment_ocid} where all {target.bucket.name='${oci_objectstorage_bucket.photos.name}', any {request.permission='BUCKET_INSPECT', request.permission='BUCKET_READ', request.permission='OBJECT_INSPECT', request.permission='OBJECT_UPDATE_TIER', request.permission='OBJECT_DELETE', request.permission='OBJECT_VERSION_DELETE'}}",
    "Allow service objectstorage-${var.region} to manage object-family in compartment id ${var.compartment_ocid} where all {target.bucket.name='${oci_objectstorage_bucket.backups.name}', any {request.permission='BUCKET_INSPECT', request.permission='BUCKET_READ', request.permission='OBJECT_INSPECT', request.permission='OBJECT_UPDATE_TIER', request.permission='OBJECT_DELETE', request.permission='OBJECT_VERSION_DELETE'}}",
  ]
}
