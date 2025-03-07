(ns metabase.models.database
  (:require
   [medley.core :as m]
   [metabase.db.util :as mdb.u]
   [metabase.driver :as driver]
   [metabase.driver.impl :as driver.impl]
   [metabase.driver.util :as driver.u]
   [metabase.models.interface :as mi]
   [metabase.models.permissions :as perms]
   [metabase.models.permissions-group :as perms-group]
   [metabase.models.secret :as secret :refer [Secret]]
   [metabase.models.serialization :as serdes]
   [metabase.models.setting :as setting]
   [metabase.plugins.classloader :as classloader]
   [metabase.util :as u]
   [metabase.util.i18n :refer [trs]]
   [metabase.util.log :as log]
   [methodical.core :as methodical]
   [toucan.models :as models]
   [toucan2.core :as t2]))

;;; ----------------------------------------------- Entity & Lifecycle -----------------------------------------------

(models/defmodel Database :metabase_database)

(doto Database
  (derive ::mi/read-policy.partial-perms-for-perms-set)
  (derive ::mi/write-policy.full-perms-for-perms-set))

(defn- schedule-tasks!
  "(Re)schedule sync operation tasks for `database`. (Existing scheduled tasks will be deleted first.)"
  [database]
  (try
    ;; this is done this way to avoid circular dependencies
    (classloader/require 'metabase.task.sync-databases)
    ((resolve 'metabase.task.sync-databases/check-and-schedule-tasks-for-db!) database)
    (catch Throwable e
      (log/error e (trs "Error scheduling tasks for DB")))))

;; TODO - something like NSNotificationCenter in Objective-C would be really really useful here so things that want to
;; implement behavior when an object is deleted can do it without having to put code here

(defn- unschedule-tasks!
  "Unschedule any currently pending sync operation tasks for `database`."
  [database]
  (try
    (classloader/require 'metabase.task.sync-databases)
    ((resolve 'metabase.task.sync-databases/unschedule-tasks-for-db!) database)
    (catch Throwable e
      (log/error e (trs "Error unscheduling tasks for DB.")))))

(defn- post-insert [database]
  (u/prog1 database
    ;; add this database to the All Users permissions group
    (perms/grant-full-data-permissions! (perms-group/all-users) database)
    ;; give full download perms for this database to the All Users permissions group
    (perms/grant-full-download-permissions! (perms-group/all-users) database)
    ;; schedule the Database sync & analyze tasks
    (schedule-tasks! database)))

(def ^:private ^:dynamic *normalizing-details*
  "Track whether we're calling [[driver/normalize-db-details]] already to prevent infinite
  recursion. [[driver/normalize-db-details]] is actually done for side effects!"
  false)

(defn- post-select [{driver :engine, :as database}]
  (letfn [(normalize-details [db]
            (binding [*normalizing-details* true]
              (driver/normalize-db-details driver db)))]
    (cond-> database
      ;; TODO - this is only really needed for API responses. This should be a `hydrate` thing instead!
      (driver.impl/registered? driver)
      (assoc :features (driver.u/features driver database))

      (and (driver.impl/registered? driver)
           (:details database)
           (not *normalizing-details*))
      normalize-details)))

(defn- delete-orphaned-secrets!
  "Delete Secret instances from the app DB, that will become orphaned when `database` is deleted. For now, this will
  simply delete any Secret whose ID appears in the details blob, since every Secret instance that is currently created
  is exclusively associated with a single Database.

  In the future, if/when we allow arbitrary association of secret instances to database instances, this will need to
  change and become more complicated (likely by consulting a many-to-many join table)."
  [{:keys [id details] :as database}]
  (when-let [conn-props-fn (get-method driver/connection-properties (driver.u/database->driver database))]
    (let [conn-props                 (conn-props-fn (driver.u/database->driver database))
          possible-secret-prop-names (keys (secret/conn-props->secret-props-by-name conn-props))]
      (doseq [secret-id (reduce (fn [acc prop-name]
                                  (if-let [secret-id (get details (keyword (str prop-name "-id")))]
                                    (conj acc secret-id)
                                    acc))
                                []
                                possible-secret-prop-names)]
        (log/info (trs "Deleting secret ID {0} from app DB because the owning database ({1}) is being deleted"
                       secret-id
                       id))
        (t2/delete! Secret :id secret-id)))))

(defn- pre-delete [{id :id, driver :engine, :as database}]
  (unschedule-tasks! database)
  (t2/query-one {:delete-from :permissions
                 :where       [:like :object (str "%" (perms/data-perms-path id) "%")]})
  (delete-orphaned-secrets! database)
  (try
    (driver/notify-database-updated driver database)
    (catch Throwable e
      (log/error e (trs "Error sending database deletion notification")))))

(defn- handle-db-details-secret-prop!
  "Helper fn for reducing over a map of all the secret connection-properties, keyed by name. This is side effecting. At
  each iteration step, if there is a -value suffixed property set in the details to be persisted, then we instead insert
  (or update an existing) Secret instance and point to the inserted -id instead."
  [database details conn-prop-nm conn-prop]
  (let [sub-prop   (fn [suffix]
                     (keyword (str conn-prop-nm suffix)))
        id-kw      (sub-prop "-id")
        value-kw   (sub-prop "-value")
        new-name   (format "%s for %s" (:display-name conn-prop) (:name database))
        kind       (:secret-kind conn-prop)
        ;; in the future, when secret values can simply be changed by passing
        ;; in a new ID (as opposed to a new value), this behavior will change,
        ;; but for now, we should simply look for the value
        secret-map (secret/db-details-prop->secret-map details conn-prop-nm)
        value      (:value secret-map)
        src        (:source secret-map)] ; set the :source due to the -path suffix (see above)]
    (if (nil? value) ;; secret value for this conn prop was not changed
      details
      (let [{:keys [id] :as secret*} (secret/upsert-secret-value!
                                       (id-kw details)
                                       new-name
                                       kind
                                       src
                                       value)]
        (-> details
            ;; remove the -value keyword (since in the persisted details blob, we only ever want to store the -id),
            ;; but the value may be re-added by expand-inferred-secret-values below (if appropriate)
            (dissoc value-kw (sub-prop "-path"))
            (assoc id-kw id)
            (secret/expand-inferred-secret-values conn-prop-nm conn-prop secret*))))))

(defn- handle-secrets-changes [{:keys [details] :as database}]
  (if (map? details)
    (let [updated-details (secret/reduce-over-details-secret-values
                            (driver.u/database->driver database)
                            details
                            (partial handle-db-details-secret-prop! database))]
      (assoc database :details updated-details))
    database))

(defn- pre-update
  [{new-metadata-schedule    :metadata_sync_schedule,
    new-fieldvalues-schedule :cache_field_values_schedule,
    new-engine               :engine
    new-settings             :settings
    :as                      database}]
  (let [{is-sample?               :is_sample
         old-metadata-schedule    :metadata_sync_schedule
         old-fieldvalues-schedule :cache_field_values_schedule
         existing-settings        :settings
         existing-engine          :engine
         existing-name            :name} (t2/select-one [Database
                                                         :metadata_sync_schedule
                                                         :cache_field_values_schedule
                                                         :engine
                                                         :name
                                                         :is_sample
                                                         :settings] :id (u/the-id database))
        new-engine                       (some-> new-engine keyword)]
    (if (and is-sample?
             new-engine
             (not= new-engine existing-engine))
      (throw (ex-info (trs "The engine on a sample database cannot be changed.")
                      {:status-code     400
                       :existing-engine existing-engine
                       :new-engine      new-engine}))
      (u/prog1  (-> database
                    (cond->
                      ;; If the engine doesn't support nested field columns, `json_unfolding` must be nil
                      (and (some? (:details database))
                           (not (driver/database-supports? (or new-engine existing-engine) :nested-field-columns database)))
                      (update :details dissoc :json_unfolding))
                    handle-secrets-changes)
        ;; TODO - this logic would make more sense in post-update if such a method existed
        ;; if the sync operation schedules have changed, we need to reschedule this DB
        (when (or new-metadata-schedule new-fieldvalues-schedule)
          ;; if one of the schedules wasn't passed continue using the old one
          (let [new-metadata-schedule    (or new-metadata-schedule old-metadata-schedule)
                new-fieldvalues-schedule (or new-fieldvalues-schedule old-fieldvalues-schedule)]
            (when (not= [new-metadata-schedule new-fieldvalues-schedule]
                        [old-metadata-schedule old-fieldvalues-schedule])
              (log/info
               (trs "{0} Database ''{1}'' sync/analyze schedules have changed!" existing-engine existing-name)
               "\n"
               (trs "Sync metadata was: ''{0}'' is now: ''{1}''" old-metadata-schedule new-metadata-schedule)
               "\n"
               (trs "Cache FieldValues was: ''{0}'', is now: ''{1}''" old-fieldvalues-schedule new-fieldvalues-schedule))
              ;; reschedule the database. Make sure we're passing back the old schedule if one of the two wasn't supplied
              (schedule-tasks!
               (assoc database
                      :metadata_sync_schedule      new-metadata-schedule
                      :cache_field_values_schedule new-fieldvalues-schedule)))))
         ;; This maintains a constraint that if a driver doesn't support actions, it can never be enabled
         ;; If we drop support for actions for a driver, we'd need to add a migration to disable actions for all databases
        (when (and (:database-enable-actions (or new-settings existing-settings))
                   (not (driver/database-supports? (or new-engine existing-engine) :actions database)))
          (throw (ex-info (trs "The database does not support actions.")
                          {:status-code     400
                           :existing-engine existing-engine
                           :new-engine      new-engine})))))))

(defn- pre-insert [{:keys [details], :as database}]
  (-> (cond-> database
        (not details) (assoc :details {}))
      handle-secrets-changes
      (assoc :initial_sync_status "incomplete")))

(defmethod mi/perms-objects-set Database
  [{db-id :id} read-or-write]
  #{(case read-or-write
      :read  (perms/data-perms-path db-id)
      :write (perms/db-details-write-perms-path db-id))})

(mi/define-methods
 Database
 {:hydration-keys (constantly [:database :db])
  :types          (constantly {:details                     :encrypted-json
                               :options                     :json
                               :engine                      :keyword
                               :metadata_sync_schedule      :cron-string
                               :cache_field_values_schedule :cron-string
                               :start_of_week               :keyword
                               :settings                    :encrypted-json
                               :dbms_version                :json})
  :post-insert    post-insert
  :post-select    post-select
  :pre-insert     pre-insert
  :pre-update     pre-update
  :pre-delete     pre-delete})

(defmethod serdes/hash-fields Database
  [_database]
  [:name :engine])


;;; ---------------------------------------------- Hydration / Util Fns ----------------------------------------------

(mi/define-simple-hydration-method tables
  :tables
  "Return the `Tables` associated with this `Database`."
  [{:keys [id]}]
  ;; TODO - do we want to include tables that should be `:hidden`?
  (t2/select 'Table, :db_id id, :active true, {:order-by [[:%lower.display_name :asc]]}))

(defn pk-fields
  "Return all the primary key `Fields` associated with this `database`."
  [{:keys [id]}]
  (let [table-ids (t2/select-pks-set 'Table, :db_id id, :active true)]
    (when (seq table-ids)
      (t2/select 'Field, :table_id [:in table-ids], :semantic_type (mdb.u/isa :type/PK)))))


;;; -------------------------------------------------- JSON Encoder --------------------------------------------------

(def ^:const protected-password
  "The string to replace passwords with when serializing Databases."
  "**MetabasePass**")

(defn sensitive-fields-for-db
  "Gets all sensitive fields that should be redacted in API responses for a given database. Delegates to
  driver.u/sensitive-fields using the given database's driver (if valid), so refer to that for full details. If a valid
  driver can't be clearly determined, this simply returns the default set (driver.u/default-sensitive-fields)."
  [database]
  (if (and (some? database) (not-empty database))
      (let [driver (driver.u/database->driver database)]
        (if (some? driver)
            (driver.u/sensitive-fields (driver.u/database->driver database))
            driver.u/default-sensitive-fields))
      driver.u/default-sensitive-fields))

(methodical/defmethod mi/to-json Database
  "When encoding a Database as JSON remove the `details` for any User without write perms for the DB.
  Users with write perms can see the `details` but remove anything resembling a password. No one gets to see this in
  an API response!

  Also remove settings that the User doesn't have read perms for."
  [db json-generator]
  (next-method
   (let [db (if (not (mi/can-write? db))
              (dissoc db :details)
              (update db :details (fn [details]
                                    (reduce
                                     #(m/update-existing %1 %2 (constantly protected-password))
                                     details
                                     (sensitive-fields-for-db db)))))]
     (update db :settings (fn [settings]
                            (when settings
                              (into {}
                                    (filter (fn [[setting-name _v]]
                                              (setting/can-read-setting? setting-name
                                                                         (setting/current-user-readable-visibilities))))
                                    settings)))))
   json-generator))

;;; ------------------------------------------------ Serialization ----------------------------------------------------

(defmethod serdes/extract-one "Database"
  [_model-name {secrets :database/secrets :or {secrets :exclude}} entity]
  ;; TODO Support alternative encryption of secret database details.
  ;; There's one optional foreign key: creator_id. Resolve it as an email.
  (cond-> (serdes/extract-one-basics "Database" entity)
    true                 (update :creator_id serdes/*export-user*)
    true                 (dissoc :features) ; This is a synthetic column that isn't in the real schema.
    (= :exclude secrets) (dissoc :details)))

(defmethod serdes/entity-id "Database"
  [_ {:keys [name]}]
  name)

(defmethod serdes/generate-path "Database"
  [_ {:keys [name]}]
  [{:model "Database" :id name}])

(defmethod serdes/load-find-local "Database"
  [[{:keys [id]}]]
  (t2/select-one Database :name id))

(defmethod serdes/load-xform "Database"
  [database]
  (-> database
      serdes/load-xform-basics
      (update :creator_id serdes/*import-user*)))

(defmethod serdes/load-insert! "Database" [_ ingested]
  (let [m (get-method serdes/load-insert! :default)]
    (m "Database"
       (if (:details ingested)
         ingested
         (assoc ingested :details {})))))

(defmethod serdes/load-update! "Database" [_ ingested local]
  (let [m (get-method serdes/load-update! :default)]
    (m "Database"
       (update ingested :details #(or % (:details local) {}))
       local)))

(defmethod serdes/storage-path "Database" [{:keys [name]} _]
  ;; ["databases" "db_name" "db_name"] directory for the database with same-named file inside.
  ["databases" name name])

(defn json-unfolding-default
  "Returns true if JSON fields should be unfolded by default for this database, and false otherwise."
  [database]
  ;; If json-unfolding=nil in the database details, return true. This allows adding support for
  ;; nested-field-columns for drivers in the future and have json-unfolding enabled by default, without
  ;; needing a migration to add the `json-unfolding=true` key to the database details.
  ((fnil identity true) (get-in database [:details :json-unfolding])))
