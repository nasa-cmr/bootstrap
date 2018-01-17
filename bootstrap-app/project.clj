(defproject nasa-cmr/cmr-bootstrap-app "0.1.0-SNAPSHOT"
  :description "Bootstrap is a CMR application that can bootstrap the CMR with data from Catalog REST."
  :url "https://github.com/nasa/Common-Metadata-Repository/tree/master/bootstrap-app"
  :exclusions [
    [cheshire]
    [clj-time]
    [com.fasterxml.jackson.core/jackson-core]
    [commons-codec/commons-codec]
    [commons-io]
    [org.apache.httpcomponents/httpclient]
    [org.apache.httpcomponents/httpcore]
    [org.clojure/tools.reader]
    [potemkin]
    [ring/ring-codec]]
  :dependencies [
    [cheshire "5.8.0"]
    [clj-http "2.3.0"]
    [clj-time "0.14.2"]
    [com.fasterxml.jackson.core/jackson-core "2.9.3"]
    [commons-codec/commons-codec "1.11"]
    [commons-io "2.6"]
    [compojure "1.6.0"]
    [nasa-cmr/cmr-access-control-app "0.1.0-SNAPSHOT"]
    [nasa-cmr/cmr-common-app-lib "0.1.0-SNAPSHOT"]
    [nasa-cmr/cmr-indexer-app "0.1.0-SNAPSHOT"]
    [nasa-cmr/cmr-metadata-db-app "0.1.0-SNAPSHOT"]
    [nasa-cmr/cmr-oracle-lib "0.1.0-SNAPSHOT"]
    [nasa-cmr/cmr-transmit-lib "0.1.0-SNAPSHOT"]
    [nasa-cmr/cmr-virtual-product-app "0.1.0-SNAPSHOT"]
    [org.apache.httpcomponents/httpclient "4.5.4"]
    [org.apache.httpcomponents/httpcore "4.4.8"]
    [org.clojure/clojure "1.8.0"]
    [org.clojure/tools.nrepl "0.2.13"]
    [org.clojure/tools.reader "1.1.1"]
    [potemkin "0.4.4"]
    [ring/ring-codec "1.1.0"]
    [ring/ring-core "1.6.3"]
    [ring/ring-json "0.4.0"]]
  :plugins [
    [drift "1.5.3"]
    [lein-exec "0.3.7"]
    [lein-shell "0.5.0"]
    [test2junit "1.3.3"]]
  :repl-options {:init-ns user}
  :jvm-opts ^:replace ["-server"
                       "-Dclojure.compiler.direct-linking=true"]
  :profiles {
    :dev {
      :dependencies [
        [org.clojure/tools.namespace "0.2.11"]
        [org.clojars.gjahad/debug-repl "0.3.3"]]
      :jvm-opts ^:replace ["-server"]
      :source-paths ["src" "dev" "test"]}
    :uberjar {:main cmr.bootstrap.runner
              :aot :all}
    :static {}
    ;; This profile is used for linting and static analysis. To run for this
    ;; project, use `lein lint` from inside the project directory. To run for
    ;; all projects at the same time, use the same command but from the top-
    ;; level directory.
    :lint {
      :source-paths ^:replace ["src"]
      :test-paths ^:replace []
      :plugins [
        [jonase/eastwood "0.2.5"]
        [lein-ancient "0.6.15"]
        [lein-bikeshed "0.5.0"]
        [lein-kibit "0.1.6"]
        [venantius/yagni "0.1.4"]]}}
  ;; Database migrations run by executing "lein migrate"
  :aliases {"create-user" ["exec" "-p" "./support/create_user.clj"]
            "drop-user" ["exec" "-p" "./support/drop_user.clj"]
            ;; Prints out documentation on configuration environment variables.
            "env-config-docs" ["exec" "-ep" "(do (use 'cmr.common.config) (print-all-configs-docs) (shutdown-agents))"]
            ;; Alias to test2junit for consistency with lein-test-out
            "test-out" ["test2junit"]
            ;; Linting aliases
            "kibit" ["do" ["with-profile" "lint" "shell" "echo" "== Kibit =="]
                          ["with-profile" "lint" "kibit"]]
            "eastwood" ["with-profile" "lint" "eastwood" "{:namespaces [:source-paths]}"]
            "bikeshed" ["with-profile" "lint" "bikeshed" "--max-line-length=100"]
            "yagni" ["with-profile" "lint" "yagni"]
            "check-deps" ["with-profile" "lint" "ancient" ":all"]
            "lint" ["do" ["check"] ["kibit"] ["eastwood"]]
            ;; Placeholder for future docs and enabler of top-level alias
            "generate-static" ["with-profile" "static" "shell" "echo"]})
