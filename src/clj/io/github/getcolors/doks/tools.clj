(ns io.github.getcolors.doks.tools
  "The lifecycle steps: managed compute through colors-compute, the optional
  package-owned registry stage through green.tofu, and the DigitalOcean API
  calls that bind the two. Every secret reaches a process through its
  environment or an HTTP header; nothing here renders one."
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [green.cli :as green-cli]
            [green.scaffold :as sc]
            [green.tofu :as tofu]
            [io.github.getcolors.compute :as compute]
            [io.github.getcolors.compute-managed :as managed]
            [io.github.getcolors.doks.utils :as utils]
            [io.github.getcolors.doks.validate :as validate]))

(def registry-tool "doks-registry")
(def digitalocean-provider-version "2.51.0")
(def api-base "https://api.digitalocean.com/v2")

;; ------------------------------------------------------------------- paths

(defn tool-dir [opts tool] (green-cli/stage-dir opts tool {:default-profile "doks"}))
(defn profile-dir [opts] (str (.getParentFile (io/file (tool-dir opts registry-tool)))))
(defn compute-dir [opts] (str (io/file (profile-dir opts) "compute" "managed-kubernetes")))
(defn kubeconfig-path [opts] (str (io/file (profile-dir opts) "kubeconfig")))
(defn registry-dir [opts] (str (io/file (profile-dir opts) "registry")))
(defn push-config-path [opts] (str (io/file (registry-dir opts) "push" "config.json")))
(defn registry-state-key [opts] (str (:profile opts) "/registry.tfstate"))

(defn write-private!
  "Write `content` to `path` readable by the owner alone, inside a directory
  readable by the owner alone: temp file beside the target, permissions,
  rename. A crash never leaves a half-written or world-readable credential."
  [path content]
  (let [target (fs/path path) tmp (fs/path (str path ".tmp"))]
    (fs/create-dirs (fs/parent target) {:posix-file-permissions "rwx------"})
    (fs/set-posix-file-permissions (fs/parent target) "rwx------")
    (fs/delete-if-exists tmp)
    (fs/create-file tmp {:posix-file-permissions "rw-------"})
    (spit (str tmp) content)
    (fs/set-posix-file-permissions tmp "rw-------")
    (fs/move tmp target {:replace-existing true :atomic-move true})
    (str target)))

;; ------------------------------------------------------------- credentials

