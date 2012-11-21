;   Copyright (c) Timo Westkämper. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns symbol.emission-test
  (:require [clojure.string :as string] 
            [symbol.analysis :as analysis]
            [symbol.compiler :as compiler]
            [symbol.types :as types])
  (:use symbol.emission        
        midje.sweet))

(def core-env 
  (concat 
    compiler/core-env
    (types/to-env  '((println (fn [A] void))
                     (inc   (fn [long] long))
                     (dec   (fn [long] long))))))
  
(defn expand
  [form]
  (compiler/expand-all (:macros compiler/core-forms) form))
                 
(defn cpp
  [form]
  (let [expanded  (expand form)
        converted (analysis/convert expanded)
        env (types/new-env core-env converted)
        emitted (emit env nil converted)]
    (reduce 
      (fn [s [k v]] (string/replace s k v))
      emitted
      (zipmap
        (distinct (re-seq #"G__\d+" emitted))
        (list "_a" "_b" "_c" "_d" "_e")))))

(defn cpp-pprint
  [form]
  (print (format-cpp (cpp form))))
   
(facts "emit"
  (fact "let"
    (cpp '(let [a 1 b 2] (+ a b))) 
    =>  "int64_t _a = 1;\nint64_t _b = 2;\n(_a + _b);")
  
  (fact "def"
    (cpp '(def a 123.0)) => "double a = 123.0;") 
  
  (fact "defn (non-generic)"
    (cpp '(defn inc2 [a] (+ a 1))) 
    => "int64_t inc2(int64_t _a) {\nreturn (_a + 1);\n}")
  
  (fact "defn (generic)"
    (cpp '(defn identity [a] a)) 
    => "template <class A>\nA identity(A _a) {\nreturn _a;\n}")
  
  (fact "when"
    (cpp '(when a (println "hello") (println "world"))) 
    => "if (a) {\nprintln(\"hello\");\nprintln(\"world\");\n}")
  
  (fact "when-not"
    (cpp '(when-not a (println "hello") (println "world"))) 
    => "if (!a) {\nprintln(\"hello\");\nprintln(\"world\");\n}")
  
  (fact "cond"
    (cpp '(cond a 1 b 2 c 3)) 
    => "if (a) {\n1;\n} else if (b) {\n2;\n} else if (c) {\n3;\n}")
  
  (fact "if-not"
    (cpp '(if-not a b c)) => "if (!a) {\nb;\n} else {\nc;\n}")
  
  (fact "and"
    (cpp '(and (< 3 4) (< -1.0 1.0))) => "if ((3 < 4)) {\n(-1.0 < 1.0);\n}"
    (cpp '(and (< 0 1) (< 1 2) (< 2 3))) => "if ((0 < 1)) {\nif ((1 < 2)) {\n(2 < 3);\n}\n}")
  
  (fact "or"
    (cpp '(or (< 3 4) (< -1.0 1.0))) => "if ((3 < 4)) {\ntrue;\n} else {\n(-1.0 < 1.0);\n}"
    (cpp '(or (< 0 1) (< 1 2) (< 2 3))) 
    => "if ((0 < 1)) {\ntrue;\n} else if ((1 < 2)) {\ntrue;\n} else {\n(2 < 3);\n}")

  (fact "if and"
    (cpp '(if (and (< 3 4) (< 0 1)) (println 5)))
    => "boolean _a;\nif ((3 < 4)) {\n_a = (0 < 1);\n}\nif (_a) {\nprintln(5);\n}")
  
  (fact "if or"
    (cpp '(if (or (< 3 4) (< 0 1)) (println 5)))
    =>  "boolean _a;\nif ((3 < 4)) {\n_a = true;\n} else {\n_a = (0 < 1);\n}\nif (_a) {\nprintln(5);\n}")
  
   (comment (fact "if"
    (cpp '((if (< 0 1) inc dec) 5)) 
    => (str "std::function<int64_t(int64_t)> a__4219__auto__;\n"
            "if ((0 < 1)) {\na__4219__auto__ = inc;\n} else {\na__4219__auto__ = dec;\n}\n"
            "a__4219__auto__(5);")))
  
   (fact "new"
    (cpp '(new Entity 3 (+ 1 2))) => "new Entity(3, (1 + 2))"
    (cpp '(Entity. 3 (+ 1 2))) => "new Entity(3, (1 + 2))")
  
  (fact "eq"
    (cpp '(= 1 2)) => "(1 == 2)")
   
  (fact "->"
    (cpp '(-> a (b 1) c)) => "c(b(a, 1))")
  
  (fact "->>"
     (cpp '(->> a (b 1) c)) => "c(b(1, a))")
  
  ;(fact "if-let"
  ;   (cpp '(if-let [a (< 1 2)] (println a))) => 'x)
  
  (fact "when-let"
     (cpp '(when-let [a (< 1 2)] (println a))) 
     => "boolean _a = (1 < 2);\nif (_a) {\nboolean _b = _a;\nprintln(_b);\n}")
  
  (fact "dotimes"
    (cpp '(dotimes [i 5] (println i))) 
    => (str "int64_t _a = 5;\nint64_t _b = 0;\n"
            "_c:\n"
            "if ((_b < _a)) {\nprintln(_b);\n_b = inc(_b)\ngoto _c;\n}"))
  
  (fact "fn generic"
    (cpp '(fn [x] x)) =>  "[](A _a){\nreturn _a;\n}")
  
  (fact "fn typed"
    (cpp '(fn [a] (+ a 1))) =>  "[](int64_t _a){\nreturn (_a + 1);\n}")
  
  (fact "fn"
    (cpp '(fn [a] (if (< a 2) (println 2)))) 
    =>  "[](int64_t _a){\nif ((_a < 2)) {\nprintln(2);\n}\n}")
  
  (fact "loop"
    (cpp '(loop [x 4] x)) => "int64_t _a = 4;\n_b:\n_a;"))

(facts "examples"
       
  (fact "multiplier"
    (cpp '(defn multiplier [factor]
            (fn [x] (* (+ x 0) factor))))    
    => (str "std::function<int64_t(int64_t)> multiplier(int64_t _a) {\n"
            "return [](int64_t _b){\nreturn ((_b + 0) * _a);\n}\n}"))
  
  (fact "inline fn"
    (cpp '((fn [x] (+ x 1)) 1)) => "[](int64_t _a){\nreturn (_a + 1);\n}(1)") 
  
  (fact "let over fn"
    (cpp '(def inc (let [x 1] (fn [y] (+ x y))))) 
    => (str "std::function<int64_t(int64_t)> _a;\n"
            "int64_t _b = 1;\n"
            "_a = [](int64_t _c){\nreturn (_b + _c);\n}\n"
            "std::function<int64_t(int64_t)> inc = _a;")) 

  (fact "eq"
    (cpp '(defn eq [x y] (= x y))) 
    => "template <class A>\nboolean eq(A _a, A _b) {\nreturn (_b == _a);\n}")
  
  (fact "string"
    (cpp '(def greeting "Hello, world!")) => "string greeting = \"Hello, world!\";")
  
) 


