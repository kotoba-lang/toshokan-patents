#!/usr/bin/env nbb
;; toshokan-patents harvest CLI — fetches ONE patent by id from the live
;; source and appends the bibliographic quads to this repo's own
;; 80-data/public/google-patents.journal.edn (the git-authoritative EDN
;; quad-log per ADR-2607072300). This script only ever WRITES to this repo's
;; working tree; it never talks to kotobase.net.
;;
;; Usage (from the repo root):
;;   npx nbb --classpath "src" scripts/harvest.cljs <patent-id>
;;
;; <patent-id> is a worldwide patent identifier as accepted by Google Patents,
;; e.g. US10196540B2 / EP2835404A1 / JP2004224907A / WO2013151050A1.

(ns harvest
  (:require ["node:path" :as path]
            [clojure.string :as str]
            [toshokan_patents.quad :as quad]
            [toshokan_patents.sources.google-patents :as gp]))

(defn -main [patent-id]
  (let [journal-path (path/join "80-data" "public" "google-patents.journal.edn")
        existing (quad/read-journal journal-path)
        known (set (map first existing))
        tx (quad/next-tx existing)
        retrieved-at (.toISOString (js/Date.))]
    (println "lookup" patent-id "→" journal-path)
    (-> (gp/search patent-id)
        (.then (fn [recs]
                 (let [fresh (remove #(contains? known (:entity %)) recs)
                       new-quads (into [] (mapcat #(gp/->quads tx retrieved-at %)) fresh)]
                   (if (seq recs)
                     (do (quad/append-journal! journal-path new-quads)
                         (println "fetched=" (count recs)
                                  "new=" (count fresh)
                                  "quads=" (count new-quads)
                                  "tx=" tx))
                     (println "no record (404?) for" patent-id)))))
        (.catch (fn [e]
                  (println "FAILED:" (.-message e))
                  (js/process.exit 1))))))

(let [argv (js->clj js/process.argv)
      idx (or (some (fn [[i a]] (when (str/ends-with? a "harvest.cljs") i))
                    (map-indexed vector argv))
              2)
      [patent-id] (drop (inc idx) argv)]
  (if patent-id
    (-main patent-id)
    (do
      (println "usage: harvest.cljs <patent-id e.g. US10196540B2>")
      (js/process.exit 1))))
