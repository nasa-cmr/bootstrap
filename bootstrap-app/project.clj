(defproject nasa-cmr/cmr-bootstrap-app "0.1.0-SNAPSHOT"
  :description "Bootstrap is a CMR application that can bootstrap the CMR with data from Catalog REST."
  :url "***REMOVED***projects/CMR/repos/cmr/browse/bootstrap-app"
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [clj-http "2.0.0"]
                 [nasa-cmr/cmr-oracle-lib "0.1.0-SNAPSHOT"]
                 [nasa-cmr/cmr-metadata-db-app "0.1.0-SNAPSHOT"]
                 [nasa-cmr/cmr-indexer-app "0.1.0-SNAPSHOT"]
                 [nasa-cmr/cmr-common-app-lib "0.1.0-SNAPSHOT"]
                 [nasa-cmr/cmr-virtual-product-app "0.1.0-SNAPSHOT"]
                 [compojure "1.5.1"]
                 [ring/ring-core "1.5.0"]
                 [ring/ring-json "0.4.0"]
                 [org.clojure/tools.nrepl "0.2.12"]
                 [nasa-cmr/cmr-transmit-lib "0.1.0-SNAPSHOT"]]
  :plugins [[test2junit "1.2.1"]
            [drift "1.5.3"]
            [lein-exec "0.3.2"]]

  :repl-options {:init-ns user}
  :jvm-opts ^:replace ["-server"
                       "-Dclojure.compiler.direct-linking=true"]
  :profiles
  {:dev {:dependencies [[org.clojure/tools.namespace "0.2.11"]
                        [org.clojars.gjahad/debug-repl "0.3.3"]]
         :jvm-opts ^:replace ["-server"]
         :source-paths ["src" "dev" "test"]}
   :uberjar {:main cmr.bootstrap.runner
             :aot :all}}
  ;; Database migrations run by executing "lein migrate"
  :aliases {"create-user" ["exec" "-p" "./support/create_user.clj"]
            "drop-user" ["exec" "-p" "./support/drop_user.clj"]
            ;; Prints out documentation on configuration environment variables.
            "env-config-docs" ["exec" "-ep" "(do (use 'cmr.common.config) (print-all-configs-docs) (shutdown-agents))"]
            ;; Alias to test2junit for consistency with lein-test-out
            "test-out" ["test2junit"]})
