#!/usr/bin/env nbb
;; run-tests.cljs — the ClojureScript half of the parity check.
;;
;; The JVM half is `clojure -M:test`. Both run the SAME .cljc test namespace
;; against the SAME fixture; that is the whole point. If only one of them is
;; ever run, `parse-html` is not portable, it is merely untested on one side.
;;
;;   nbb --classpath src:test run-tests.cljs
(ns run-tests
  (:require [clojure.test :as t]
            [toshokan-patents.google-patents-test]))

(defmethod t/report [:cljs.test/default :end-run-tests] [m]
  (println (str "\nRan " (:test m) " tests containing "
                (+ (:pass m) (:fail m) (:error m)) " assertions."))
  (println (str (:fail m) " failures, " (:error m) " errors."))
  (when-not (t/successful? m) (js/process.exit 1)))

(t/run-tests 'toshokan-patents.google-patents-test)
