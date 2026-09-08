(ns officesupervision.governor
  "OfficeSupervisionGovernor — the independent safety/traceability
  layer named in this repository's README/business-model.md, gating
  every office-coordination action an advisor may propose. The
  governor never dispatches hardware itself and never lets a proposal
  finalize a disciplinary action, termination or performance-review
  determination — those decisions stay with a human, permanently and
  unconditionally. Modeled on cloud-itonami-isco-3313's
  accountingsupport.governor. Task twist: a proposal must cite a
  REGISTERED workflow or staff-member (depending on `:op`) belonging
  to the requesting office, a supply order's cost is an arithmetic
  ceiling against the workflow's registered `:max-supply-cost`, and
  the op itself must be a member of a CLOSED allowlist — every op
  outside it (in particular anything that finalizes a disciplinary
  action, termination or performance-review determination) is a hard,
  permanent, non-overridable block.

  HARD invariants (:hard? true, ALWAYS :hold, never overridable):
    1. office provenance     — the requesting office must be
                                registered.
    2. no-actuation           — proposal :effect must be :propose
                                (the governor never dispatches
                                hardware and never commits an
                                operating record itself; it only
                                gates what the advisor may propose).
    3. closed op-allowlist    — `:op` must be one of
                                #{:log-workflow-record
                                  :schedule-staff-operation
                                  :flag-hr-concern
                                  :coordinate-supply-order}. Nothing
                                that finalizes a disciplinary action,
                                termination or performance-review
                                determination is ever a member of this
                                set — such an op is unconditionally
                                :hard?.
    4. workflow/staff basis   — a `:log-workflow-record` or
                                `:coordinate-supply-order` proposal
                                must cite a REGISTERED workflow
                                belonging to this office; a
                                `:schedule-staff-operation` or
                                `:flag-hr-concern` proposal must cite
                                a REGISTERED staff member belonging to
                                this office.
    5. finalization-language  — a proposal whose rationale describes
                                actually TAKING a finalizing action
                                (\"finalize the termination\",
                                \"finalize the disciplinary action\",
                                \"finalize the performance review
                                determination\", etc. — the ACTION
                                phrase, not the bare topic noun) is a
                                hard, permanent block regardless of
                                which `:op` it is filed under. This is
                                defense-in-depth on top of invariant 3
                                and is deliberately phrased as the
                                finalize-ACTION, not the bare noun, so
                                it does not self-trip on a
                                `:flag-hr-concern` proposal's own
                                default rationale merely discussing a
                                disciplinary/termination/performance-
                                review topic — see
                                `officesupervision.advisor`'s default
                                rationale and this namespace's
                                `never-self-trips-on-default-mock-
                                advisor-proposals` test.
  ESCALATION invariants (:escalate? true, ALWAYS human sign-off per
  business-model.md's Trust Controls — these are :high/
  :safety-critical regardless of confidence):
    6. :op :flag-hr-concern (surfacing a concern is never
                                auto-commit-eligible; a human always
                                reviews it).
    7. supply-order cost ceiling (a `:coordinate-supply-order` whose
                                :cost exceeds the workflow's
                                registered :max-supply-cost always
                                requires human sign-off, rather than
                                being refused outright — it may be a
                                legitimate one-off purchase).
    8. low confidence (< `confidence-floor`)."
  (:require [kotoba.lang.text :as str]
            [officesupervision.store :as store]))

(def confidence-floor 0.6)

(def ^:private allowed-ops
  #{:log-workflow-record :schedule-staff-operation :flag-hr-concern
    :coordinate-supply-order})

