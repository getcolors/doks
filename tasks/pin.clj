(ns pin (:require [clojure.java.shell :as sh] [clojure.string :as str]))
;; One payload, one SHA. The launcher is born unpinned (nil) and `bb pin`
;; stamps or re-stamps it after a clean, pushed HEAD. Never hand-edit the SHA.
(def path "skills/package-doks-green/green")
(def rx #"\(def \^:private doks-sha (nil|\"[0-9a-f]{40}\")\)")
(defn git [& args] (let [{:keys [exit out]} (apply sh/sh "git" args)] (when (zero? exit) (str/trim out))))
(let [dirty (git "status" "--porcelain") sha (git "rev-parse" "HEAD") remotes (git "branch" "-r" "--contains" sha)]
  (cond (seq dirty) (do (binding [*out* *err*] (println "doks working tree is dirty; commit before pinning")) (System/exit 2))
        (not (str/includes? (str remotes) "origin/")) (do (binding [*out* *err*] (println "doks HEAD is not pushed")) (System/exit 2))
        :else (let [s (slurp path)]
                (when-not (re-find rx s)
                  (binding [*out* *err*] (println "could not locate the doks-sha form in" path)) (System/exit 2))
                (spit path (str/replace s rx (str "(def ^:private doks-sha \"" sha "\")")))
                (println "pinned doks launcher to" (subs sha 0 7)))))
