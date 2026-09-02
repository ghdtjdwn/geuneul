from __future__ import annotations

import pathlib
import unittest


REPOSITORY_ROOT = pathlib.Path(__file__).resolve().parents[3]
SERVER_ROOT = REPOSITORY_ROOT / "infra/oci/server"


class HostStorageContractTest(unittest.TestCase):
    def test_zero_cost_boot_storage_is_required_by_every_server_entrypoint(self) -> None:
        verifier = (SERVER_ROOT / "verify-host-storage.sh").read_text(encoding="utf-8")
        self.assertIn('"boot-bind-v1"', verifier)
        self.assertIn('GEUNEUL_STORAGE_SOURCE="/var/lib/marketvalley"', verifier)
        self.assertIn('GEUNEUL_HOST_MINIMUM_FREE_KIB=$((40 * 1024 * 1024))', verifier)
        self.assertIn('GEUNEUL_HOST_MAXIMUM_INODE_USE_PERCENT=90', verifier)
        self.assertIn('FSROOT', verifier)
        self.assertIn('nosuid', verifier)
        self.assertIn('nodev', verifier)

        for name in (
            "bootstrap-ubuntu-rootless.sh",
            "prepare-host.sh",
            "remote-release.sh",
            "check-production-health.sh",
        ):
            with self.subTest(name=name):
                entrypoint = (SERVER_ROOT / name).read_text(encoding="utf-8")
                self.assertIn("geuneul_verify_host_storage", entrypoint)

        bootstrap = (SERVER_ROOT / "bootstrap-ubuntu-rootless.sh").read_text(encoding="utf-8")
        storage_start = (SERVER_ROOT / "verify-storage-start.sh").read_text(encoding="utf-8")
        remote_release = (SERVER_ROOT / "remote-release.sh").read_text(encoding="utf-8")
        self.assertIn("ExecStartPre=/usr/local/lib/geuneul/verify-storage-start.sh", bootstrap)
        self.assertIn("geuneul_verify_host_storage", storage_start)
        self.assertNotIn("geuneul_require_host_capacity", storage_start)
        self.assertIn("geuneul_require_host_capacity", remote_release)
        common_runtime = remote_release[
            remote_release.index("require_runtime()") : remote_release.index("acquire_lock()")
        ]
        self.assertIn("geuneul_verify_host_storage", common_runtime)
        self.assertNotIn("geuneul_require_host_capacity", common_runtime)
        self.assertIn('activate_release "$previous_sha" no', remote_release)
        self.assertIn('activate_release "$target_sha" no', remote_release)


if __name__ == "__main__":
    unittest.main()
