(ns toshokan-patents.quad
  "EDN quad-log codec — the git-authoritative journal convention of ADR-2607072300
  (same shape as kotoba-lang/toshokan and cloud-itonami-lei-*).

  A journal file's whole contents is ONE top-level EDN vector of
  `[entity attr value tx op]` tuples (op is `:add` or `:retract`).
  Cardinality-many attrs fan out to one tuple per element.

  ## This is a library, not a runner

  Everything above the `read-journal` line is PURE and portable — it never
  touches a clock, a socket, or a filesystem. The file legs at the bottom are
  reader-conditional (`clojure.java.io` on the JVM, `node:fs` on nbb/cljs) and
  exist so a consumer does not have to re-derive the on-disk convention.

  **Who owns the loop is not this repo's business** (2026-08-10 owner decision:
  kotoba-lang holds libraries only). The residency, the seed policy, the cursor,
  and the git commits live in the actor that consumes this —
  `cloud-itonami/hirameki`."
  (:refer-clojure :exclude [read-string])
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            #?(:clj [clojure.java.io :as io])
            #?(:cljs ["node:fs" :as fs])))

;; ── pure ─────────────────────────────────────────────────────────────────────

(defn next-tx
  "The tx number a fresh append should carry: one past the highest already in
  `existing-quads`. 1 for an empty journal."
  [existing-quads]
  (inc (reduce max 0 (map #(nth % 3) existing-quads))))

(defn record->quads
  "field-map is `{attr value-or-nil-or-coll ...}`.

  - `nil` values are DROPPED — a source page that omits a field costs nothing
    and never writes a null into the log.
  - sequential values FAN OUT to one quad per element (cardinality-many). This
    is what turns `:patent/cites` into a walkable citation graph.

  Pure: the caller supplies `tx`, so the same inputs always give the same quads."
  [entity tx field-map]
  (into []
        (mapcat (fn [[attr v]]
                  (cond
                    (nil? v) []
                    (sequential? v) (for [item v :when (some? item)]
                                      [entity attr item tx :add])
                    :else [[entity attr v tx :add]])))
        field-map))

(defn entities
  "The distinct entity ids present in a quad vector — the dedupe key a harvester
  checks before spending a request."
  [quads]
  (into #{} (map first) quads))

(defn merge-quads
  "Append `new-quads` after `existing`. Pure counterpart of `append-journal!`."
  [existing new-quads]
  (into (vec existing) new-quads))

;; ── file legs (reader-conditional) ───────────────────────────────────────────

(defn- slurp* [path]
  #?(:clj  (slurp (io/file path))
     :cljs (fs/readFileSync path "utf8")))

(defn- spit* [path s]
  #?(:clj  (spit (io/file path) s)
     :cljs (fs/writeFileSync path s)))

(defn- exists?* [path]
  #?(:clj  (.exists (io/file path))
     :cljs (fs/existsSync path)))

(defn read-journal
  "Read a journal file into a quad vector. A missing file reads as `[]` — an
  absent journal and an empty journal are the same thing to a harvester."
  [path]
  (if (exists?* path)
    (edn/read-string (slurp* path))
    []))

(defn render-journal
  "Quads → the on-disk text: ONE top-level EDN vector, one quad per line.

  Still a single `edn/read-string`-able form, so every existing reader keeps
  working — but line-oriented, which is what makes it survive a long-running
  harvest. The original writer emitted `(pr-str (vec quads))`, i.e. the whole
  journal on ONE line; at 618 patents that was a single 691 KB line. Git stores
  a fresh blob per commit either way, but a one-line file also defeats delta
  compression and makes `git diff` and `git blame` useless on the exact artifact
  that is supposed to be the authoritative record (ADR-2607072300)."
  [quads]
  (str "[" (reduce str (map #(str "\n" (pr-str %)) quads)) "\n]\n"))

(defn write-journal! [path quads]
  (spit* path (render-journal quads)))

(defn append-journal!
  "Read `path`, append `new-quads`, write back. Returns the full merged vector.

  O(n) per append over the whole journal. Fine for a bounded corpus; use the
  sharded API below for one that grows without end."
  [path new-quads]
  (let [merged (merge-quads (read-journal path) new-quads)]
    (write-journal! path merged)
    merged))

;; ── sharded journal ──────────────────────────────────────────────────────────
;;
;; A journal that a resident loop appends to forever cannot be one file. Not
;; because of disk — because every commit stores a new copy of the WHOLE file,
;; so a 44 MB journal appended to 120 times a day writes 5 GB of git objects a
;; day for 8 KB a day of new facts.
;;
;; Shards are sealed once full: an old shard is byte-identical forever, so git
;; stores it exactly once and DataLad/annex can take it if it ever needs to.
;; Only the ACTIVE shard is rewritten, and it is bounded.
;;
;; Shards live in the same directory with a `<source>.NNNN.journal.edn` name
;; rather than a subdirectory, so every existing consumer — the query plane's
;; "read *.journal.edn in this dir", the corpus fold, verify — keeps working
;; with no change at all.

(def default-shard-max-bytes
  "Seal a shard past 1 MiB. Small enough that an append rewrites little and a
  diff stays reviewable; large enough that a million patents is ~1,000 files
  rather than a directory nobody can list."
  (* 1024 1024))

(defn shard-name [source n]
  (str source "." (subs (str "0000" n) (- (count (str "0000" n)) 4)) ".journal.edn"))

(defn- shard-index [source filename]
  (when-let [m (re-matches (re-pattern (str "^" source "\\.(\\d{4})\\.journal\\.edn$")) filename)]
    #?(:clj (Long/parseLong (second m))
       :cljs (js/parseInt (second m) 10))))

(defn- list-dir [dir]
  #?(:clj  (let [f (io/file dir)] (if (.isDirectory f) (mapv #(.getName %) (.listFiles f)) []))
     :cljs (if (and (exists?* dir) (.isDirectory (fs/statSync dir)))
             (vec (fs/readdirSync dir))
             [])))

(defn- join* [dir name]
  #?(:clj  (str (io/file dir name))
     :cljs (str dir "/" name)))

(defn shard-paths
  "Every shard of `source` under `dir`, in index order."
  [dir source]
  (->> (list-dir dir)
       (keep (fn [f] (when-let [i (shard-index source f)] [i (join* dir f)])))
       (sort-by first)
       (mapv second)))

(defn read-sharded
  "All quads of `source`, shards concatenated in index order.

  Also picks up a legacy single `<source>.journal.edn` if one is still present,
  BEFORE the shards — that file holds the oldest facts, and dropping it silently
  would look exactly like a corpus that had always been smaller."
  [dir source]
  (let [legacy (join* dir (str source ".journal.edn"))
        parts (cond-> (shard-paths dir source)
                (exists?* legacy) (->> (into [legacy])))]
    (into [] (mapcat read-journal) parts)))

(defn append-sharded!
  "Append `new-quads` to the active shard of `source`, sealing and rolling over
  past `:shard-max-bytes`.

  Only the active shard is read and rewritten, so the cost of an append is
  bounded by the shard size rather than by the size of the corpus. Returns
  `{:shard <path> :sealed <bool> :quads <n-in-shard>}`."
  ([dir source new-quads] (append-sharded! dir source new-quads {}))
  ([dir source new-quads {:keys [shard-max-bytes] :or {shard-max-bytes default-shard-max-bytes}}]
   (let [paths (shard-paths dir source)
         active (last paths)
         active-size (if (and active (exists?* active))
                       #?(:clj  (.length (io/file active))
                          :cljs (.-size (fs/statSync active)))
                       0)
         roll? (or (nil? active) (>= active-size shard-max-bytes))
         idx (if roll? (count paths) (dec (count paths)))
         path (join* dir (shard-name source idx))
         existing (if roll? [] (read-journal path))
         merged (merge-quads existing new-quads)]
     (write-journal! path merged)
     {:shard path :sealed roll? :quads (count merged)})))
