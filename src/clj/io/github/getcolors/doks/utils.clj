(ns io.github.getcolors.doks.utils
  (:require [clojure.string :as str]))

(def contract
  "The launcher contract this library satisfies. The payload launcher refuses
  a pinned library whose contract is older than the one it was written for."
  1)

(defn missing? [x] (or (nil? x) (and (string? x) (str/blank? x))))

(def registry-host "registry.digitalocean.com")

(defn registry-name
  "The container registry this deployment owns, derived from the profile
  (Compute Name Standard): DigitalOcean registry names accept lowercase
  alphanumerics and hyphens, so the profile is lowercased and every other
  character removed. Validation rejects a profile whose derivation is empty
  or otherwise invalid rather than inventing a name."
  [profile]
  (str/replace (str/lower-case (str profile)) #"[^a-z0-9-]" ""))

(defn compute-request
  "The managed-kubernetes request handed to colors-compute. The one legacy key
  is the state address the pre-library doks package wrote; declaring it makes
  the library refuse a create while that state still owns a cluster, so the
  old and new layouts can never own the same resources."
  [opts]
  {:legacy_state_keys [(str (:profile opts) "/cluster.tfstate")]})
