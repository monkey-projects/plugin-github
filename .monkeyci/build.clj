(ns plugin-clj.build
  (:require [monkey.ci.plugin
             [clj :as p]
             [github :as gh]]))

(defn jobs [ctx]
  (concat ((p/deps-library) ctx)
          [(gh/release-job {:name-format "v%s"})]))
