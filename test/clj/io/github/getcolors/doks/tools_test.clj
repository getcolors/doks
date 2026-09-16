(ns io.github.getcolors.doks.tools-test
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [green.cli :as green-cli]
            [io.github.getcolors.compute-managed :as managed]
            [io.github.getcolors.doks.tools :as sut]
            [io.github.getcolors.doks.utils :as utils]))

(defn fixture [name]
  (let [file (str "test/fixtures/" name ".yml")]
    (assoc (green-cli/read-state file (slurp file)) :green/state-file (str (fs/absolutize file)) :workdir ".colors")))

(defn tmp-dir [] (str (fs/create-temp-dir {:prefix "doks-test-"})))

(deftest compute-request-declares-the-legacy-key
  (is (= {:legacy_state_keys ["doks-fixture/cluster.tfstate"]} (utils/compute-request (fixture "digitalocean")))))

(deftest paths-live-under-the-profile
  (let [opts (fixture "digitalocean")]
    (is (str/ends-with? (sut/kubeconfig-path opts) "/.colors/doks-fixture/kubeconfig"))
    (is (str/ends-with? (sut/compute-dir opts) "/.colors/doks-fixture/compute/managed-kubernetes"))
    (is (str/ends-with? (sut/tool-dir opts sut/registry-tool) "/.colors/doks-fixture/doks-registry"))
    (is (str/ends-with? (sut/push-config-path opts) "/.colors/doks-fixture/registry/push/config.json"))
    (is (= "doks-fixture/registry.tfstate" (sut/registry-state-key opts)))
    (testing "the library's kubeconfig sink and the package's path agree for an absolute workdir"
      (let [abs (assoc opts :workdir "/var/tmp/doks-work")]
        (is (= (managed/managed-kubeconfig-path abs) (sut/kubeconfig-path abs)))))))

(deftest plan-shapes-the-cluster-from-the-profile
  (doseq [[name resource] [["digitalocean" :digitalocean_kubernetes_cluster] ["vultr" :vultr_kubernetes]]]
    (let [plan (managed/plan-managed-kubernetes (fixture name) (utils/compute-request (fixture name)))
          document (walk/keywordize-keys (get-in plan [:documents "managed-kubernetes.tf.json"]))]
      (is (= "doks-fixture/compute/managed-kubernetes.tfstate" (:state_key plan)) name)
      (is (= "doks-fixture" (get-in plan [:params :name])) name)
      (is (true? (get-in document [:resource resource :cluster :lifecycle :prevent_destroy])) name)
      (is (= "doks-fixture/compute/managed-kubernetes.tfstate"
             (get-in plan [:documents "backend.tf.json" :terraform :backend :s3 :key])) name)))
  (testing "the override names the cluster, never the state"
    (let [plan (managed/plan-managed-kubernetes (assoc (fixture "digitalocean") :digitalocean-name "custom") {})]
      (is (= "custom" (get-in plan [:params :name])))
      (is (= "doks-fixture/compute/managed-kubernetes.tfstate" (:state_key plan))))))

(deftest registry-document
  (let [opts (fixture "digitalocean")
        doc (json/parse-string (sut/registry-json opts) true)
        registry (get-in doc [:resource :digitalocean_container_registry :registry])]
    (is (= "doks-fixture" (:name registry)))
    (is (= "basic" (:subscription_tier_slug registry)))
    (is (not (contains? registry :region)))
    (testing "destruction protection is bound to the guard flag"
      (is (true? (get-in registry [:lifecycle :prevent_destroy])))
      (is (false? (get-in (json/parse-string (sut/registry-json (assoc opts :compute-prevent-destroy false)) true)
                          [:resource :digitalocean_container_registry :registry :lifecycle :prevent_destroy]))))
    (testing "one provider version, the library's"
      (is (= "2.51.0" (get-in doc [:terraform :required_providers :digitalocean :version]))))
    (testing "outputs describe ownership without secrets"
      (is (= "container-registry" (get-in doc [:output :params :value :kind])))
      (is (= "doks-fixture" (get-in doc [:output :params :value :profile])))
      (is (not (str/includes? (sut/registry-json opts) "docker_credentials"))))
    (testing "deterministic"
      (is (= (sut/registry-json opts) (sut/registry-json opts))))))

(deftest registry-stage-is-conditional
  (testing "no registry: no render, no backend, no failure"
    (let [dir (tmp-dir) opts (assoc (fixture "vultr") :workdir dir :green/event :build)]
      (is (zero? (:green/exit (sut/registry-step opts))))
      (is (= opts (sut/registry-backend-advice opts)))
      (is (not (fs/exists? (sut/tool-dir opts sut/registry-tool))))))
  (testing "registry: build renders the document and the backend under the package key"
    (let [dir (tmp-dir) opts (assoc (fixture "digitalocean") :workdir dir :green/event :build)
          stage (sut/tool-dir opts sut/registry-tool)]
      (sut/registry-backend-advice opts)
      (is (zero? (:green/exit (sut/registry-step opts))))
      (is (fs/exists? (str stage "/registry.tf.json")))
      (let [backend (json/parse-string (slurp (str stage "/backend.tf.json")) true)]
        (is (= "doks-fixture/registry.tfstate" (get-in backend [:terraform :backend :s3 :key])))
        (is (= "doks-state" (get-in backend [:terraform :backend :s3 :bucket])))
        (is (true? (get-in backend [:terraform :backend :s3 :use_lockfile])))))))

