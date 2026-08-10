(ns toshokan-patents.google-patents-test
  "Parser tests against a REAL Google Patents page.

  The fixture is `test/toshokan_patents/fixtures/US8697359B1.html` — the live
  page for the Broad Institute CRISPR-Cas9 patent, reduced to its `<title>` and
  its 2,329 `<meta>` tags (the parser reads nothing else). It is a real page,
  not a hand-written minimal one, because the bugs this parser can have are
  bugs about SCALE and ORDER: a `name`/`scheme` pair that only conflates on a
  page carrying both, an attribute order that only appears once in a thousand
  tags. A three-tag fixture proves nothing about those.

  These tests are `.cljc` and run on BOTH platforms on purpose — `parse-html` is
  the pure half of the library, and 'pure' is a claim about platform agreement,
  not a comment."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [toshokan-patents.sources.google-patents :as gp]
            [toshokan-patents.quad :as quad]
            #?(:clj [clojure.java.io :as io])
            #?(:cljs ["node:fs" :as fs])))

(def fixture-path "test/toshokan_patents/fixtures/US8697359B1.html")

(def html
  (delay #?(:clj  (slurp (io/file fixture-path))
            :cljs (fs/readFileSync fixture-path "utf8"))))

(deftest normalize-and-derive
  (testing "id normalization is idempotent and whitespace-proof"
    (is (= "US8697359B1" (gp/normalize-patent-id "  us8697359b1 ")))
    (is (= "US8697359B1" (gp/normalize-patent-id (gp/normalize-patent-id "US8697359B1")))))
  (testing "jurisdiction comes from the id prefix"
    (is (= "US" (gp/country-code "US8697359B1")))
    (is (= "JP" (gp/country-code "jp2004224907a")))
    (is (= "WO" (gp/country-code "WO2013151050A1"))))
  (testing "page-url normalizes before building the URL"
    (is (= "https://patents.google.com/patent/US8697359B1/en"
           (gp/page-url " us8697359b1 ")))))

(deftest parses-the-real-page
  (let [m (gp/parse-html @html "US8697359B1")]
    (testing "identity"
      (is (= "gp:US8697359B1" (:entity m)))
      (is (= "US8697359B1" (:patent-id m)))
      (is (= "US" (:country m)))
      (is (= "US:8697359" (:number m))))
    (testing "title comes from DC.title"
      (is (str/includes? (:title m) "CRISPR-Cas systems")))
    (testing "filing and grant dates are told apart by `scheme`, not by `name`"
      ;; Both are <meta name="DC.date">. Reading `name` alone would collapse them.
      (is (= "2013-10-15" (:filed-at m)))
      (is (= "2014-04-15" (:granted-at m)))
      (is (not= (:filed-at m) (:granted-at m))))
    (testing "inventor and assignee are told apart by `scheme` too"
      ;; Both are <meta name="DC.contributor">.
      (is (some #{"Feng Zhang"} (:inventors m)))
      (is (some #(str/includes? % "Broad Institute") (:assignees m)))
      (is (empty? (filter (set (:inventors m)) (:assignees m)))))
    (testing "citations are compacted and country-filtered"
      (is (seq (:citations m)))
      (is (every? #(re-find #"^[A-Z]{2}\d" %) (:citations m)))
      (is (not-any? #(str/includes? % ":") (:citations m)))
      (is (apply distinct? (:citations m))))))

(deftest quads-are-pure-and-fan-out
  (let [m (gp/parse-html @html "US8697359B1")
        qs (gp/->quads 7 "2026-08-10T00:00:00Z" m)]
    (testing "same inputs, same quads — no hidden clock"
      (is (= qs (gp/->quads 7 "2026-08-10T00:00:00Z" m))))
    (testing "every quad is [entity attr value tx op] on the supplied tx"
      (is (every? #(= 5 (count %)) qs))
      (is (every? #(= 7 (nth % 3)) qs))
      (is (every? #(= :add (nth % 4)) qs))
      (is (= #{"gp:US8697359B1"} (quad/entities qs))))
    (testing "cardinality-many attrs fan out one quad per element"
      (is (= (count (:citations m))
             (count (filter #(= :patent/cites (nth % 1)) qs))))
      (is (= (count (:inventors m))
             (count (filter #(= :patent/inventor (nth % 1)) qs)))))
    (testing "nil fields are dropped, never written as null"
      (is (not-any? #(nil? (nth % 2)) qs)))))

(deftest quad-log-conventions
  (testing "an absent journal and an empty journal are the same thing"
    (is (= [] (quad/read-journal "test/toshokan_patents/fixtures/does-not-exist.edn"))))
  (testing "next-tx starts at 1 and then advances past the highest"
    (is (= 1 (quad/next-tx [])))
    (is (= 4 (quad/next-tx [["a" :x 1 3 :add] ["b" :y 2 1 :add]]))))
  (testing "merge-quads appends without reordering"
    (is (= [["a" :x 1 1 :add] ["b" :y 2 2 :add]]
           (quad/merge-quads [["a" :x 1 1 :add]] [["b" :y 2 2 :add]])))))
