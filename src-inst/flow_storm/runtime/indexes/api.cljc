(ns flow-storm.runtime.indexes.api
  (:require [flow-storm.runtime.indexes.protocols :as indexes]
            [flow-storm.runtime.indexes.frame-index :as frame-index]
            [flow-storm.runtime.values :refer [val-pprint]]
            [flow-storm.runtime.indexes.fn-call-stats-index :as fn-call-stats-index]
            [flow-storm.runtime.events :as events]            
            [flow-storm.runtime.indexes.thread-registry :as thread-registry]
            [flow-storm.runtime.indexes.form-registry :as form-registry]            
            [clojure.string :as str]
            [clojure.core.async :as async]
            [clojure.pprint :as pp])
  #?(:clj (:require [flow-storm.utils :as utils])))

(declare discard-flow)

(def flow-thread-registry nil)

(def forms-registry nil)


(defn register-form [{:keys [flow-id form-id ns form def-kind mm-dispatch-val]}]
  (let [form-data (cond-> {:form/id form-id
                           :form/flow-id flow-id
                           :form/ns ns
                           :form/form form
                           :form/def-kind def-kind}
                    (= def-kind :defmethod)
                    (assoc :multimethod/dispatch-val mm-dispatch-val))]
    (indexes/register-form forms-registry form-id form-data)))

#?(:clj
   (defn start []
     (alter-var-root #'flow-thread-registry (constantly
                                             (indexes/start-thread-registry
                                              (if (utils/storm-env?)
                                                ((requiring-resolve 'flow-storm.runtime.indexes.storm-index/make-storm-thread-registry))
                                                (thread-registry/make-thread-registry))
                                              {:on-thread-created (fn [{:keys [flow-id thread-id thread-name form-id]}]                                                                    
                                                                    (events/publish-event!
                                                                     (events/make-thread-created-event flow-id thread-id thread-name form-id)))})))
     (alter-var-root #'forms-registry (constantly
                                       (indexes/start-form-registry
                                        (if (utils/storm-env?)
                                          ((requiring-resolve 'flow-storm.runtime.indexes.storm-index/make-storm-form-registry))
                                          (form-registry/make-form-registry))))))
   :cljs
   (defn start []
     (set! flow-thread-registry (indexes/start-thread-registry
                                 (thread-registry/make-thread-registry)
                                 {:on-thread-created (fn [{:keys [flow-id thread-id thread-name form-id]}]
                                                       (events/publish-event!
                                                        (events/make-thread-created-event flow-id thread-id thread-name form-id)))}))
     (set! forms-registry (indexes/start-form-registry
                           (form-registry/make-form-registry)))))

#?(:clj
   (defn stop []
     (alter-var-root #'flow-thread-registry indexes/stop-thread-registry)
     (alter-var-root #'forms-registry indexes/stop-form-registry))
   
   :cljs
   (defn stop []
     (set! flow-thread-registry indexes/stop-thread-registry)
     (set! forms-registry indexes/stop-form-registry)))


(defn flow-exists? [flow-id]
  (indexes/flow-exists? flow-thread-registry flow-id))

(defn create-flow [{:keys [flow-id ns form timestamp]}]
  (discard-flow flow-id)
  (events/publish-event! (events/make-flow-created-event flow-id ns form timestamp)))

(defn create-thread-indexes! [flow-id thread-id thread-name form-id]
  (let [thread-indexes {:frame-index (frame-index/make-index)
                        :fn-call-stats-index (fn-call-stats-index/make-index)}]    

    (indexes/register-thread-indexes flow-thread-registry flow-id thread-id thread-name form-id thread-indexes)
    
    thread-indexes))

(defn get-thread-indexes [flow-id thread-id]
  (indexes/get-thread-indexes flow-thread-registry flow-id thread-id))

(defn get-or-create-thread-indexes [{:keys [flow-id thread-id thread-name form-id]}]
  
  (when (and (nil? flow-id)
             (not (flow-exists? nil)))
    (create-flow {:flow-id nil}))
  
  (if-let [ti (get-thread-indexes flow-id thread-id)]
    ti
    (create-thread-indexes! flow-id thread-id thread-name form-id)))

;;;;;;;;;;;;;;;;;;;;;;;
;; Indexes Build API ;;
;;;;;;;;;;;;;;;;;;;;;;;

(defn add-flow-init-trace [trace]
  (create-flow trace))

(defn add-form-init-trace [trace]
  (register-form trace))

(defn add-fn-call-trace [trace]
  (let [thread-indexes (get-or-create-thread-indexes trace)]

    (doseq [[_ thread-index] thread-indexes]
      (indexes/add-fn-call thread-index trace))))

(defn add-expr-exec-trace [trace]
  (doseq [[_ thread-index] (get-or-create-thread-indexes trace)]
    (indexes/add-expr-exec thread-index trace)))

(defn add-bind-trace [{:keys [flow-id thread-id] :as trace}]
  (doseq [[_ thread-index] (get-thread-indexes flow-id thread-id)]
    (indexes/add-bind thread-index trace)))

;;;;;;;;;;;;;;;;;
;; Indexes API ;;
;;;;;;;;;;;;;;;;;

(defn get-form [_ _ form-id]
  (indexes/get-form forms-registry form-id))

(defn all-threads []
  (indexes/all-threads flow-thread-registry))

(defn all-forms [_ _]
  (indexes/all-forms forms-registry))

(defn timeline-count [flow-id thread-id]
  (let [{:keys [frame-index]} (get-thread-indexes flow-id thread-id)]
    (indexes/timeline-count frame-index)))

(defn timeline-entry [flow-id thread-id idx]
  (let [{:keys [frame-index]} (get-thread-indexes flow-id thread-id)]
    (indexes/timeline-entry frame-index idx)))

(defn frame-data [flow-id thread-id idx]
  (let [{:keys [frame-index]} (get-thread-indexes flow-id thread-id)]
    (indexes/frame-data frame-index idx)))

(defn- coor-in-scope? [scope-coor current-coor]
  (if (empty? scope-coor)
    true
    (and (every? true? (map = scope-coor current-coor))
         (> (count current-coor) (count scope-coor)))))

(defn bindings [flow-id thread-id idx]
  (let [entry (timeline-entry flow-id thread-id idx)]
    (cond
      (= :frame (:timeline/type entry))
      []

      (= :expr (:timeline/type entry))
      (let [expr entry
            {:keys [bindings]} (frame-data flow-id thread-id idx)]
        (->> bindings
             (keep (fn [bind]
                     (when (and (coor-in-scope? (:coor bind) (:coor expr))
                                (<= (:timestamp bind) (:timestamp expr)))
                       [(:symbol bind) (:value bind)])))
             (into {}))))))

(defn callstack-tree-root-node [flow-id thread-id]
  (let [{:keys [frame-index]} (get-thread-indexes flow-id thread-id)]
    (indexes/callstack-tree-root-node frame-index)))

(defn callstack-node-childs [node]
  (indexes/get-childs node))

(defn callstack-node-frame [node]
  (indexes/get-node-immutable-frame node))

(defn fn-call-stats [flow-id thread-id]
  (let [{:keys [fn-call-stats-index]} (get-thread-indexes flow-id thread-id)]
    (->> (indexes/all-stats fn-call-stats-index)
         (keep (fn [[fn-call cnt]]
                 (when (and (= (:flow-id fn-call) flow-id)
                            (= (:thread-id fn-call) thread-id))                   
                   (let [form (get-form flow-id thread-id (:form-id fn-call))]
                     (cond-> {:fn-ns (:fn-ns fn-call)
                              :fn-name (:fn-name fn-call)
                              :form-id (:form-id fn-call)
                              :form (:form/form form)
                              :form-def-kind (:form/def-kind form)
                              :dispatch-val (:multimethod/dispatch-val form)
                              :cnt cnt}
                       (:multimethod/dispatch-val form) (assoc :dispatch-val (:multimethod/dispatch-val form))))))))))

(defn find-fn-frames [flow-id thread-id fn-ns fn-name form-id]
  (let [{:keys [frame-index]} (get-thread-indexes flow-id thread-id)
        frames (indexes/timeline-frame-seq frame-index)]
    (->> frames
         (filter (fn [frame-data]
                   (and (= fn-ns (:fn-ns frame-data))
                        (= fn-name (:fn-name frame-data))
                        (= form-id (:form-id frame-data))))))))

(defn search-next-frame-idx
  ([flow-id thread-id query-str from-idx params]
   (search-next-frame-idx flow-id thread-id query-str from-idx params (async/promise-chan) nil))
  
  ([flow-id thread-id query-str from-idx {:keys [print-level] :or {print-level 2}} interrupt-ch on-progress]
   
   (let [{:keys [frame-index]} (get-thread-indexes flow-id thread-id)
         total-traces (indexes/timeline-count frame-index)
         entries-ch (async/to-chan! (indexes/timeline-seq frame-index))]
     (async/go
       (let [match-stack (loop [i 0
                                stack ()]
                           (let [[v ch] (async/alts! [interrupt-ch entries-ch] :priority true)]
                             (when (and (< i total-traces) (= ch entries-ch))                        
                               (let [tl-entry v]
                                 (when (and on-progress (zero? (mod i 10000)))
                                   (on-progress (* 100 (/ i total-traces))))

                                 (if (= :frame (:timeline/type tl-entry))

                                   ;; it is a fn-call
                                   (let [{:keys [fn-name args-vec]} (indexes/get-immutable-frame (:frame tl-entry))]
                                     (if (and (> i from-idx)
                                              (or (str/includes? fn-name query-str)
                                                  (str/includes? (val-pprint args-vec {:print-length 10 :print-level print-level :pprint? false}) query-str)))

                                       ;; if matches
                                       (conj stack i)

                                       ;; else
                                       (recur (inc i) (conj stack i))))

                                   ;; else expr, check if it is returning
                                   (if (:outer-form? tl-entry)
                                     (recur (inc i) (pop stack))
                                     (recur (inc i) stack)))))))]
         (when-let [found-idx (first match-stack)]           
           {:frame-data (indexes/frame-data frame-index found-idx) 
            :match-stack match-stack}))))))

(defn discard-flow [flow-id]
  (let [discard-keys (->> (indexes/all-threads flow-thread-registry)
                          (filter (fn [[fid _]] (= fid flow-id))))]
    (indexes/discard-threads flow-thread-registry discard-keys)))

#?(:cljs (defn flow-threads-info [_] [{:thread/id 0 :thread/name "main"}])
   :clj (defn flow-threads-info [flow-id]
          (indexes/flow-threads-info flow-thread-registry flow-id)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Utilities for exploring indexes from the repl ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def selected-thread (atom nil))

(defn print-threads []
  (->> (all-threads)
       (map #(zipmap [:flow-id :thread-id :thread-name] %))
       pp/print-table))

(defn select-thread [flow-id thread-id]
  (reset! selected-thread [flow-id thread-id]))

(defn print-forms []  
  (let [[flow-id thread-id] @selected-thread]
    (->> (all-forms flow-id thread-id)
         (map #(dissoc % :form/flow-id ))
         pp/print-table)))
