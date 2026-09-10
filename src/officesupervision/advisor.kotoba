(ns officesupervision.advisor
  "Office Supervision Advisor — the advisor named in this repository's
  README, proposing an office-coordination operation (log a workflow
  record, schedule a staff operation, flag an HR concern, or
  coordinate a supply order) from a staff roster, a workflow/task
  board and a supply catalog. Swappable mock/llm; the advisor ONLY
  proposes — `officesupervision.governor` checks workflow/staff basis
  and the supply-cost ceiling independently and always escalates
  flagged HR concerns and over-ceiling supply orders. The advisor can
  never propose to finalize a disciplinary action, termination or
  performance-review determination: those ops are not members of the
  closed op-allowlist below, and the governor's finalization-language
  check hard-blocks any proposal whose rationale describes taking
  that action regardless of which `:op` it is filed under. Modeled on
  cloud-itonami-isco-3313's advisor.

  A proposal: {:op :log-workflow-record|:schedule-staff-operation|
               :flag-hr-concern|:coordinate-supply-order
               :effect :propose :office-id str :staff-id str
               :workflow-id str :cost number :stake kw :confidence n
               :rationale str, plus op-specific fields}

  NOTE: `:flag-hr-concern` only ever surfaces a concern for human HR
  review — it is never a vehicle for the advisor itself to conclude
  that a disciplinary action, termination or performance-review
  determination is warranted. Its default rationale below is
  deliberately phrased to describe the flagging action, not to
  narrate a finalization — see `officesupervision.governor`'s
  finalization-language check and the self-trip regression test in
  `officesupervision.governor-test` for why the phrasing matters."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])))

(defprotocol Advisor
  (-advise [advisor store request] "request -> proposal map"))

(defn- rationale-for [op {:keys [office-id staff-id workflow-id status
                                  concern-category cost item] :as _request}]
  (case op
    :log-workflow-record
    (str "proposed logging a " (name (or status :task)) " record on workflow "
         workflow-id " for office " office-id)

    :schedule-staff-operation
    (str "proposed a shift/task scheduling assignment for staff member "
         staff-id " in office " office-id)

    ;; Deliberately phrased so the topic words (disciplinary, termination,
    ;; performance-review) only ever appear as the OBJECT of "never
    ;; finalizes" — never as "finalize the <noun>" — so the governor's
    ;; finalization-language check (which matches the finalize-ACTION
    ;; phrase, not the bare noun) cannot self-trip on this default text.
    :flag-hr-concern
    (str "surfaces a possible " (name (or concern-category :conduct))
         " concern for staff member " staff-id
         " for human HR review; flagging only — this op never finalizes a"
         " disciplinary action, a termination, or a performance-review"
         " determination")

    :coordinate-supply-order
    (str "proposed a supply order (" item ", cost " cost ") on workflow "
         workflow-id " for office " office-id)

    (str "proposed " (name op) " for office " office-id)))

(defn- infer [_store {:keys [op stake office-id staff-id workflow-id task-id
                              status shift-start shift-end concern-category
                              cost item] :as request}]
  {:op op
   :effect :propose
   :office-id office-id
   :staff-id staff-id
   :workflow-id workflow-id
   :task-id task-id
   :status status
   :shift-start shift-start
   :shift-end shift-end
   :concern-category concern-category
   :cost cost
   :item item
   :stake (or stake :low)
   :confidence (case (or stake :low) :high 0.7 :medium 0.85 :low 0.95)
   :rationale (rationale-for op request)})

(defn mock-advisor []
  (reify Advisor
    (-advise [_ store request] (infer store request))))

(def ^:private system-prompt
  "You are an office-supervision advisor. Given a request, propose an
   :op from the closed allowlist (:log-workflow-record,
   :schedule-staff-operation, :flag-hr-concern,
   :coordinate-supply-order only), the relevant office-id/staff-id/
   workflow-id, an honest :confidence and a :stake. Never propose an
   op that finalizes a disciplinary action, a termination or a
   performance-review determination — those decisions are always made
   by a human, outside this actor's allowlist. :flag-hr-concern only
   surfaces a concern for human review and always requires human
   sign-off regardless of confidence; a supply order above the
   workflow's registered cost ceiling always requires human sign-off
   too.")

(defn- parse-proposal [content]
  (try
    (let [p (edn/read-string content)]
      (if (map? p)
        (assoc p :effect :propose)
        {:op :unknown :effect :propose :confidence 0.0 :stake :high
         :rationale "unparseable LLM response"}))
    (catch #?(:clj Exception :cljs js/Error) _
      {:op :unknown :effect :propose :confidence 0.0 :stake :high
       :rationale "LLM response parse failure"})))

(defn llm-advisor
  [chat-model model-generate-fn gen-opts]
  (reify Advisor
    (-advise [_ _store request]
      (let [msgs [{:role :system :content system-prompt}
                  {:role :user :content (str "operation request: " (pr-str request))}]
            resp (model-generate-fn chat-model msgs gen-opts)]
        (parse-proposal (:content resp))))))
