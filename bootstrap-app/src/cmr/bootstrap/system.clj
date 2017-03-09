(ns cmr.bootstrap.system
  "Defines functions for creating, starting, and stopping the application. Applications are
  represented as a map of components. Design based on
  http://stuartsierra.com/2013/09/15/lifecycle-composition and related posts."
  (:require
   [clojure.core.async :as ca :refer [chan]]
   [clojure.string :as str]
   [cmr.access-control.system :as ac-system]
   [cmr.acl.core :as acl]
   [cmr.bootstrap.api.routes :as routes]
   [cmr.bootstrap.config :as bootstrap-config]
   [cmr.bootstrap.data.bulk-index :as bi]
   [cmr.bootstrap.data.bulk-migration :as bm]
   [cmr.bootstrap.data.virtual-products :as vp]
   [cmr.bootstrap.services.replication :as replication]
   [cmr.common-app.api.health :as common-health]
   [cmr.common-app.services.jvm-info :as jvm-info]
   [cmr.common-app.services.kms-fetcher :as kf]
   [cmr.common.api.web-server :as web]
   [cmr.common.cache :as cache]
   [cmr.common.cache.in-memory-cache :as mem-cache]
   [cmr.common.config :as cfg :refer [defconfig]]
   [cmr.common.jobs :as jobs]
   [cmr.common.lifecycle :as lifecycle]
   [cmr.common.log :as log :refer (debug info warn error)]
   [cmr.common.nrepl :as nrepl]
   [cmr.common.system :as common-sys]
   [cmr.indexer.data.concepts.granule :as g]
   [cmr.indexer.system :as idx-system]
   [cmr.metadata-db.config :as mdb-config]
   [cmr.metadata-db.system :as mdb-system]
   [cmr.oracle.connection :as oracle]
   [cmr.transmit.config :as transmit-config]))

(defconfig db-batch-size
  "Batch size to use when batching database operations."
  {:default 100 :type Long})

(defconfig bootstrap-log-level
  "App logging level"
  {})

(def ^:private component-order
  "Defines the order to start the components."
  [:log :caches :db :scheduler :db-scheduler :web :nrepl])

(def system-holder
  "Required for jobs"
  (atom nil))

(defn create-system
  "Returns a new instance of the whole application."
  []
  (let [metadata-db (-> (mdb-system/create-system "metadata-db-in-bootstrap-pool")
                        (dissoc :log :web :scheduler :unclustered-scheduler :queue-broker))
        indexer (-> (idx-system/create-system)
                    (dissoc :log :web :queue-broker)
                    ;; Setting the parent-collection-cache to cache parent collection umm
                    ;; of granules during bulk indexing.
                    (assoc-in [:caches g/parent-collection-cache-key]
                              (mem-cache/create-in-memory-cache :lru {} {:threshold 2000}))
                    ;; Specify an Elasticsearch http retry handler
                    (assoc-in [:db :config :retry-handler] bi/elastic-retry-handler))
        access-control (-> (ac-system/create-system)
                           (dissoc :log :web :queue-broker)
                           (assoc-in [:db :config :retry-handler] bi/elastic-retry-handler))
        sys {:log (log/create-logger
                   (when-let [log-level (bootstrap-log-level)]
                     {:level (keyword (str/lower-case log-level))}))
             :embedded-systems {:metadata-db metadata-db
                                :indexer indexer
                                :access-control access-control}
             :db-batch-size (db-batch-size)

             ;; Channel for requesting full provider migration - provider/collections/granules.
             ;; Takes single provider-id strings.
             :provider-db-channel (chan 10)
             ;; Channel for requesting single collection/granules migration.
             ;; Takes maps, e.g., {:collection-id collection-id :provider-id provider-id}
             :collection-db-channel (chan 100)

             ;; Channel for requesting full provider indexing - collections/granules
             :provider-index-channel (chan 10)

             ;; Channel for processing collections to index.
             :collection-index-channel (chan 100)

             ;; Channel for processing data newer than a given date-time.
             :data-index-channel (chan 10)

             ;; Channel for processing bulk index requests for system concepts (tags, acls, access-groups)
             :system-concept-channel (chan 10)

             ;; Channel for bootstrapping virtual products
             vp/channel-name (chan)

             :catalog-rest-user (mdb-config/catalog-rest-db-username)
             :db (oracle/create-db (bootstrap-config/db-spec "bootstrap-pool"))
             :web (web/create-web-server (transmit-config/bootstrap-port) routes/make-api)
             :nrepl (nrepl/create-nrepl-if-configured (bootstrap-config/bootstrap-nrepl-port))
             :relative-root-url (transmit-config/bootstrap-relative-root-url)
             :caches {acl/token-imp-cache-key (acl/create-token-imp-cache)
                      kf/kms-cache-key (kf/create-kms-cache)
                      common-health/health-cache-key (common-health/create-health-cache)}
             :db-scheduler (when (replication/index-recently-replicated)
                             (jobs/create-clustered-scheduler
                              `system-holder :db [replication/index-recently-replicated-job]))
             :scheduler (jobs/create-scheduler `system-holder [jvm-info/log-jvm-statistics-job])}]
    (transmit-config/system-with-connections sys [:metadata-db :echo-rest :kms :cubby :index-set
                                                  :indexer])))

(defn start
  "Performs side effects to initialize the system, acquire resources,
  and start it running. Returns an updated instance of the system."
  [this]
  (info "bootstrap System starting")
  (let [;; Need to start indexer first so the connection will be in the context of synchronous
        ;; bulk index requests
        started-system (update-in this [:embedded-systems :indexer] idx-system/start)
        started-system (update-in started-system [:embedded-systems :metadata-db] mdb-system/start)
        started-system (common-sys/start started-system component-order)]
    (bm/handle-copy-requests started-system)
    (bi/handle-bulk-index-requests started-system)
    (vp/handle-virtual-product-requests started-system)
    (info "Bootstrap System started")
    started-system))


(defn stop
  "Performs side effects to shut down the system and release its
  resources. Returns an updated instance of the system."
  [this]
  (info "bootstrap System shutting down")
  (let [stopped-system (common-sys/stop this component-order)
        stopped-system (update-in stopped-system [:embedded-systems :metadata-db] mdb-system/stop)
        stopped-system (update-in stopped-system [:embedded-systems :indexer] idx-system/stop)]
    (info "bootstrap System stopped")
    stopped-system))