(def ^:private always-escalate-ops #{:flag-hr-concern})

(def ^:private workflow-basis-ops #{:log-workflow-record :coordinate-supply-order})
(def ^:private staff-basis-ops #{:schedule-staff-operation :flag-hr-concern})

;; Phrased as the finalize-ACTION (verb + object), never the bare topic
;; noun, so a legitimate proposal that merely MENTIONS "disciplinary",
;; "termination" or "performance review" (e.g. :flag-hr-concern's own
;; default rationale, which explicitly disclaims finalizing any of
;; these) can never self-trip this check.
(def ^:private finalization-phrases
  ["finalize the disciplinary action"
   "finalize a disciplinary action"
   "finalize disciplinary action"
   "finalize the termination"
   "finalize a termination"
   "finalize termination"
   "terminate the employee"
   "terminate this employee"
   "issue the termination"
   "execute the termination"
   "finalize the performance review determination"
   "finalize the performance-review determination"
   "finalize a performance review determination"
   "finalize a performance-review determination"
   "finalize performance review determination"
   "finalize performance-review determination"
   "finalize the performance review"
   "finalize the performance-review"])

(defn- finalization-language?
  "True when `proposal`'s rationale describes actually TAKING a
  finalizing action on a disciplinary/termination/performance-review
  matter, never merely mentioning the topic."
  [proposal]
  (let [text (str/lower (str (:rationale proposal)))]
    (boolean (some #(str/includes? text %) finalization-phrases))))

(defn- hard-violations [{:keys [request proposal]} office-record basis-record]
  (let [{:keys [op]} proposal
        allowed? (contains? allowed-ops op)
        needs-workflow? (contains? workflow-basis-ops op)
        needs-staff? (contains? staff-basis-ops op)]
    (cond-> []
      (nil? office-record)
      (conj {:rule :no-office :detail "未登録 office"})

      (not= :propose (:effect proposal))
      (conj {:rule :no-actuation :detail "effect は :propose のみ許可（governor は直接実行しない）"})

      (not allowed?)
      (conj {:rule :op-not-allowed
             :detail (str "op " (pr-str op) " は closed allowlist に含まれない"
                          "（懲戒処分・解雇・人事考課の最終決定は常に人間の専管事項であり、"
                          "このアクターの allowlist に含めない）")})

      (and allowed? needs-workflow? (nil? basis-record))
      (conj {:rule :unknown-workflow :detail "未登録 workflow への提案は不可"})

      (and allowed? needs-workflow? basis-record
           (not= (:office-id basis-record) (:office-id request)))
      (conj {:rule :workflow-wrong-office :detail "workflow が別 office のもの"})

      (and allowed? needs-staff? (nil? basis-record))
      (conj {:rule :unknown-staff-member :detail "未登録 staff member への提案は不可"})

      (and allowed? needs-staff? basis-record
           (not= (:office-id basis-record) (:office-id request)))
      (conj {:rule :staff-wrong-office :detail "staff member が別 office のもの"})

      (finalization-language? proposal)
      (conj {:rule :finalization-language-blocked
             :detail (str "懲戒処分・解雇・人事考課の最終決定を実行する提案は、"
                          "op の種類によらず常に恒久的にブロックされる"
                          "（人間の専管事項であり、上書き不可）")}))))

(defn check
  "Assess a proposal against `request`/`context`/`proposal` and a
  `store` implementing `officesupervision.store/Store`. Pure — never
  mutates the store, never commits an operating record itself."
  [request context proposal store]
  (let [office-record (store/office store (:office-id request))
        op (:op proposal)
        needs-workflow? (contains? workflow-basis-ops op)
        needs-staff? (contains? staff-basis-ops op)
        basis-record (cond
                       needs-workflow? (some->> (:workflow-id proposal) (store/workflow store))
                       needs-staff? (some->> (:staff-id proposal) (store/staff-member store))
                       :else nil)
        hard (hard-violations {:request request :proposal proposal}
                              office-record basis-record)
        hard? (boolean (seq hard))
        conf (or (:confidence proposal) 0.0)
        low? (< conf confidence-floor)
        always-risky? (contains? always-escalate-ops op)
        over-cost? (and (= :coordinate-supply-order op)
                        basis-record
                        (number? (:cost proposal))
                        (> (:cost proposal) (:max-supply-cost basis-record)))]
    {:ok? (and (not hard?) (not low?) (not always-risky?) (not over-cost?))
     :violations hard
     :confidence conf
     :hard? hard?
     :escalate? (and (not hard?) (or low? always-risky? over-cost?))}))
