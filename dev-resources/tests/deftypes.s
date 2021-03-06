(ns deftypes)

; generic #1 (not yet supported)

(comment 
(deftype Generic [f s] 
  (first [_1] f) 
  (second [_2] s))

(defn new-generic [fn ln]
  (Generic. fn ln))

(defn generic2 []
  (let [obj (Generic. 1 2)]
    (+ (first obj) (second obj)))))
        
; concrete #1

(deftype Concrete [n1 n2] 
  (f1 [_3] (+ n1 1)) 
  (f2 [_4] (+ n2 1.0)))

(defn new-concrete [n1 n2]
  (Concrete. n1 n2))