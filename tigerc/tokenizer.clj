(ns tokenizer
  (:require [clojure.string :as str]))

(def token-complex
  #{:id :comment :digits :string})

(def token-keyword
  #{:array :break :do :else :end :for :function :if :in
    :let :nil :of :then :to :type :var :while})

(def token-punct
  {[\: \=] :assign
   [\< \=] :leq
   [\[]    :open-bracket
   [\.]    :period
   [\*]    :star
   [\)]    :close-paren
   [\+]    :plus
   [\(]    :open-paren
   [\:]    :colon
   [\,]    :comma
   [\;]    :semi-colon
   [\&]    :and
   [\>]    :gt
   [\<]    :lt
   [\}]    :close-brace
   [\> \=] :geq
   [\]]    :close-bracket
   [\{]    :open-brace
   [\-]    :minus
   [\=]    :equal
   [\/]    :slash
   [\< \>] :diamond,
   [\|]    :pipe})

(def token-set
  (clojure.set/union token-complex
                     token-keyword
                     (set (vals token-punct))))

(def token-queue clojure.lang.PersistentQueue/EMPTY)
;;print-method implementation is from Joy of Clojure(2nd edition)
(defmethod print-method clojure.lang.PersistentQueue [queue writer]
  (print-method '<- writer)
  (print-method (seq queue) writer)
  (print-method '-< writer))

(defn get-keyword
  "get corresponding keyword, otherwise nil, for a token"
  {:test
   #(let [kwords     (map name token-keyword)
          non-kwords (map (partial str "1") kwords)]
      (assert (empty? (clojure.set/intersection (set kwords)
                                                (set non-kwords)))
              "ineffective test!")
      (doall
       (concat
        (for [kw kwords]
          (assert (contains? token-keyword (get-keyword kw))))
        (for [nkw non-kwords]
          (assert (nil? (get-keyword nkw)))))))}

  [s]
  (token-keyword (keyword s)))

(defn id-recognizer
  {:test
   #(let [ids    ["x" "x1" "x_1" "x_1_"]
          kwords (map name token-keyword)]
      (assert (empty? (clojure.set/intersection (set ids)
                                                (set kwords)))
              "ineffective test!")
      (doall
       (concat
        (for [id ids]
          (assert (= [() {:token :id :name id}]
                     (id-recognizer (seq id)))))
        (for [kw kwords]
          (assert (= [() {:token (keyword kw)}]
                     (id-recognizer (seq kw))))))))}
  [source]
  (let [c (first source)]
    (assert (Character/isLetter c))
    (loop [s (rest source)
           t [c]]
      (let [c (first s)]
        (if (and c (or (Character/isLetterOrDigit c) (= c \_)))
          (recur (rest s) (conj t c))
          (let [token (str/join t)
                kword (get-keyword token)]
            (if kword
              [s {:token kword}]
              [s {:token :id :name token}])))))))

(defn digits-recognizer
  {:test
   #(let [all-digits ["0" "1" "123" "123"]
          tail "f"
          with-non-digit-tail (map (fn [ds] (str ds tail)) all-digits)]
      (doall
       (concat
        (for [ds all-digits]
          (assert (= [() {:token :digits :value ds}]
                     (digits-recognizer (seq ds)))))
        (for [ts with-non-digit-tail]
          (let [[s t] (digits-recognizer (seq ts))]
            (assert (= s (seq tail)))
            (assert (= (str (:value t) tail) ts)))))))}
  [source]
  (let [c (first source)]
    (assert (Character/isDigit c))
    (loop [s (rest source)
           t [c]]
      (let [c (first s)]
        (if (and c (Character/isDigit c))
          (recur (rest s) (conj t c))
          [s {:token :digits :value (str/join t)}])))))

