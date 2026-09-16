(ns io.github.getcolors.doks.operator-test
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [green.cli :as green-cli]
            [io.github.getcolors.doks.operator :as sut]
            [io.github.getcolors.doks.tools :as tools]))

(defn fixture []
  (assoc (green-cli/read-state "test/fixtures/digitalocean.yml" (slurp "test/fixtures/digitalocean.yml"))
         :green/state-file (str (fs/absolutize "test/fixtures/digitalocean.yml"))
         :do-token "t"))

(defn node [name ready ip]
  {:metadata {:name name}
   :status {:conditions [{:type "MemoryPressure" :status "False"} {:type "Ready" :status ready}]
            :addresses (cond-> [{:type "InternalIP" :address "10.0.0.1"} {:type "Hostname" :address name}]
                         ip (conj {:type "ExternalIP" :address ip}))}})

(deftest node-readiness
  (is (sut/node-ready? (node "a" "True" "203.0.113.1")))
  (is (not (sut/node-ready? (node "a" "False" nil))))
  (is (not (sut/node-ready? (node "a" "Unknown" nil))))
  (is (not (sut/node-ready? {:metadata {:name "bare"}})))
  (is (= {:name "a" :ip "203.0.113.1" :ready? true} (sut/node-summary (node "a" "True" "203.0.113.1"))))
  (is (nil? (:ip (sut/node-summary (node "a" "True" nil))))))

(deftest node-report-lists-every-problem
  (is (= [] (:errors (sut/node-report {:items [(node "a" "True" "1.1.1.1") (node "b" "True" "1.1.1.2")]}))))
  (is (= ["the cluster reports no nodes"] (:errors (sut/node-report {:items []}))))
  (is (= ["node a is not Ready" "node c is not Ready"]
         (:errors (sut/node-report {:items [(node "a" "False" nil) (node "b" "True" "1.1.1.2") (node "c" "Unknown" nil)]})))))

(deftest check-nodes-step-runs-kubectl-against-the-kubeconfig
  (let [seen (atom nil)
        opts (assoc (fixture) :doks/cluster {:name "doks-fixture" :cluster_id "c1"} :doks/kubeconfig-path "/k")]
    (with-redefs [sut/kubectl-nodes (fn [kc] (reset! seen kc) {:exit 0 :out (json/generate-string {:items [(node "a" "True" "203.0.113.1")]}) :err ""})]
      (let [out (with-out-str (is (zero? (:green/exit (sut/check-nodes-step opts)))))]
        (is (= "/k" @seen))
        (is (str/includes? out "cluster doks-fixture c1"))
        (is (str/includes? out "node a 203.0.113.1 Ready"))))
    (with-redefs [sut/kubectl-nodes (fn [_] {:exit 0 :out (json/generate-string {:items [(node "a" "False" nil)]}) :err ""})]
      (let [r (with-out-str (sut/check-nodes-step opts))]
        (is (str/includes? r "node a - NotReady")))
      (is (= "node a is not Ready" (:green/err (sut/check-nodes-step opts)))))
    (with-redefs [sut/kubectl-nodes (fn [_] {:exit 1 :out "" :err "connection refused"})]
      (let [r (sut/check-nodes-step opts)]
        (is (= 1 (:green/exit r)))
        (is (= "kubectl get nodes failed: connection refused" (:green/err r)))))))

(deftest check-registry-step
  (let [opts (assoc (fixture) :doks/cluster {:cluster_id "c1"})]
    (with-redefs [tools/account-registries (fn [_] ["doks-fixture"]) tools/registry-integrated? (fn [_ _] true)]
      (is (zero? (:green/exit (sut/check-registry-step opts)))))
    (with-redefs [tools/account-registries (fn [_] ["other"]) tools/registry-integrated? (fn [_ _] true)]
      (is (= "registry doks-fixture does not exist in this account" (:green/err (sut/check-registry-step opts)))))
    (with-redefs [tools/account-registries (fn [_] ["doks-fixture"]) tools/registry-integrated? (fn [_ _] false)]
      (is (= "registry doks-fixture is not integrated with cluster c1" (:green/err (sut/check-registry-step opts)))))
    (with-redefs [tools/account-registries (fn [_] nil)]
      (is (= 1 (:green/exit (sut/check-registry-step opts)))))
    (testing "no registry configured: nothing to check"
      (with-redefs [tools/account-registries (fn [_] (throw (Exception. "must not call")))]
        (is (zero? (:green/exit (sut/check-registry-step (dissoc opts :digitalocean-registry-tier)))))))))

(deftest registry-credentials-step-writes-privately-and-prints-no-secret
  (let [dir (str (fs/create-temp-dir {:prefix "doks-op-"}))
        opts (assoc (fixture) :workdir dir)
        body "{\"auths\":{\"registry.digitalocean.com\":{\"auth\":\"c2VjcmV0\"}}}"]
    (with-redefs [tools/docker-credentials (fn [_] body)]
      (let [out (with-out-str (is (zero? (:green/exit (sut/registry-credentials-step opts)))))]
        (is (str/includes? out "registry registry.digitalocean.com/doks-fixture"))
        (is (str/includes? out (tools/push-config-path opts)))
        (is (not (str/includes? out "c2VjcmV0")))
        (is (= body (slurp (tools/push-config-path opts))))
        (is (= "rw-------" (fs/posix->str (fs/posix-file-permissions (tools/push-config-path opts)))))))
    (with-redefs [tools/docker-credentials (fn [_] (throw (ex-info "registry credential request failed: HTTP 401" {})))]
      (is (= "registry credential request failed: HTTP 401" (:green/err (sut/registry-credentials-step opts)))))))

(deftest kubeconfig-step-reports-the-path
  (let [dir (str (fs/create-temp-dir {:prefix "doks-kc-"})) opts (assoc (fixture) :workdir dir)]
    (is (= 1 (:green/exit (sut/kubeconfig-step opts))))
    (tools/write-private! (tools/kubeconfig-path opts) "kc")
    (let [out (with-out-str (is (zero? (:green/exit (sut/kubeconfig-step opts)))))]
      (is (str/includes? out (tools/kubeconfig-path opts))))))
