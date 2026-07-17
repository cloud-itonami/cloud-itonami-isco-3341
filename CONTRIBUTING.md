# Contributing

`cloud-itonami-isco-3341` accepts contributions to the OSS actor, policy
tests, documentation, examples and open occupation blueprint.

## Development

```bash
clojure -M:test
```

Keep changes small and include tests for policy, audit, store or
disclosure behavior.

## Rules

- Do not commit real staff data, credentials or operating documents.
- Keep production writes and disclosures behind Office Supervision
  Governor.
- Never add an op to the closed allowlist that finalizes a disciplinary
  action, termination or performance-review determination.
- Treat this occupation's workflows as high-risk: add tests for
  permission, purpose, safety and audit logging.
- Document any new business-model or operator assumption in `docs/`.

## Pull Requests

PRs should describe:

- what behavior changed
- which policy invariant is affected
- how it was tested
- whether operator or certification docs need updates