(deftest credential-environment
  (let [opts (assoc (fixture "digitalocean") :do-token "T" :r2-access-key-id "A" :r2-secret-access-key "S")]
    (is (= {"DIGITALOCEAN_TOKEN" "T" "AWS_ACCESS_KEY_ID" "A" "AWS_SECRET_ACCESS_KEY" "S"}
           (sut/credential-env opts :provider-compute)))
    (is (nil? (sut/credential-env (fixture "digitalocean"))))))

(deftest compute-result-translation
  (let [opts {:green/event :create}]
    (is (= {:cluster_id "c1"} (:doks/cluster (sut/compute-result opts {:status "ready" :params {:cluster_id "c1"} :kubeconfig_path "/k"}))))
    (is (= "/k" (:doks/kubeconfig-path (sut/compute-result opts {:status "ready" :params {} :kubeconfig_path "/k"}))))
    (is (true? (:doks/cluster-absent (sut/compute-result opts {:status "destroyed"}))))
    (is (= 1 (:green/exit (sut/compute-result opts {:status "error"}))))
    (is (= "a\nb" (:green/err (sut/compute-result opts {:status "error" :errors ["a" "b"]}))))))

(deftest infrastructure-step-plans-and-renders
  (let [dir (tmp-dir) opts (assoc (fixture "digitalocean") :workdir dir :green/event :build)
        result (sut/infrastructure-step opts)]
    (is (zero? (:green/exit result)))
    (is (= "planned-cluster" (get-in result [:doks/cluster :cluster_id])))
    (is (fs/exists? (str (sut/compute-dir opts) "/managed-kubernetes.tf.json")))
    (is (fs/exists? (str (sut/compute-dir opts) "/backend.tf.json"))))
  (testing "a delete after an absent read is a no-op, never a library call"
    (with-redefs [managed/managed-kubernetes (fn [& _] (throw (Exception. "must not be called")))]
      (is (zero? (:green/exit (sut/infrastructure-step (assoc (fixture "digitalocean") :green/event :delete :doks/cluster-absent true))))))))

(deftest load-infrastructure-outcomes
  (let [opts (fixture "digitalocean")]
    (with-redefs [managed/read-managed-kubernetes (fn [& _] {:status "present" :params {:cluster_id "c1" :name "doks-fixture"} :kubeconfig_path "/k"})]
      (let [r (sut/load-infrastructure-step (assoc opts :green/event :check))]
        (is (zero? (:green/exit r)))
        (is (= "c1" (get-in r [:doks/cluster :cluster_id])))))
    (doseq [status ["absent" "destroyed"]]
      (with-redefs [managed/read-managed-kubernetes (fn [& _] {:status status})]
        (testing (str status " fails check but lets delete continue")
          (is (= 1 (:green/exit (sut/load-infrastructure-step (assoc opts :green/event :check)))))
          (let [r (sut/load-infrastructure-step (assoc opts :green/event :delete))]
            (is (zero? (:green/exit r)))
            (is (true? (:doks/cluster-absent r)))))))
    (with-redefs [managed/read-managed-kubernetes (fn [& _] {:status "error"})]
      (is (= 1 (:green/exit (sut/load-infrastructure-step (assoc opts :green/event :delete))))))))

;; ------------------------------------------------------- DigitalOcean API

(defn stub-http
  "An http-request replacement answering from `routes` ({[method path] response})
  and recording every request."
  [routes calls]
  (fn [request]
    (swap! calls conj request)
    (let [path (subs (:uri request) (count sut/api-base))
          response (get routes [(:method request) path] {:status 404 :body "{\"id\":\"not_found\",\"message\":\"no\"}"})]
      (if (fn? response) (response request) response))))

(def opts (assoc (fixture "digitalocean") :do-token "secret-token"))

(deftest api-carries-the-token-in-a-header-only
  (let [calls (atom [])]
    (with-redefs [sut/http-request (stub-http {[:get "/account"] {:status 200 :body "{\"account\":{}}"}} calls)]
      (let [r (sut/api opts :get "/account")]
        (is (= 200 (:status r)))
        (is (= {:account {}} (:body r)))
        (is (= "Bearer secret-token" (get-in (first @calls) [:headers "Authorization"])))
        (is (nil? (:body (first @calls))))))))

