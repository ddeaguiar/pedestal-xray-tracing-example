(ns acme.videos.service.rentals
  "Houses the rental service implementation."
  (:require [io.pedestal.http :as http]
            [io.pedestal.interceptor :as interceptor]
            [io.pedestal.interceptor.trace :as trace]
            [io.pedestal.log :as log]
            [io.pedestal.log.aws.xray :as xray]
            [io.pedestal.http.body-params :as body-params]
            [ring.util.response :as ring-resp]
            [clojure.java.jdbc :as jdbc]
            [honeysql.core :as sql]
            [honeysql-postgres.helpers :as psqlh]
            [honeysql-postgres.format]
            [clojure.java.io :as io]
            [cheshire.core :as json]
            [clj-time.coerce])
  (:import (com.amazonaws.xray.proxies.apache.http HttpClientBuilder)
           (com.amazonaws.xray.entities Entity)
           (org.apache.http.client.methods HttpGet)
           (com.amazonaws.services.sns AmazonSNS)
           (java.util Date UUID)))

(defn- tag-sql
  "Refer to https://docs.aws.amazon.com/xray/latest/devguide/xray-api-segmentdocuments.html#api-segmentdocuments-sql
  for valid keys. Unrecognized keys are ignored."
  [span k v]
  (.putSql ^Entity span k v))

(def movies
  "List movies interceptor. Creates a subsegment with the op-name `list-movies`.
  If the `:title` query parameter is specified, it is used to filter movies and
  the `title` annotation is added to the `list-movies` subsegment.

  Logs the list movies query to the `list-movies` subsegment using the `sql` `sanitized_query`
  metadata.

  Returns a 200 response on success with the retrieved movies as the response body.

  Returns a 500 response on error. Logs errors and adds exception info to the
  `list-movies` subsegment."
  (interceptor/interceptor
   {:name  ::movies
    :enter (fn [{:keys [datasource] :as ctx}]
             (let [title       (-> ctx :request :params :title)
                   parent-span (log/active-span)
                   span        (if title
                                 (log/span "list-movies"
                                           parent-span
                                           ;; Can't use a Clojure map
                                           ;; See https://github.com/pedestal/pedestal/issues/626
                                           {:initial-tags (doto (java.util.concurrent.ConcurrentHashMap.)
                                                            (.put "title" title))})
                                 (log/span "list-movies" parent-span))
                   q           (sql/format
                                (cond-> {:select [:film-id :title :description :rating :release-year]
                                         :from   [:film]}
                                  title
                                  (assoc :where [:like :title (str "%" title "%")])))]
               (try
                 (tag-sql span "sanitized_query" (first q))
                 (let [result (vec (jdbc/query {:datasource datasource}
                                               q))]
                   (assoc ctx :response (ring-resp/response result)))
                 (catch Exception e
                   (let [msg "Failed to retrieve movies."]
                     (log/error :msg msg :exception e)
                     (log/log-span span e)
                     (assoc ctx :response (-> (ring-resp/response {:error msg})
                                              (ring-resp/status 500)))))
                 (finally
                   (log/finish-span span)))))}))

(defn get-movie
  "Given `datasource` and `id`, retrieves the movie identified by `id` from the
  database.

  Result contains `:query` metadata."
  [datasource id]
  (let [q (sql/format {:select [:film-id :title :description :rating :release-year]
                       :from   [:film]
                       :where  [:= :film-id id]})
        result (jdbc/query {:datasource datasource}
                           q)]
    (with-meta result {:query (first q)})))

(defn get-recommendations
  "Retrieves movie recommendations from the recommendation service based on `id`.
  Uses the AWS XRay HttpClient proxy to trace the request.

  May return `nil`.

  Ignores non-200 results."
  [id]
  (try
    (let [http-client (.build (HttpClientBuilder/create))
          response    (.execute http-client (HttpGet. (str "http://recommendations.acmevideos.com:8080/recommendations/" id)))
          content     (io/reader (.getContent (.getEntity response)))
          status      (.. response (getStatusLine) (getStatusCode))]
      (when (= 200 status)
        (json/parse-stream content keyword)))
    (catch Exception e
      (log/error :msg "Recommendation service integration error." :exception e))))

