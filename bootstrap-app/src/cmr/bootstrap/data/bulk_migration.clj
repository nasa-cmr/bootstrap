(ns cmr.bootstrap.data.bulk-migration
  "Functions to support migration of data form catlog rest to metadata db."
  (:require [cmr.common.log :refer (debug info warn error)]
            [clojure.java.jdbc :as j]
            [clojure.string :as str]
            [cheshire.core :as json]
            [sqlingvo.core :as sql :refer [sql select insert from where with order-by desc delete as]]
            [sqlingvo.vendor :as v]
            [cmr.metadata-db.data.oracle.sql-utils :as su]
            [clojure.core.async :as ca :refer [thread alts!! <!!]]
            [cmr.oracle.connection :as oc]
            [cmr.metadata-db.data.oracle.concept-tables :as tables]
            [cmr.transmit.config :as transmit-config]
            [cmr.transmit.metadata-db :as transmit-mdb]
            [cmr.oracle.config :as oracle-config]))

;; FIXME - Why not just use the metadata db code directly for creating the provider instead of using http?
(defn system->metadata-db-url
  [system]
  (let [{:keys [host port]} (transmit-config/context->app-connection {:system system} :metadata-db)]
    (format "http://%s:%s" host port)))


;; To copy a provider
;; 1. Tell the metadata db to drop the provider
;; 2. Tell the metadata db to create the provider
;; 3. Insert collections by selecting from dataset table.
;; 4. Iterate over dataset ids for provider and insert granules into metadata db table
;; by selecting from catlaog-rest table.

(defn catalog-rest-user
  [system]
  (get-in system [:catalog-rest-user]))

(defn metadata-db-user
  [system]
  (get-in system [:db :spec :user]))


(defn catalog-rest-dataset-table
  "Get the dataset table name for a given provider."
  [system provider-id]
  (str (catalog-rest-user system) "." provider-id "_dataset_records"))

(defn catalog-rest-granule-table
  "Get the granule table name for a given provider."
  [system provider-id]
  (str (catalog-rest-user system) "." provider-id "_granule_records"))

(defn metadata-db-concept-table
  "Get the collection/granule table name for a given provider."
  [system provider-id concept-type]
  (str (metadata-db-user system) "." (tables/get-table-name provider-id concept-type)))

(defn- delete-collection-sql
  "Generate SQL to delete a collection from a provider's collection table."
  [system provider-id collection-id]
  (let [collection-table (tables/get-table-name provider-id :collection)]
    (su/build (delete collection-table (where `(= :concept-id ~collection-id))))))

(defn- delete-collection
  "Delete a collection from a provider's collection table."
  [system provider-id collection-id]
  (info "Deleting collection" collection-id "from provider" provider-id)
  (j/with-db-transaction
    [conn (:db system)]
    (j/execute! conn (delete-collection-sql system provider-id collection-id))))

(defn- delete-collection-granules-sql
  "Generate SQL to delete granules for a given collection from a provider's granule table."
  [provider-id collection-id]
  (let [granule-table (tables/get-table-name provider-id :granule)]
    (su/build (delete granule-table (where `(= :parent-collection-id ~collection-id))))))

(defn- delete-collection-granules
  "Delete granules for a given collection from a provider's granule table."
  [system provider-id collection-id]
  (info "Deleting granules for collection" collection-id)
  (j/with-db-transaction
    [conn (:db system)]
    (j/execute! conn (delete-collection-granules-sql provider-id collection-id))))

