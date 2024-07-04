(ns plugin-clj.build
  (:require [monkey.ci.plugin
             [clj :as p]
             [github :as gh]]))

(conj (p/deps-library)
      (gh/release-job {:name-format "v%s"}))
