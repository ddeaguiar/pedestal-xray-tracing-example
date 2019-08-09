(ns acme.videos.server
  (:gen-class) ; for -main method in uberjar
  (:require [io.pedestal.http :as server]
            [io.pedestal.http.route :as route]
            [io.pedestal.interceptor :as interceptor]
            [acme.videos.service :as service])
  (:import (org.apache.tomcat.jdbc.pool DataSource)
           (com.amazonaws.services.sns.model MessageAttributeValue PublishRequest)
           (com.amazonaws.services.sns AmazonSNS AmazonSNSAsyncClient AmazonSNSAsyncClientBuilder)))

(defn- make-db-url
  "Returns the JDBC connection string based on the passed in opts.
  Supported opts:

  - :host (REQUIRED) The db host name.
  - :port (REQUIRED) The db port.
  - :db   (REQUIRED) The db name."
  [{:keys [host port db]}]
  (str "jdbc:postgresql://"
       host
       ":"
       port
       "/"
       db))

(defn inject-db-interceptor
  "Given `service-map`, creates a datasource and returns an interceptor
  which appends the datasource as `:datasource` to the context."
  [service-map]
  (let [ds (doto (DataSource.)
             (.setUrl (make-db-url {:host (System/getenv "DB_HOST")
                                    :port (System/getenv "DB_PORT")
                                    :db   (System/getenv "DB_NAME")}))
             (.setUsername (System/getenv "DB_USERNAME"))
             (.setPassword (System/getenv "DB_PASSWORD"))
             (.setDriverClassName "org.postgresql.Driver")
             (.setJdbcInterceptors "com.amazonaws.xray.sql.postgres.TracingInterceptor"))]
    (update-in service-map [::server/interceptors]
               #(vec (cons (interceptor/interceptor
                            {:name  ::db-interceptor
                             :enter (fn [ctx]
                                      (assoc ctx :datasource ds))}) %)))))

(defn inject-sns-client
  "Given `service-map`, creates an sns client and returns an interceptor
  which appends the sns client as `:sns-client` to the context."
  [service-map]
  (let [sns-client (AmazonSNSAsyncClientBuilder/defaultClient)]
    (update-in service-map [::server/interceptors]
               #(vec (cons (interceptor/interceptor
                            {:name  ::sns-interceptor
                             :enter (fn [ctx]
                                      (assoc ctx :sns-client sns-client))}) %)))))

(defn run-dev
  "The entry-point for 'lein run-dev'"
  [& args]
  (println "\nCreating your [DEV] server...")
  (-> service/service ;; start with production configuration
      (merge {:env :dev
              ;; do not block thread that starts web server
              ::server/join? false
              ;; Routes can be a function that resolve routes,
              ;;  we can use this to set the routes to be reloadable
              ::server/routes #(service/combined-routes)
              ;; all origins are allowed in dev mode
              ::server/allowed-origins {:creds true :allowed-origins (constantly true)}
              ;; Content Security Policy (CSP) is mostly turned off in dev mode
              ::server/secure-headers {:content-security-policy-settings {:object-src "'none'"}}})
      ;; Wire up interceptor chains
      server/default-interceptors
      server/dev-interceptors
      ;; inject app-specific components into the context.
      inject-db-interceptor
      inject-sns-client
      ;; create and start the server
      server/create-server
      server/start))

;; This is an adapted service map, that can be started and stopped
;; From the REPL you can call server/start and server/stop on this service
(defonce runnable-service (-> service/service
                              server/default-interceptors
                              inject-db-interceptor
                              inject-sns-client
                              server/create-server))

(defn -main
  "The entry-point for 'lein run'"
  [& args]
  (println "\nCreating your server...")
  (server/start runnable-service))
