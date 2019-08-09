(ns acme.videos.service
  (:require [io.pedestal.http.route :as route]
            [io.pedestal.http :as http]
            [acme.videos.service.rentals :as rentals]
            [acme.videos.service.recommendations :as recommendations]))

(defn combined-routes
  []
  (into (route/expand-routes rentals/routes)
        (route/expand-routes recommendations/routes)))

(def service {:env                     :prod
              ::http/routes            (combined-routes)
              ::http/resource-path     "/public"
              ::http/type              :jetty
              ::http/port              8080
              ::http/container-options {:h2c? true
                                        :h2?  false
                                        :ssl? false}})