(defn- get-dataset-record-id-for-collection-sql
  "Generate SQL to retrieve the id for a given collection/dataset from the catalog-rest table."
  [system provider-id collection-id]
  (su/build (select [:id] (from (catalog-rest-dataset-table system provider-id))
                    (where `(= :echo-collection-id ~collection-id)))))

(defn- get-dataset-record-id-for-collection
  "Retrieve the id for a given collection/dataset from the catalog-rest table."
  [system provider-id collection-id]
  (-> (j/with-db-transaction
        [conn (:db system)]
        (j/query conn (get-dataset-record-id-for-collection-sql system provider-id collection-id)))
      first
      :id
      int))

(defn- get-provider-collection-list-sql
  "Gengerate SQL to get the list of collections (datasets) for the given provider."
  [system provider-id]
  (su/build (select [:id :echo-collection-id] (from (catalog-rest-dataset-table system provider-id)))))

(defn get-provider-collection-list
  "Get the list of collections (datasets) for the given provider."
  [system provider-id]
  (j/with-db-transaction
    [conn (:db system)]
    (j/query conn (get-provider-collection-list-sql system provider-id))))

(defn- copy-collection-data-sql
  "Generate SQL to copy the dataset/collection data from the catalog rest database to the
  metadata db for the given provider for all the provider's collections or a single collection."
  ([system provider-id]
   (copy-collection-data-sql system provider-id nil))
  ([system provider-id  collection-id]
   (let [dataset-table (catalog-rest-dataset-table system provider-id)
         collection-table (metadata-db-concept-table system provider-id :collection)
         ;; FIXME: Format is incorrect when copied over. Catalog REST has a constant like "ECHO10"
         ;; but we expect application/echo10+xml
         stmt (format (str "INSERT INTO %s (id, concept_id, native_id, metadata, format, short_name, "
                           "version_id, entry_title, delete_time, revision_date) SELECT %s_seq.NEXTVAL,"
                           "echo_collection_id, dataset_id, compressed_xml, xml_mime_type, short_name,"
                           "version_id, dataset_id, delete_time, ingest_updated_at FROM %s")
                      collection-table
                      collection-table
                      dataset-table
                      collection-id)]
     (if collection-id
       (format "%s WHERE echo_collection_id = '%s'" stmt collection-id)
       stmt))))


(defn- copy-collection-data
  "Copy the dataset/collection data from the catalog rest database to the metadata db
  for the given provider."
  ([system provider-id]
   (copy-collection-data system provider-id nil))
  ([system provider-id collection-id]
   (info "Copying collection data for provider" provider-id)
   (let [stmt (copy-collection-data-sql system provider-id collection-id)]
     (j/with-db-transaction
       [conn (:db system)]
       (j/execute! conn [stmt])))))

(defn- copy-granule-data-for-collection-sql
  "Generate the SQL to copy the granule data from the catalog reset datbase to the metadata db."
  [system provider-id collection-id dataset-record-id]
  (let [granule-echo-table (catalog-rest-granule-table system provider-id)
        granule-mdb-table (metadata-db-concept-table system provider-id :granule)
        stmt (format (str "INSERT INTO %s (id, concept_id, native_id, parent_collection_id, "
                          "metadata, format, delete_time, revision_date) "
                          "SELECT %s_seq.NEXTVAL, echo_granule_id, granule_ur, '%s', "
                          "compressed_xml, xml_mime_type, delete_time, ingest_updated_at "
                          "FROM %s WHERE dataset_record_id = %d")
                     granule-mdb-table
                     granule-mdb-table
                     collection-id
                     granule-echo-table
                     (.intValue dataset-record-id))]
    stmt))

(defn- copy-granule-data-for-collection
  "Copy the granule data from the catalog reset datbase to the metadata db."
  [system provider-id collection-id dataset-record-id]
  (let [stmt (copy-granule-data-for-collection-sql system provider-id collection-id dataset-record-id)]
    (j/with-db-transaction
      [conn (:db system)]
      (j/execute! conn [stmt]))))

(defn- copy-granule-data-for-provider
  "Copy the granule data for every collection for a given provider."
  [system provider-id]
  (info "Copying granule data for provider" provider-id)
  (doseq [{:keys [id echo_collection_id]} (get-provider-collection-list system provider-id)]
    (info (str "Copying granule data for collection " echo_collection_id))
    (let [result (copy-granule-data-for-collection system provider-id echo_collection_id id)]
      (info result))))

(defn copy-single-collection
  "Delete a collection's associated granules and then copy the data from the catalog-rest db."
  [system provider-id collection-id]
  (let [dataset-record-id (get-dataset-record-id-for-collection system provider-id collection-id)]
    (delete-collection-granules system provider-id collection-id)
    (copy-granule-data-for-collection system provider-id collection-id dataset-record-id)
    (info "Processing of collection" collection-id "for provider" provider-id "completed.")))

(defn copy-provider
  "Copy all data for a given provider (including datasets and granules from the catalog-rest
  database into the metadata db database."
  [system provider-id]
  (transmit-mdb/delete-provider {:system system} provider-id)
  (transmit-mdb/create-provider {:system system} provider-id)
  (copy-collection-data system provider-id)
  (copy-granule-data-for-provider system provider-id)
  (info "Processing of provider" provider-id "completed."))

;; Background task to handle requests
(defn handle-copy-requests
  "Handle any requests for copying data from echo catalog rest to metadata db."
  [system]
  (info "Starting background task for monitoring bulk migration channels.")
  (let [channels ((juxt :provider-db-channel :collection-db-channel) system)] ; add other channels as needed
    (dotimes [n 2]
      (thread (while true
                (try ; catch any errors and log them, but don't let the thread die
                  (let [[v ch] (alts!! channels)]
                    (cond
                      ;; add other channels as needed
                      (= (:provider-db-channel system) ch)
                      (do
                        (info "Processing provider" v)
                        (copy-provider system v))

                      (= (:collection-db-channel system) ch)
                      (let [{:keys [provider-id collection-id]} v]
                        (info "Processing collection" collection-id "for provider" provider-id)
                        (copy-single-collection system provider-id collection-id))

                      :else
                      (error (format "Received message [%s] on channel [%s] that is unrecognized"
                                     v ch))))
                  (catch Throwable e
                    (error e (.getMessage e)))))))))


(comment
  (delete-provider "FIX_PROV1")
  (get-provider-collection-list (get-in user/system [:apps :bootstrap]) "FIX_PROV1")
  (copy-provider (get-in user/system [:apps :bootstrap]) "FIX_PROV1")
  (copy-single-collection (oc/create-db (apply oc/db-spec (oracle-config/db-spec-args))) "FIX_PROV1" "C1000000073-FIX_PROV1")
  (get-provider-collection-list-sql  "FIX_PROV1")
  (copy-granule-data-for-provider (oc/create-db (mdb-config/db-spec)) "FIX_PROV1")
  (delete-collection-granules-sql "FIX_PROV1" "C1000000073-FIX_PROV1")
  (metadata-db-concept-table "FIX_PROV1" :collection)
  )

