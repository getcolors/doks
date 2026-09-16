(ns io.github.getcolors.doks.workflow-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [green.cli :as green-cli]
            [green.workflow :as wf]
            [io.github.getcolors.compute-managed :as managed]
            [io.github.getcolors.doks.operator :as operator]
            [io.github.getcolors.doks.tools :as tools]
            [io.github.getcolors.doks.workflow :as sut]))

(def scratch
  "A private workdir for the runs below: the real workflow's backend advice
  renders, and nothing may land beside the fixtures."
  (str (fs/create-temp-dir {:prefix "doks-workflow-"})))

(defn fixture [name]
  (let [file (str "test/fixtures/" name ".yml")]
    (assoc (green-cli/read-state file (slurp file)) :green/state-file (str (fs/absolutize file)) :workdir scratch)))

(defn chain [event]
  (loop [step :doks/start acc []]
    (let [[_ next-step] (sut/wire-fn step {:green/event event})]
      (if next-step (recur next-step (conj acc next-step)) acc))))

(deftest graph-per-verb
  (is (= [:doks/infrastructure :doks/registry :doks/registry-link] (chain :build)))
  (is (= [:doks/infrastructure :doks/registry :doks/registry-link] (chain :create)))
  (is (= [:doks/load :doks/check-nodes :doks/check-registry] (chain :check)))
  (is (= [:doks/load :doks/kubeconfig] (chain :kubeconfig)))
  (is (= [:doks/registry-credentials] (chain :registry)))
  (testing "delete: unlink while the cluster exists, cluster before registry, local material last"
    (is (= [:doks/load :doks/registry-unlink :doks/infrastructure :doks/registry :doks/cleanup] (chain :delete)))))

