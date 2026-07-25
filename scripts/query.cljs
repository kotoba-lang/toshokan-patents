#!/usr/bin/env nbb
;; query.cljs — load 80-data/public/*.journal.edn into DataScript and query it.
;;
;; Attributes are bare strings ("patent/title") for datascript.js. Raw Datalog
;; works the same. The live graph on kotobase.net is a separate path
;; (scripts/kotobase-ingest-toshokan-patents.cljs); this is local verification
;; over this repo's own journals.
;;
;; Usage (repo root):
;;   nbb --classpath src:../../../scripts/nbb_compat scripts/query.cljs stats
;;   nbb ... scripts/query.cljs sample [N]
;;   nbb ... scripts/query.cljs sources
;;   nbb ... scripts/query.cljs q '[:find ?t :where [?e "patent/title" ?t]]'

(ns query
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]
            [cljs.reader :as edn]
            ["datascript" :as ds-mod]))

(def ds (or (.-default ds-mod) ds-mod))
(def journal-dir (path/join "80-data" "public"))

(defn- journal-files []
  (if (fs/existsSync journal-dir)
    (->> (js->clj (fs/readdirSync journal-dir))
         (filter #(str/ends-with? % ".journal.edn"))
         (map #(path/join journal-dir %)) sort)
    []))

(defn- quads []
  (->> (journal-files)
       (mapcat (fn [p] (try (edn/read-string (fs/readFileSync p "utf8"))
                            (catch :default _ []))))
       vec))

(defn- kw->attr [k]
  (if (keyword? k)
    (if-let [ns (namespace k)] (str ns "/" (name k)) (name k))
    (str k)))

(defn- build-db []
  (let [by-entity (group-by first (quads))
        entities
        (map-indexed
         (fn [i [entity entries]]
           (let [obj (js-obj)]
             (aset obj ":db/id" (- (inc i)))
             (aset obj "patent/entity" (str entity))
             (doseq [[_ a v _ op] entries]
               (when (= op :add)
                 (let [attr (kw->attr a) prev (aget obj attr)]
                   (cond (nil? prev) (aset obj attr v)
                         (array? prev) (.push prev v)
                         :else (aset obj attr #js [prev v])))))
             obj))
         by-entity)
        conn (.create_conn ds (js-obj))]
    (.transact ds conn (into-array entities))
    (.db ds conn)))

(defn cmd-stats []
  (let [qs (quads)
        ents (set (map first qs))
        by-src (frequencies (for [[e a v] qs :when (= a :patent/source)] v))
        titles (count (filter #(= (second %) :patent/title) qs))
        cites (count (filter #(= (second %) :patent/cites) qs))]
    (println (str "quads=" (count qs) " entities=" (count ents)
                  " title-attrs=" titles " citation-edges=" cites
                  " journals=" (count (journal-files))))
    (println "\n-- by :patent/source --")
    (doseq [[k v] (sort-by val > by-src)] (println (str "  " k ": " v)))))

(defn cmd-sources []
  (doseq [p (journal-files)]
    (let [qs (try (edn/read-string (fs/readFileSync p "utf8")) (catch :default _ []))
          ents (count (set (map first qs)))]
      (println (str ents "\t" (count qs) "\t" p)))))

(defn- rows []
  (let [by-e (group-by first (quads))]
    (for [[entity entries] by-e
          :let [m (into {} (keep (fn [[_ a v _ op]] (when (= op :add) [a v])) entries))
                t (:patent/title m)]
          :when t]
      {:entity entity :title t :source (:patent/source m)
       :applicant (:patent/applicant m)})))

(defn cmd-sample [n]
  (let [n (js/parseInt (or n "8") 10)
        rows (take n (shuffle (vec (rows))))]
    (doseq [{:keys [title source applicant]} rows]
      (println (str "[" source "] "
                    (subs (str title) 0 (min 72 (count (str title))))
                    (when applicant (str " / " (first (if (sequential? applicant) applicant [applicant])))))))))

(defn cmd-q [qstr]
  (let [db (build-db) res (.q ds qstr db)]
    (println (pr-str (js->clj res)))))

(defn -main [& args]
  (let [cmd (or (first args) "stats")]
    (case cmd
      "stats" (cmd-stats)
      "sources" (cmd-sources)
      "sample" (cmd-sample (second args))
      "q" (if-let [q (second args)] (cmd-q q)
            (do (println "usage: query.cljs q '<datalog>'") (js/process.exit 1)))
      (do (println "usage: query.cljs stats|sources|sample [N]|q '<datalog>'")
          (js/process.exit 1)))))

(let [argv (js->clj js/process.argv)
      idx (or (some (fn [[i a]] (when (str/ends-with? a "query.cljs") i))
                    (map-indexed vector argv)) 2)]
  (apply -main (drop (inc idx) argv)))
