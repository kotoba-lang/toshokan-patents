#!/usr/bin/env nbb
;; kotobase-ingest-toshokan-patents.cljs — fold patent journals into
;; kotobase.net (same pull-based shape as toshokan ingest / ADR-2607113500).
;;
;; Usage (repo root):
;;   NODE_PATH="<path-to>/kotobase-client/node_modules" npx nbb \
;;     --classpath "<path-to>/kotobase-client/src:src" \
;;     scripts/kotobase-ingest-toshokan-patents.cljs
;;
;; Identity: Ed25519 seed in scripts/.kotobase-ingest-toshokan-patents-identity.hex
;; (gitignored). Graph: kotobase/db/<did>/toshokan-patents-catalog.

(ns kotobase-ingest-toshokan-patents
  (:require ["node:crypto" :as node-crypto]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [cljs.reader :as edn]
            [clojure.string :as str]
            [kotobase.client :as client]))

(def script-dir
  (let [argv (js->clj js/process.argv)
        idx (or (some (fn [[i a]]
                        (when (str/ends-with? a "kotobase-ingest-toshokan-patents.cljs") i))
                      (map-indexed vector argv))
                2)]
    (path/dirname (nth argv idx))))

(def identity-path
  (path/join script-dir ".kotobase-ingest-toshokan-patents-identity.hex"))

(defn load-or-create-identity! []
  (if (fs/existsSync identity-path)
    (js/Uint8Array.from (js/Buffer.from (str/trim (fs/readFileSync identity-path "utf8")) "hex"))
    (let [sk (js/Uint8Array. (.randomBytes node-crypto 32))]
      (fs/writeFileSync identity-path (.toString (js/Buffer.from sk) "hex"))
      (println "Minted a NEW ingestion identity at" identity-path
               "— back this file up, losing it orphans the graph.")
      sk)))

(def sk (load-or-create-identity!))
;; Edge is kotobase.net (cf-wasm). backend.kotobase.net rejects direct client writes.
(def c (client/make-client {:endpoint "https://kotobase.net"
                            :operator-did "did:web:kotobase.net"
                            :secret-key sk}))
(def db-name "toshokan-patents-catalog")

(def repo-root (path/join script-dir ".."))
(def sources ["google-patents"])

(defn journal-path [source]
  (path/join repo-root "80-data" "public" (str source ".journal.edn")))

(defn read-journal [source]
  (let [p (journal-path source)]
    (if (fs/existsSync p)
      (edn/read-string (fs/readFileSync p "utf8"))
      [])))

(defn build-tx-data
  "Group [entity attr value tx op] quads into entity maps for tx_edn.
   Cardinality-many attrs (e.g. :patent/cites) keep last scalar only — same
   simplification as toshokan ingest; fine for first fold."
  [source journal]
  (let [by-entity (group-by first journal)]
    (vec (for [[entity entries] by-entity]
           (into {:db/id entity :patent/harvest-source source}
                 (keep (fn [[_ a v _tx op]] (when (= op :add) [a v])))
                 entries)))))

(defn ingest-source! [source]
  (let [journal (read-journal source)]
    (if (empty? journal)
      (do (println "SKIP" source "(no journal / empty)")
          (js/Promise.resolve {:source source :ok true :entities 0}))
      (let [tx-data (build-tx-data source journal)
            tx-edn (pr-str tx-data)]
        (-> (client/transact c db-name tx-edn {:retry? true})
            (.then (fn [res]
                     (println "OK  " source " entities=" (count tx-data)
                              " datom_count=" (.-datom_count res))
                     {:source source :ok true :entities (count tx-data)}))
            (.catch (fn [e]
                      (println "FAIL" source (.-message e))
                      {:source source :ok false :error (.-message e)})))))))

(defn run-sequential [srcs]
  (reduce (fn [chain-p source]
            (.then chain-p (fn [acc]
                             (-> (ingest-source! source)
                                 (.then (fn [r] (.concat acc #js [r])))))))
          (js/Promise.resolve #js [])
          srcs))

(defn -main []
  (println "ingest identity did:" (:did c))
  (-> (run-sequential sources)
      (.then (fn [results]
               (let [results (js->clj results :keywordize-keys true)
                     ok (filter :ok results)
                     failed (remove :ok results)]
                 (println "=== SUMMARY ===")
                 (println "total:" (count results) "ok:" (count ok) "failed:" (count failed))
                 (when (seq failed)
                   (doseq [f failed] (println " -" (:source f) (:error f)))))))
      (.catch (fn [e] (println "FATAL:" (.-message e)) (println (.-stack e))))))

(-main)