(def movie
  "Movie interceptor. Creates a subsegment with op-name `get-movie`.
  Retrieves a movie from the db based on the `:id` path parameter and
  enriches the result with recommendations. Recommendation retrieval failure
  are treated as if no recommendations were found and the movie result will not be
  enriched.

  Logs movie query to the `get-movie` subsegment using the `sql` `sanitized_query`
  metadata.

  Logs a `:recommendations-requested` event to the `get-movie` subsegment. This event is
  available through the subsegment's metadata.

  Returns a 200 response on success with the retrieved movie as the response body.

  Returns a 404 response if movie not found.
  Returns a 500 response on error. Logs errors and adds exception info to the
  `get-movie` subsegment."
  (interceptor/interceptor
   {:name  ::movie
    :enter (fn [{:keys [datasource] :as ctx}]
             (let [id          (Integer/parseInt (get-in ctx [:request :path-params :id]))
                   parent-span (log/active-span)
                   span        (log/span "get-movie" parent-span)]
               (try
                 (let [movie-result (get-movie datasource id)]
                   (tag-sql (log/active-span) "sanitized_query" (-> movie-result
                                                                    meta
                                                                    :query))
                   (if (seq movie-result)
                     (let [movie (first movie-result)
                           recs  (get-recommendations id)]
                       (log/log-span span :recommendations-requested)
                       (assoc ctx :response (ring-resp/response (cond-> movie
                                                                  recs
                                                                  (assoc :recommendations recs)))))
                     (assoc ctx :response (-> (ring-resp/response {:error "Not Found"})
                                              (ring-resp/status 404)))))
                 (catch Exception e
                   (log/error :msg "Unable to get movie." :exception e)
                   (log/log-span span e)
                   (assoc ctx :response (-> (ring-resp/response {:error "Failed to get movie"})
                                            (ring-resp/status 500))))
                 (finally
                   (log/finish-span span)))))}))

(defn movie-inventory
  "Returns a collection of inventory ids from `datasource` based on
  `movie-id` and `store-id`."
  [datasource movie-id store-id]
  (let [stmt ["SELECT film_in_stock(?,?)" movie-id store-id]
        results (->> stmt
                     (jdbc/query {:datasource datasource})
                    (mapcat vals))]
    results))

(defn persist-rental
  "Persists rental to the db.

  Returns the rental id.
  Throws on error."
  [datasource inventory-id customer-id]
  (let [q (sql/format (-> {:insert-into :rental
                           :values      [{:inventory_id inventory-id
                                          :customer_id  customer-id
                                          :staff_id     1
                                          :rental_date  (clj-time.coerce/to-sql-time (Date.))}]}
                          (psqlh/returning :rental_id)))
        result (jdbc/query {:datasource datasource}
                           q)]
    (-> result first :rental_id)))

(defn send-rental-notification
  "Sends a notificagtion to the topic specified by the `SNS_TOPIC_ARN` envar.
  Logs errors but does not throw."
  [sns-client msg]
  (try
    (.publish ^AmazonSNS sns-client (System/getenv "SNS_TOPIC_ARN")
              (json/encode msg))
    (catch Exception e
      (log/error :msg "Unable to send rental notification." :exception e))))

