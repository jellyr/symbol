(ns symbol.analysis-test
  (:use symbol.analysis
        midje.sweet))

(facts "unique names"

  (fact "let* bindings"  
    (unique-names '(let* [a 1 b 2] (+ a b))) => '(let* [x 1 x 2] (+ x x))
    (provided (gensym) => 'x))
  
  (fact "loop*"
    (unique-names '(loop* named [a 1 b 2] (+ a b))) => '(loop* named [x 1 x 2] (+ x x))
    (provided (gensym) => 'x))
    
  (fact "fn* args"
    (unique-names '(fn* named ([a b c] (+ a b c)))) => '(fn* named ([x x x] (+ x x x)))
    (provided (gensym) => 'x)
    (unique-names '(fn* ([a b c] (+ a b c)))) => '(fn* ([x x x] (+ x x x)))
    (provided (gensym) => 'x)))

(facts "expand"  

  (fact "recur"
    (expand-recur '(loop* [i 0] (when (< i 5) (println i) (recur (inc i))))  'x)
    => '(loop* x [i 0] (when (< i 5) (println i) (recur* x (inc i)))))

  (fact "loop"
    (expand-loop '(loop* [i 0] (when (< i 5) (println i) (recur (inc i)))))
    => '(loop* x [i 0] (when (< i 5) (println i) (recur* x (inc i))))
    (provided (gensym) => 'x)))

(facts "simplify"
       
  (fact "if"
    (simplify '(if (let* [a 1] a) true false)) => '(let* [x (let* [a 1] a)] (if x true false))
    (provided (gensym) => 'x))
  
  (fact "let*"
    (simplify '(let* [a 1] (let* [b 2] (+ a b)))) => '(let* [a 1 b 2] (+ a b)))

  (fact "new"
    (simplify '(new MyClass (let* [a 1 b 2] (+ a b)) (+ c d) 4 "str")) 
     => '(let* [x (let* [a 1 b 2] (+ a b))
                x (+ c d)]
           (new MyClass x x 4 "str"))
     (provided (gensym) => 'x))
  
  (fact "do"
    (simplify '(do (println 1) 2)) => '(do (println 1) 2)
    (simplify '(do (println 1)) => '(do (println 1))))
  
  (fact "apply"
    (simplify '(f (let* [a 1] a) 2 3)) => '(let* [x (let* [a 1] a)] (f x 2 3))
    (provided (gensym) => 'x)))
  
; TODO tests for convert
  