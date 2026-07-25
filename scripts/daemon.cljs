#!/usr/bin/env nbb
;; daemon.cljs — toshokan-patents 自己成長常駐ティック (ADR-2607251552).
;;
;; ## 何をするか
;; 1. seeds.edn の次の patent-id を 1 件 lookup (Google Patents 個別ページ)
;; 2. 既存 journal と entity で dedupe して 80-data/public/google-patents.journal.edn に追記
;; 3. 新しい patent の cited patents (DC.relation references) から seed を自動追加
;;    → repo が citation graph を自己成長で探索
;; 4. --push なら git commit + push (git 履歴が正本 / ADR-2607072300)
;; 5. --ingest なら kotobase-ingest-toshokan-patents.cljs で backend.kotobase.net へ fold
;;
;; ## 何をしないか
;; - claims / specification 全文は取らない (metadata-only 不変条件)
;; - bot 検出回避・CAPTCHA 突破はしない (identifying User-Agent, 順次 + sleep)
;; - 高並列はしない (1 tick = 1 lookup, polite)
;;
;; ## murakumo.cloud 常駐
;; LaunchAgent (deploy/com.kotoba-lang.toshokan-patents-tick.plist) が
;; daemon.cljs --once --push --ingest を定期実行する。toshokan-tick と同型。
;; WASM on-tick への載せ替えは ADR-2607252400 capability が揃ってから (排他ではない)。
;;
;; Usage (repo root):
;;   nbb --classpath src scripts/daemon.cljs --once
;;   nbb --classpath src scripts/daemon.cljs --once --push --ingest
;;   nbb --classpath src scripts/daemon.cljs --interval 21600 --push --ingest

(ns daemon
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:child_process" :as cp]
            [clojure.string :as str]
            [cljs.reader :as edn]
            [toshokan_patents.quad :as quad]
            [toshokan_patents.sources.google-patents :as gp]))

(def state-path "state.edn")
(def seeds-path "seeds.edn")
(def journal-dir (path/join "80-data" "public"))

;; Source table — patent-id lookup. `search` takes the id as the query;
;; count/page are ignored (one id = one record).
(def sources
  {"google-patents" {:search     (fn [q _n _page] (gp/search q))
                     :->quads    gp/->quads}})

(defn- read-edn [p default]
  (if (fs/existsSync p)
    (try (edn/read-string (fs/readFileSync p "utf8"))
         (catch :default e
           (println "[daemon] WARN failed to read" p (.-message e))
           default))
    default))

(defn- write-edn! [p data comment]
  (fs/writeFileSync p (str ";; " comment "\n" (pr-str data) "\n")))

(defn- journal-path [source]
  (path/join journal-dir (str source ".journal.edn")))

(defn- known-entities [source]
  (->> (quad/read-journal (journal-path source)) (map first) set))

(defn- entity-count [source] (count (known-entities source)))

(defn- sh-status [& args]
  (let [r (.spawnSync cp (first args) (clj->js (rest args))
                      #js {:encoding "utf8"
                           :maxBuffer (* 32 1024 1024)
                           :stdio "inherit"})]
    (if (nil? (.-status r)) 1 (.-status r))))

(defn- sh-ok? [& args] (zero? (apply sh-status args)))

(defn- pair-key [source seed-id page]
  (str source "|" seed-id "|p" page))

(defn- load-seeds []
  (let [m (read-edn seeds-path {:policy {} :seeds []})]
    {:policy (merge {:max-records-per-tick 8
                     :max-new-seeds-per-tick 10
                     :max-seeds 5000
                     :sources (vec (keys sources))
                     :inter-source-sleep-ms 2000}
                    (:policy m))
     :seeds (vec (:seeds m))}))

(defn- save-seeds! [seeds-data]
  (write-edn! seeds-path seeds-data
              "seeds.edn — managed by daemon self-grow (citation graph) + human edits."))

