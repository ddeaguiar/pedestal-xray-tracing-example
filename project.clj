(defproject rentals "0.0.1-SNAPSHOT"
  :description "Video rental service example illustrating tracing with AWS XRay."
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.10.1"]

                 [io.pedestal/pedestal.service "0.5.7"]
                 [io.pedestal/pedestal.jetty "0.5.7"]

                 ;; logging deps
                 [ch.qos.logback/logback-classic "1.2.3" :exclusions [org.slf4j/slf4j-api]]
                 [org.slf4j/jul-to-slf4j "1.7.26"]
                 [org.slf4j/jcl-over-slf4j "1.7.26"]
                 [org.slf4j/log4j-over-slf4j "1.7.26"]

                 ;; db deps
                 [org.postgresql/postgresql "42.2.5.jre7"]
                 [org.clojure/java.jdbc "0.7.8"]
                 [honeysql "0.9.4"]
                 [nilenso/honeysql-postgres "0.2.6"]

                 ;; X-Ray Tracing support
                 [io.pedestal/pedestal.aws "0.5.7" :exclusions [com.amazonaws/aws-java-sdk-core
                                                                com.amazonaws/aws-xray-recorder-sdk-core]]

                 [com.amazonaws/aws-xray-recorder-sdk-core "2.3.0" :exclusions [org.apache.httpcomponents/httpclient]]
                 ;; Tracing SDK interactions
                 [com.amazonaws/aws-xray-recorder-sdk-aws-sdk "2.3.0" :exclusions [org.apache.httpcomponents/httpclient]]
                 ;; Tracing db interactions
                 [com.amazonaws/aws-xray-recorder-sdk-sql-postgres "2.3.0" :exclusions [org.apache.httpcomponents/httpclient]]
                 ;; Tracing HTTP client to test propagation
                 [com.amazonaws/aws-xray-recorder-sdk-apache-http "2.3.0" :exclusions [org.apache.httpcomponents/httpclient]]
                 ;; Instrument all aws clients
                 [com.amazonaws/aws-xray-recorder-sdk-aws-sdk-instrumentor "2.3.0"]

                 ;; aws SDKs
                 [com.amazonaws/aws-java-sdk-core "1.11.598" :exclusions [commons-logging]] ;; Needed for x-ray
                 [com.amazonaws/aws-java-sdk-s3 "1.11.598" :exclusions [org.apache.httpcomponents/httpclient]]
                 [com.amazonaws/aws-java-sdk-sns "1.11.598" :exclusions [org.apache.httpcomponents/httpclient]]
                 [com.amazonaws/aws-java-sdk-sqs "1.11.598" :exclusions [org.apache.httpcomponents/httpclient]]]
  :min-lein-version "2.0.0"
  :resource-paths ["config", "resources"]
  ;; If you use HTTP/2 or ALPN, use the java-agent to pull in the correct alpn-boot dependency
  ;:java-agents [[org.mortbay.jetty.alpn/jetty-alpn-agent "2.0.5"]]
  :profiles {:provided {:dependencies [[org.apache.tomcat/tomcat-jdbc "8.0.36"]]}
             :dev {:aliases {"run-dev" ["trampoline" "run" "-m" "acme.videos.server/run-dev"]}
                   :dependencies [[io.pedestal/pedestal.service-tools "0.5.7"]]}
             :uberjar {:aot [acme.videos.server]}}
  :main ^{:skip-aot true} acme.videos.server)
