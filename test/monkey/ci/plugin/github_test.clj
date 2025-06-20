(ns monkey.ci.plugin.github-test
  (:require [clojure.test :refer [deftest testing is]]
            [cheshire.core :as json]
            [clj-github
             [changeset :as cs]
             [test-helpers :as h]]
            [clojure.string :as s]
            [monkey.ci.build
             [api :as api]
             [core :as bc]]
            [monkey.ci.jobs :as j]
            [monkey.ci.test :as mt]
            [monkey.ci.plugin.github :as sut]))

(deftest create-release!
  (testing "sends `post` to release endpoint"
    (h/with-fake-github ["/repos/test-org/test-repo/releases" {:status 201}]
      (let [client (sut/make-client {:token "test-token"})]
        (is (= 201 (-> (sut/create-release! client {:org "test-org"
                                                    :repo "test-repo"
                                                    :tag "test-tag"})
                       :status))))))

  (testing "does not send `nil` values"
    (h/with-fake-github [(fn [req]
                           (->> req
                                :body
                                (json/parse-string)
                                vals
                                (not-any? nil?)))
                         {:status 201}]
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
                                                  :name "v0.1.0"
                                                  :tag "0.1.0"}
                                                 (select-keys opts [:org :repo :name :tag]))
                                            {:status 201}
                                            {:status 400}))]
        (is (bc/success? @(j/execute! job ctx)))
        (is (true? @inv)))))

  (testing "formats name"
    (let [r (sut/release-job {:token "test-token"
                              :name-format "version %s"})
          ctx {:build
               {:git
                {:ref "refs/tags/0.1.0"}}}
          job (r ctx)]
      (with-redefs [sut/create-release! (fn [client opts]
                                          (if (= "version 0.1.0" (:name opts))
                                            {:status 201}
                                            {:status 400}))]
        (is (bc/success? @(j/execute! job ctx))))))

  (testing "uses provided token"
    (let [r (sut/release-job {:token "test-token"})
          ctx {:build
               {:git
                {:ref "refs/tags/0.1.0"
                 :url "https://github.com/test-org/test-repo.git"}}}
          job (r ctx)]
      (with-redefs [sut/create-release! (fn [client opts]
                                          (if (= "test-token" ((:token-fn client)))
                                            {:status 201}
                                            {:status 400}))]
        (is (bc/success? @(j/execute! job ctx))))))

  (testing "uses `github-token` build param"
    (let [r (sut/release-job)
          ctx {:build
               {:git
                {:ref "refs/tags/0.1.0"
                 :url "https://github.com/test-org/test-repo.git"}}}
          job (r ctx)]
      (with-redefs [api/build-params (constantly {"github-token" "test-token"})
                    sut/create-release! (fn [client opts]
                                          (if (= "test-token" ((:token-fn client)))
                                            {:status 201}
                                            {:status 400}))]
        (is (bc/success? @(j/execute! job ctx))))))

  (testing "fails if no token provided"
    (let [r (sut/release-job)
          ctx {:build
               {:git
                {:ref "refs/tags/0.1.0"
                 :url "https://github.com/test-org/test-repo.git"}}}
          job (r ctx)]
      (with-redefs [api/build-params {}
                    sut/create-release! {:status 500 :body "Unexpected invocation"}]
        (is (bc/failed? @(j/execute! job ctx))))))

  (testing "fails on backend exception"
    (let [r (sut/release-job)
          ctx {:build
               {:git
                {:ref "refs/tags/0.1.0"
                 :url "https://github.com/test-org/test-repo.git"}}}
          job (r ctx)]
      (with-redefs [api/build-params {}
                    sut/create-release! (fn [& _]
                                         (throw (ex-info
                                                 "Test error"
                                                 {:response
                                                  {:status 500 :body "Github error"}})))]
        (let [r @(j/execute! job ctx)]
          (is (bc/failed? r))
          (is (s/includes? (:message r) "500"))))))

  (testing "adds dependencies"
    (let [r (sut/release-job {:dependencies ["other-job"]})
          ctx {:build
               {:git
                {:ref "refs/tags/0.1.0"
                 :url "https://github.com/test-org/test-repo.git"}}}
          job (r ctx)
          inv (atom false)]
      (with-redefs [api/build-params (constantly {"github-token" "test-token"})]
        (is (= ["other-job"] (:dependencies job)))))))

(deftest parse-url
  (testing "parses https url"
    (is (= ["test-org" "test-repo"] (sut/parse-url "https://github.com/test-org/test-repo.git"))))

  (testing "parses ssh url"
    (is (= ["test-org" "test-repo"] (sut/parse-url "git@github.com:test-org/test-repo.git"))))

  (testing "`nil` on invalid url"
    (is (nil? (sut/parse-url "http://invalid")))
    (is (nil? (sut/parse-url nil)))))

(deftest patch-file
  (testing "downloads file, applies arg fn to it and commits the changes"
    (let [path "path/to/file"]
      (with-redefs [cs/from-branch!
                    (constantly {:base-revision ::test-changeset})
                    
                    cs/get-content
                    (constantly "original file contents")
                    
                    cs/put-content
                    (fn [cs p content]
                      (when (and (= "updated file contents" content)
                                 (= p path))
                        {:base-revision ::new-changeset}))
                    
                    cs/update-branch!
                    identity]
        (is (= ::new-changeset
               (-> (sut/patch-file
                    (sut/make-client {:token "test-token"})
                    {:org "test-org"
                     :repo "test-repo"
                     :branch "test-branch"
                     :path path}
                    s/replace #"^original" "updated")
                   :base-revision)))))))

(deftest patch-job
  (testing "creates action job"
    (is (bc/action-job? (sut/patch-job {:path "test/file"}))))

  (testing "applies patcher to file at location"
    (let [job (sut/patch-job {:path "test/file"
                              :org "test-org"
                              :repo "test-repo"
                              :branch "test-branch"
                              :patcher (constantly "patched")})
          ctx mt/test-ctx
          patches (atom [])]
      (with-redefs [sut/patch-file (fn [_ opts _]
                                     (swap! patches conj opts))]
        (mt/with-build-params {}
          (is (bc/success? @(j/execute! job ctx)))
          (is (= 1 (count @patches)))
          (is (= {:org "test-org"
                  :repo "test-repo"
                  :branch "test-branch"}
                 (select-keys (first @patches) [:org :repo :branch]))))))))
