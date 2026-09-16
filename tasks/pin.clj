(ns pin (:require [clojure.java.shell :as sh] [clojure.string :as str] [cheshire.core :as json]))

(defn git [& args]
  (let [{:keys [exit out]} (apply sh/sh "git" args)]
    (when (zero? exit) (str/trim out))))
(defn fail [message]
  (binding [*out* *err*] (println message))
  (System/exit 2))
(defn replace-one [text pattern replacement]
  (when-not (= 1 (count (re-seq pattern text)))
    (fail (str "expected one pin site for " pattern)))
  (str/replace text pattern replacement))

(let [sha (git "rev-parse" "HEAD")
      red-deps (:dependencies (json/parse-string (slurp "red/package.json") true))
      pyproject (slurp "blue/pyproject.toml")
      blue-sha (second (re-find #"blue = \{ git = \"https://github.com/getcolors/blue.git\", rev = \"([0-9a-f]{40})\"" pyproject))
      compute-sha (second (re-find #"colors-compute-blue = .*rev = \"([0-9a-f]{40})\"" pyproject))]
  (when (seq (git "status" "--porcelain")) (fail "doks working tree is dirty; commit before pinning"))
  (when-not (str/includes? (str (git "branch" "-r" "--contains" sha)) "origin/main")
    (fail "doks HEAD is not pushed to origin/main"))
  (when-not (and blue-sha compute-sha) (fail "could not read immutable Blue dependency pins"))
  (let [green-path "skills/package-doks-green/green"
        red-path "skills/package-doks-red/red"
        blue-path "skills/package-doks-blue/blue"
        green (replace-one (slurp green-path) #"\(def \^:private doks-sha (nil|\"[0-9a-f]{40}\")\)"
                           (str "(def ^:private doks-sha \"" sha "\")"))
        red (-> (slurp red-path)
                (replace-one #"\"package-doks-red\": (null|\"github:getcolors/doks#[0-9a-f]{40}\")"
                             (str "\"package-doks-red\": \"github:getcolors/doks#" sha "\""))
                (replace-one #"\"colors-compute-red\": \"github:getcolors/colors-compute#[0-9a-f]{40}\""
                             (str "\"colors-compute-red\": \"" (:colors-compute-red red-deps) "\""))
                (replace-one #"\"red\": \"github:getcolors/red#[0-9a-f]{40}\""
                             (str "\"red\": \"" (:red red-deps) "\"")))
        blue (-> (slurp blue-path)
                 (replace-one #"(# package-doks-blue = .*rev = )\"(UNPINNED|[0-9a-f]{40})\""
                              (str "$1\"" sha "\""))
                 (replace-one #"(# blue = .*rev = )\"[0-9a-f]{40}\""
                              (str "$1\"" blue-sha "\""))
                 (replace-one #"colors-compute.git@[0-9a-f]{40}#subdirectory=blue"
                              (str "colors-compute.git@" compute-sha "#subdirectory=blue")))]
    (spit green-path green)
    (spit red-path red)
    (spit blue-path blue)
    (println "pinned all DOKS launchers to" sha)))