(defn- load-state []
  (read-edn state-path {:cursor 0 :pages {} :exhausted #{} :ticks 0 :last-tick nil}))

(defn- save-state! [st]
  (write-edn! state-path st "state.edn — daemon managed; do not hand-edit."))

(defn- work-pairs [seeds-data]
  (let [srcs (vec (get-in seeds-data [:policy :sources]))]
    (vec (for [seed (:seeds seeds-data) src srcs]
           {:seed seed :source src
            :seed-id (or (:id seed) (str "q-" (hash (:query seed))))}))))

(defn- next-work
  "Pick next (source, seed, page) not exhausted. Round-robin over pairs."
  [st seeds-data]
  (let [pairs (work-pairs seeds-data)
        n (count pairs)
        exhausted (set (:exhausted st))
        pages (:pages st {})]
    (when (pos? n)
      (loop [i 0]
        (when (< i n)
          (let [idx (mod (+ (:cursor st 0) i) n)
                {:keys [seed source seed-id]} (nth pairs idx)
                page (get pages (str source "|" seed-id) 1)
                k (pair-key source seed-id page)]
            (if (contains? exhausted k)
              (recur (inc i))
              {:source source :seed seed :seed-id seed-id
               :page page :seed-index idx :pair-key k})))))))

(defn- grow-seeds!
  "Append new seeds from the CITED PATENTS of freshly harvested records
  (DC.relation scheme=references). The patent-domain analogue of toshokan
  growing seeds from creators — here we walk the citation graph."
  [seeds-data new-records source]
  (let [policy (:policy seeds-data)
        max-seeds (:max-seeds policy)
        max-new (:max-new-seeds-per-tick policy)
        existing-q (->> (:seeds seeds-data) (map :query) (map str/upper-case) set)
        candidates (->> new-records
                        (mapcat :citations)
                        (map str/upper-case)
                        (remove str/blank?)
                        (remove #(> (count %) 30))
                        (remove #(contains? existing-q %))
                        distinct
                        (take max-new))
        room (- max-seeds (count (:seeds seeds-data)))
        to-add (take (max 0 room)
                     (map (fn [pid]
                            {:id (str "cited-" (str/lower-case pid))
                             :query pid
                             :grown-from source
                             :grown-at (.toISOString (js/Date.))})
                          candidates))]
    (if (seq to-add)
      (do (println "[daemon] self-grow +" (count to-add) "citation seeds:"
                   (pr-str (map :query to-add)))
          (update seeds-data :seeds into to-add))
      seeds-data)))

(defn harvest-one!
  "Harvest one (source, seed=patent-id). Returns promise of result map."
  [{:keys [source seed seed-id page pair-key]} policy]
  (let [{:keys [search ->quads]} (get sources source)
        q (:query seed)
        known (known-entities source)
        jpath (journal-path source)]
    (println (str "[daemon] harvest " source " seed=" seed-id " q=" (pr-str q)))
    (-> (search q (:max-records-per-tick policy) page)
        (.then
         (fn [recs]
           (let [recs (vec recs)
                 fresh (filterv #(not (contains? known (:entity %))) recs)
                 existing (quad/read-journal jpath)
                 tx (quad/next-tx existing)
                 retrieved-at (.toISOString (js/Date.))
                 new-quads (into [] (mapcat #(->quads tx retrieved-at %)) fresh)]
             (when (seq new-quads)
               (quad/append-journal! jpath new-quads))
             (println (str "[daemon]   fetched=" (count recs)
                           " new-entities=" (count fresh)
                           " quads=" (count new-quads)
                           " journal-entities≈" (+ (count known) (count fresh))))
             {:source source :seed-id seed-id :page page :pair-key pair-key
              :fetched (count recs) :new (count fresh) :quads (count new-quads)
              :records fresh
              ;; a lookup returning 0 records means the id is dead (404) → exhausted.
              ;; lookups always return ≤1 record, so there is no pagination to advance.
              :page-exhausted? true
              :failed? false})))
        (.catch
         (fn [e]
           (println "[daemon]   FAIL" source (.-message e))
           {:source source :seed-id seed-id :page page :pair-key pair-key
            :fetched 0 :new 0 :quads 0 :records [] :page-exhausted? false
            :failed? true :error (.-message e)})))))

(defn- git-push! [summary]
  (println "[daemon] git commit + push:" summary)
  (and (sh-ok? "git" "add" "80-data/public" "seeds.edn" "state.edn")
       (let [st (sh-status "git" "diff" "--cached" "--quiet")]
         (if (zero? st)
           (do (println "[daemon]   nothing to commit") true)
           (and (sh-ok? "git" "commit" "-m" summary)
                (sh-ok? "git" "-c" "core.sshCommand=/usr/bin/ssh"
                        "push" "origin" "HEAD"))))))

(defn- kotobase-ingest! []
  (println "[daemon] kotobase ingest fold (toshokan-patents)")
  (let [client-src (or (.-env.KOTOBASE_CLIENT_SRC js/process)
                       (path/resolve ".." "kotobase-client" "src"))
        nm (or (.-env.NODE_PATH js/process)
               (path/resolve ".." "kotobase-client" "node_modules"))
        cp-str (str client-src ":" "src")]
    (zero? (sh-status "env"
                      (str "NODE_PATH=" nm)
                      "nbb" "--classpath" cp-str
                      "scripts/kotobase-ingest-toshokan-patents.cljs"))))

(defn tick!
  [{:keys [push? ingest?]}]
  (let [seeds-data (load-seeds)
        st (load-state)
        policy (:policy seeds-data)
        work (next-work st seeds-data)]
    (if-not work
      (do (println "[daemon] no remaining work (all seeds exhausted or empty)")
          (save-state! (assoc st :last-tick (.toISOString (js/Date.))
                              :ticks (inc (:ticks st 0))))
          {:ok true :idle? true})
      (-> (harvest-one! work policy)
          (.then
           (fn [r]
             (let [st2 (-> st
                           (assoc :last-tick (.toISOString (js/Date.))
                                  :cursor (inc (:seed-index work))
                                  :ticks (inc (:ticks st 0)))
                           (assoc-in [:pages (str (:source work) "|" (:seed-id work))]
                                     (if (:page-exhausted? r) (:page work) (inc (:page work))))
                           (cond-> (:page-exhausted? r)
                             (update :exhausted (fnil conj #{}) (:pair-key work))
                             (:failed? r)
                             (update :failures (fnil conj [])
                                     {:at (.toISOString (js/Date.))
                                      :source (:source r) :error (:error r)})))
                   seeds2 (if (and (not (:failed? r)) (seq (:records r)))
                            (grow-seeds! seeds-data (:records r) (:source r))
                            seeds-data)]
               (save-state! st2)
               (when (not= seeds2 seeds-data) (save-seeds! seeds2))
               (when push?
                 (git-push!
                  (str "toshokan-patents: lookup " (:source r)
                       " seed=" (:seed-id r)
                       " new=" (:new r)
                       " entities≈" (entity-count (:source r)))))
               (when (and ingest? (pos? (:new r))) (kotobase-ingest!))
               (println "[daemon] tick done"
                        (pr-str (select-keys r [:source :seed-id :fetched :new :quads :failed?])))
               r)))))))

(defn- parse-args [argv]
  (let [args (set argv)]
    {:once? (contains? args "--once")
     :push? (contains? args "--push")
     :ingest? (contains? args "--ingest")
     :interval (let [i (.indexOf (clj->js argv) "--interval")
                     v (when (and (>= i 0) (< (inc i) (count argv)))
                         (js/parseInt (nth argv (inc i)) 10))]
                 (if (and v (pos? v)) v 21600))}))

(defn -main []
  (let [argv (js->clj js/process.argv)
        script-idx (or (some (fn [[i a]] (when (str/ends-with? a "daemon.cljs") i))
                             (map-indexed vector argv)) 2)
        opts (parse-args (drop (inc script-idx) argv))]
    (println "[daemon] start" (pr-str opts) "cwd=" (.cwd js/process))
    (if (:once? opts)
      (-> (tick! opts)
          (.then (fn [_] (js/process.exit 0)))
          (.catch (fn [e] (println "[daemon] fatal" (.-message e)) (js/process.exit 1))))
      (let [loop-fn (atom nil)]
        (reset! loop-fn
                (fn []
                  (-> (tick! opts)
                      (.then (fn [_]
                               (println "[daemon] sleep" (:interval opts) "s")
                               (js/setTimeout @loop-fn (* 1000 (:interval opts)))))
                      (.catch (fn [e]
                                (println "[daemon] tick error" (.-message e))
                                (js/setTimeout @loop-fn (* 1000 (:interval opts))))))))
        (@loop-fn)))))

(-main)
