(ns oauth-test.http-server
  (:require [integrant.core :as ig]
            [org.httpkit.server :as http-kit]))

(defmethod ig/init-key :http-kit/server [_ {:keys [handler port]}]
  (http-kit/run-server handler {:port port
                                :legacy-return-value? false}))

(defmethod ig/halt-key! :http-kit/server [_ server]
  (http-kit/server-stop! server))
