(ns tana.aggregate
  "`count`, `min` and `max` answered from the root, reading no object at all.

  `columnar.aggregate` answers these from a file's footer without touching a
  page. One level up, the footers are already in the root, so the whole table
  answers them without touching an **object** — one request, the root, which
  the query needed anyway.

  ## The guards are the namespace

  Every disqualification `columnar.aggregate` documents applies here for the
  same reason, one level up, plus one that only exists here:

  - **Any predicate disqualifies statistics.** Bounds describe every row of a
    chunk and a filter selects some of them. Prune-then-fold is wrong for
    exactly the surviving chunks, which hold both matching and non-matching
    rows.
  - **One chunk without bounds disqualifies them all.** Folding the chunks
    that did report answers about a subset of the table while looking like an
    answer about the table.
  - **`sum` is never available.** min/max/rows/nulls cannot produce it. Named
    so that its absence is a decision rather than an oversight.
  - **`:location-only` trust disqualifies them.** An aggregate answered from
    a root's bounds *is* the bounds. A caller that will not trust them for
    pruning — where a wrong bound costs a read — cannot trust them for an
    answer, where a wrong bound **is** the answer. This is the sharper case
    of the asymmetry `tana.table` describes, and it is why refusing is the
    default rather than a mode.

  A refusal returns `{:from :refused :reason ...}`, never a value with a
  quiet caveat. `:reason` says which guard fired, because \"could not\" and
  \"the answer is nothing\" must not share a shape."
  (:require [columnar.stats :as stats]))

(def from-statistics
  "What a root can answer outright, absent a predicate."
  #{:count :count-non-null :min :max})

(defn- all-null? [s] (= (:nulls s) (:rows s)))

(defn- chunk-stats-seq [root column]
  (for [m (:members root), c (:chunks m)] (get-in c [:columns column :stats])))

(defn aggregate
  "`{:agg :count|:count-non-null|:min|:max :column c :trust t}` over `root`.

  -> `{:value v :from :statistics :read 0 :requests 1}` when the root can
  answer, or `{:from :refused :reason k}` when a guard fires. Never a value
  without saying where it came from."
  [root {:keys [agg column predicates trust]}]
  (cond
    (empty? (:members root))
    {:from :refused :reason :no-members}

    (seq predicates)
    ;; Not "unsupported": the fold would be wrong, and quietly.
    {:from :refused :reason :predicate-disqualifies-statistics}

    (= :sum agg)
    {:from :refused :reason :sum-is-not-derivable-from-bounds}

    (not (contains? from-statistics agg))
    {:from :refused :reason :unknown-aggregate}

    (= :location-only trust)
    {:from :refused :reason :bounds-not-trusted}

    (not (contains? #{:from-footers :declared} (:bounds-authority root)))
    {:from :refused :reason :no-bounds-authority}

    (and (= :from-footers trust) (not= :from-footers (:bounds-authority root)))
    {:from :refused :reason :bounds-not-trusted}

    (= :count agg)
    ;; Row counts are recorded per chunk and are not bounds — every format
    ;; this stack reads records them, including Arrow, which records no
    ;; min/max at all.
    {:value (reduce + 0 (for [m (:members root), c (:chunks m)] (:rows c)))
     :from :statistics :read 0 :requests 1}

    :else
    (let [ss (chunk-stats-seq root column)]
      (cond
        (some nil? ss)
        {:from :refused :reason :column-absent-from-a-member}

        (= :count-non-null agg)
        (if (every? #(contains? % :nulls) ss)
          {:value (reduce + 0 (map #(- (:rows %) (:nulls %)) ss))
           :from :statistics :read 0 :requests 1}
          {:from :refused :reason :null-counts-not-recorded})

        :else
        (let [usable (remove all-null? ss)
              k (if (= :min agg) :min :max)]
          (if (every? #(contains? % k) usable)
            {:value (when (seq usable)
                      ((if (= :min agg) stats/value-min stats/value-max) (map k usable)))
             :from :statistics :read 0 :requests 1}
            {:from :refused :reason :bounds-not-recorded}))))))
