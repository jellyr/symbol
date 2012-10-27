(ns symbol.compiler-test
  (:require [symbol.types :as types])
  (:use symbol.compiler        
        midje.sweet))

(facts "normalize"
  (normalize 'clojure.core/let*) => 'let*)

(defn expand
  [form]
  (expand-all (:macros core-forms) form))

(facts "expand"
  (fact "let"
    (expand '(let [a 1 b 2] (+ a b))) => '(let* [a 1 b 2] (+ a b)))
  (fact "defn"
    (expand '(defn identity [a] a)) => '(def identity (fn* ([a] a))))
  (fact "when"
    (expand '(when a (println "hello") (println "world"))) 
          => '(if a (do (println "hello") (println "world"))))
  (fact "when-not"
    (expand '(when-not a (println "hello") (println "world"))) 
          => '(if (not a) (do (println "hello") (println "world"))))
  (fact "cond"
    (expand '(cond a 1 b 2 c 3)) =>  '(if a 1 (if b 2 (if c 3))))
  (fact "if-not"
    (expand '(if-not a b c)) => '(if (not a) b c))
  ; and
  ; or
  (fact "->"
    (expand '(-> a (b 1) c)) => '(c (b a 1)))
  (facts "->>"
     (expand '(->> a (b 1) c)) => '(c (b 1 a)))
  ; if-let
  ; when-let
  (facts "dotimes"
    (expand '(dotimes [i 5] (println i))) => anything)
  (facts "fn"
    (expand '(fn [x] x)) => '(fn* ([x] x)))
  (facts "loop"
    (expand '(loop [x 4] x)) => '(loop* [x 4] x)))

(def env (types/to-env  '((set!  (fn [A A] void))
                          (<     (fn [A A] boolean))
                          (+     (fn [A A] A))                
                          (*     (fn [A A] A))
                          (not   (fn [boolean] boolean))
                          (substr (fn [string long] string)))))

(defn typeof
  [form]
  (types/typeof env (expand form)))

(facts "types"
   (typeof '(let [a 1 b 2] (+ a b))) => 'long
   (typeof '(defn identity [a] a)) => '(fn [_.0] _.0)
   (typeof '(when true 1 2 (+ 1.0 2.1))) => 'double
   (typeof '(when-not (< 1 2) 2.0)) => 'double
   (typeof '(let [a 5] (cond (< a 1) 2 (< a 2) 3))) => 'long
   (typeof '(let [a 5] (cond (< a 1) 2 (< a 2) 3 :else 5))) => 'long
   (typeof '(if-not (< 1 2) 3.0 4.0)) => 'double
   (typeof '(-> 2 (+ 3) (* 5))) => 'long
   (typeof '(->> 2 (+ 3) (* 5))) => 'long
   ; dotimes
   (typeof '(fn [a] a)) => '(fn [_.0] _.0))


   
   

