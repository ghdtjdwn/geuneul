# Security Policy

## Supported versions

Security fixes target the latest `main` revision and the currently deployed revision only. Older branches, tags, forks, and local modifications are not supported.

## Reporting a vulnerability

Use this repository's Security tab and select **Report a vulnerability** to submit a private report. Do not open a public issue for an unpatched vulnerability.

Include the affected revision, impact, minimal reproduction steps, and a remediation suggestion when available. Do not include real user data, JWT or OAuth tokens, S3 keys or objects, precise GPS history, private UGC, credentials, or other personal information. Use synthetic identifiers and redact secrets.

In scope:

- authentication, OAuth account linking, authorization, and session revocation;
- photo claim ownership, upload validation, and cross-user data access;
- rate-limit bypasses, SSRF, and unintended outbound requests;
- deployment manifests and infrastructure configuration maintained in this repository.

Out of scope:

- testing Kakao, Naver, AWS, Vercel, or another third party's own systems;
- denial-of-service activity, social engineering, physical attacks, or destructive testing;
- findings that require exposing real user data or secrets.

Please avoid privacy impact, service disruption, data modification, and persistence while validating a report.