(defn string-recognizer
  {:test
   #(let [strings ["\"\"" "\"say\"" "\"say \\\"hello, world!\\\"\""]
          missing-closing-quote (map (fn [s]
                                       (subs s 0 (dec (count s))))
                                     strings)
          tail " "
          with-tail (map (fn [s] (str s tail)) strings)]
      (doall
       (concat
        (for [s strings]
          (assert (= [() {:token :string :value (subs s 1 (dec (count s)))}]
                     (string-recognizer (seq s)))))
        (for [m missing-closing-quote]
          (assert (= [(seq m)]
                     (string-recognizer (seq m)))))
        (for [ts with-tail]
          (let [[s t] (string-recognizer (seq ts))]
            (assert (= s (seq tail)))
            (assert (= (str \" (:value t) \" tail) ts)))))))}
  [source]
  (let [c (first source)]
    (assert (= c \"))
    (let [suc (second source)]
      (case suc
        \"
        [(rest (rest source)) {:token :string :value ""}]

        nil
        (do (println "String misses closing double-quote.")
            [source])

        (loop [s (rest source)
               t [suc]
               consecutive-backslash-count 0]
          (assert (not (empty? s)))
          (if (empty? (rest s))
            (do (println "String" (str/join t)
                         "misses closing double quote.")
                [source])
            (let [c   (first s)
                  suc (second s) ;suc is non-nil
                  cbc (if (= c \\) (inc consecutive-backslash-count)
                          0)]
              (if (and (= suc \") (even? cbc))
                [(rest (rest s)) {:token :string :value (str/join t)}]
                (recur (rest s) (conj t suc) cbc)))))))))

(def comment-opening [\/ \*])
(def comment-closing [\* \/])
(defn comment-recognizer
  {:test
   #(let [carve-out (fn [cmt] (subs cmt 2 (- (count cmt) 2)))
          cmts ["/**/"
                "/*hello*/"
                "/*hello\nworld*/"
                "/*comment looks like this: /*...*/*/"]
          ambiguous ["/*/" "/*/*/"]
          missing-closing (->> cmts
                               (map (comp (partial str "/*") carve-out))
                               (concat ambiguous))
          tail "*/"
          with-tail (map (fn [s] (str s tail)) cmts)]
      (doall
       (concat
        (for [cmt cmts]
          (assert (= [() {:token :comment :value (carve-out cmt)}]
                     (comment-recognizer (seq cmt)))))
        (for [m missing-closing]
          (assert (= [(seq m)]
                     (comment-recognizer (seq m)))))
        (for [ts with-tail]
          (let [[s t] (comment-recognizer (seq ts))]
            (assert (= s (seq tail)))
            (assert (= (str "/*" (:value t) "*/" tail) ts)))))))}
  [source]
  (let [[s0 s1 & smore] source]
    (assert (= [s0 s1] comment-opening))
    (let [pairs (as-> smore $
                     (concat $ [\space]) ;left-shifted
                     (partition 2 1 $))]
      (letfn [(recursive-cmt-reader [pairs]
                (loop [[p & pmore :as ps] pairs
                       cv []]
                  (cond
                    (empty? ps)
                    [ps cv false]

                    (= p comment-closing)
                    [(rest pmore) cv true]

                    (= p comment-opening)
                    (let [[ps' cv'] (recursive-cmt-reader (rest pmore))]
                      (recur ps' (into cv (concat comment-opening
                                                  cv'
                                                  comment-closing))))

                    :else
                    (recur pmore (conj cv (first p))))))]
        (let [[remaining-pairs
               collected-cmt
               with-closing?] (recursive-cmt-reader pairs)]
          (if with-closing?
            [(map first remaining-pairs)
             {:token :comment :value (str/join collected-cmt)}]
            (do (println "missing comment closing")
                [source])))))))

(defn skip-spaces
  {:test
   #(let [left-paddings [" " "  " "\t" "\n" " \n\t"]
          result "x = 0"
          strings (for [lp left-paddings]
                    (str lp result))]
      (doall
       (for [s strings]
         (assert (= [(seq result)] (skip-spaces (seq s)))))))}
  [source]
  (let [c (first source)]
    (assert (Character/isWhitespace c))
    [(drop-while #(Character/isWhitespace %) source)]))

(defn punct-recognizer
  {:test
   #(let [puncts (map str/join (keys token-punct))
          tail " "
          with-tail (map (fn [x] (str x tail)) puncts)]
      (doall
       (concat
        (for [p puncts]
          (assert (= [() {:token (token-punct (vec p))}]
                     (punct-recognizer (seq p)))))
        (for [w with-tail]
          (assert (= [(seq tail) {:token (token-punct
                                          (vec
                                           (let [l (- (count w)
                                                      (count tail))]
                                             (subs w 0 l))))}]
                     (punct-recognizer (seq w))))))))}
  [[c c' :as source]]
  (let [t (token-punct [c])]
    (assert t)
    (if-let [t' (token-punct [c c'])]
      [(nthrest source 2) {:token t'}]
      [(rest source) {:token t}])))

(defn tokenize-str
  {:test
   #(let [string-value "hello"
          comment-value "..."
          src {:spaces  " "
               :id      "x"
               :string  (str \" string-value \")
               :digits  "0"
               :comment (str "/*" comment-value "*/")
               :plus    "+"
               :illegal "%"}
          variations (for [x (keys src)
                           y (keys src)
                           :let [pair [x y]]
                           :when (nil? (#{[:spaces :spaces]
                                          [:id :id]
                                          [:id :digits]
                                          [:digits :digits]} pair))]
                       pair)]
      (doall
       (for [pair variations]
         (let [test-str (str/join ((apply juxt pair) src))
               result (tokenize-str test-str)]
           (if (= (first pair) :illegal)
             (assert (= result token-queue))
             (assert (= result
                        (map
                         (fn [k]
                           (cond-> {:token k}
                             (= k :id)      (assoc :name  (:id src))
                             (= k :string)  (assoc :value string-value)
                             (= k :digits)  (assoc :value (:digits src))
                             (= k :comment) (assoc :value comment-value)))
                         (remove #{:spaces :illegal} pair)))))))))}
  [s]
  (assert (string? s))
  (let [inject (fn [f env]
                 (let [[s t] (f (:char-seq env))]
                   (cond-> (assoc env :char-seq s)
                     t (update :token-seq conj t))))
        puncts (set (seq ",:;()[]{}.+-*/=<>&|"))]
    (loop [{:keys [char-seq] :as curr-env}
           {:char-seq s :token-seq token-queue}]
      (let [recognizer (let [[c c'] char-seq]
                         (cond
                           ;;skip leading spaces
                           (and c (Character/isWhitespace c)) skip-spaces

                           ;;consume an :id, or keyword token
                           (and c (Character/isLetter c)) id-recognizer

                           ;;consume a :string token
                           (= c \") string-recognizer

                           ;;consume a :digits token
                           (and c (Character/isDigit c)) digits-recognizer

                           ;;consume a :comment
                           (= [c c'] comment-opening) comment-recognizer

                           ;;consume the rest of defined symbols
                           (puncts c) punct-recognizer

                           :else (partial conj [])))
            next-env (inject recognizer curr-env)]
        (if (= (:char-seq next-env) char-seq) ;check if fixpoint is reached
          (:token-seq curr-env)
          (recur next-env))))))

(defn norm-id-to-ty-id
  "find ALL cases where :id should be replaced by :ty-id, and replace them"
  {:test
   #(let [target
          {:token :id :name "int"}

          essential-forms
          [[{:token :colon} :slot]
           [{:token :type } :slot]
           [{:token :type } :slot {:token :equal} :slot]
           [{:token :array} {:token :of} :slot]
           [:slot {:token :open-brace}]
           [:slot {:token :open-bracket}
            {:token :digits :value "2"}
            {:token :close-bracket} {:token :of}]]

          broken-forms
          (mapv (fn [[v0 & v]]
                  (into [v0 {:token :if}] v))
                essential-forms)

          slot-to-id
          (fn [v] (replace {:slot target} v))

          slot-to-ty-id
          (fn [v] (replace {:slot (assoc target :token :ty-id)} v))]
      (doall
       (concat
        (for [v essential-forms]
          (assert (= (slot-to-ty-id v)
                     (norm-id-to-ty-id (slot-to-id v)))))
        (for [v broken-forms]
          (assert (= (slot-to-id v)
                     (norm-id-to-ty-id (slot-to-id v))))))))}
  [token-v]
  ;;TODO remove no-comment constraint when ready
  (assert (empty? (filterv #(= (:token %) :comment) token-v)))
  (assert (vector? token-v))
  (comment
    "Rules:"
    "- :id that immediately follows :colon is :ty-id;"
    "- :id that immediately follows :type is :ty-id;"
    "- :id that immediately follows :array :of is :ty-id;"
    "- :id that immediately follows :equal, which in turn,"
    "  immediately follows :type :ty-id is :ty-id;"
    "- :id that is immediately followed by { is :ty-id"
    "- :id that is immediately followed by [...] :of is :ty-id.")
  (let [v token-v
        seq-1 '(:colon)
        seq-2 '(:type)
        seq-3 (into () [:array :of])
        seq-4 (into () [:type :ty-id :equal])

        follows?
        (fn [[prevs s] pattern]
          (assert (= (:token (first s)) :id))
          (loop [[x :as xs] prevs
                 [y :as ys] pattern]
            (or (empty? ys)
                (if (not= (:token x) y)
                  false
                  (recur (rest xs) (rest ys))))))

        followed-by-open-brace?
        (fn [[_ [x y]]]
          (assert (= (:token x) :id))
          (= (:token y) :open-brace))

        skip-matching-brackets
        (fn [s]
          (assert (= (:token (first s)) :open-bracket))
          (loop [cnt 1
                 [x :as xs] (rest s)]
            (if (or (= cnt 0) (empty? xs))
              xs
              (recur ((condp = (:token x)
                        :open-bracket inc
                        :close-bracket dec
                        identity) cnt)
                     (rest xs)))))

        followed-by-matching-brackets-then-of?
        (fn [[_ [x & xs]]]
          (assert (= (:token x) :id))
          (and (= (:token (first xs)) :open-bracket)
               (= (:token (first (skip-matching-brackets xs))) :of)))
        ]
    (loop [[acc nexts :as curr] [() (seq v)]]
      (if (empty? nexts)
        (into [] (reverse acc))
        (recur [(if (and (= (:token (first nexts)) :id)
                         (or (follows? curr seq-1)
                             (follows? curr seq-2)
                             (follows? curr seq-3)
                             (follows? curr seq-4)
                             (followed-by-open-brace? curr)
                             (followed-by-matching-brackets-then-of? curr)))
                  (conj acc (assoc (first nexts) :token :ty-id))
                  (conj acc (first nexts)))
                (rest nexts)])))))

(defn tokenize-file [path-to-file]
  (let [str (slurp path-to-file)
        tv (vec (tokenize-str str))]
    (norm-id-to-ty-id tv)))
