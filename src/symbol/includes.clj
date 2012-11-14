;   Copyright (c) Timo Westkämper. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns symbol.includes
  (:import [java.io File])
  (:use [clojure.data.zip.xml :only (attr attr= text xml-> xml1->)] 
        [clojure.java.shell :only [sh]])
  (:require [clojure.data.zip :as zf]
            [clojure.xml :as xml]
            [clojure.zip :as zip]))

; TODO make this independent of the gcc version
(def default-paths
  ["/usr/local/include"
   "/usr/include/c++/4.6"
   "/usr/include"])

(defn get-file
  [search-paths local-path]
  (loop [paths default-paths]
    (let [f (File. (first paths) local-path)]
      (cond (.exists f) f
            (seq paths) (recur (rest paths))))))          

(def get-xml (comp zip/xml-zip xml/parse))

(defn- dump  
  [local-path]
  (if-let [f (get-file default-paths local-path)]
    (slurp f)))

(def cpp-types
  '{"char" char
    "unsigned char" uchar
    "short int" short
    "unsigned short int" ushort
    "short" short
    "unsigned short" ushort
    "int" int
    "unsigned int" uint
    "long int" long
    "unsigned long int" ulong
    "long" long
    "unsigned long" ulong
    "bool" boolean
    "float" float
    "unsigned float" ufloat
    "double" double
    "unsigned double" double
    "long double" ldouble
    "unsigned long double" uldouble})
          
(defn xml-get
  [xml type k & vs]
  (into {} (for [entry (xml-> xml type)]
    [(xml1-> entry (attr k))
     (assoc
       (into {} (for [v vs]
                  [v (xml1-> entry (attr v))]))
       :cat type)])))

(defn mapv
  [f m]
  (into {} (for [[k v] m] [k (f v)])))

(declare typedef)

; TODO defmulti
(def typedefs
  {:ArrayType (fn [all t] (list 'array (typedef all (:type t)) (:size t)))
   :CvQualifiedType (fn [all t] (typedef all (:type t)))
   :FundamentalType (fn [all t] (cpp-types (:name t)))
   :PointerType (fn [all t] (list 'pointer (typedef all (:type t))))
   :ReferenceType (fn [all t] (list 'reference (typedef all (:type t))))
   :Typedef (fn [all t] (typedef all (:type t)))
   :Union (fn [all t] (cons 'union
                            (map #(typedef all %)
                                 (.split (:members t) " "))))
   :Constructor (fn [all t] nil) ;TODO
   :Destructor (fn [all t] nil) ;TODO
   :OperatorMethod (fn [all t] (list (:name t) 
                                     (list 'fn [] (typedef all (:returns t)))))
   :FunctionType (fn [all t] (list 'fn (typedef all (:returns t))))                               
   :Field (fn [all t] (list (:name t) (typedef all (:type t))))
   :Struct (fn [all t] (if (:members t) 
                         (let [members (map #(typedef all %) 
                                            (.split (:members t) " "))]
                           (list 'struct (:name t) members))
                         (list 'struct (:name t))))})
                             
(defn typedef
  [types id]
  (let [type (types id)
        f (typedefs (:cat type))]
    (if f
      (f types type)
      (throw (IllegalStateException. (str "No function for " id))))))
   
; TODO Destructor, OperatorMethod, Constructor
(defn include
  [local-path]
  (if-let [f (get-file default-paths local-path)]
    (let [temp (doto (File/createTempFile "gccxml" "xml")
                 (.deleteOnExit))
          out  (sh "gccxml" (.getAbsolutePath f) (str "-fxml=" (.getAbsolutePath temp)))
          xml  (get-xml temp)          
          types (merge 
                  (xml-get xml :ArrayType :id :type :size)
                  (xml-get xml :CvQualifiedType :id :type)
                  (xml-get xml :FundamentalType :id :name)                  
                  (xml-get xml :PointerType :id :type)
                  (xml-get xml :ReferenceType :id :type)
                  (xml-get xml :Typedef :id :name :type)
                  (xml-get xml :Union :id :members)
                  (xml-get xml :Constructor :id :name) ; TODO args
                  (xml-get xml :Destructor :id)
                  (xml-get xml :OperatorMethod :id :name :returns) ; TODO args                  
                  (xml-get xml :FunctionType :id :returns) ; TODO args
                  (xml-get xml :Field :id :name :type)
                  (xml-get xml :Struct :id :name :members))
          typedefs (into {} (map (fn [id] [id (typedef types id)]) 
                                 (keys types)))                            
          variables (for [[id v] (xml-get xml :Variable :id :name :type)]
                      (list (:name v) (typedefs (:type v))))                                    
          functions (for [function (xml-> xml :Function)]
                      (list (xml1-> function (attr :name))
                            (list 'fn
                                  (map typedefs (xml-> function :Argument (attr :type)))
                                  (typedefs (xml1-> function (attr :returns))))))]
      (concat
        variables
        (remove #(.startsWith (first %) "__") functions)))))

;(def include (memoize include*))

(defn include-pp
  [local-path]
  (doseq [function (include local-path)]
    (println function)))
          
      