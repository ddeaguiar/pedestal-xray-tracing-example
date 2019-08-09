(ns acme.videos.service.recommendations
  "Houses the recommendation service implementation."
  (:require [io.pedestal.http :as http]
            [io.pedestal.interceptor :as interceptor]
            [io.pedestal.interceptor.trace :as trace]
            [io.pedestal.log :as log]
            [io.pedestal.log.aws.xray :as xray]
            [io.pedestal.http.body-params :as body-params]
            [ring.util.response :as ring-resp]
            [clojure.java.jdbc :as jdbc]
            [honeysql.core :as sql]
            [clojure.java.io :as io]
            [cheshire.core :as json]))

(defn get-recommendations
  "Stub recommendations impl. Ignores the passed in movie id and
  returns 5 movies from `datasource`."
  [datasource _]
  (let [q (sql/format {:select [:film-id :title :description :rating :release-year]
                       :from   [:film]
                       :limit 5})]
    (jdbc/query {:datasource datasource}
                  q)))

(def recommendations
  "Recommendations interceptor. Creates a subsegment with op-name `get-recommendations`.
  Simulates latency and failure (throws 25% of the time).

  Returns a 200 response on success with the movie recommendation list as the response
  body."
  (interceptor/interceptor
   {:name  ::recommendations
    :enter (fn [{:keys [datasource] :as ctx}]
             (let [id    (Integer/parseInt (get-in ctx [:request :path-params :id]))
                   span (log/span "get-recommendations" (log/active-span))]
               (try
                 ;; simulate latency
                 (Thread/sleep (rand-int 2000))
                 ;; simulate failure
                 (when (> 25 (rand-int 100))
                   (throw (ex-info "Boom!" {})))
                 (let [recs (get-recommendations datasource id)]
                   (assoc ctx :response (ring-resp/response recs)))
                 (finally
                   (log/finish-span span)))))}))

(def tracing-interceptor
  "Recommendations service tracing interceptor. Sets `:default-span-operation` to \"RecommendationService\".
  This operation name will be used for root segments instead of the incoming request's uri."
  (trace/tracing-interceptor {:span-resolver          xray/span-resolver
                                                     :span-postprocess       xray/span-postprocess
                                                     :uri-as-span-operation? false
                                                     :default-span-operation "RecommendationService"}))

(def common-interceptors [tracing-interceptor (body-params/body-params) http/json-body])

(def routes
  "Recommentation service routes. These routes will only match requests whose host is
  `recommendations.acmevideos.com`."
  #{{:host "recommendations.acmevideos.com" :scheme :http}
    ["/recommendations/:id" :get (conj common-interceptors recommendations)]})
