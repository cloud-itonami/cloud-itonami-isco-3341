# cloud-itonami-isco-3341

Open Occupation Blueprint for **ISCO-08 3341**: Office Supervisors.

This repository designs a forkable OSS business for an independent office
supervision practice: an office-coordination robot manages task-record
filing, shift-board updates and supply-closet restocking under a
governor-gated actor, so the practice keeps its own coordination records
instead of renting a closed workforce-management SaaS.

**Maturity: `:implemented`.** `src/officesupervision/` implements the
`OfficeSupervisionActor` as a `langgraph.graph/state-graph`
(`officesupervision.actor`) wired to an `Office Supervision Advisor`
(`officesupervision.advisor`) and an independent
`OfficeSupervisionGovernor` (`officesupervision.governor`), following the
itonami actor pattern (ADR-2607011000): `:intake -> :advise -> :govern ->
:decide -+-> :commit (:ok?) +-> :request-approval (:escalate?,
human-in-the-loop interrupt) +-> :hold (:hard?)`. 20 tests / 61 assertions
green (`kbb -M:test`).

HARD invariants (always hold, never overridable): office provenance (the
requesting office must be registered), no-actuation (`:effect` must be
`:propose`), a closed op-allowlist (`:log-workflow-record`,
`:schedule-staff-operation`, `:flag-hr-concern`,
`:coordinate-supply-order` — nothing else, ever), a registered
workflow/staff-member basis belonging to the requesting office for any
proposal that cites one, and a finalization-language check: **any
proposal that describes actually finalizing a disciplinary action,
termination or performance-review determination is a hard, permanent
block, regardless of which `:op` it is nominally filed under.** This
actor never has authority to conclude a disciplinary matter itself —
`:flag-hr-concern` only ever surfaces a concern for a human to decide.

Always-escalate (human sign-off regardless of confidence, mapping this
repo's Trust Controls in [`docs/business-model.md`](docs/business-model.md)):
`:flag-hr-concern` (every single time — never auto-commit-eligible) and a
`:coordinate-supply-order` whose cost exceeds the workflow's registered
`:max-supply-cost` ceiling.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
performs the physical domain work**. Here an office-coordination robot
performs task-record filing, shift-board updates and supply-closet
restocking under an actor that proposes actions and an independent
**Office Supervision Governor** that gates them. The governor never
dispatches hardware itself; `:high`/`:safety-critical` actions (such as
flagging an HR concern or an over-ceiling supply order) require human
sign-off, and no action may ever finalize a disciplinary decision.

## Core Contract

```text
staff roster + workflow/task board + supply catalog
        |
        v
Office Supervision Advisor -> Office Supervision Governor -> log/schedule/order, or human sign-off
        |
        v
robot actions (gated) + operating records + audit ledger
```

No automated advice can dispatch a robot action the governor refuses,
finalize a disciplinary action, termination or performance-review
determination, or suppress an operating record.

## Capability layer

Resolves via [`kotoba-lang/occupation`](https://github.com/kotoba-lang/occupation)
(ISCO-08 `3341`). Required capabilities:

- :robotics
- :identity
- :audit-ledger

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md).

## License

AGPL-3.0-or-later.
