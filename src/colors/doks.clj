(ns colors.doks
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]
            [green.cli :as cli]
            [green.process :as process]
            [green.workflow :as wf]))

(def required [:profile :cluster-name :region :kubernetes-version :worker-size
               :state-bucket :state-endpoint])
(defn validate [opts]
  (cond-> (mapv #(str (name %) " must be a nonempty string")
                (filter #(not (and (string? (get opts %)) (not (str/blank? (get opts %))))) required))
    (not (re-matches #"[a-z0-9][a-z0-9-]{0,62}" (str (:profile opts))))
    (conj "profile must be a safe lowercase name")
    (not (and (integer? (:worker-count opts)) (<= 1 (:worker-count opts) 10)))
    (conj "worker-count must be between 1 and 10")
    (not (boolean? (:compute-prevent-destroy opts)))
    (conj "compute-prevent-destroy must be boolean")
    (not (str/starts-with? (str (:state-endpoint opts)) "https://"))
    (conj "state-endpoint must use HTTPS")))

(defn configuration [opts]
  {:terraform {:required_version ">= 1.10.0"
               :required_providers {:digitalocean {:source "digitalocean/digitalocean" :version "2.51.0"}}
               :backend {:s3 {:bucket (:state-bucket opts)
                              :key (str (:profile opts) "/cluster.tfstate")
                              :region "auto" :endpoints {:s3 (:state-endpoint opts)}
                              :skip_credentials_validation true :skip_requesting_account_id true
                              :skip_region_validation true :skip_metadata_api_check true
                              :skip_s3_checksum true :use_path_style true :use_lockfile true}}}
   :provider {:digitalocean {}}
   :resource {:digitalocean_kubernetes_cluster
              {:cluster {:name (:cluster-name opts) :region (:region opts)
                         :version (:kubernetes-version opts) :ha false
                         :tags ["colors-doks" (str "colors-profile-" (:profile opts))]
                         :lifecycle {:prevent_destroy (not= :delete (:green/event opts))}
                         :node_pool [{:name (str (:cluster-name opts) "-workers")
                                      :size (:worker-size opts) :node_count (:worker-count opts)}]}}}
   :output {:cluster_id {:value "${digitalocean_kubernetes_cluster.cluster.id}"}
            :endpoint {:value "${digitalocean_kubernetes_cluster.cluster.endpoint}"}}})

(defn command-env [opts]
  (let [mapping {"DIGITALOCEAN_TOKEN" :do-token
                 "AWS_ACCESS_KEY_ID" :doks-state-r2-access-key-id
                 "AWS_SECRET_ACCESS_KEY" :doks-state-r2-secret-access-key}]
    (doseq [[_ key] mapping]
      (when (str/blank? (get opts key))
        (throw (ex-info (str "Missing credential " (name key)) {}))))
    (merge {"AWS_SESSION_TOKEN" "" "AWS_SECURITY_TOKEN" "" "AWS_PROFILE" ""
            "AWS_EC2_METADATA_DISABLED" "true" "TF_IN_AUTOMATION" "1"}
           (into {} (map (fn [[env key]] [env (get opts key)])) mapping))))

(defn run-tofu [opts args]
  (let [r (process/run-with-timeout (into ["tofu"] args)
                                   {:dir (:directory opts) :extra-env (command-env opts)} 1800000)]
    (when-not (zero? (:exit r))
      ;; Do not put provider diagnostics containing credentials into public logs.
      (throw (ex-info (str "OpenTofu " (first args) " failed (exit " (:exit r) ")")
                      {:exit (:exit r)})))
    (:out r)))

(defn init! [opts] (run-tofu opts ["init" "-input=false" "-no-color"]))
(defn cluster-id [opts]
  (str/trim (run-tofu opts ["output" "-raw" "cluster_id"])))
(defn api-get [opts path]
  (let [response (http/get (str "https://api.digitalocean.com/v2" path)
                           {:headers {"Authorization" (str "Bearer " (:do-token opts))}
                            :throw false :timeout 30000})]
    (when-not (= 200 (:status response))
      (throw (ex-info (str "DigitalOcean request failed (HTTP " (:status response) ")") {})))
    (:body response)))
(defn kubeconfig! [opts]
  (let [id (cluster-id opts)
        body (api-get opts (str "/kubernetes/clusters/" id "/kubeconfig?expiry_seconds=86400"))
        path (str (:directory opts) "/kubeconfig")]
    (when-not (fs/exists? path)
      (fs/create-file path {:posix-file-permissions "rw-------"}))
    (spit path body)
    (fs/set-posix-file-permissions path "rw-------")
    (println (str "Private kubeconfig: " path))
    (assoc opts :cluster-id id :kubeconfig path)))

(defn execute [opts]
  (let [errors (validate opts)
        event (:green/event opts)]
    (when (seq errors) (throw (ex-info (str/join "; " errors) {})))
    (when (and (= event :delete) (:compute-prevent-destroy opts))
      (throw (ex-info "Deletion protected; use COLORS_PAR_COMPUTE_PREVENT_DESTROY=false for an explicit delete" {})))
    (if (:green/dry-run opts)
      (do (println (str "Dry run: " (name event) " " (:cluster-name opts))) opts)
      (let [base (fs/parent (:green/state-file opts))
            directory (str (fs/path base ".colors" (:profile opts) "cluster"))
            opts (assoc opts :directory directory)]
        (fs/create-dirs directory {:posix-file-permissions "rwx------"})
        (spit (str directory "/main.tf.json") (json/generate-string (configuration opts) {:pretty true}))
        (if (= event :build)
          (do (println (str "Rendered: " directory)) opts)
          (do
            (init! opts)
            (case event
              :create (do (run-tofu opts ["plan" "-input=false" "-no-color" "-out=converge.tfplan"])
                          (run-tofu opts ["apply" "-input=false" "-no-color" "converge.tfplan"])
                          (kubeconfig! opts))
              :kubeconfig (kubeconfig! opts)
              :check (let [id (cluster-id opts)
                           actual (:kubernetes_cluster (json/parse-string
                                   (api-get opts (str "/kubernetes/clusters/" id)) true))]
                       (when-not (and (= (:cluster-name opts) (:name actual))
                                      (= "running" (get-in actual [:status :state])))
                         (throw (ex-info "Cluster identity/readiness check failed" {})))
                       (println (str "Healthy DOKS cluster: " id))
                       (assoc opts :cluster-id id))
              :delete (do (run-tofu opts ["destroy" "-input=false" "-no-color" "-auto-approve"])
                          opts))))))))

(def workflow (wf/workflow {:start ::execute :wire-fn (fn [_ _] [execute])}))
(defn -main [& args]
  (if (System/getenv "COLORS_PAR_PROFILE")
    (do (binding [*out* *err*] (println "COLORS_PAR_PROFILE is forbidden")) (System/exit 2))
    (let [result (cli/run-cli workflow (or (seq args) *command-line-args*)
                             {:default-file "colors.yml" :search-parents true
                              :allowed-events #{:build :create :check :kubeconfig :delete}})]
      (when-let [err (:green/err result)] (binding [*out* *err*] (println err)))
      (System/exit (or (:green/exit result) 0)))))
