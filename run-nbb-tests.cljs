(ns run-nbb-tests
  "The nbb half of the suite. Same namespaces the JVM runner loads.

  A runner that loads a namespace without handing it to `run-tests` reports
  success for tests it never ran (root ADR-2608170300), so the namespaces are
  listed once and both required and run from that one list."
  (:require [clojure.test :as t]
            [tana.plan-test]
            [tana.parquet-range-test]
            [tana.chunk-only-test]))

(def namespaces '[tana.plan-test tana.parquet-range-test tana.chunk-only-test])

(let [{:keys [fail error test]} (apply t/run-tests namespaces)]
  (when (zero? test)
    (println "no tests ran")
    (js/process.exit 2))
  (js/process.exit (if (pos? (+ fail error)) 1 0)))
