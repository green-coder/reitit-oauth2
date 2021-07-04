(ns oauth-test.core
  (:require [integrant.core :as ig]
            [oauth-test.http-server]
            [oauth-test.ring-handler]))

(def config
  {:http-kit/server {:handler (ig/ref :ring/handler)
                     :port 3000}
   :ring/handler {}})

(def system
  (ig/init config))

(comment
  (ig/halt! system))
