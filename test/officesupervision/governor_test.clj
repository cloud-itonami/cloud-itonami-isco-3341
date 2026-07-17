(ns officesupervision.governor-test
  (:require [clojure.test :refer [deftest is testing]]
            [officesupervision.store :as store]
            [officesupervision.advisor :as advisor]
            [officesupervision.governor :as governor]))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-office! st {:office-id "office-1" :name "HQ Office"})
    (store/register-staff-member! st {:staff-id "S-1" :office-id "office-1" :name "Aya Tanaka"})
    (store/register-workflow! st {:workflow-id "WF-1" :office-id "office-1"
                                  :name "front-desk-tasks" :max-supply-cost 500})
    st))

(def ^:private req {:office-id "office-1"})

(defn- log-op [workflow-id]
  {:op :log-workflow-record :effect :propose :workflow-id workflow-id
   :task-id "T-1" :status :completed :confidence 0.9 :stake :low
   :rationale "proposed logging a completed task record"})

(defn- schedule-op [staff-id]
  {:op :schedule-staff-operation :effect :propose :staff-id staff-id
   :shift-start "09:00" :shift-end "17:00" :confidence 0.9 :stake :low
   :rationale "proposed a shift scheduling assignment"})

(defn- flag-op [staff-id concern-category]
  {:op :flag-hr-concern :effect :propose :staff-id staff-id
   :concern-category concern-category :confidence 0.9 :stake :low
   :rationale "flags a concern for human HR review; never finalizes a disciplinary action, a termination, or a performance-review determination"})

(defn- supply-op [workflow-id cost]
  {:op :coordinate-supply-order :effect :propose :workflow-id workflow-id
   :item "printer paper" :cost cost :confidence 0.9 :stake :low
   :rationale "proposed a supply order"})

(deftest ok-log-workflow-record-on-registered-workflow
  (let [st (fresh-store)
        v (governor/check req {} (log-op "WF-1") st)]
    (is (:ok? v))
    (is (not (:hard? v)))
    (is (not (:escalate? v)))))

(deftest ok-schedule-staff-operation-for-registered-staff
  (let [st (fresh-store)
        v (governor/check req {} (schedule-op "S-1") st)]
    (is (:ok? v))))

