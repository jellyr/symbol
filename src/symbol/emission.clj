(ns symbol.emission
  (:require [clojure.string :as string]
            [clojure.pprint :as pprint])
  (:use symbol.util))

; if fn* let* loop* recur* . new def do 
; set! pset! pref 
; < > <= >= + - * /

(def cpp-types
  '{string  string
    boolean boolean
    char   int8_t
    short  int16_t
    int    int32_t
    long   int64_t
    uchar  uint8_t
    ushort uint16_t
    uint   uint32_t
    ulong  uint64_t})

(defn get-type
  [env form]
  (let [matches (for [[f t] env :when (= f form)] t)]
    (first matches)))

(defn- emit-selector
  [env target form]
  (cond (seq? form) (first form)
        (literal-types (type form)) (literal-types (type form))
        :else form))

(defmulti emit emit-selector)

(defmethod emit 'if 
  [env target form]
  (let [[_ c t e] form  
        ce (emit env nil c)
        te (emit env target t)
        ee (if e (emit env target e))]
    (if ee
      (format "if (%s) {\n%s\n} else {\n%s\n}" ce te ee)
      (format "if (%s) {\n%s\n}" ce te))))     

(defn type->string
  [env type]  
  (cond (symbol? type) (cpp-types type)
        ; TODO
        :else 'unknown))

(defn args->string
  [env args types]
  (->> (for [[arg type] (zipmap args types)]
         (str (type->string env type) " " arg))
    (string/join ", ")))
               
(defn stmt 
  [& args]
  (let [combined (string/join " " args)]
    (if (.endsWith combined "}")
      (str combined "\n")
      (str combined ";\n"))))

(defn stmts
  [env target body]
  (let [start (map #(emit env nil %) (butlast body))
        end (emit env target (last body))]
    (str (string/join (map stmt start)) 
         (stmt end))))

(defmethod emit 'fn* ; (fn* (args body)
  [env target form]
  (let [[_ argtypes rtype] (get-type env form)
        [args & body] (second form)
        args-str (args->string env args argtypes)
        return (if (= rtype 'void) nil (gensym))
        l (last body)]
    (if return 
      (str "[](" args-str "){\n"           
           (stmts env nil (butlast body))
           (if (complex? l)
             (str 
               (stmt (type->string env rtype) return)
               (stmt (emit env return l))
               (stmt "return" return))
             (stmt "return" (emit env nil l)))                      
           "}")
      (str "[](" args-str "){\n"
           (stmts env nil body) "}"))))
      
(defn assignment
  [env [name value]]
  (let [type (type->string env (get-type env name))
        v (gensym)]
    (if (complex? value)
      (str 
        (stmt type v)
        (stmt (emit env v value)))
      (stmt type v "=" (emit env nil value)))))    

(defmethod emit 'let*
  [env target form]
  (let [[_ bindings & body] form
        bind-pairs (partition 2 bindings)]
    (str 
      (string/join (map #(assignment env %) bind-pairs))
      (stmts env target body))))
        
(defmethod emit 'loop* ; TODO
  [env target form]
  (string/join " " (map str form)))

(defmethod emit 'recur* ; TODO
  [env target form]
  (string/join " " (map str form)))

(defmethod emit '. ; TODO
  [env target form]
  (string/join " " (map str form)))

(defmethod emit 'new ; TODO 
  [env target form]
  (string/join " " (map str form)))

(defmethod emit 'def
  [env target form]
  (let [[_ name value] form
        type (get-type env name)]
    (str (type->string env type) name (emit env nil value)))) 

(defmethod emit 'do
  [env target form]
  (stmts env target (rest form)))

(defmethod emit 'long
  [env target form]
  (str form))

(defmethod emit 'string
  [env target form]
  (str "\"" form "\""))

(defmethod emit 'char
  [env target form]
  (str "'" form "'"))

(defmethod emit 'boolean
  [env target form]
  (str form))

(defmethod emit 'ratio
  [env target form]
  (str (double form)))

(defmethod emit 'symbol
  [env target form]
  (str form))

(def math-ops 
  (into {} (for [k '#{+ - * / < > <= >= != ==}]
             [k (str " " k " ")])))

(def unary-ops '{not "!" + "+" - "-"}) 

(defmethod emit :default
  [env target form]
  (if (seq? form)
    (let [f (first form)
          r (map #(emit env nil %) (rest form))
          unary (= 1 (count r))
          val (cond (and unary (unary-ops f)) (str (unary-ops f) (first r))             
                    (math-ops f) (str "(" (string/join (math-ops f) r) ")")
                    :else (str f "(" (string/join ", " r) ")"))]
      (if target
        (stmt target "=" val)
        val))
    (if target (stmt target "=" val) form)))
  