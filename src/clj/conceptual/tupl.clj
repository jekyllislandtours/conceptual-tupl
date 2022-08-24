(ns conceptual.tupl
  (:refer-clojure :exclude [load])
  (:require [conceptual.arrays :as arrays])
  (:import [org.cojen.tupl Cursor Database DatabaseConfig DurabilityMode EntryConsumer Scanner Index Transaction]
           [org.cojen.tupl.ext CipherCrypto Crypto]
           [clojure.lang Keyword]
           [java.io File]
           [java.util.concurrent TimeUnit]
           [javax.crypto SecretKey]
           [java.lang AutoCloseable]))


(set! *warn-on-reflection* true)

(def ^:private kw->durability-mode
  {:no-flush DurabilityMode/NO_FLUSH
   :no-redo DurabilityMode/NO_REDO
   :no-sync DurabilityMode/NO_SYNC
   :sync DurabilityMode/SYNC})

(def ^:private kw->time-unit
  {:days TimeUnit/DAYS
   :hours TimeUnit/HOURS
   :microseconds TimeUnit/MICROSECONDS
   :milliseconds TimeUnit/MILLISECONDS
   :minutes TimeUnit/MINUTES
   :nanoseconds TimeUnit/NANOSECONDS
   :seconds TimeUnit/SECONDS})

(defn- time-unit
  [{:keys [time-unit]}]
  (or (kw->time-unit time-unit)
      (throw (ex-info "invalid :time-unit" {:time-unit time-unit
                                            :valid-time-units (set (keys kw->time-unit))}))))

(defonce ^{:dynamic true :tag Keyword} *default-identity* :default)

(defonce ^{:dynamic true :tag Index} *index* nil)
(defonce ^{:dynamic true :tag Cursor} *cursor* nil)
(defonce ^{:dynamic true :tag Transaction} *transaction* nil)

(defonce dbs (atom {}))

(defonce ^{:dynamic true :tag Database} *db* nil)

(defonce ^:dynamic *initialized?* (atom false))
(def ^:dynamic *key-deserializer* identity)
(def ^:dynamic *value-deserializer* identity)
(def ^:dynamic *key-serializer* arrays/ensure-bytes)
(def ^:dynamic *value-serializer* arrays/ensure-bytes)


(defn ^Crypto crypto
  ([key-or-key-size]
   (cond
     (bytes? key-or-key-size) (CipherCrypto. ^bytes key-or-key-size)
     (int? key-or-key-size) (CipherCrypto. ^int key-or-key-size)
     :else (throw (ex-info "Invalid input" {:type (type key-or-key-size)}))))
  ([^SecretKey key key-size]
   (CipherCrypto. key key-size)))

(defn db-stats
  [^Database db]
  (let [x (.stats db)]
    {:cache-pages (.-cachePages x)
     :checkpoint-duration (.checkpointDuration x)
     :cursor-count (.cursorCount x)
     :dirty-pages (.dirtyPages x)
     :free-pages (.freePages x)
     :lock-count (.lockCount x)
     :open-indexes (.openIndexes x)
     :page-size (.pageSize x)
     :replication-backlog (.replicationBacklog x)
     :total-pages (.totalPages x)
     :transaction-count (.transactionCount x)}))

(defn ^:private nsname
  "Given a keyword return the namespace/name string representation of the keyword.
  Not sure why this isn't in clojure core or at least a method of Keyword."
  [^clojure.lang.Keyword k] (str (.sym k)))

