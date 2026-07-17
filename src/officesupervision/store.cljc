(ns officesupervision.store
  "SSoT for the ISCO-08 3341 independent office supervision practice
  actor (itonami actor pattern, ADR-2607011000 / CLAUDE.md Actors
  section; README's 'Robotics premise' — an office-coordination robot
  performs task-record filing, shift-board updates and supply-closet
  restocking under this advisor/governor pair, which never dispatches
  hardware itself and never lets a proposal finalize a disciplinary
  action, termination or performance-review determination). Modeled
  on cloud-itonami-isco-3313's accountingsupport.store.

  Domain:

    office        — a registered office/site (:office-id, :name).
    staff-member   — a registered clerical staff member reporting to
                     this office ({:staff-id :office-id :name}).
    workflow       — a registered workflow/task-board owned by this
                     office ({:workflow-id :office-id :name
                     :max-supply-cost number}). `:max-supply-cost` is
                     the registered ceiling a proposed
                     :coordinate-supply-order's :cost is compared
                     against — an order above it always escalates to
                     human sign-off rather than auto-committing.
    record         — a committed operating record (a logged workflow
                     entry, a schedule entry, a flagged concern, or a
                     supply order) — written ONLY via commit-record!.
    ledger         — append-only audit trail, commit or hold."
  )

(defprotocol Store
  (office [s office-id])
  (staff-member [s staff-id])
  (workflow [s workflow-id])
  (records-of [s office-id])
  (ledger [s])
  (register-office! [s o])
  (register-staff-member! [s sm])
  (register-workflow! [s w])
  (commit-record! [s record])
  (append-ledger! [s fact]))

(defrecord MemStore [a]
  Store
  (office [_ office-id] (get-in @a [:offices office-id]))
  (staff-member [_ staff-id] (get-in @a [:staff-members staff-id]))
  (workflow [_ workflow-id] (get-in @a [:workflows workflow-id]))
  (records-of [_ office-id] (filter #(= office-id (:office-id %)) (:records @a)))
  (ledger [_] (:ledger @a))
  (register-office! [s o]
    (swap! a assoc-in [:offices (:office-id o)] o) s)
  (register-staff-member! [s sm]
    (swap! a assoc-in [:staff-members (:staff-id sm)] sm) s)
  (register-workflow! [s w]
    (swap! a assoc-in [:workflows (:workflow-id w)] w) s)
  (commit-record! [s record]
    (swap! a update :records (fnil conj []) record) s)
  (append-ledger! [s fact]
    (swap! a update :ledger (fnil conj []) fact) s))

(defn mem-store
  ([] (mem-store {}))
  ([seed] (->MemStore (atom (merge {:offices {} :staff-members {} :workflows {}
                                    :records [] :ledger []}
                                   seed)))))
