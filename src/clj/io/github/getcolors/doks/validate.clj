(ns io.github.getcolors.doks.validate
  "Credential-free desired-state validation. Provider settings, versions and
  node counts are validated by the pinned colors-compute library from its
  managed-provider recipes; this namespace owns only what the package adds:
  the profile, the guard flag, and the optional registry."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [green.cli :as green-cli]
            [io.github.getcolors.compute :as compute]
            [io.github.getcolors.compute-managed :as managed]
            [io.github.getcolors.doks.utils :as utils]))

(def profile-par (green-cli/par-name :profile))

(def managed-providers
  "The managed Kubernetes providers the pinned library knows, read from its
  recipe registry rather than listed here (Compute Provider Standard §3)."
  (vec (sort (map name (keys (json/parse-string
                              (slurp (io/resource "colors_compute/managed-providers.json")) true))))))

(def required [:profile :workdir :provider-compute :provider-backend :compute-prevent-destroy])
(def profile-re #"[A-Za-z0-9][A-Za-z0-9_-]{0,62}")
(def registry-re #"[a-z0-9][a-z0-9-]{1,62}")
(def registry-tiers #{"starter" "basic" "professional"})

(def missing? utils/missing?)

(defn env-errors [env]
  (when (not-empty (str (get env profile-par)))
    [(str profile-par " is set; profile must come from colors.yml only")]))

(defn registry?
  "Presence of `digitalocean-registry-tier` is the one switch: set, the
  deployment owns a profile-named DigitalOcean container registry; absent,
  there is no registry and no registry verb."
  [opts]
  (not (missing? (:digitalocean-registry-tier opts))))

(defn registry-name [opts] (utils/registry-name (:profile opts)))

(defn registry-errors [opts]
  (when (registry? opts)
    (concat
     (when-not (= "digitalocean" (:provider-compute opts))
       [":digitalocean-registry-tier requires :provider-compute digitalocean"])
     (when-not (contains? registry-tiers (str (:digitalocean-registry-tier opts)))
       [":digitalocean-registry-tier must be starter, basic, or professional"])
     (when-not (re-matches registry-re (registry-name opts))
       [(str "the profile-derived registry name \"" (registry-name opts)
             "\" is not a valid DigitalOcean registry name")]))))

(defn basic-errors [opts]
  (concat
   (for [k required :when (missing? (get opts k))] (str k " is required"))
   (when (and (not (missing? (:profile opts))) (not (re-matches profile-re (str (:profile opts)))))
     [":profile must be a safe identifier"])
   (when-not (contains? (set managed-providers) (:provider-compute opts))
     [(str ":provider-compute must be one of " (str/join ", " managed-providers))])
   (when-not (boolean? (:compute-prevent-destroy opts))
     [":compute-prevent-destroy must be true or false"])))

(defn state-errors
  "Every problem with the desired state. The library's own validation runs
  only once the package-level shape holds, so one missing profile does not
  also surface as three opaque library refusals."
  [opts]
  (let [basic (vec (basic-errors opts))]
    (vec (concat basic
                 (when (empty? basic) (managed/managed-errors opts (utils/compute-request opts)))
                 (registry-errors opts)))))

(defn compute-secrets [opts]
  (mapv keyword (:secrets (get-in compute/registry [:compute (keyword (:provider-compute opts))]))))

(defn backend-secrets [opts]
  (mapv keyword (:secrets (get-in compute/registry [:backend (keyword (:provider-backend opts))]))))

(def registry-secrets
  "The registry is DigitalOcean's whatever the cluster provider, so its API
  verbs need the DigitalOcean token specifically."
  [:do-token])

(defn required-secrets
  "What a real run of `event` needs. create and delete talk to the provider
  and the backend; check reads state (backend) and, with a registry, the
  provider API; kubeconfig reads state alone; registry talks to the provider
  API alone."
  [opts event]
  (case event
    (:create :delete) (concat (compute-secrets opts) (backend-secrets opts))
    :check (concat (backend-secrets opts) (when (registry? opts) registry-secrets))
    :kubeconfig (backend-secrets opts)
    :registry registry-secrets
    []))

(defn secret-errors [opts event]
  (for [k (distinct (required-secrets opts event)) :when (missing? (get opts k))]
    (str "required credential is not set: " (green-cli/par-name k))))

(defn tofu-env
  "Flat credential key -> environment variable for the package-owned registry
  stage: the provider token as the library registry names it, and the
  backend credentials the way OpenTofu's S3-compatible backend reads them."
  [opts slot]
  (case slot
    :provider-compute (into {} (get-in compute/registry [:compute (keyword (:provider-compute opts)) :tofu-env]))
    :provider-backend (case (:provider-backend opts)
                        "r2" {:r2-access-key-id "AWS_ACCESS_KEY_ID" :r2-secret-access-key "AWS_SECRET_ACCESS_KEY"}
                        "oci" {:oci-access-key-id "AWS_ACCESS_KEY_ID" :oci-secret-access-key "AWS_SECRET_ACCESS_KEY"}
                        {})
    {}))