(defn ^DatabaseConfig db-config
  [{:keys [base-file
           base-file-path
           cache-priming?
           cache-size
           checkpoint-delay-threshold
           checkpoint-rate
           checkpoint-size-threshold
           checksum-pages-supplier
           clean-shutdown?
           create-file-path?
           crypto
           custom-handlers
           data-file
           data-files
           data-page-array
           direct-page-access?
           durability-mode
           event-listeners
           enable-jmx?
           lock-timeout
           lock-upgrade-rule
           map-data-files?
           max-cache-size
           max-checkpoint-threads
           max-replica-threads
           min-cache-size
           page-size
           prepare-handlers
           readonly?
           sync-writes?]}]
  (cond-> (DatabaseConfig.)
    base-file (.baseFile base-file)
    base-file-path (.baseFilePath base-file-path)
    (some? cache-priming?) (.cachePriming cache-priming?)
    cache-size (.cacheSize cache-size)
    checkpoint-delay-threshold (.checkpointDelayThreshold
                                (:delay checkpoint-delay-threshold)
                                (time-unit checkpoint-delay-threshold))
    checkpoint-rate (.checkpointRate (:rate checkpoint-rate) (time-unit checkpoint-rate))
    checkpoint-size-threshold (.checkpointSizeThreshold checkpoint-size-threshold)
    (some? create-file-path?) (.createFilePath create-file-path?)
    checksum-pages-supplier (.checksumPages checksum-pages-supplier)
    (some? clean-shutdown?) (.cleanShutdown clean-shutdown?)
    (some? create-file-path?) (.createFilePath create-file-path?)
    crypto (.encrypt crypto)
    custom-handlers (.customHandlers custom-handlers)
    data-file (.dataFile data-file)
    data-files (.dataFiles (into-array File data-files))
    data-page-array (.dataPageArray data-page-array)
    (some? direct-page-access?) (.directPageAccess direct-page-access?)
    durability-mode (.durabilityMode (kw->durability-mode durability-mode))
    (some? enable-jmx?) (.enableJMX enable-jmx?)
    event-listeners (.eventListeners (into-array event-listeners))
    lock-timeout (.lockTimeout (:timeout lock-timeout) (time-unit lock-timeout))
    lock-upgrade-rule (.lockUpgradeRule lock-upgrade-rule)
    (some? map-data-files?) (.mapDataFiles map-data-files?)
    max-cache-size (.maxCacheSize max-cache-size)
    max-checkpoint-threads (.maxCheckpointThreads max-checkpoint-threads)
    max-replica-threads (.maxReplicaThreads max-replica-threads)
    min-cache-size (.minCacheSize min-cache-size)
    page-size (.pageSize page-size)
    prepare-handlers (.prepareHandlers prepare-handlers)
    (some? readonly?) (.readOnly readonly?)
    (some? sync-writes?) (.syncWrites sync-writes?)))

(defn ^Database open-db
  "Opens a database using the specified config. `config` is either a
  map or a `DatabaseConfig`."
  [config]
  (Database/open (cond-> config
                   (map? config) db-config)))


(defn close!
  [^AutoCloseable x]
  (some-> x .close))

(defn ^Database db
  "Given a keyword database name opens that database if it exists,
  otherwise creates that database. Returns the default database given no arguments.
  If it does not exist the default database is created. If given a database
  instance returns that database instance."
  ([] (db *default-identity*))
  ([k] (db k {}))
  ([k config]
   (cond
    (instance? Database k) k
    (keyword? k)
    (if-let [-db (@dbs k)]
      -db
      (let [new-db (open-db config)]
        (swap! dbs assoc k new-db)
        new-db)))))

(defn set-default-identity-and-db!
  "Sets the `*default-identity*` and `*db*` vars based on the
  keyword `k`. Creates a new database if needed otherwise uses
  the existing db referenced by `k` to set `*db*`."
  ([k] (set-default-identity-and-db! k {}))
  ([k config]
   (alter-var-root #'*default-identity* (constantly k))
   (alter-var-root #'*db* (fn [d]
                            (close! d)
                            (db k config)))))

(defn shutdown!
  "Calls shutdown on the database."
  ([]
   (Thread.
    (fn []
      (doseq [db (vals @dbs)]
        (shutdown! db)))))
  ([^Database db]
   (.shutdown db)))


(defn open-index!
  ([k] (open-index! *db* k))
  ([^Database db ^Keyword k]
   (.openIndex db ^String (nsname k))))


