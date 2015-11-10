(ns leiningen.localrepo
  (:require
    [leiningen.core.main  :as main]
    [cemerick.pomegranate.aether :as aether]
    [clojure.java.io      :as jio]
    [clojure.string       :as str]
    [clojure.pprint       :as ppr]
    [clojure.set          :as set]
    [clojure.tools.cli    :as cli]
    [clojure.xml          :as xml]
    [leiningen.localrepo.internal :as in])
  (:import
    (java.util Date)
    (java.text DateFormat)
    (java.io File)
    (java.util.jar JarFile)))


(def local-repo-path (str/join File/separator [(System/getProperty "user.home")
                                               ".m2" "repository"]))


(defn pwd []
  (.getParent (.getAbsoluteFile (File. "."))))


(defn assert-dir
  [^String dir]
  (if (in/dir? (jio/file dir))
    dir
    (main/abort (format "ERROR: '%s' is not a directory, current directory is: %s" dir (pwd)))))


(defn assert-file
  [^String file]
  (if (in/file? (jio/file file))
    file
    (main/abort (format "ERROR: '%s' is not a file, current directory is: %s" file (pwd)))))


(defn split-artifactid
  "Given 'groupIp/artifactId' string split them up and return
  as a vector of 2 elements."
  [^String artifact-id]
  (let [tokens (str/split artifact-id #"/")
        tcount (count tokens)
        [gi ai] tokens]
    (if (or (zero? tcount)
            (> tcount 2)
            (nil? gi))
      (in/illegal-arg "Invalid groupId/artifactId:" artifact-id)
      (if (nil? ai)
          [gi gi]
          [gi ai]))))


(def doc-coords
  "Guess Leiningen coordinates of given filename.
  Arguments:
    <filepath>
  Example:
    Input  -  local/jars/foo-bar-1.0.6.jar
    Output - foo-bar-1.0.6.jar foo-bar 1.0.6")


(defn c-coords
  "Guess Leiningen coordinates of given filename.
  Example:
  Input  -  local/jars/foo-bar-1.0.6.jar
  Output - foo-bar-1.0.6.jar foo-bar 1.0.6"
  [^String filepath]
  (let [filename (in/pick-filename filepath)
        tokens   (drop-last
                  (re-find (re-matcher #"(.+)\-(\d.+)\.(\w+)"
                                       filename)))
        [_ artifact-id version] tokens]
    (println filepath (str artifact-id "/" artifact-id) version)))


(def doc-install
  "Install artifact to local repository
  Arguments:
    [options] <filename> <artifact-id> <version>
  Options:
    -r | --repo repo-path
    -p | --pom  POM-file (minimal POM is generated if no POM file specified)
  Example:
    foo-1.0.jar bar/foo 1.0")


(def default-pom-format
  "<?xml version=\"1.0\" encoding=\"UTF-8\"?>
<project xmlns=\"http://maven.apache.org/POM/4.0.0\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd\">
  <modelVersion>4.0.0</modelVersion>
  <groupId>%s</groupId>
  <artifactId>%s</artifactId>
  <version>%s</version>
  <name>%s</name>
</project>")


(defn default-pom-file
  "Generate default temporary POM file returning the file name"
  [group-id artifact-id version]
  (let [pom-file (File/createTempFile "pom" ".xml")
        pom-name (.getAbsolutePath pom-file)]
    (.deleteOnExit pom-file)
    (spit pom-name (format default-pom-format
                           group-id artifact-id version artifact-id))
    pom-name))


(defn c-install*
  [repo-path pom-filename filename artifact-id version]
  (aether/install :local-repo (assert-dir repo-path)
                  :coordinates [(symbol artifact-id) version]
                  :jar-file (jio/file (assert-file filename))
                  :pom-file (assert-file pom-filename)))


(defn c-install
  [& args]
  (let [[options args banner] (cli/cli args
                                       ["-r" "--repo" "Local repo path" :default local-repo-path]
                                       ["-p" "--pom" "Artifact POM file"])
        help-abort (fn []
                     (println doc-install)
                     (main/abort))]
    (cond
     (not= 3 (count args)) (help-abort)
     :otherwise (let [[filename artifact-id version] args
                      [pom-group-id pom-artifact-id] (let [i (.indexOf artifact-id "/")]
                                                       (if (>= i 0)
                                                         [(.substring artifact-id 0 i)
                                                          (.substring artifact-id (inc i))]
                                                         [artifact-id artifact-id]))
                      pom-filename (if (:pom options)
                                     (:pom options)
                                     (default-pom-file pom-group-id pom-artifact-id version))]
                  (c-install* (:repo options) pom-filename filename artifact-id version)))))


(defn read-artifact-description
  [pom-file]
  (if (.isFile pom-file)
    (let [raw-content (slurp pom-file)
          xml-content (xml/parse pom-file)
          map-content (in/xml-map xml-content)]
      (first (:description (:project map-content))))
    "(No description available)"))


(defn read-artifact-details
  "Given a POM file, read project details"
  [pom-file]
  (if (.isFile pom-file)
    (let [raw-content (slurp pom-file)
          xml-content (xml/parse pom-file)
          map-content (in/xml-map xml-content)]
      (with-out-str
        (ppr/pprint map-content)))
    "(No details available)"))


(defn read-artifact-entries
  "Read artifact entries from specified `dir` and return as a list. If
  dir contains only sub-dirs then it recurses to find actual entries."
  [dir]
  (assert (in/dir? dir))
  (let [entries         (filter #(not (.startsWith (.getName %) "."))
                                (.listFiles dir))
        {subdirs :dir
         nondirs :file} (group-by in/dir-or-file entries)]
    (if (not (empty? subdirs))
      (reduce into [] (map read-artifact-entries subdirs))
      (let [ignore-ext #{"lastUpdated" "pom" "properties"
                         "repositories" "sha1" "xml"}
            arti-file? #(in/not-contains? ignore-ext
                                          (in/pick-filename-ext %))]
        (for [each (filter #(arti-file? (.getName %)) nondirs)]
          ;; parent = version, parent->parent = artifactId
          ;; parent->parent->parent[relative-path] = groupId
          (let [parent  (.getParentFile each)
                version (.getName parent)
                artifact-id (.getName (.getParentFile parent))
                group-path  (in/java-filepath
                             (in/relative-path local-repo-path
                                               (-> parent
                                                   .getParentFile
                                                   .getParentFile
                                                   .getAbsolutePath)))
                group-clean (let [slash? #(= \/ %)
                                  rtrim  #(if (slash? (last  %)) (drop-last %) %)
                                  ltrim  #(if (slash? (first %)) (rest %)      %)]
                              (apply str (-> group-path rtrim ltrim)))
                group-id    (str/replace group-clean "/" ".")]
            [group-id
             artifact-id
             version
             (jio/file each)
             (jio/file (let [[dir fne] (in/split-filepath
                                        (.getAbsolutePath each))
                             [fnm ext] (in/split-filename fne)]
                         (str dir "/" fnm ".pom")))]))))))


(def doc-list
  "List artifacts in local Maven repo
  Arguments:
    [-r | --repo repo-path] [-d | -f | -s]
  Options:
    -r | --repo repo-path => specifies the path of the local Maven repository
    No arguments lists with concise information
    -d lists in detail
    -f lists with filenames of artifacts
    -s lists with project description")


(defn c-list*
  "List artifacts in local Maven repo"
  [repo-path listing-type]
  (let [artifact-entries (sort (read-artifact-entries
                                (jio/file (assert-dir repo-path))))
        artifact-str  (fn artstr
                        ([gi ai] (if (= gi ai) ai (str gi "/" ai)))
                        ([[gi ai & more]] (artstr gi ai)))
        each-artifact (fn [f] ; args to f: 1. art-name, 2. artifacts
                        (let [by-art-id (group-by artifact-str
                                                  artifact-entries)]
                          (doseq [art-str (keys by-art-id)]
                            (f art-str (get by-art-id art-str)))))
        df            (DateFormat/getDateTimeInstance)
        date-format   #(.format df %)
        ljustify      (fn [s n]
                        (let [s (str/trim (str s))]
                          (if (> (count s) n) s
                              (apply str
                                     (take n (concat
                                              s (repeat n \space)))))))
        rjustify      (fn [s n]
                        (let [s (str/trim (str s))]
                          (if (> (count s) n) s
                              (apply str
                                     (take-last
                                      n (concat (repeat n \space)
                                                s))))))]
    (cond
     (nil? listing-type)
     (each-artifact
      (fn [art-name artifacts]
        (println
         (format "%s (%s)" art-name
                 (str/join ", "
                           (distinct (for [[g a v f p] artifacts]
                                       v)))))))
     (= :description listing-type)
     (each-artifact
      (fn [art-name artifacts]
        (println
         (format "%s (%s) -- %s" (ljustify art-name 20)
                 (str/join ", "
                           (distinct (for [[g a v f p] artifacts]
                                       v)))
                 (or (some #(read-artifact-description
                             (last %)) artifacts)
                     "")))))
     (= :filename listing-type)
     (each-artifact
      (fn [art-name artifacts]
        (doseq [each artifacts]
          (let [[g a v ^File f] each
                an (ljustify (format "[%s \"%s\"]"
                                     art-name v) 30)
                nm (ljustify (.getName f) 30)
                sp (ljustify (format "%s %s" an nm) 62)
                ln (rjustify (.length f)
                             (min (- 70 (count sp)) 10))]
            (println
             (format "%s %s %s" sp ln
                     (date-format
                      (Date. (.lastModified f)))))))))
     (= :detail listing-type)
     (each-artifact
      (fn [art-name artifacts]
        (println
         (format "%s (%s)\n%s" (ljustify art-name 20)
                 (str/join ", "
                           (distinct (for [[g a v f p] artifacts]
                                       v)))
                 (or (some #(read-artifact-details
                             (last %)) artifacts)
                     "")))))
     :otherwise
     (throw (RuntimeException.
             (str "Expected valid listing type, found " listing-type))))))


(defn c-list
  [& args]
  (let [[options args banner] (cli/cli args
                                       ["-r" "--repo" "Local repo path" :default local-repo-path]
                                       ["-d" "--detail" "List in detail" :flag true]
                                       ["-f" "--filename" "List with filenames" :flag true]
                                       ["-s" "--description" "List with description" :flag true])
        list-types #{:detail :filename :description}
        help-abort (fn []
                     (println doc-list)
                     (println "Only either of -d, -f, -s may be selected")
                     (main/abort))]
    (cond
     (seq args) (do (println "Invalid arguments" args) (help-abort))
     (->> (keys options)
          (filter options)
          set
          (set/intersection list-types)
          rest
          seq) (help-abort)
     :otherwise (c-list* (:repo options)
                         (some #(and (options %) %) list-types)))))


(def doc-remove
  "Remove artifacts from local Maven repo")
(defn c-remove
  "Remove artifacts from local Maven repo"
  [& args]
  (println "Not yet implemented"))


(def doc-help
  "Display help for plugin, or for specified command
  Arguments:
    [<command>] the command to show help for
  No argument lists generic help page")


(defn c-help
  "Display help for plugin, or for specified command"
  ([]
    (println "
Leiningen plugin to work with local Maven repository.

coords   Guess Leiningen (Maven) coords of a file
install  Install artifact to local repository
list     List artifacts in local repository
remove   Remove artifact from local repository (Not Yet Implemented)
help     This help screen

For help on individual commands use 'help' with command name, e.g.:

$ lein localrepo help install
"))
  ([command]
    (case command
      "coords"  (println doc-coords)
      "install" (println doc-install)
      "list"    (println doc-list)
      "remove"  (println doc-remove)
      "help"    (println doc-help)
      (in/illegal-arg "Illegal command:" command
        ", Allowed: coords, install, list, remove, help"))))


(defn apply-cmd
  [pred cmd f args]
  (if (pred) (apply f args)
             (c-help cmd)))


(defn ^:no-project-needed localrepo
  "Work with local Maven repository"
  ([_]
    (c-help))
  ([_ command & args]
    (let [argc (count args)]
      (case command
        "coords"  (apply-cmd #(= argc 1)        command c-coords  args)
        "install" (apply-cmd #(#{3 5 7} argc)   command c-install args)
        "list"    (apply-cmd #(#{0 1 2 3} argc) command c-list    args)
        "remove"  (apply-cmd #(>= argc 0)       command c-remove  args)
        "help"    (apply-cmd #(or (= argc 0)
                                  (= argc 1))   command c-help    args)
        (c-help)))))
