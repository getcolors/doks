(ns colors.doks-test
  (:require [clojure.test :refer [deftest is testing]]
            [colors.doks :as doks]
            [cheshire.core :as json]))
(def opts {:profile "doks-dev" :cluster-name "colors-doks-dev" :region "ams3"
           :kubernetes-version "1.36.3-do.2" :worker-size "s-2vcpu-4gb"
           :worker-count 1 :state-bucket "doks-state" :state-endpoint "https://example.r2.cloudflarestorage.com"
           :compute-prevent-destroy true})
(deftest state-and-ownership
  (let [config (doks/configuration (assoc opts :do-token "SECRET"))]
    (is (= [] (doks/validate opts)))
    (is (= "doks-dev/cluster.tfstate" (get-in config [:terraform :backend :s3 :key])))
    (is (true? (get-in config [:terraform :backend :s3 :use_lockfile])))
    (is (= #{:digitalocean_kubernetes_cluster} (set (keys (:resource config)))))
    (is (not (.contains (json/generate-string config) "SECRET")))
    (is (true? (get-in config [:resource :digitalocean_kubernetes_cluster :cluster :lifecycle :prevent_destroy])))))
(deftest guard-before-mutation
  (is (thrown-with-msg? Exception #"Deletion protected"
        (doks/execute (assoc opts :green/event :delete))))
  (is (seq (doks/validate (assoc opts :profile "../other"))))
  (is (seq (doks/validate (assoc opts :worker-count 0)))))
(deftest isolated-credentials
  (let [env (doks/command-env (assoc opts :do-token "do" :doks-state-r2-access-key-id "id"
                                  :doks-state-r2-secret-access-key "secret"))]
    (is (= "id" (env "AWS_ACCESS_KEY_ID")))
    (is (= "" (env "AWS_SESSION_TOKEN"))))
  (is (thrown-with-msg? Exception #"Missing credential" (doks/command-env opts))))
