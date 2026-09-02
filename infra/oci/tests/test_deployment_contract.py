from __future__ import annotations

import pathlib
import unittest


REPOSITORY_ROOT = pathlib.Path(__file__).resolve().parents[3]
DEPLOY_WORKFLOW = REPOSITORY_ROOT / ".github/workflows/deploy.yml"
SERVER_ROOT = REPOSITORY_ROOT / "infra/oci/server"
CADDY_CONFIG = REPOSITORY_ROOT / "infra/oci/caddy/geuneul.caddy.example"


class DeploymentContractTest(unittest.TestCase):
    def test_production_job_is_blocked_by_a_main_ref_gate(self) -> None:
        workflow = DEPLOY_WORKFLOW.read_text(encoding="utf-8")
        authorize = workflow.index("  authorize:\n")
        test_job = workflow.index("  test:\n", authorize)
        deploy_job = workflow.index("  deploy:\n", test_job)

        authorization_contract = workflow[authorize:test_job]
        test_contract = workflow[test_job:deploy_job]
        deploy_contract = workflow[deploy_job:]
        self.assertIn('DISPATCH_REF: ${{ github.ref }}', authorization_contract)
        self.assertIn('"refs/heads/main"', authorization_contract)
        self.assertIn("exit 1", authorization_contract)
        self.assertIn("needs: authorize", test_contract)
        self.assertIn("needs: [authorize, test]", deploy_contract)
        self.assertIn("if: github.ref == 'refs/heads/main'", deploy_contract)
        self.assertLess(
            deploy_contract.index("needs: [authorize, test]"),
            deploy_contract.index("environment: production"),
        )
        self.assertLess(
            deploy_contract.index("if: github.ref == 'refs/heads/main'"),
            deploy_contract.index("environment: production"),
        )

    def test_stage_transfers_images_without_starting_services(self) -> None:
        workflow = DEPLOY_WORKFLOW.read_text(encoding="utf-8")
        self.assertNotIn('"start-data $RELEASE_SHA"', workflow)
        self.assertIn('if [[ "$OPERATION" == "deploy" ]]', workflow)

    def test_activation_has_a_verified_backup_and_explicit_restore_boundary(self) -> None:
        remote_release = (SERVER_ROOT / "remote-release.sh").read_text(encoding="utf-8")
        backup = remote_release.index('ensure_predeploy_backup "$release_sha"')
        app_start = remote_release.index('compose "$release_sha" up --detach --no-build postgres redis app')
        self.assertLess(backup, app_start)
        self.assertIn('predeploy-backup-${release_sha}', remote_release)
        self.assertIn('validate_backup_marker "$release_backup_marker"', remote_release)
        self.assertIn(
            "restore the database explicitly before starting an older binary",
            remote_release,
        )
        self.assertNotIn("automatic rollback", remote_release)
        self.assertNotIn('activate_release "$previous_sha"', remote_release)
        self.assertIn("activate is only allowed for the initial release", remote_release)

        for name in ("release-manager.sh", "deploy-gateway.sh"):
            with self.subTest(name=name):
                self.assertNotIn("rollback", (SERVER_ROOT / name).read_text(encoding="utf-8"))

    def test_presigned_upload_queries_are_excluded_from_access_logs(self) -> None:
        caddy = CADDY_CONFIG.read_text(encoding="utf-8")
        self.assertIn("@presigned_upload_path path /object-storage/*", caddy)
        self.assertIn("log_skip @presigned_upload_path", caddy)


if __name__ == "__main__":
    unittest.main()