(def get-inventory
  "Get inventory interceptor. Creates a subsegment with op-name `get-inventory` and
  adds the `movie-id` (as `movie_id`) and `customer-id` (as `customer_id`)
  as subsegment annotations.

  If inventory exists, assoc's `::inventory-id`, `::movie-id` and `::customer-id` to the
  context.

  Notes:

  - `-` are not valid in annotations. See https://docs.aws.amazon.com/xray/latest/devguide/xray-sdk-java-segment.html#xray-sdk-java-segment-annotations.
  - Inventory checks are limited to store `1` for demo purposes.

  Returns a 404 response if no inventory found.
  Returns a 500 response on error. Logs errors and adds exception info to the
  `get-inventory` span."
  (interceptor/interceptor
   {:name ::get-inventory
    :enter (fn [{{{:keys [movie-id customer-id]} :json-params} :request
                 datasource                                    :datasource
                 :as ctx}]
             (let [span (log/span "get-inventory"
                                  (log/active-span)
                                  {:initial-tags (doto (java.util.concurrent.ConcurrentHashMap.)
                                                   (.put "movie_id" movie-id)
                                                   (.put "customer_id" customer-id))})]
               (try
                 (let [;; We'll limit rentals to store `1`
                       store-id (int 1)
                       inventory   (movie-inventory datasource movie-id store-id)]
                   (if (seq inventory)
                     (assoc ctx
                            ::inventory-id (first inventory)
                            ::movie-id movie-id
                            ::customer-id customer-id)
                     (assoc ctx :response (-> (ring-resp/response {:error "No inventory."})
                                              (ring-resp/status 404)))))
                 (catch Exception e
                   (let [msg "Unable to check inventory."]
                     (log/error :msg msg :exception e)
                     (log/log-span span e)
                     (assoc ctx :response (-> (ring-resp/response {:error msg})
                                              (ring-resp/status 500)))))
                 (finally
                   (log/finish-span span)))))}))

(def rent-movie
  "Movie rental interceptor. Creates a subsegment with op-name `rent-movie` and
  adds `::inventory-id` (as `inventory_id`), `::movie-id` (as `movie_id`),
  `::customer-id` (as `customer_id`) and the resulting rental id (as `rental_id`)
  as subsegment annotations.

  Note: `-` are not valid in annotations. See https://docs.aws.amazon.com/xray/latest/devguide/xray-sdk-java-segment.html#xray-sdk-java-segment-annotations.

  Persists rental to the db and sends a rental notification but does not throw
  on rental notification failure.

  Returns a 201 response on success.
  Returns a 500 response on error. Logs errors and adds exception info to the
  `rent-movie` subsegment."
  (interceptor/interceptor
   {:name  ::rentals
    :enter (fn [{:keys [datasource sns-client ::movie-id ::customer-id ::inventory-id] :as ctx}]
             (let [ parent-span (log/active-span)
                   span (log/span "rent-movie"
                                  parent-span
                                  {:initial-tags (doto (java.util.concurrent.ConcurrentHashMap.)
                                                   (.put "movie_id" movie-id)
                                                   (.put "customer_id" customer-id)
                                                   (.put "inventory_id" inventory-id))})]
               (try
                 (let [rental-id (persist-rental datasource inventory-id customer-id)]
                   (log/log-span span "rental_id" rental-id)
                   (send-rental-notification sns-client {:movie-id    movie-id
                                                         :customer-id customer-id
                                                         :rental-id rental-id})
                   (assoc ctx :response (-> (ring-resp/response {:rental-id rental-id})
                                            (ring-resp/status 201))))
                 (catch Exception e
                   (log/error :msg "Unable to rent movie." :exception e)
                   (log/log-span span e)
                   (assoc ctx :response (-> (ring-resp/response {:error "Failed to rent movie"})
                                            (ring-resp/status 500))))
                 (finally
                   (log/finish-span span)))))}))

(def tracing-interceptor
  "Rental service tracing interceptor. Sets `:default-span-operation` to \"RentalService\".
  This operation name will be used for root segments instead of the incoming request's uri."
  (trace/tracing-interceptor {:span-resolver          xray/span-resolver
                              :span-postprocess       xray/span-postprocess
                              :uri-as-span-operation? false
                              :default-span-operation "RentalsService"}))

(def common-interceptors [tracing-interceptor (body-params/body-params) http/json-body])

(def routes
  "Rental service routes. These routes will only match requests whose host is
  `rental.acmevidoes.com`."
  #{{:host "rentals.acmevideos.com" :scheme :http}
    ["/movies" :get (conj common-interceptors movies)]
    ["/movie/:id" :get (conj common-interceptors movie)]
    ["/rentals" :post (into common-interceptors [get-inventory rent-movie])]})
