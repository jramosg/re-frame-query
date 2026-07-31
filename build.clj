(ns build
  "Build + deploy for re-frame-query.

  The version is derived from the latest `vMAJOR.MINOR.PATCH` git tag, so
  `bb tag` is the only place a version is ever set — nothing here or in
  deps.edn needs bumping.

    clojure -T:build build     ; jar -> target/re-frame-query-<version>.jar
    clojure -T:build install   ; build, then install into ~/.m2
    clojure -T:build release   ; build, then deploy to Clojars
    clojure -T:build clean

  `release` reads Clojars credentials from the CLOJARS_USERNAME /
  CLOJARS_PASSWORD environment variables."
  (:require
   [clojure.string :as str]
   [clojure.tools.build.api :as b]
   [deps-deploy.deps-deploy :as dd]))

(def lib 'com.shipclojure/re-frame-query)

(def ^:private class-dir "target/classes")
(def ^:private src-dirs ["src"])

;; Carries clj-kondo.exports/, so consumers get the query/mutation hooks from
;; `clj-kondo --copy-configs --dependencies`. Kept out of `src-dirs` so the
;; generated pom keeps advertising sources only.
(def ^:private resource-dirs ["resources"])

(defn- git-tag-version
  "The version from the latest `v*` git tag (leading `v` stripped), or nil
  when there is no matching tag / git is unavailable."
  []
  (try
    (let [{:keys [exit out]} (b/process {:command-args ["git" "describe" "--tags"
                                                        "--abbrev=0" "--match" "v*"]
                                         :out :capture
                                         :err :capture})]
      (when (zero? exit)
        (second (re-matches #"v(.*)" (str/trim out)))))
    (catch Exception _ nil)))

(defn- resolve-version [version]
  (or version
      (git-tag-version)
      (throw (ex-info (str "No version: pass :version or create a `v*` git tag "
                           "(e.g. `git tag v0.1.0`).")
                      {:lib lib}))))

(defn- jar-file [version]
  (format "target/%s-%s.jar" (name lib) version))

(defn clean
  "Delete the target directory."
  [_]
  (b/delete {:path "target"}))

(defn build
  "Build target/re-frame-query-<version>.jar with a generated pom. `:version`
  defaults to the latest `v*` git tag. Returns the resolved version."
  [{:keys [version]}]
  (clean nil)
  (let [version (resolve-version version)
        ;; `:root nil` skips the root deps.edn, which would otherwise put
        ;; org.clojure/clojure in the pom. This library has no runtime deps —
        ;; consumers bring their own Clojure, ClojureScript, re-frame and reagent.
        basis (b/create-basis {:project "deps.edn" :root nil})]
    (b/copy-dir {:src-dirs (into src-dirs resource-dirs)
                 :target-dir class-dir})
    (b/write-pom {:class-dir class-dir
                  :lib lib
                  :version version
                  :basis basis
                  :src-dirs src-dirs
                  :scm {:url "https://github.com/shipclojure/re-frame-query"
                        :connection "scm:git:git://github.com/shipclojure/re-frame-query.git"
                        :developerConnection "scm:git:ssh://git@github.com/shipclojure/re-frame-query.git"
                        :tag (str "v" version)}
                  :pom-data [[:description
                              "Declarative data fetching and caching for re-frame, inspired by TanStack Query and RTK Query"]
                             [:url "https://github.com/shipclojure/re-frame-query"]
                             [:licenses
                              [:license
                               [:name "MIT License"]
                               [:url "https://opensource.org/licenses/MIT"]]]
                             [:developers
                              [:developer
                               [:name "Ovi Stoica"]]]]})
    (b/jar {:class-dir class-dir
            :jar-file (jar-file version)})
    (println "Wrote" (jar-file version))
    version))

(defn install
  "Build the jar and install it into the local ~/.m2 repository.

  Careful: with no `:version`, this installs under the *latest tag*, shadowing
  that released artifact in ~/.m2 with your working-tree build. For testing
  unreleased code against a local consumer, pass an explicit version:

    clojure -T:build install :version '\"0.11.0-SNAPSHOT\"'"
  [opts]
  (let [version (build opts)]
    (b/install {:basis (b/create-basis {:project "deps.edn" :root nil})
                :lib lib
                :version version
                :jar-file (jar-file version)
                :class-dir class-dir})
    (println "Installed" lib version "to ~/.m2")
    version))

(defn release
  "Build the jar and deploy it to Clojars. Credentials come from the
  CLOJARS_USERNAME / CLOJARS_PASSWORD env vars."
  [opts]
  (let [version (build opts)]
    (dd/deploy {:installer :remote
                :sign-releases? false
                :artifact (b/resolve-path (jar-file version))
                :pom-file (b/pom-path {:lib lib :class-dir class-dir})})
    (println "Deployed" lib version "to Clojars")))
