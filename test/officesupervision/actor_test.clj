(ns officesupervision.actor-test
  (:require [clojure.test :refer [deftest is testing]]
            [officesupervision.actor :as actor]
            [officesupervision.advisor :as advisor]
            [officesupervision.store :as store]))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-office! st {:office-id "office-1" :name "HQ Office"})
    (store/register-staff-member! st {:staff-id "S-1" :office-id "office-1" :name "Aya Tanaka"})
    (store/register-workflow! st {:workflow-id "WF-1" :office-id "office-1"
                                  :name "front-desk-tasks" :max-supply-cost 500})
    st))

(deftest commits-a-registered-workflow-log-record
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:office-id "office-1" :op :log-workflow-record :stake :low
                 :workflow-id "WF-1" :task-id "T-1" :status :completed}
        result (actor/run-request! graph request {} "thread-1")]
    (is (= :done (:status result)))
    (is (some? (get-in result [:state :record])))
    (is (= 1 (count (store/records-of st "office-1"))))))

(deftest holds-an-over-cost-supply-order-until-escalated
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:office-id "office-1" :op :coordinate-supply-order :stake :low
                 :workflow-id "WF-1" :item "standing desks" :cost 5000}
        interrupted (actor/run-request! graph request {} "thread-2")]
    (is (= :interrupted (:status interrupted)))
    (is (empty? (store/records-of st "office-1")))
    (let [resumed (actor/approve! graph "thread-2")]
      (is (= :done (:status resumed)))
      (is (= 1 (count (store/records-of st "office-1")))))))

(deftest interrupts-then-approves-flag-hr-concern-on-human-approval
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:office-id "office-1" :op :flag-hr-concern :staff-id "S-1"
                 :concern-category :conduct :stake :low}
        interrupted (actor/run-request! graph request {} "thread-3")]
    (is (= :interrupted (:status interrupted)))
    (is (empty? (store/records-of st "office-1")))
    (let [resumed (actor/approve! graph "thread-3")]
      (is (= :done (:status resumed)))
      (is (= 1 (count (store/records-of st "office-1")))))))

(deftest holds-a-finalization-language-proposal-regardless-of-nominal-op
  (testing "even though the advisor filed the proposal under a
            legitimate, allowlisted op (:log-workflow-record), a
            rationale that describes actually taking a finalizing
            action is a hard, permanent block through the full graph
            -- it never reaches :request-approval, it goes straight
            to :hold"
    (let [st (fresh-store)
          rogue-advisor (reify advisor/Advisor
                          (-advise [_ _store _request]
                            {:op :log-workflow-record :effect :propose
                             :workflow-id "WF-1" :confidence 0.99 :stake :low
                             :rationale "let's finalize the termination of this employee"}))
          graph (actor/build-graph {:store st :advisor rogue-advisor})
          request {:office-id "office-1" :op :log-workflow-record :stake :low :workflow-id "WF-1"}
          result (actor/run-request! graph request {} "thread-4")]
      (is (= :hold (:disposition (:state result))))
      (is (empty? (store/records-of st "office-1"))))))

(deftest holds-an-op-outside-the-closed-allowlist
  (testing "an op that is not a member of the closed allowlist (here,
            a direct disciplinary finalization) is a hard, permanent
            block -- it never reaches :request-approval either"
    (let [st (fresh-store)
          rogue-advisor (reify advisor/Advisor
                          (-advise [_ _store _request]
                            {:op :finalize-disciplinary-action :effect :propose
                             :staff-id "S-1" :confidence 0.99 :stake :low
                             :rationale "proceeding with disciplinary finalization"}))
          graph (actor/build-graph {:store st :advisor rogue-advisor})
          request {:office-id "office-1" :op :finalize-disciplinary-action :stake :low :staff-id "S-1"}
          result (actor/run-request! graph request {} "thread-5")]
      (is (= :hold (:disposition (:state result))))
      (is (empty? (store/records-of st "office-1"))))))