(deftest every-side-effecting-step-is-dry-runnable
  (doseq [step (distinct (mapcat chain sut/events))]
    (is (some #{step} sut/side-effecting) (str step))))

(deftest cli-options-name-the-verbs
  (is (= [:build :create :check :kubeconfig :registry :delete] (:allowed-events sut/cli-options))))

(deftest start-validates
  (testing "both fixtures pass a build"
    (is (zero? (:green/exit (sut/start-step (assoc (fixture "digitalocean") :green/event :build) {}))))
    (is (zero? (:green/exit (sut/start-step (assoc (fixture "vultr") :green/event :build) {})))))
  (testing "a relative workdir is resolved against the desired-state file"
    (let [out (sut/start-step (assoc (fixture "digitalocean") :green/event :build :workdir ".colors") {})]
      (is (str/ends-with? (:workdir out) "/test/fixtures/.colors"))
      (is (= (managed/managed-kubeconfig-path out) (tools/kubeconfig-path out)))))
  (testing "every problem at exit 2"
    (let [out (sut/start-step (-> (fixture "digitalocean") (dissoc :profile) (assoc :green/event :build :compute-prevent-destroy "x")) {})]
      (is (= 2 (:green/exit out)))
      (is (str/includes? (:green/err out) ":profile is required"))
      (is (str/includes? (:green/err out) ":compute-prevent-destroy must be true or false"))))
  (testing "the profile overlay is refused"
    (let [out (sut/start-step (assoc (fixture "digitalocean") :green/event :build) {"COLORS_PAR_PROFILE" "other"})]
      (is (= 2 (:green/exit out)))
      (is (str/includes? (:green/err out) "COLORS_PAR_PROFILE"))))
  (testing "real events need their credentials; dry runs and builds do not"
    (is (= 2 (:green/exit (sut/start-step (assoc (fixture "digitalocean") :green/event :create) {}))))
    (is (zero? (:green/exit (sut/start-step (assoc (fixture "digitalocean") :green/event :create :green/dry-run true) {}))))
    (let [out (sut/start-step (assoc (fixture "digitalocean") :green/event :create)
                              {"COLORS_PAR_DO_TOKEN" "t" "COLORS_PAR_R2_ACCESS_KEY_ID" "a" "COLORS_PAR_R2_SECRET_ACCESS_KEY" "s"})]
      (is (zero? (:green/exit out)))
      (is (= "t" (:do-token out)))))
  (testing "the registry verb needs a configured registry"
    (let [out (sut/start-step (assoc (fixture "vultr") :green/event :registry) {"COLORS_PAR_DO_TOKEN" "t"})]
      (is (= 2 (:green/exit out)))
      (is (str/includes? (:green/err out) ":digitalocean-registry-tier is not set")))))

(deftest delete-guard
  (let [env {"COLORS_PAR_DO_TOKEN" "t" "COLORS_PAR_R2_ACCESS_KEY_ID" "a" "COLORS_PAR_R2_SECRET_ACCESS_KEY" "s"}]
    (testing "refused while the committed flag stands, rehearsal included"
      (doseq [dry [nil true]]
        (let [out (sut/start-step (cond-> (assoc (fixture "digitalocean") :green/event :delete) dry (assoc :green/dry-run true)) env)]
          (is (= 2 (:green/exit out)))
          (is (str/includes? (:green/err out) "COLORS_PAR_COMPUTE_PREVENT_DESTROY=false")))))
    (testing "lifted for one run through the overlay, coerced to a boolean"
      (let [out (sut/start-step (assoc (fixture "digitalocean") :green/event :delete)
                                (assoc env "COLORS_PAR_COMPUTE_PREVENT_DESTROY" "false"))]
        (is (zero? (:green/exit out)))
        (is (false? (:compute-prevent-destroy out)))))))

(defn run-delete [opts]
  (wf/run sut/workflow (assoc opts :green/event :delete :compute-prevent-destroy false
                              :do-token "t" :r2-access-key-id "a" :r2-secret-access-key "s")))

(deftest delete-ordering-and-stops
  (testing "a failed state read stops before any mutation"
    (let [calls (atom [])]
      (with-redefs [tools/load-infrastructure-step (fn [o] (assoc o :green/exit 1 :green/err "unreadable"))
                    tools/registry-unlink-step (fn [o] (swap! calls conj :unlink) o)
                    tools/infrastructure-step (fn [o] (swap! calls conj :cluster) o)
                    tools/registry-step (fn [o] (swap! calls conj :registry) o)]
        (is (= 1 (:green/exit (run-delete (fixture "digitalocean")))))
        (is (= [] @calls)))))
  (testing "a failed unlink stops before the cluster is destroyed"
    (let [calls (atom [])]
      (with-redefs [tools/load-infrastructure-step (fn [o] (assoc o :green/exit 0 :doks/cluster {:cluster_id "c1"}))
                    tools/registry-unlink-step (fn [o] (swap! calls conj :unlink) (assoc o :green/exit 1 :green/err "api down"))
                    tools/infrastructure-step (fn [o] (swap! calls conj :cluster) o)
                    tools/registry-step (fn [o] (swap! calls conj :registry) o)]
        (is (= 1 (:green/exit (run-delete (fixture "digitalocean")))))
        (is (= [:unlink] @calls)))))
  (testing "an absent cluster still destroys the registry stage and cleans up"
    (let [calls (atom [])]
      (with-redefs [managed/read-managed-kubernetes (fn [& _] {:status "destroyed"})
                    managed/managed-kubernetes (fn [& _] (throw (Exception. "must not destroy an absent cluster")))
                    tools/unlink-registry! (fn [& _] (throw (Exception. "must not unlink an absent cluster")))
                    tools/registry-step (fn [o] (swap! calls conj :registry) (assoc o :green/exit 0))
                    tools/cleanup-step (fn [o] (swap! calls conj :cleanup) (assoc o :green/exit 0))]
        (is (zero? (:green/exit (run-delete (fixture "digitalocean")))))
        (is (= [:registry :cleanup] @calls)))))
  (testing "the full order on a present cluster"
    (let [calls (atom [])]
      (with-redefs [tools/load-infrastructure-step (fn [o] (swap! calls conj :load) (assoc o :green/exit 0 :doks/cluster {:cluster_id "c1"}))
                    tools/unlink-registry! (fn [_ id] (swap! calls conj [:unlink id]))
                    managed/managed-kubernetes (fn [& _] (swap! calls conj :destroy) {:status "destroyed"})
                    tools/registry-step (fn [o] (swap! calls conj :registry) (assoc o :green/exit 0))
                    tools/cleanup-step (fn [o] (swap! calls conj :cleanup) (assoc o :green/exit 0))]
        (is (zero? (:green/exit (run-delete (fixture "digitalocean")))))
        (is (= [:load [:unlink "c1"] :destroy :registry :cleanup] @calls))))))

(deftest create-links-only-after-both-stages
  (let [calls (atom [])]
    (with-redefs [managed/managed-kubernetes (fn [& _] (swap! calls conj :cluster) {:status "ready" :params {:cluster_id "c1" :name "doks-fixture"} :kubeconfig_path "/k"})
                  tools/registry-step (fn [o] (swap! calls conj :registry) (assoc o :green/exit 0))
                  tools/link-registry! (fn [_ id] (swap! calls conj [:link id]))
                  tools/registry-integrated? (fn [_ _] true)]
      (let [out (wf/run sut/workflow (assoc (fixture "digitalocean") :green/event :create :do-token "t" :r2-access-key-id "a" :r2-secret-access-key "s"))]
        (is (zero? (:green/exit out)))
        (is (= [:cluster :registry [:link "c1"]] @calls))
        (is (= "/k" (:doks/kubeconfig-path out))))))
  (testing "a refused cluster converge stops the run"
    (let [calls (atom [])]
      (with-redefs [managed/managed-kubernetes (fn [& _] {:status "error"})
                    tools/registry-step (fn [o] (swap! calls conj :registry) o)]
        (let [out (wf/run sut/workflow (assoc (fixture "digitalocean") :green/event :create :do-token "t" :r2-access-key-id "a" :r2-secret-access-key "s"))]
          (is (= 1 (:green/exit out)))
          (is (str/includes? (:green/err out) "managed compute lifecycle refused"))
          (is (= [] @calls)))))))

(deftest check-runs-state-nodes-registry
  (with-redefs [managed/read-managed-kubernetes (fn [& _] {:status "present" :params {:cluster_id "c1" :name "doks-fixture"} :kubeconfig_path "/k"})
                operator/kubectl-nodes (fn [_] {:exit 0 :out "{\"items\":[{\"metadata\":{\"name\":\"a\"},\"status\":{\"conditions\":[{\"type\":\"Ready\",\"status\":\"True\"}],\"addresses\":[{\"type\":\"ExternalIP\",\"address\":\"203.0.113.1\"}]}}]}" :err ""})
                tools/account-registries (fn [_] ["doks-fixture"])
                tools/registry-integrated? (fn [_ id] (= "c1" id))]
    (let [out (wf/run sut/workflow (assoc (fixture "digitalocean") :green/event :check :do-token "t" :r2-access-key-id "a" :r2-secret-access-key "s"))]
      (is (zero? (:green/exit out)))
      (is (= [{:name "a" :ip "203.0.113.1" :ready? true}] (:doks/nodes out))))))
