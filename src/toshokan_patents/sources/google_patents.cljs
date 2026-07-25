(ns toshokan_patents.sources.google-patents
  "Google Patents individual-page harvester — worldwide patent bibliographic
  metadata via patents.google.com/patent/<id>/en.

  Why this source first (ADR-2607251552): USPTO ODP API is mid-migration
  (search.patentsview.org sunset 2026-03; data.uspto.gov API path still fluid),
  EPO OPS needs OAuth registration. Google Patents pages are public,
  server-rendered HTML with rich Dublin Core / citation_* / itemprop metadata,
  and they cover the WORLDWIDE bibliographic space (US/EP/JP/WO/CN/KR/...) in
  one source — effectively a free, no-auth mirror of the EPO DOCDB bibliographic
  set. The page carries DC.relation scheme=references (cited patents), so the
  resident daemon self-grows by walking the citation graph — the patent-domain
  analogue of toshokan growing seeds from creators.

  Discipline (same as toshokan library catalogs): identifying User-Agent, low
  rate, sequential, metadata-only. We store only bibliographic fields extracted
  from the page's own structured metadata, never the claims/specification text.
  Replace with EPO OPS (OAuth) or USPTO ODP API once either stabilizes — the
  journal schema and daemon are source-agnostic, so swapping is non-exclusive."
  (:require [clojure.string :as str]
            [toshokan_patents.quad :as quad]))

(def ^:const page-endpoint "https://patents.google.com/patent/")
(def ^:const source-key :google-patents)

(def ^:const ua
  "toshokan-patents-harvester/0.1 (kotoba-lang/toshokan-patents; worldwide patent bibliographic metadata preservation; +https://github.com/kotoba-lang/toshokan-patents)")

;; ----- HTML meta-tag extraction (regex; no cheerio/jsdom dependency) -----

(defn- exec-all [re html]
  (let [out (atom [])]
    (loop [m (.exec re html)]
      (when m
        (swap! out conj (aget m 1))
        (recur (.exec re html))))
    @out))

(defn meta-by-name
  "All content values for <meta name=\"NAME\" content=\"...\"> in either attr order."
  [html name]
  (let [re1 (js/RegExp. (str "<meta\\s[^>]*?name=[\"']" name "[\"'][^>]*?content=[\"']([^\"']*)[\"']") "gi")
        re2 (js/RegExp. (str "<meta\\s[^>]*?content=[\"']([^\"']*)[\"'][^>]*?name=[\"']" name "[\"']") "gi")]
    (distinct (concat (exec-all re1 html) (exec-all re2 html)))))

(defn- content-of [tag]
  (let [m (.exec (js/RegExp. "content=[\"']([^\"']*)[\"']" "i") tag)]
    (when m (aget m 1))))

(defn meta-by-name-scheme
  "All content values for <meta name=NAME ... scheme=SCHEME ...> in any attr order."
  [html name scheme]
  (let [name-re   (js/RegExp. (str "name=[\"']"   name   "[\"']"))
        scheme-re (js/RegExp. (str "scheme=[\"']" scheme "[\"']"))
        tag-re    (js/RegExp. "<meta\\b[^>]*>" "gi")
        out (atom [])]
    (loop [m (.exec tag-re html)]
      (when m
        (let [tag (aget m 0)]
          (when (and (.test name-re tag) (.test scheme-re tag))
            (when-let [c (content-of tag)] (swap! out conj c))))
        (recur (.exec tag-re html))))
    (distinct @out)))

(defn meta-first [html name]
  (first (meta-by-name html name)))

;; ----- patent-id helpers -----

(defn normalize-patent-id [id]
  (-> id str/trim str/upper-case (str/replace #"\s+" "")))

(defn page-url [id]
  (str page-endpoint (normalize-patent-id id) "/en"))

(defn country-code [id]
  (let [m (re-find #"^([A-Z]{2})" (normalize-patent-id id))]
    (when m (second m))))

(defn cited-patent-ids
  "DC.relation scheme=references values like 'JP:2004224907:A' → 'JP2004224907A'.
  These are the seed candidates for self-grow (citation-graph walk)."
  [html]
  (->> (meta-by-name-scheme html "DC.relation" "references")
       (map #(str/replace % #":" ""))
       (filter #(re-find #"^[A-Z]{2}\d" %))
       distinct))

;; ----- parse + lookup -----

(defn parse-html
  "Google Patents page HTML (raw) -> field-map. nil fields are dropped by
  record->quads, so missing metadata on a given page costs nothing."
  [html id]
  (let [pid (normalize-patent-id id)]
    {:entity (str "gp:" pid)
     :source-url (page-url id)
     :patent-id pid
     :country (country-code pid)
     :title (or (meta-first html "DC.title")
                (let [m (.exec (js/RegExp. "<title>\\s*([^<]+?)\\s*-\\s*Google Patents" "i") html)]
                  (when m (aget m 1))))
     :number (meta-first html "citation_patent_number")
     :app-number (meta-first html "citation_patent_application_number")
     :filed-at (first (meta-by-name-scheme html "DC.date" "dateSubmitted"))
     :granted-at (first (meta-by-name-scheme html "DC.date" "issue"))
     :inventors (meta-by-name-scheme html "DC.contributor" "inventor")
     :assignees (meta-by-name-scheme html "DC.contributor" "assignee")
     :citations (cited-patent-ids html)}))

(defn lookup
  "Fetch one patent page by id (e.g. 'US10196540B2', 'EP2835404A1',
  'JP2004224907A'). Returns a JS Promise of a field-map, or nil on 404."
  [patent-id]
  (-> (js/fetch (page-url patent-id) #js {:headers #js {"User-Agent" ua}})
      (.then (fn [^js r]
               (cond (.-ok r) (.text r)
                     (= 404 (.-status r)) nil
                     :else (throw (js/Error. (str "Google Patents HTTP " (.-status r)
                                                  " for " patent-id))))))
      (.then (fn [html] (when html (parse-html html patent-id))))))

;; The daemon's source table calls (search query count page). For a
;; patent-id lookup the 'query' IS the patent-id; one id resolves to at most
;; one record, so count/page are ignored.
(defn search [patent-id & _opts]
  (-> (lookup patent-id)
      (.then (fn [m] (if m [m] [])))))

(defn ->quads [tx retrieved-at m]
  (quad/record->quads
   (:entity m) tx
   {:patent/source source-key
    :patent/source-url (:source-url m)
    :patent/title (:title m)
    :patent/number (:number m)
    :patent/patent-id (:patent-id m)
    :patent/country (:country m)
    :patent/application-number (:app-number m)
    :patent/filed-at (:filed-at m)
    :patent/granted-at (:granted-at m)
    :patent/inventor (:inventors m)
    :patent/applicant (:assignees m)
    :patent/cites (:citations m)          ; cardinality-many → citation graph
    :patent/retrieved-at retrieved-at}))