(defn credential-env
  "Environment for a tofu stage: the backend credentials plus every slot's
  provider token, taken from the flat keys COLORS_PAR_* overlaid."
  [opts & slots]
  (not-empty
   (into {} (keep (fn [[k env-var]]
                    (when-let [v (not-empty (str (get opts k)))] [env-var v])))
         (apply merge (map #(validate/tofu-env opts %) (conj (vec slots) :provider-backend))))))

;; ---------------------------------------------------------------- compute

(defn compute-json
  "Deterministic rendering of a library document: sorted keys, two-space
  indentation, one document per file — the same shape every colors-compute
  consumer writes, so goldens diff across packages."
  [value indent]
  (let [padding #(apply str (repeat % " "))]
    (cond
      (map? value) (if (empty? value) "{}"
                       (str "{\n" (str/join ",\n" (for [[key item] (sort-by (comp name key) value)]
                                                    (str (padding (+ indent 2)) (json/generate-string (name key)) ": " (compute-json item (+ indent 2)))))
                            "\n" (padding indent) "}"))
      (sequential? value) (if (empty? value) "[]"
                              (str "[\n" (str/join ",\n" (map #(str (padding (+ indent 2)) (compute-json % (+ indent 2))) value)) "\n" (padding indent) "]"))
      :else (json/generate-string value))))

(defn write-documents! [dir documents]
  (doseq [[file document] documents]
    (let [target (io/file dir (name file))]
      (io/make-parents target)
      (spit target (str (compute-json document 0) "\n")))))

(def refused "managed compute lifecycle refused; inspect state ownership, credentials and the journal")

(defn compute-result
  "Translate a library result into the outcome map. Cluster facts live under
  :doks/cluster, the materialized kubeconfig under :doks/kubeconfig-path; a
  destroyed or absent cluster is :doks/cluster-absent, which later delete
  stages read to skip what no longer exists."
  [opts result]
  (case (:status result)
    ("planned" "ready" "present")
    (cond-> (assoc opts :green/exit 0 :doks/cluster (:params result))
      (:kubeconfig_path result) (assoc :doks/kubeconfig-path (:kubeconfig_path result)))
    "destroyed" (assoc opts :green/exit 0 :doks/cluster-absent true)
    (assoc opts :green/exit 1
           :green/err (if (seq (:errors result)) (str/join "\n" (:errors result)) refused))))

(defn infrastructure-step
  "build renders the library's managed-kubernetes documents; create converges
  the cluster (the library owns coordination, plan safety and the kubeconfig
  write); delete destroys it unless the read before it found nothing."
  [opts]
  (try
    (let [event (:green/event opts)
          planning? (or (= :build event) (:green/dry-run opts))]
      (cond
        (and (= :delete event) (:doks/cluster-absent opts))
        (do (println "cluster already absent; nothing to destroy") (assoc opts :green/exit 0))

        planning?
        (let [result (managed/plan-managed-kubernetes opts (utils/compute-request opts))]
          (write-documents! (compute-dir opts) (:documents result))
          (compute-result opts result))

        :else (compute-result opts (managed/managed-kubernetes opts (utils/compute-request opts)))))
    (catch Exception e
      (assoc opts :green/exit 1 :green/err (str refused (some->> (ex-message e) (str ": ")))))))

(defn load-infrastructure-step
  "Read the cluster from owned state without mutating it; the library also
  re-materializes the kubeconfig. check and kubeconfig need a present
  cluster; delete carries on past an absent one to the registry stage."
  [opts]
  (try
    (let [result (managed/read-managed-kubernetes opts (utils/compute-request opts))]
      (case (:status result)
        "present" (compute-result opts result)
        ("absent" "destroyed")
        (if (= :delete (:green/event opts))
          (assoc opts :green/exit 0 :doks/cluster-absent true)
          (assoc opts :green/exit 1 :green/err "managed cluster is not present; run create first"))
        (assoc opts :green/exit 1 :green/err "managed compute inspection refused; existing owned state is required")))
    (catch Exception e
      (assoc opts :green/exit 1 :green/err (str "managed compute inspection refused" (some->> (ex-message e) (str ": ")))))))

;; --------------------------------------------------------------- registry

(defn registry-json
  "The package-owned registry stage as deterministic .tf.json: one
  digitalocean_container_registry named after the profile, its destruction
  bound to compute-prevent-destroy exactly as the cluster's is. No region: a
  DOKS region is not necessarily a registry region, and DigitalOcean picks
  the registry's when none is given."
  [opts]
  (tofu/constructs-json
   [{:terraform {:required_providers {:digitalocean {:source "digitalocean/digitalocean"
                                                     :version digitalocean-provider-version}}}}
    {:provider {:digitalocean {}}}
    (tofu/construct :resource :digitalocean_container_registry :registry
                    {:name (validate/registry-name opts)
                     :subscription_tier_slug (str (:digitalocean-registry-tier opts))
                     :lifecycle {:prevent_destroy (boolean (:compute-prevent-destroy opts))}})
    {:output {:params {:value {:kind "container-registry"
                               :provider "digitalocean"
                               :profile (:profile opts)
                               :name "${digitalocean_container_registry.registry.name}"
                               :endpoint "${digitalocean_container_registry.registry.endpoint}"
                               :server_url "${digitalocean_container_registry.registry.server_url}"}}}}]))

(defn registry-specs [opts]
  [(sc/content-spec (str (tool-dir opts registry-tool) "/registry.tf.json") (str (registry-json opts) "\n"))])

(defn registry-backend-advice
  "A :before advice writing the registry stage's backend.tf.json from the
  library's backend plan — the same bucket, endpoint and lockfile settings
  the cluster state uses, under the package's own key. Nothing is written
  when no registry is configured."
  [opts]
  (if-not (validate/registry? opts)
    opts
    (let [plan (compute/backend-plan opts (registry-state-key opts))
          [type config] (first (get-in plan [:config :terraform :backend]))]
      ((tofu/backend-advice #(tool-dir % registry-tool) (name type) config) opts))))

(defn registry-step
  "build renders; create applies; delete destroys — all through green.tofu,
  so a failing tofu command reaches :green/err with its output."
  [opts]
  (if-not (validate/registry? opts)
    (assoc opts :green/exit 0)
    (let [result (tofu/tofu-with-spec opts (registry-specs opts)
                                      {:dir (tool-dir opts registry-tool)
                                       :env (credential-env opts :provider-compute)})]
      (cond-> result
        (get-in result [:tofu/outputs :params])
        (assoc :doks/registry (walk/keywordize-keys (get-in result [:tofu/outputs :params])))))))

;; ----------------------------------------------------------- DigitalOcean

(defn http-request
  "The one HTTP boundary, replaced in tests. Returns {:status :body}."
  [request]
  (http/request (assoc request :throw false :timeout 30000)))

(defn api
  "Call the DigitalOcean API. The token travels as a header, never in argv
  or a rendered file; the response body is parsed when it is JSON."
  ([opts method path] (api opts method path nil))
  ([opts method path body]
   (let [response (http-request (cond-> {:method method :uri (str api-base path)
                                         :headers (cond-> {"Authorization" (str "Bearer " (:do-token opts))
                                                           "Accept" "application/json"}
                                                    body (assoc "Content-Type" "application/json"))}
                                  body (assoc :body (json/generate-string body))))
         raw (some-> (:body response) str)
         parsed (try (some-> (not-empty raw) (json/parse-string true)) (catch Exception _ nil))]
     {:status (:status response) :body parsed :raw raw})))

(defn ok? [{:keys [status]}] (and (integer? status) (<= 200 status 299)))

(defn api-failure [what {:keys [status body]}]
  (str what " failed: HTTP " status (when-let [m (:message body)] (str " " m))))

(defn link-registry!
  "Integrate the account registry with the cluster: DOKS then injects an
  image-pull Secret named after the registry into every namespace. The call
  is idempotent on the DigitalOcean side."
  [opts cluster-id]
  (let [r (api opts :post "/kubernetes/registry" {:cluster_uuids [cluster-id]})]
    (when-not (ok? r) (throw (ex-info (api-failure "registry integration" r) {})))))

(defn unlink-registry!
  "Remove the integration; a cluster that is not integrated (404) is already
  in the desired state."
  [opts cluster-id]
  (let [r (api opts :delete "/kubernetes/registry" {:cluster_uuids [cluster-id]})]
    (when-not (or (ok? r) (= 404 (:status r)))
      (throw (ex-info (api-failure "registry integration removal" r) {})))))

(defn cluster
  "The DOKS cluster object, or nil."
  [opts cluster-id]
  (let [r (api opts :get (str "/kubernetes/clusters/" cluster-id))]
    (when (ok? r) (:kubernetes_cluster (:body r)))))

(defn registry-integrated? [opts cluster-id]
  (true? (:registry_enabled (cluster opts cluster-id))))

(defn account-registries
  "The account's registry names, or nil when the API gave no answer: the
  multi-registry listing first, then the single-registry endpoint older
  accounts answer through."
  [opts]
  (let [many (api opts :get "/registries")
        one (when-not (ok? many) (api opts :get "/registry"))]
    (cond
      (ok? many) (mapv :name (:registries (:body many)))
      (ok? one) [(:name (:registry (:body one)))]
      (= 404 (:status one)) []
      :else nil)))

(defn docker-credentials
  "A short-lived read-write docker config for the account registry, as the
  API returns it. Callers write it privately and never print it."
  [opts]
  (let [r (api opts :get "/registry/docker-credentials?read_write=true&expiry_seconds=3600")]
    (when-not (and (ok? r) (map? (:auths (:body r))))
      (throw (ex-info (api-failure "registry credential request" r) {})))
    (:raw r)))

(defn registry-link-step [opts]
  (if-not (and (validate/registry? opts) (= :create (:green/event opts)))
    (assoc opts :green/exit 0)
    (try
      (let [id (get-in opts [:doks/cluster :cluster_id])]
        (when (utils/missing? id) (throw (ex-info "cluster id unavailable after converge" {})))
        (link-registry! opts id)
        (when-not (registry-integrated? opts id)
          (throw (ex-info "the cluster does not report the registry integration" {})))
        (println (str "registry " (validate/registry-name opts) " integrated with cluster " id))
        (assoc opts :green/exit 0))
      (catch Exception e (assoc opts :green/exit 1 :green/err (ex-message e))))))

(defn registry-unlink-step [opts]
  (if-not (and (validate/registry? opts) (= :delete (:green/event opts)) (not (:doks/cluster-absent opts)))
    (assoc opts :green/exit 0)
    (try
      (let [id (get-in opts [:doks/cluster :cluster_id])]
        (when (utils/missing? id) (throw (ex-info "cluster id unavailable from state" {})))
        (unlink-registry! opts id)
        (println (str "registry integration removed from cluster " id))
        (assoc opts :green/exit 0))
      (catch Exception e (assoc opts :green/exit 1 :green/err (ex-message e))))))

(defn cleanup-step
  "After the infrastructure is gone: the kubeconfig is a dead bearer
  credential and the push config a dead registry credential."
  [opts]
  (when (= :delete (:green/event opts))
    (fs/delete-if-exists (kubeconfig-path opts))
    (when (fs/exists? (registry-dir opts)) (fs/delete-tree (registry-dir opts))))
  (assoc opts :green/exit 0))
