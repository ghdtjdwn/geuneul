variable "region" {
  description = "OCI region identifier for the existing Geuneul host and Object Storage."
  type        = string
  default     = "ap-chuncheon-1"

  validation {
    condition     = can(regex("^[a-z]+-[a-z]+-[0-9]+$", var.region))
    error_message = "region must be an OCI region identifier such as ap-chuncheon-1."
  }
}

variable "compartment_ocid" {
  description = "Compartment that owns the Geuneul Object Storage buckets."
  type        = string
  sensitive   = true

  validation {
    condition     = startswith(var.compartment_ocid, "ocid1.compartment.") || startswith(var.compartment_ocid, "ocid1.tenancy.")
    error_message = "compartment_ocid must be a compartment or root tenancy OCID."
  }
}

variable "tenancy_ocid" {
  description = "Root tenancy OCID that owns the dedicated IAM users, groups, and least-privilege policy."
  type        = string
  sensitive   = true

  validation {
    condition     = startswith(var.tenancy_ocid, "ocid1.tenancy.")
    error_message = "tenancy_ocid must be a root tenancy OCID."
  }
}

variable "iam_name_prefix" {
  description = "Stable prefix for dedicated photo-app and backup-writer IAM identities."
  type        = string
  default     = "geuneul"

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{2,30}$", var.iam_name_prefix))
    error_message = "iam_name_prefix must be 3-31 lowercase letters, digits, or hyphens."
  }
}

variable "photo_app_email" {
  description = "Unique primary email required by identity-domain tenancies for the photo application service user."
  type        = string
  sensitive   = true

  validation {
    condition     = can(regex("^[^@[:space:]]+@[^@[:space:]]+$", var.photo_app_email))
    error_message = "photo_app_email must be a valid email address."
  }
}

variable "backup_writer_email" {
  description = "Unique primary email required by identity-domain tenancies for the backup writer service user."
  type        = string
  sensitive   = true

  validation {
    condition     = can(regex("^[^@[:space:]]+@[^@[:space:]]+$", var.backup_writer_email))
    error_message = "backup_writer_email must be a valid email address."
  }
}

variable "photos_bucket_name" {
  description = "Private bucket used by presigned photo uploads."
  type        = string
  default     = "geuneul-photos"
}

variable "backups_bucket_name" {
  description = "Private off-host bucket for logical PostgreSQL backups."
  type        = string
  default     = "geuneul-backups"
}

variable "backup_retention_days" {
  description = "Number of days to retain timestamped logical database backups."
  type        = number
  default     = 14

  validation {
    condition     = var.backup_retention_days >= 7 && var.backup_retention_days <= 90
    error_message = "backup_retention_days must be between 7 and 90."
  }
}
