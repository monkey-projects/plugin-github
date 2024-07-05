(ns monkey.ci.plugin.github
  "Provides functions for interacting with the Github API from a MonkeyCI build"
  (:require [clj-github.httpkit-client :as ghc]
            [monkey.ci.build
             [api :as api]
             [core :as bc]]))

(def make-client
  "Creates a new Github api client.  You can pass in a token, or api key with secret."
  ghc/new-client)

(defn create-release!
  "Creates a new release for the repository identified by `org` and `repo`.
   The `tag` specifies the tag to use for the release, or, if it does not
   exist, to create.  In the latter case, a `target` commitish should be
   given on which to create the tag.  If not given, the default branch is 
   used.  An additional `name` and `desc` are used for extra information.
   Returns the http result, should be status 201 if successful."
  [c {:keys [org repo tag name desc target]}]
  (ghc/request c {:path (format "/repos/%s/%s/releases" org repo)
                  :method :post
                  :body {:tag_name tag
                         :target_commitish target
                         :name name
                         :body desc}}))

(defn parse-url
  "Parses git url to extract org and repo"
  [git-url]
  (when git-url
    (some->> [#"^https://github.com/([^\/]+)/([^\/]+).git$"
              #"^git@github.com:([^\/]+)/([^\/]+).git$"]
             (map #(re-matches % git-url))
             (filter some?)
             (first)
             (rest))))

(defn format-tag [tag {:keys [name-format] :or {name-format "v%s"}}]
  (if name-format
    (format name-format tag)
    tag))

(def token-param "github-token")

(defn release-job
  "Returns a fn that will in turn create a release job, if the conditions are met.
   By default a release will be created if the build is triggered from a tag, and
   the tag name is used to format the release name.  Note that either a `:token`
   must be specified, or a `github-token` build param must be provided."
  [& [{:keys [token] :as config}]]
  (fn [ctx]
    (letfn [(get-token-param []
              (get (api/build-params ctx) token-param))]
      (when-let [tag (bc/tag ctx)]
        (bc/action-job
         "github-release"
         (fn [ctx]
           (let [[org repo] (parse-url (get-in ctx [:build :git :url]))
                 {:keys [status]} (create-release!
                                   ;; TODO Support more authentication methods
                                   (make-client (if token
                                                  {:token token}
                                                  {:token-fn get-token-param}))
                                   {:org (get config :org org)
                                    :repo (get config :repo repo)
                                    :tag tag
                                    :name (format-tag tag config)
                                    :desc (:desc config)})]
             (if (= 201 status)
               bc/success
               (-> bc/failure
                   (bc/with-message (str "Unable to create release, got response: " status))))))
         (select-keys config [:dependencies]))))))