(defn get-index-by-name
  ([^String nm]
   (get-index-by-name *db* nm))
  ([^Database db ^String nm]
   (.findIndex db nm)))

(defn get-index-by-id
  ([^long id]
   (get-index-by-id *db* id))
  ([^Database db ^long id]
   (.indexById db id)))


(defn get-index
  [-db k]
  (let [-db (db -db)]
    (cond
      (instance? Index k) k
      (instance? Long k) (get-index-by-id -db k)
      (keyword? k) (get-index-by-name -db (str (.-sym ^Keyword k)))
      (string? k) (get-index-by-name -db k))))

(defn index
  "Given a string or keyword name opens that index, otherwise
  creates that index. Uses the default database if not specified.
  If given the integer id of an index will return the index instance.
  If given an index instance returns that index instance."
  ([k] (index *db* k))
  ([-db k]
   (let [-db (db -db)]
     (or (get-index -db k)
         (open-index! -db k)))))

(defn ^Cursor cursor
  ([idx]
   (cursor *db* idx))
  ([^Database db idx]
   (.newCursor ^Index (index db idx) nil)))

(defn transaction
  "Returns a new Transaction with the given durability mode,
  uses the default durability mode if none is specified"
  ([^Database db] (.newTransaction db))
  ([^Database db mode] (.newTransaction db mode)))

(defn with-db* [^Keyword db-key f]
  (let [^Database d (db db-key)]
    (binding [*db* d]
      (f))))

(defmacro with-db
  "Evaluates body in the context of a specified database. The binding
  provides the the database for the evaluation of the body."
  [^Database db & forms]
  `(with-db* ~db (fn [] ~@forms)))

(defn with-index* [idx f]
  (let [^Index idx (index idx)]
    (binding [*index* idx]
      (f))))

(defmacro with-index
  "Evaluates body in the context of a specified index. The binding
  provides the the index for the evaluation of the body."
  [idx & forms]
  `(with-index* ~idx (fn [] ~@forms)))

(defn with-cursor*
  ([idx f]
   (let [c (cursor idx)]
     (try
       (binding [*cursor* c]
         (f))
       (finally
         (.reset c))))))

(defmacro with-cursor
  "Evaluates body in the context of a specified cursor. The binding
  provides the the cursor for the evaluation of the body."
  ([idx & forms]
   `(with-cursor* ~idx (fn [] ~@forms))))

;; TODO needs more work - dont use
(defn with-transaction*
  ([^Database db f]
   (let [^Transaction t (transaction db)]
     (try
       (with-bindings* {#'*transaction* t} f) ;; prefer using binding here
       (finally
         (.commit t))))))

(defmacro with-transaction
  ([idx & forms]
   `(with-transaction* ~idx (fn [] ~@forms))))

(defn delete!
  "Unconditionally removes the entry associated with the given key."
  ([^bytes k] (.delete *index* nil k))
  ([^Index idx ^bytes k] (.delete idx nil k))
  ([^Index idx ^Transaction t ^bytes k] (.delete idx t k)))

(defn exchange!
  "Unconditionally associates a value with the given key, returning the previous value."
  ([^bytes k ^bytes v] (.exchange *index* nil k v))
  ([^Index idx ^bytes k ^bytes v] (.exchange idx nil k v))
  ([^Index idx ^Transaction t ^bytes k ^bytes v] (.exchange idx t k v)))

(defn insert!
  "Associates a value with the given key, unless a corresponding value already exists."
  ([^bytes k ^bytes v] (.insert *index* nil k v))
  ([^Index idx ^bytes k ^bytes v] (.insert idx nil k v))
  ([^Index idx ^Transaction t ^bytes k ^bytes v] (.insert idx t k v)))

