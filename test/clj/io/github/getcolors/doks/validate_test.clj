(ns io.github.getcolors.doks.validate-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [green.cli :as green-cli]
            [io.github.getcolors.doks.validate :as sut]))

(defn fixture [name]
  (let [file (str "test/fixtures/" name ".yml")]
    (green-cli/read-state file (slurp file))))

(def do-fixture (fixture "digitalocean"))
(def vultr-fixture (fixture "vultr"))

(deftest fixtures-are-valid
  (is (= [] (sut/state-errors do-fixture)))
  (is (= [] (sut/state-errors vultr-fixture))))

(deftest every-package-level-error-is-named
  (testing "each required key"
    (doseq [k sut/required]
      (is (some #{(str k " is required")} (sut/state-errors (dissoc do-fixture k))) (str k))))
  (testing "profile shape"
    (is (some #{":profile must be a safe identifier"} (sut/state-errors (assoc do-fixture :profile "../other"))))
    (is (some #{":profile must be a safe identifier"} (sut/state-errors (assoc do-fixture :profile "a b")))))
  (testing "provider list comes from the library recipes"
    (is (= [":provider-compute must be one of digitalocean, vultr"]
           (sut/state-errors (assoc do-fixture :provider-compute "hcloud" :digitalocean-registry-tier nil)))))
  (testing "guard flag"
    (is (some #{":compute-prevent-destroy must be true or false"}
              (sut/state-errors (assoc do-fixture :compute-prevent-destroy "yes")))))
  (testing "every package-level problem is listed at once"
    (let [errors (sut/state-errors (-> do-fixture (dissoc :workdir) (assoc :profile "a b" :compute-prevent-destroy "x")))]
      (is (= 3 (count errors))))))

(deftest library-validation-runs-once-the-shape-holds
  (testing "provider settings are the library's to validate"
    (is (= ["missing managed Kubernetes settings"] (sut/state-errors (dissoc do-fixture :doks-version))))
    (is (= ["invalid managed Kubernetes version"] (sut/state-errors (assoc do-fixture :doks-version "1.36.3"))))
    (is (= ["invalid managed Kubernetes node count"] (sut/state-errors (assoc do-fixture :digitalocean-node-count 0))))
    (is (= ["missing managed Kubernetes settings"] (sut/state-errors (dissoc vultr-fixture :vultr-node-plan)))))
  (testing "backend settings are the library's to validate"
    (is (= [":provider-backend must be one of gcs, oci, r2, s3"] (sut/state-errors (assoc do-fixture :provider-backend "local"))))
    (is (= [":r2-bucket is required"] (sut/state-errors (dissoc do-fixture :r2-bucket)))))
  (testing "a missing profile does not also surface as library refusals"
    (is (= [":profile is required"] (sut/state-errors (dissoc (assoc do-fixture :digitalocean-registry-tier nil) :profile))))))

(deftest registry-rule
  (testing "presence of the tier is the switch"
    (is (sut/registry? do-fixture))
    (is (not (sut/registry? vultr-fixture)))
    (is (not (sut/registry? (assoc do-fixture :digitalocean-registry-tier "")))))
  (testing "the registry is DigitalOcean-only"
    (is (= [":digitalocean-registry-tier requires :provider-compute digitalocean"]
           (sut/state-errors (assoc vultr-fixture :digitalocean-registry-tier "basic")))))
  (testing "the tier is one of the subscription slugs"
    (is (= [":digitalocean-registry-tier must be starter, basic, or professional"]
           (sut/state-errors (assoc do-fixture :digitalocean-registry-tier "gold")))))
  (testing "the name derives from the profile"
    (is (= "doks-fixture" (sut/registry-name do-fixture)))
    (is (= "myprofile" (sut/registry-name {:profile "My_Profile"})))
    (is (= ["the profile-derived registry name \"a\" is not a valid DigitalOcean registry name"]
           (sut/state-errors (assoc do-fixture :profile "A_"))))))

(deftest env-guard
  (is (= ["COLORS_PAR_PROFILE is set; profile must come from colors.yml only"]
         (sut/env-errors {"COLORS_PAR_PROFILE" "other"})))
  (is (nil? (sut/env-errors {"COLORS_PAR_DO_TOKEN" "x"}))))

(deftest secrets-per-event
  (let [names (fn [opts event] (vec (sut/secret-errors opts event)))]
    (testing "create and delete need provider and backend"
      (is (= ["required credential is not set: COLORS_PAR_DO_TOKEN"
              "required credential is not set: COLORS_PAR_R2_ACCESS_KEY_ID"
              "required credential is not set: COLORS_PAR_R2_SECRET_ACCESS_KEY"]
             (names do-fixture :create)))
      (is (= ["required credential is not set: COLORS_PAR_VULTR_API_KEY"
              "required credential is not set: COLORS_PAR_R2_ACCESS_KEY_ID"
              "required credential is not set: COLORS_PAR_R2_SECRET_ACCESS_KEY"]
             (names vultr-fixture :delete))))
    (testing "check needs the backend, plus the DigitalOcean token only with a registry"
      (is (= 3 (count (names do-fixture :check))))
      (is (= 2 (count (names vultr-fixture :check)))))
    (testing "kubeconfig needs the backend alone; registry the DigitalOcean token alone"
      (is (= 2 (count (names do-fixture :kubeconfig))))
      (is (= ["required credential is not set: COLORS_PAR_DO_TOKEN"] (names do-fixture :registry)))
      (is (= ["required credential is not set: COLORS_PAR_DO_TOKEN"] (names vultr-fixture :registry))))
    (testing "build needs nothing"
      (is (= [] (names do-fixture :build))))
    (testing "an overlaid value satisfies the requirement"
      (is (= [] (names (assoc do-fixture :do-token "t" :r2-access-key-id "a" :r2-secret-access-key "s") :create))))))

(deftest tofu-environment
  (is (= {:do-token "DIGITALOCEAN_TOKEN"} (sut/tofu-env do-fixture :provider-compute)))
  (is (= {:vultr-api-key "VULTR_API_KEY"} (sut/tofu-env vultr-fixture :provider-compute)))
  (is (= {:r2-access-key-id "AWS_ACCESS_KEY_ID" :r2-secret-access-key "AWS_SECRET_ACCESS_KEY"}
         (sut/tofu-env do-fixture :provider-backend)))
  (is (= {} (sut/tofu-env (assoc do-fixture :provider-backend "s3") :provider-backend))))
