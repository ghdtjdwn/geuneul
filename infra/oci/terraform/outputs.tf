output "object_storage_namespace" {
  description = "Namespace used to construct the OCI S3 compatibility endpoint."
  value       = data.oci_objectstorage_namespace.current.namespace
  sensitive   = true
}

output "photos_bucket_name" {
  value = oci_objectstorage_bucket.photos.name
}

output "backups_bucket_name" {
  value = oci_objectstorage_bucket.backups.name
}

output "s3_compatibility_endpoint" {
  description = "Path-style S3 endpoint for the application and migration scripts."
  value       = "https://${data.oci_objectstorage_namespace.current.namespace}.compat.objectstorage.${var.region}.oci.customer-oci.com"
  sensitive   = true
}

output "photo_app_user_ocid" {
  description = "Create the application Customer Secret Key on this dedicated user after apply."
  value       = oci_identity_user.photo_app.id
  sensitive   = true
}

output "backup_writer_user_ocid" {
  description = "Create a separate backup Customer Secret Key on this dedicated non-delete user after apply."
  value       = oci_identity_user.backup_writer.id
  sensitive   = true
}