(defn replace!
  "Associates a value with the given key, but only if a corresponding value already exists."
  ([^bytes k ^bytes v] (.replace *index* nil k v))
  ([^Index idx ^bytes k ^bytes v] (.replace idx nil k v))
  ([^Index idx ^Transaction t ^bytes k ^bytes v] (.replace idx t k v)))

;; rename to store!
(defn store!
  "Unconditionally associates a value with the given key."
  ([^bytes k ^bytes v] (.store *index* nil k v))
  ([^Index idx ^bytes k ^bytes v] (.store idx nil k v))
  ([^Index idx ^Transaction t ^bytes k ^bytes v] (.store idx t k v)))

(defn load
  "Returns a copy of the value for the given key, or null if no matching entry exists."
  ([^bytes k] (load *index* k))
  ([^Index idx ^bytes k] (load idx nil k))
  ([^Index idx ^Transaction t ^bytes k] (.load idx t k)))

(defn seek
  "Experimental function aimed at arriving at a uniform interface
  for the in-memory and durable indices. There is the additional
  level of indirection here with the index."
  ([idx k] (load idx k))
  ([db idx k]
   (with-db db
     (load idx k))))


(defn index-first
  "Grabs the first entry for a given index."
  ([] (index-first *cursor*))
  ([^Cursor cursor & {:keys [key-deserializer value-deserializer]
                      :or {key-deserializer *key-deserializer*
                           value-deserializer *value-deserializer*}}]
   (.first cursor)
   (vector (key-deserializer (.key cursor))
           (value-deserializer (.value cursor)))))

(defn index-last
  "Grabs the last entry for a given index."
  ([] (index-last *cursor*))
  ([^Cursor cursor & {:keys [key-deserializer value-deserializer]
                      :or {key-deserializer *key-deserializer*
                           value-deserializer *value-deserializer*}}]
   (.last cursor)
   (vector (key-deserializer (.key cursor))
           (value-deserializer (.value cursor)))))

(defn index-range-keys*
  "Eager seq over range of entries for a given index starting at start,
  or from beginning if start is nil, and ending before end,
  or through the end of the index if end is nil.
  Use inside with-cursor."
  ([idx start stop] (index-range-keys* *db* idx start stop))
  ([db idx start stop & {:keys [key-deserializer
                                key-serializer]
                         :or {key-deserializer *key-deserializer*
                              key-serializer *key-serializer*}}]
   (let [-idx (index db idx)
         ^Cursor cursor (cursor -idx)
         -start (some-> start key-serializer)
         -stop (some-> stop key-serializer)]
     (try
       (if -start
         (.findGe cursor -start)
         (.first cursor))
       (loop [k (.key cursor)
              result []]
         (if k
           (do
             ;; this v was not used - bug?
             ;;let [v (.value cursor)]
             (if -stop
               (.nextLt cursor -stop)
               (.next cursor))
             (recur (.key cursor)
                    (conj result (key-deserializer k))))
           result))
       (finally
         (.reset cursor))))))

(defn index-range-keys
  "Lazy seq over range of keys for a given index starting at start,
  and ending before end. Use inside with-cursor."
  ([start stop] (index-range-keys *cursor* start stop))
  ([^Cursor cursor start stop & {:keys [key-deserializer
                                        key-serializer]
                                 :or {key-deserializer *key-deserializer*
                                      key-serializer *key-serializer*}}]
   (let [-start (some-> start key-serializer)
         -stop (some-> stop key-serializer)]
      (if -start
        (.findGe cursor -start)
        (.first cursor))
      (letfn [(results []
                (when-not cursor
                  (throw (IllegalStateException.
                          "you let the lazy-seq-out")))
                (when-let [k (.key cursor)]
                  (cons (key-deserializer k)
                        (do (if -stop
                              (.nextLt cursor -stop)
                              (.next cursor))
                            (lazy-seq (results))))))]
        (results)))))

