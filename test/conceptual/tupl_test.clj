(ns conceptual.tupl-test
  (:require [clojure.test :refer [deftest use-fixtures]]
            [expectations.clojure.test :refer [expect]]
            [conceptual.tupl :as tupl :refer [*db*]]
            [conceptual.arrays :as arrays])
  (:import (java.nio.file
              FileVisitOption
              Files
              LinkOption)
           (java.nio.file.attribute
            FileAttribute)))

;;---------------------------------------------------------------------------------------------
;; Fixture related
;;---------------------------------------------------------------------------------------------
(defn make-temp-file!
  "Returns a path"
  []
  (Files/createTempFile (str (random-uuid)) nil (into-array FileAttribute [])))

(defn delete!
  "Delete a file or if directory recursively delete the directory."
  [path]
  (when (Files/exists path (into-array LinkOption []))
    (doseq [f (-> (Files/walk path
                              (into-array FileVisitOption [FileVisitOption/FOLLOW_LINKS]))
                  .sorted
                  .iterator
                  iterator-seq
                  reverse)]
      (Files/deleteIfExists f))))


(defn with-db
  [f]
  (let [file-path (make-temp-file!)]
    (with-open [db (tupl/open-db {:base-file-path (str file-path)})]
      (binding [*db* db]
        (f)))
    (delete! file-path)))

(use-fixtures :each with-db)


(defn store!
  [idx k v]
  (tupl/store! idx (arrays/ensure-bytes k) (arrays/ensure-bytes v)))

(deftest index-keys-test
  (with-open [idx (tupl/open-index! :idx)]
    (expect #{} (set (tupl/index-keys idx)))
    (store! idx 1 2)
    (expect #{1} (set (tupl/index-keys idx)))
    (store! idx 3 4)
    (expect #{1 3} (set (tupl/index-keys idx)))))

(deftest index-entries-count-test
  (with-open [idx (tupl/open-index! :idx)]
    (expect 0 (tupl/index-entries-count idx))
    (store! idx 1 2)
    (expect 1 (tupl/index-entries-count idx))
    (store! idx 3 4)
    (expect 2 (tupl/index-entries-count idx))))
