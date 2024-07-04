(ns monkey.ci.plugin.github-test
  (:require [clojure.test :refer [deftest testing is]]
            [clj-github.test-helpers :as h]
            [monkey.ci.build.core :as bc]
            [monkey.ci.jobs :as j]
            [monkey.ci.plugin.github :as sut]))

(deftest create-release!
  (testing "sends `post` to release endpoint"
    (h/with-fake-github ["/repos/test-org/test-repo/releases" {:status 201}]
      (let [client (sut/make-client {:token "test-token"})]
        (is (= 201 (-> (sut/create-release! client {:org "test-org"
                                                    :repo "test-repo"
                                                    :tag "test-tag"})
                       :status)))))))

(deftest release-job
  (testing "returns a fn"
    (is (fn? (sut/release-job))))

  (testing "creates an action job for tag"
    (let [r (sut/release-job)]
      (is (bc/action-job? (r {:build
                              {:git
                               {:ref "refs/tags/0.1.0"}}})))))

  (testing "returns `nil` if no tag"
    (let [r (sut/release-job)]
      (is (nil? (r {:build
                    {:git
                     {:ref "refs/heads/main"}}})))))

  (testing "job creates github release"
    (let [r (sut/release-job {:token "test-token"})
          ctx {:build
               {:git
                {:ref "refs/tags/0.1.0"
                 :url "https://github.com/test-org/test-repo.git"}}}
          job (r ctx)
          inv (atom false)]
      (with-redefs [sut/create-release! (fn [client opts]
                                          (reset! inv true)
                                          (if (= {:org "test-org"
                                                  :repo "test-repo"
                                                  :name "0.1.0"
                                                  :tag "0.1.0"}
                                                 (select-keys opts [:org :repo :name :tag]))
                                            {:status 201}
                                            {:status 400}))]
        (is (bc/success? @(j/execute! job ctx)))
        (is (true? @inv)))))

  (testing "formats name"
    (let [r (sut/release-job {:token "test-token"
                              :name-format "v%s"})
          ctx {:build
               {:git
                {:ref "refs/tags/0.1.0"}}}
          job (r ctx)
          inv (atom false)]
      (with-redefs [sut/create-release! (fn [client opts]
                                          (reset! inv true)
                                          (if (= "v0.1.0" (:name opts))
                                            {:status 201}
                                            {:status 400}))]
        (is (bc/success? @(j/execute! job ctx)))
        (is (true? @inv))))))

(deftest parse-url
  (testing "parses https url"
    (is (= ["test-org" "test-repo"] (sut/parse-url "https://github.com/test-org/test-repo.git"))))

  (testing "parses ssh url"
    (is (= ["test-org" "test-repo"] (sut/parse-url "git@github.com:test-org/test-repo.git"))))

  (testing "`nil` on invalid url"
    (is (nil? (sut/parse-url "http://invalid")))
    (is (nil? (sut/parse-url nil)))))
