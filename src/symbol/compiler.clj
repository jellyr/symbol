;   Copyright (c) Timo Westkämper. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns symbol.compiler
  (:use [symbol.common])
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.pprint :as pprint]
            [clojure.walk :as walk]
            [clojure.zip :as zip]
            [symbol.analysis :as analysis]
            [symbol.types :as types]
            [symbol.emission :as emission]))

; copied from clojure.core
(defn- sigs
 [fdecl]
   (let [asig 
         (fn [fdecl]
           (let [arglist (first fdecl)
                 ;elide implicit macro args
                 arglist (if (clojure.lang.Util/equals '&form (first arglist)) 
                           (clojure.lang.RT/subvec arglist 2 (clojure.lang.RT/count arglist))
                           arglist)
                 body (next fdecl)]
             (if (map? (first body))
               (if (next body)
                 (with-meta arglist (conj (if (meta arglist) (meta arglist) {}) (first body)))
                 arglist)
               arglist)))]
     (if (seq? (first fdecl))
       (loop [ret [] fdecls fdecl]
         (if fdecls
           (recur (conj ret (asig (first fdecls))) (next fdecls))
           (seq ret)))
       (list (asig fdecl)))))

; copied from clojure.core
(defn- maybe-destructured
  [params body]
  (if (every? symbol? params)
    (cons params body)
    (loop [params params
           new-params []
           lets []]
      (if params
        (if (symbol? (first params))
          (recur (next params) (conj new-params (first params)) lets)
          (let [gparam (gensym "p__")]
            (recur (next params) (conj new-params gparam)
                   (-> lets (conj (first params)) (conj gparam)))))
        `(~new-params
          (let ~lets
            ~@body))))))

(defn load-forms [f]
  "load of the forms of the given resource and return them as a vector"
  (let [res (or (io/resource f) (io/as-url (io/as-file f)))]
    (assert res (str "Can't find " f " in classpath"))
    (with-open [r (io/reader res)]
      (let [pbr (clojure.lang.LineNumberingPushbackReader. r)
            eof (java.lang.Object.)]
        (loop [forms []
               r (read pbr false eof false)]
            (if-not (identical? eof r)
              (recur
                (conj forms r)
                (read pbr false eof false))
              forms))))))

(defn is-form? [& s]
  (let [symbols (set s)]
    (fn [f]
      (and (seq? f) (symbols (first f))))))

(def is-macro? (is-form? 'defmacro))

(declare expand-all)

(def internal-ns #{"symbol.compiler" "clojure.core"})

(defn normalize
  [form]
  (walk/postwalk
    (fn [x] (if (and (symbol? x) (internal-ns (.getNamespace x)))
              (symbol (name x))
              x))
    form))

(defn to-fn
  [macros macro]
  (let [ex (-> (macroexpand-1 macro) second macroexpand-1 (nth 2) normalize)
        f  (eval (expand-all macros ex))]
    (fn [form] (normalize (apply f (concat [form nil] (rest form)))))))

(defn expand-dot
  [[m obj & args]]
  (let [member (symbol (.substring (str m) 1))] 
    (concat (list '. obj member) args)))
  
(defn expand-new
  [[cl & args]]
  (let [s (str cl)
        clazz (symbol (.substring s 0 (dec (.length s))))]
    (concat (list 'new clazz) args)))

(defn expand-form
  [macros form]
  (let [fst (first form)]
    (cond (.startsWith (str fst) ".") (expand-dot form)
          (.endsWith (str fst) ".") (expand-new form) 
          :else (if-let [f (macros fst)]
                  (let [ex (f form)]
                    (cond (identical? ex form) form
                          (seq? ex) (expand-form macros ex)
                          :else ex))
                  form))))

(defn expand-all
  [macros form]
  (walk/prewalk 
    (fn [x] (if (seq? x) (expand-form macros x) x)) 
    form))

(defn expand-forms
  [ns m f]
  (loop* [in f macros {} forms []]
    (if (seq in)
      (let [ex (expand-all (merge m macros) (first in))]
        (cond (form? ex 'do) (recur (mapcat rest [ex in]) macros forms)
              (is-macro? ex) (recur (rest in) 
                                     (assoc macros (second ex) (to-fn macros ex))
                                     forms)
              :else (recur (rest in) macros (conj forms ex))))
      {:ns ns :macros macros :forms forms})))
                
(def core-forms
  (expand-forms 'symbol.core {} (load-forms "symbol/core.clj")))

(defn get-contents
  [file]
  (let [forms (load-forms file)
        namespace (->> forms (filter (is-form? 'ns)) first second)]
    (expand-forms namespace (:macros core-forms) forms)))

(def core-env   
  '{nil   [void]
    
     ; special forms 
    set!  [(sf [_0 _0] void)] 
    pset! [(sf [(pointer _0) _0] void)
           (sf [(pointer _0) long _0] void)]
    pref  [(sf [(pointer _0) long] _0)]
    not   [(sf [boolean] boolean)]
    
    ; operators
    =     [(fn [_0 _0] boolean)] 
    !=    [(fn [_0 _0] boolean)]
    <     [(fn [_0 _0] boolean)]
    >     [(fn [_0 _0] boolean)]
    <=    [(fn [_0 _0] boolean)]
    >=    [(fn [_0 _0] boolean)]
    +     [(fn [_0 _0] _0)]
    -     [(fn [_0 _0] _0)]
    *     [(fn [_0 _0] _0)]    
    /     [(fn [_0 _0] _0)]
    %     [(fn [_0 _0] _0)]
    
    ; casts
    short [(fn [_0] short)]
    ushort [(fn [_0] ushort)]
    int [(fn [_0] int)]
    uint [(fn [_0] uint)]
    long [(fn [_0] long)]
    ulong [(fn [_0] ulong)]
    float [(fn [_0] float)]
    ufloat [(fn [_0] ufloat)]
    double [(fn [_0] double)]
    udouble [(fn [_0] udouble)]
    ldouble [(fn [_0] ldouble)]
    uldouble [(fn [_0] uldouble)]
        
    ; IO operators (temporary)
    std/cout [out]
    <<    [(fn [_0 _1] _0)]
    std/cin [in]
    >>    [(fn [_0 _1] _0)]})

(defn read-emit
  [file]
  (let [{:keys [ns forms macros]} (get-contents file)
        normalized (map analysis/convert forms)]
    (loop [forms normalized env core-env emitted []]
      (let [form (first forms)
            nenv (or (types/new-env env form)
                     (throw  (IllegalStateException. (str "Type inference failed for " form))))
            output (emission/emit nenv nil form)
            nemitted (conj emitted output)]
        (if (seq (rest forms))
          (recur (rest forms) nenv nemitted)
          (emission/format-cpp (string/join "\n" nemitted)))))))
