(ns monkey.ci.plugin.github
  "Provides functions for interacting with the Github API from a MonkeyCI build"
  (:require [clj-github
             [changeset :as cs]
             [httpkit-client :as ghc]]
            [medley.core :as mc]
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
                  :body (->> {:tag_name tag
                              :target_commitish target
                              :name name
                              :body desc}
                             (mc/filter-vals some?))}))

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

(defn github-job
  "Template that invokes a target fn using a github client created from context."
  [token f]
  (fn [ctx]
    (letfn [(get-token-param []
              (get (api/build-params ctx) token-param))]
      ;; TODO Support more authentication methods
      (let [client (make-client (if token
                                  {:token token}
                                  {:token-fn get-token-param}))]
        (f client ctx)))))

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
         (github-job
          token
          (fn [client _]
            (try
              (let [[org repo] (parse-url (get-in ctx [:build :git :url]))
                    {:keys [status]} (create-release!
                                      client
                                      {:org (get config :org org)
                                       :repo (get config :repo repo)
                                       :tag tag
                                       :name (format-tag tag config)
                                       :desc (:desc config)})]
                (if (= 201 status)
                  bc/success
                  (-> bc/failure
                      (bc/with-message (str "Unable to create release, got response: " status)))))
              (catch Exception ex
                (let [resp (:response (ex-data ex))]
                  (println "Github error response:" (:body resp))
                  (-> bc/failure
                      (bc/with-message
                        (str "Unable to create release, got response: " (:status resp)))))))))
         (select-keys config [:dependencies]))))))

(defn make-changeset [c {:keys [org repo branch]}]
  ;; TODO Check params because the error thrown if a param is nil is cryptic
  (cs/from-branch! c org repo branch))

(defn patch-files
  "Patches multiple files, as indiciated in the `patches` option.  Each patch
   contains a `path` and a `patcher` fn, which receives the file contents and
   returns updated contents.  A single commit is created for the combined 
   patches."
  [client {:keys [commit-msg patches] :as opts}]
  (letfn [(apply-patch [cs {:keys [path patcher]}]
            (cs/update-content cs path patcher))
          (apply-patches [cs]
            (reduce apply-patch cs patches))]
    (some-> (make-changeset client opts)
            (apply-patches)
            (cs/commit! commit-msg)
            (cs/update-branch!))))

(defn patch-file
  "Patches file indicated by given location by applying `f` to it with arguments.
   A new commit is created on the specific branch with the given commit msg."
  [client opts f & args]
  (patch-files client
               (-> opts
                   (dissoc :path)
                   (assoc :patches [{:path (:path opts)
                                     :patcher (fn [c]
                                                (apply f c args))}]))))

(defn patch-job
  "Creates a job that patches one or more files in a Github repo.  Either a
   `path` and a `patcher` is given, or multiple `patches` are specified. The
    patcher functions receive the file contents as argument, and are expected
    to return the new file contents."
  [{:keys [job-id token org repo branch]
    :or {job-id "patch"
         branch "main"}
    :as opts}]
  (bc/action-job
   job-id
   (github-job
    token
    (fn [client ctx]
      (try
        (let [opts (cond-> (dissoc opts :patcher :path)
                     (:patcher opts) (assoc :patches [(select-keys opts [:patcher :path])]))]
          (if (patch-files client opts)
            bc/success
            bc/failure))
        (catch Exception ex
          ;; Print response
          (println "Github request failed:" (:response (ex-data ex)))
          (bc/with-message bc/failure (ex-message ex))))))
   (select-keys opts [:dependencies])))