(defn index-range*
  "Eager seq over range of entries for a given index starting at start,
  or from beginning if start is nil, and ending before end,
  or through the end of the index if end is nil.
  Use inside with-cursor."
  ([idx start stop] (index-range* *db* idx start stop))
  ([db idx start stop & {:keys [key-deserializer key-serializer value-deserializer]
                         :or {key-deserializer *key-deserializer*
                              key-serializer *key-serializer*
                              value-deserializer *value-deserializer*}}]
   (let [-idx (index db idx)
         ^Cursor cursor (cursor -idx)
         -start (some-> start key-serializer)
         -stop (some-> stop key-serializer)]
     (try
       (if -start
         (.findGe cursor -start)
         (.first cursor))
       (loop [k (.key cursor)
              result []]
         (if k
           (let [v (.value cursor)]
             (if -stop
               (.nextLt cursor -stop)
               (.next cursor))
             (recur (.key cursor)
                    (conj result [(key-deserializer k)
                                  (value-deserializer v)])))
           result))
       (finally
         (.reset cursor))))))

(defn index-range
  "Lazy seq over range of entries for a given index starting at start,
  or from beginning if start is nil, and ending before end,
  or through the end of the index if end is nil.
  Use inside with-cursor."
  ([start stop] (index-range *cursor* start stop))
  ([^Cursor cursor start stop & {:keys [key-deserializer key-serializer value-deserializer]
                                 :or {key-deserializer *key-deserializer*
                                      key-serializer *key-serializer*
                                      value-deserializer *value-deserializer*}}]
   (let [-start (some-> start key-serializer)
         -stop (some-> stop key-serializer)]
      (if -start
        (.findGe cursor -start)
        (.first cursor))
      (letfn [(results []
                (when (nil? cursor)
                  (throw (IllegalStateException.
                          "you let the lazy-seq-out")))
                (when-let [k (.key cursor)]
                  (cons [(key-deserializer k)
                         (value-deserializer (.value cursor))]
                        (do (if -stop
                              (.nextLt cursor -stop)
                              (.next cursor))
                            (lazy-seq (results))))))]
        (results)))))

(defn clear-index!
  "Clears all entries for a given index."
  ([idx] (clear-index! *db* idx))
  ([db idx]
   (let [^Index -idx (index db idx)
         keys-to-remove (with-db db
                          (with-cursor idx
                            (index-range-keys nil nil)))]
     (doseq [k keys-to-remove]
       (.delete -idx nil k)))))

(defn drop-index!
  "Drops a given index. If the index is not empty, first clears it."
  ([idx] (drop-index! *db* idx))
  ([db idx]
   (let [^Index -idx (index db idx)
         cnt (clear-index! db idx)]
     (.drop -idx)
     cnt)))

(defn index-names
  ([] (index-names *db*))
  ([^Database db]
   (let [^Cursor c (.newCursor (.indexRegistryByName db) nil)]
     (.first c)
     (loop [c c
            result []]
       (if-let [b (.key c)]
         (recur (do (.next c) c) (conj result (String. b)))
         result)))))


(defn flush!
  [^Database db]
  (.flush db))

(defn sync!
  [^Database db]
  (.sync db))


(defn ^Scanner scan
  ([^Index idx]
   (scan idx Transaction/BOGUS))
  ([^Index idx ^Transaction t]
   (.newScanner idx t)))


(defn index-entries-count
  "Returns the number of entries in a given index."
  [^Index idx]
  (let [n (volatile! 0)]
    (with-open [s (scan idx)]
      (.scanAll s
                (reify EntryConsumer
                  (accept [this _k _v]
                    (vswap! n inc)))))
    @n))

(defn index-keys
  "Returns all the keys from the index"
  [^Index idx & {:keys [key-deserializer]
                 :or {key-deserializer *key-deserializer*}} ]
  (let [ks (transient [])]
    (with-open [s (scan idx)]
      (.scanAll s
                (reify EntryConsumer
                  (accept [this k _v]
                    (conj! ks (key-deserializer k))))))
    (persistent! ks)))


(defn checkpoint!
  [^Database db]
  (.checkpoint db))