(deftest registry-integration-calls
  (let [calls (atom [])
        routes {[:post "/kubernetes/registry"] {:status 204 :body ""}
                [:delete "/kubernetes/registry"] {:status 204 :body ""}
                [:get "/kubernetes/clusters/c1"] {:status 200 :body "{\"kubernetes_cluster\":{\"id\":\"c1\",\"registry_enabled\":true}}"}}]
    (with-redefs [sut/http-request (stub-http routes calls)]
      (testing "link sends the cluster uuid and verifies the cluster reports it"
        (let [r (sut/registry-link-step (assoc opts :green/event :create :doks/cluster {:cluster_id "c1"}))]
          (is (zero? (:green/exit r)))
          (is (= {:cluster_uuids ["c1"]} (json/parse-string (:body (first @calls)) true)))
          (is (true? (sut/registry-integrated? opts "c1")))))
      (testing "unlink"
        (reset! calls [])
        (is (zero? (:green/exit (sut/registry-unlink-step (assoc opts :green/event :delete :doks/cluster {:cluster_id "c1"})))))
        (is (= :delete (:method (first @calls))))))
    (testing "unlink treats a cluster that is not integrated as done"
      (with-redefs [sut/http-request (stub-http {[:delete "/kubernetes/registry"] {:status 404 :body "{\"message\":\"not integrated\"}"}} calls)]
        (is (zero? (:green/exit (sut/registry-unlink-step (assoc opts :green/event :delete :doks/cluster {:cluster_id "c1"})))))))
    (testing "a refused link fails with the API's status and message"
      (with-redefs [sut/http-request (stub-http {[:post "/kubernetes/registry"] {:status 422 :body "{\"message\":\"registry missing\"}"}} calls)]
        (let [r (sut/registry-link-step (assoc opts :green/event :create :doks/cluster {:cluster_id "c1"}))]
          (is (= 1 (:green/exit r)))
          (is (= "registry integration failed: HTTP 422 registry missing" (:green/err r))))))
    (testing "no registry, no calls"
      (reset! calls [])
      (with-redefs [sut/http-request (stub-http {} calls)]
        (is (zero? (:green/exit (sut/registry-link-step (assoc (fixture "vultr") :green/event :create :doks/cluster {:cluster_id "c1"})))))
        (is (zero? (:green/exit (sut/registry-unlink-step (assoc opts :green/event :delete :doks/cluster-absent true)))))
        (is (empty? @calls))))))

(deftest account-registry-listing
  (let [calls (atom [])]
    (with-redefs [sut/http-request (stub-http {[:get "/registries"] {:status 200 :body "{\"registries\":[{\"name\":\"a\"},{\"name\":\"b\"}]}"}} calls)]
      (is (= ["a" "b"] (sut/account-registries opts))))
    (with-redefs [sut/http-request (stub-http {[:get "/registry"] {:status 200 :body "{\"registry\":{\"name\":\"one\"}}"}} calls)]
      (is (= ["one"] (sut/account-registries opts))))
    (with-redefs [sut/http-request (stub-http {} calls)]
      (is (= [] (sut/account-registries opts))))
    (with-redefs [sut/http-request (stub-http {[:get "/registries"] {:status 500 :body ""} [:get "/registry"] {:status 500 :body ""}} calls)]
      (is (nil? (sut/account-registries opts))))))

(deftest docker-credentials-are-returned-raw
  (let [calls (atom []) body "{\"auths\":{\"registry.digitalocean.com\":{\"auth\":\"YWJj\"}}}"]
    (with-redefs [sut/http-request (stub-http {[:get "/registry/docker-credentials?read_write=true&expiry_seconds=3600"] {:status 200 :body body}} calls)]
      (is (= body (sut/docker-credentials opts))))
    (with-redefs [sut/http-request (stub-http {} calls)]
      (is (thrown-with-msg? Exception #"registry credential request failed: HTTP 404" (sut/docker-credentials opts))))))

(deftest private-writes
  (let [dir (tmp-dir) path (str dir "/registry/push/config.json")]
    (sut/write-private! path "secret")
    (is (= "secret" (slurp path)))
    (is (= "rw-------" (fs/posix->str (fs/posix-file-permissions path))))
    (is (= "rwx------" (fs/posix->str (fs/posix-file-permissions (fs/parent path)))))
    (sut/write-private! path "rotated")
    (is (= "rotated" (slurp path)))
    (is (not (fs/exists? (str path ".tmp"))))))

(deftest cleanup-removes-access-material
  (let [dir (tmp-dir) opts (assoc (fixture "digitalocean") :workdir dir :green/event :delete)]
    (sut/write-private! (sut/kubeconfig-path opts) "kc")
    (sut/write-private! (sut/push-config-path opts) "cfg")
    (is (zero? (:green/exit (sut/cleanup-step opts))))
    (is (not (fs/exists? (sut/kubeconfig-path opts))))
    (is (not (fs/exists? (sut/registry-dir opts))))
    (is (zero? (:green/exit (sut/cleanup-step opts))) "idempotent")))
