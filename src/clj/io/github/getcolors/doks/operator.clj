(ns io.github.getcolors.doks.operator
  "The read-only and access verbs: check, kubeconfig and registry."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [green.process :as process]
            [io.github.getcolors.doks.tools :as tools]
            [io.github.getcolors.doks.utils :as utils]
            [io.github.getcolors.doks.validate :as validate]))

;; ------------------------------------------------------------------ nodes

(defn node-ready? [node]
  (boolean (some #(and (= "Ready" (:type %)) (= "True" (:status %)))
                 (get-in node [:status :conditions]))))

(defn node-external-ip [node]
  (some #(when (= "ExternalIP" (:type %)) (:address %)) (get-in node [:status :addresses])))

(defn node-summary [node]
  {:name (get-in node [:metadata :name]) :ip (node-external-ip node) :ready? (node-ready? node)})

(defn node-report
  "Summaries and problems from a `kubectl get nodes -o json` document."
  [document]
  (let [nodes (mapv node-summary (:items document))]
    {:nodes nodes
     :errors (vec (concat (when (empty? nodes) ["the cluster reports no nodes"])
                          (for [{:keys [name ready?]} nodes :when (not ready?)]
                            (str "node " name " is not Ready"))))}))

(defn kubectl-nodes [kubeconfig]
  (process/run ["kubectl" "--kubeconfig" kubeconfig "get" "nodes" "-o" "json"]))

(defn check-nodes-step [opts]
  (let [{:keys [name cluster_id]} (:doks/cluster opts)
        kubeconfig (or (:doks/kubeconfig-path opts) (tools/kubeconfig-path opts))
        {:keys [exit out err]} (kubectl-nodes kubeconfig)]
    (if-not (zero? exit)
      (assoc opts :green/exit 1 :green/err (str "kubectl get nodes failed: " (or (not-empty err) (not-empty out) "(no output)")))
      (let [{:keys [nodes errors]} (node-report (try (json/parse-string out true) (catch Exception _ {})))]
        (println (str "cluster " name " " cluster_id))
        (doseq [{:keys [name ip ready?]} nodes]
          (println (str "node " name " " (or ip "-") " " (if ready? "Ready" "NotReady"))))
        (if (seq errors)
          (assoc opts :green/exit 1 :green/err (str/join "\n" errors))
          (assoc opts :green/exit 0 :doks/nodes nodes))))))

;; --------------------------------------------------------------- registry

(defn check-registry-step [opts]
  (if-not (validate/registry? opts)
    (assoc opts :green/exit 0)
    (let [name (validate/registry-name opts)
          id (get-in opts [:doks/cluster :cluster_id])
          names (tools/account-registries opts)
          errors (cond
                   (nil? names) ["the DigitalOcean registry API gave no answer"]
                   (not (some #{name} names)) [(str "registry " name " does not exist in this account")]
                   (not (tools/registry-integrated? opts id)) [(str "registry " name " is not integrated with cluster " id)])]
      (if (seq errors)
        (assoc opts :green/exit 1 :green/err (str/join "\n" errors))
        (do (println (str "registry " utils/registry-host "/" name " integrated with cluster " id))
            (assoc opts :green/exit 0))))))

(defn registry-credentials-step
  "Short-lived read-write docker credentials for pushing images, written
  privately under the profile directory. The credential is never printed."
  [opts]
  (try
    (let [content (tools/docker-credentials opts)
          path (tools/write-private! (tools/push-config-path opts) content)]
      (println (str "registry " utils/registry-host "/" (validate/registry-name opts)))
      (println (str "docker config (read-write, 1 hour): " path))
      (assoc opts :green/exit 0 :doks/push-config-path path))
    (catch Exception e (assoc opts :green/exit 1 :green/err (ex-message e)))))

;; ------------------------------------------------------------- kubeconfig

(defn kubeconfig-step [opts]
  (let [path (or (:doks/kubeconfig-path opts) (tools/kubeconfig-path opts))]
    (if-not (fs/exists? path)
      (assoc opts :green/exit 1 :green/err (str "no kubeconfig was materialized at " path))
      (do (fs/set-posix-file-permissions path "rw-------")
          (println (str "kubeconfig: " path))
          (assoc opts :green/exit 0 :doks/kubeconfig-path path)))))
