(ns build
  (:require [clojure.tools.build.api :as b]
            [org.corfield.build :as bb]
            [clojure.pprint :as pprint]))

(def lib 'org.clojars.jekyllislandtours/conceptual-tupl)
(def version (format "0.1.%s" (b/git-count-revs nil)))

(defn show-defaults [_]
  (println "default-basis:")
  (pprint/pprint (bb/default-basis))
  (println "default-target:" (bb/default-target))
  (println "default-class-dir:" (bb/default-class-dir))
  (println "default-jar-file:" (bb/default-jar-file lib version)))

(defn clean [_]
  (bb/clean {}))


(defn compile-clj [_]
  (println "Compiling clj...")
  (b/compile-clj {:src-dirs ["src/clj"]
                  :class-dir (bb/default-class-dir)
                  :basis (bb/default-basis)}))

(defn jar [_]
  (clean nil)
  (bb/jar {:lib lib
           :version version
           :src-dirs ["src/clj"]}))

(defn install [_]
  (jar nil)
  (println "Installing jar into local Maven repo cache...")
  (bb/install {:lib lib
               :version version
               :src-dirs ["src/clj"]}))

(defn deploy [_]
  (bb/deploy {:lib lib
              :version version
              :src-dirs ["src/clj"]}))
