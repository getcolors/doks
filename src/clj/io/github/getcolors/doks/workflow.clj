(ns io.github.getcolors.doks.workflow
  (:require [clojure.java.io :as io]
            [green.cli :as green-cli]
            [green.dry-run :as dry-run]
            [green.lifecycle :as lifecycle]
            [green.progress :as progress]
            [green.workflow :as wf]
            [io.github.getcolors.doks.operator :as operator]
            [io.github.getcolors.doks.tools :as tools]
            [io.github.getcolors.doks.validate :as validate]))

(def events [:build :create :check :kubeconfig :registry :delete])

(def cli-options
  "What the launcher hands green.cli/run-cli. The launcher holds no logic of
  its own, so even the verb list lives here."
  {:allowed-events events})

(def defaults {:provider-compute "digitalocean" :provider-backend "r2"
               :compute-prevent-destroy true :workdir ".colors"})

(defn absolute-workdir
  "Resolve a relative workdir against the desired-state file, the way
  green.cli/stage-dir does, so the library's kubeconfig sink and the
  package's stage directories agree wherever the launcher is run from."
  [opts]
  (let [workdir (io/file (str (:workdir opts)))
        state-file (:green/state-file opts)]
    (if (or (.isAbsolute workdir) (nil? state-file))
      opts
      (assoc opts :workdir (str (io/file (.getParentFile (.getAbsoluteFile (io/file state-file))) workdir))))))

(defn start-step
  ([opts] (start-step opts (System/getenv)))
  ([opts env]
   (lifecycle/preflight
    opts {:defaults defaults :overlay green-cli/read-pars
          :validators
          [(fn [_ env _] (validate/env-errors env))
           (fn [opts _ _] (validate/state-errors opts))
           (fn [opts _ {:keys [event]}]
             (when (and (= :registry event) (not (validate/registry? opts)))
               [":digitalocean-registry-tier is not set; the registry verb needs a deployment-owned registry"]))
           (fn [opts _ {:keys [event real?]}]
             (when real? (validate/secret-errors opts event)))
           ;; The guard holds for a dry run too: rehearsing a delete the guard
           ;; would refuse rehearses nothing.
           (fn [opts _ {:keys [event]}]
             (when (and (= :delete event) (not (false? (:compute-prevent-destroy opts))))
               [(str "compute destruction is protected; set "
                     (green-cli/par-name :compute-prevent-destroy) "=false to delete")]))]
          :after-validate (fn [opts _ _] (assoc (absolute-workdir opts) :green/exit 0))}
    env)))

(defn wire-fn [step run-opts]
  (case (:green/event run-opts)
    :delete
    ;; The integration goes first while the cluster still exists to be
    ;; unlinked; the cluster before the registry it pulled from; the local
    ;; access material last.
    (case step
      :doks/start [start-step :doks/load]
      :doks/load [tools/load-infrastructure-step :doks/registry-unlink]
      :doks/registry-unlink [tools/registry-unlink-step :doks/infrastructure]
      :doks/infrastructure [tools/infrastructure-step :doks/registry]
      :doks/registry [tools/registry-step :doks/cleanup]
      :doks/cleanup [tools/cleanup-step])

    :check
    (case step
      :doks/start [start-step :doks/load]
      :doks/load [tools/load-infrastructure-step :doks/check-nodes]
      :doks/check-nodes [operator/check-nodes-step :doks/check-registry]
      :doks/check-registry [operator/check-registry-step])

    :kubeconfig
    (case step
      :doks/start [start-step :doks/load]
      :doks/load [tools/load-infrastructure-step :doks/kubeconfig]
      :doks/kubeconfig [operator/kubeconfig-step])

    :registry
    (case step
      :doks/start [start-step :doks/registry-credentials]
      :doks/registry-credentials [operator/registry-credentials-step])

    ;; build and create: the cluster, then the registry, then the binding
    ;; between them.
    (case step
      :doks/start [start-step :doks/infrastructure]
      :doks/infrastructure [tools/infrastructure-step :doks/registry]
      :doks/registry [tools/registry-step :doks/registry-link]
      :doks/registry-link [tools/registry-link-step])))

(def side-effecting
  [:doks/infrastructure :doks/load :doks/registry :doks/registry-link
   :doks/registry-unlink :doks/check-nodes :doks/check-registry
   :doks/kubeconfig :doks/registry-credentials :doks/cleanup])

(defn next-steps [_ successors opts]
  (if (wf/failed? opts) [] (mapv #(vector % opts) successors)))

(def workflow
  (-> (wf/workflow {:start :doks/start :wire-fn wire-fn :next-fn next-steps})
      (wf/advice-add :doks/registry :before ::backend tools/registry-backend-advice)
      progress/advise
      (dry-run/advise side-effecting)))