(deftest hard-on-unregistered-office
  (let [st (fresh-store)
        v (governor/check {:office-id "ghost-office"} {} (log-op "WF-1") st)]
    (is (:hard? v))
    (is (some #(= :no-office (:rule %)) (:violations v)))))

(deftest hard-on-no-actuation-violation
  (let [st (fresh-store)
        v (governor/check req {} (assoc (log-op "WF-1") :effect :direct-write) st)]
    (is (:hard? v))
    (is (some #(= :no-actuation (:rule %)) (:violations v)))))

(deftest hard-on-op-not-in-closed-allowlist
  (testing "an op that finalizes a disciplinary action, termination or
            performance-review determination is never a member of the
            closed allowlist -- it is unconditionally hard-blocked"
    (let [st (fresh-store)]
      (doseq [op [:finalize-disciplinary-action :finalize-termination
                  :finalize-performance-review-determination
                  :terminate-employee :approve-termination]]
        (let [v (governor/check req {} {:op op :effect :propose :workflow-id "WF-1"
                                        :confidence 0.99 :stake :low :rationale "n/a"} st)]
          (is (:hard? v) (str op " should hard-block"))
          (is (some #(= :op-not-allowed (:rule %)) (:violations v))
              (str op " should be flagged :op-not-allowed")))))))

(deftest hard-on-unknown-workflow
  (let [st (fresh-store)
        v (governor/check req {} (log-op "WF-ghost") st)]
    (is (:hard? v))
    (is (some #(= :unknown-workflow (:rule %)) (:violations v)))))

(deftest hard-on-workflow-wrong-office
  (let [st (fresh-store)]
    (store/register-office! st {:office-id "office-2" :name "Branch Office"})
    (store/register-workflow! st {:workflow-id "WF-2" :office-id "office-2"
                                  :name "branch-tasks" :max-supply-cost 300})
    (let [v (governor/check req {} (log-op "WF-2") st)]
      (is (:hard? v))
      (is (some #(= :workflow-wrong-office (:rule %)) (:violations v))))))

(deftest hard-on-unknown-staff-member
  (let [st (fresh-store)
        v (governor/check req {} (schedule-op "S-ghost") st)]
    (is (:hard? v))
    (is (some #(= :unknown-staff-member (:rule %)) (:violations v)))))

(deftest hard-on-staff-wrong-office
  (let [st (fresh-store)]
    (store/register-office! st {:office-id "office-2" :name "Branch Office"})
    (store/register-staff-member! st {:staff-id "S-2" :office-id "office-2" :name "Other Person"})
    (let [v (governor/check req {} (schedule-op "S-2") st)]
      (is (:hard? v))
      (is (some #(= :staff-wrong-office (:rule %)) (:violations v))))))

(deftest hard-on-finalization-language-regardless-of-op
  (testing "a proposal whose rationale describes actually TAKING a
            finalizing action is a hard, permanent block no matter
            which op it is nominally filed under -- this is the
            defense-in-depth check on top of the closed allowlist"
    (let [st (fresh-store)]
      (doseq [rationale ["let's finalize the termination of this employee"
                         "recommend we finalize the disciplinary action today"
                         "ready to finalize the performance review determination"]]
        (let [v (governor/check req {} (assoc (log-op "WF-1") :confidence 0.99 :rationale rationale) st)]
          (is (:hard? v) rationale)
          (is (some #(= :finalization-language-blocked (:rule %)) (:violations v)) rationale))))))

(deftest always-escalates-flag-hr-concern-even-at-high-confidence
  (testing "surfacing a concern is never auto-commit-eligible"
    (let [st (fresh-store)
          v (governor/check req {} (assoc (flag-op "S-1" :conduct) :confidence 0.99) st)]
      (is (not (:hard? v)))
      (is (:escalate? v))
      (is (not (:ok? v))))))

(deftest ok-at-exact-supply-cost-ceiling-boundary
  (testing "the supply-cost ceiling is inclusive"
    (let [st (fresh-store)
          v (governor/check req {} (supply-op "WF-1" 500) st)]
      (is (:ok? v)))))

(deftest escalates-over-supply-cost-ceiling-even-at-high-confidence
  (testing "a supply order above the workflow's registered cost ceiling always requires human sign-off"
    (let [st (fresh-store)
          v (governor/check req {} (assoc (supply-op "WF-1" 5000) :confidence 0.99) st)]
      (is (not (:hard? v)))
      (is (:escalate? v)))))

(deftest escalates-low-confidence
  (let [st (fresh-store)
        v (governor/check req {} (assoc (log-op "WF-1") :confidence 0.3) st)]
    (is (not (:hard? v)))
    (is (:escalate? v))))

(deftest never-self-trips-on-default-mock-advisor-proposals
  (testing "the mock advisor's own default rationale for every allowed
            op -- including :flag-hr-concern, which necessarily
            discusses disciplinary/termination/performance-review
            topics -- never triggers the finalization-language hard
            block. Regression test for the known self-tripping bug
            pattern: scope-exclusion terms must be phrased as the
            finalize-ACTION, not the bare topic noun."
    (let [st (fresh-store)
          adv (advisor/mock-advisor)
          requests [{:office-id "office-1" :op :log-workflow-record :workflow-id "WF-1"
                     :task-id "T-1" :status :completed :stake :low}
                    {:office-id "office-1" :op :schedule-staff-operation :staff-id "S-1"
                     :shift-start "09:00" :shift-end "17:00" :stake :low}
                    {:office-id "office-1" :op :flag-hr-concern :staff-id "S-1"
                     :concern-category :disciplinary :stake :low}
                    {:office-id "office-1" :op :flag-hr-concern :staff-id "S-1"
                     :concern-category :performance :stake :low}
                    {:office-id "office-1" :op :flag-hr-concern :staff-id "S-1"
                     :concern-category :conduct :stake :low}
                    {:office-id "office-1" :op :coordinate-supply-order :workflow-id "WF-1"
                     :item "printer paper" :cost 100 :stake :low}]]
      (doseq [request requests]
        (let [proposal (advisor/-advise adv st request)
              v (governor/check request {} proposal st)]
          (is (not (:hard? v))
              (str request " -> proposal " proposal " unexpectedly hard-blocked: " (:violations v))))))))
