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

(defn write-journal! [path quads]
  (spit* path (str (pr-str (vec quads)) "\n")))

(defn append-journal!
  "Read `path`, append `new-quads` (tx numbers already stamped by the caller via
  `next-tx`), write back. Returns the full merged vector."
  [path new-quads]
  (let [merged (merge-quads (read-journal path) new-quads)]
    (write-journal! path merged)
    merged))
