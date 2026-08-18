(ns run-nbb-tests
  "The nbb half of the suite. Same namespaces the JVM runner loads — and,
  since 2026-08-18, provably the same **dependency versions**.

  Two things this runner refuses to do quietly:

  1. **Run zero tests and exit 0.** A runner that loads a namespace without
     handing it to `run-tests` reports success for tests it never ran (root
     ADR-2608170300), so the list below is both required and run, and an
     empty run exits 2 — neither pass nor fail.

  2. **Test different code than `clojure -M:test` did.** The nbb classpath
     points at sibling *checkouts* while `deps.edn` pins *shas*, and those
     drift: measured on this machine the same day this check was written,
     `columnar` was checked out 4 commits behind the pin and 13 days older,
     so \"JVM and nbb are both green\" was a claim about two different
     columnars. Root ADR-2608180100 (git dep pin diamonds) is the same
     failure one level out. The check reads the pins out of `deps.edn` and
     compares them to the checkouts, and a mismatch exits 2 with the table.

  `TANA_ALLOW_PIN_DRIFT=1` runs anyway, for a deliberate cross-version run."
  (:require ["node:child_process" :as cp]
            ["node:fs" :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :as t]
            [tana.plan-test]
            [tana.parquet-range-test]
            [tana.chunk-only-test]
            [tana.aggregate-test]
            [tana.arrow-test]
            [tana.invariant-test]))

(def namespaces
  '[tana.plan-test tana.parquet-range-test tana.chunk-only-test
    tana.aggregate-test tana.arrow-test tana.invariant-test])

(defn- git-head [dir]
  (try (str/trim (str (cp/execSync (str "git -C " dir " rev-parse HEAD")
                                   #js {:stdio #js ["ignore" "pipe" "ignore"]})))
       (catch :default _ nil)))

(defn- declared-pins []
  (let [d (edn/read-string (str (fs/readFileSync "deps.edn" "utf8")))]
    (->> (concat (:deps d) (get-in d [:aliases :test :extra-deps]))
         (keep (fn [[lib coord]]
                 (when-let [sha (:git/sha coord)]
                   (when (str/starts-with? (str lib) "io.github.kotoba-lang/")
                     {:lib (name lib) :sha sha}))))
         vec)))

(defn- check-pins! []
  (let [rows (for [{:keys [lib sha]} (declared-pins)
                   :let [dir (str "../" lib)
                         head (git-head dir)]]
               {:lib lib :pinned sha :checked-out head
                :ok? (= sha head)})
        bad (remove :ok? rows)]
    (println (str "pins checked: " (count rows)))
    (when (zero? (count rows))
      ;; Evidence floor: a check that examined nothing must not read as clean.
      (println "no git pins found in deps.edn — the check could not run")
      (js/process.exit 2))
    (when (seq bad)
      (println "\nthe nbb classpath is NOT the dependency set deps.edn pins:\n")
      (doseq [{:keys [lib pinned checked-out]} bad]
        (println (str "  " lib
                      "\n    pinned      " (subs pinned 0 12)
                      "\n    checked out " (if checked-out (subs checked-out 0 12) "MISSING"))))
      (println "\nfix: west update --fetch smart" (str/join " " (map :lib bad)))
      (println "override: TANA_ALLOW_PIN_DRIFT=1")
      (when-not (.-TANA_ALLOW_PIN_DRIFT js/process.env)
        (js/process.exit 2)))))

(check-pins!)

(let [{:keys [fail error test]} (apply t/run-tests namespaces)]
  (when (zero? test)
    (println "no tests ran")
    (js/process.exit 2))
  (js/process.exit (if (pos? (+ fail error)) 1 0)))
