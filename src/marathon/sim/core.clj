;;marathon.sim.core is a glue library that provides access to a common set of 
;;subsystems upon which a more complicated federated simulation infrastructure
;;is implemented.  

;;Marathon utilizes a generic simulation context, defined in __sim.simcontext__, 
;;which provides primitive access to a generic simulation state - heterogenous 
;;chunks of data relevant to different domains of the simulation - and a basic
;;discrete event simulation framework.  
;;Systems are then defined over this shared architecture, and contribute special 
;;means to affect a simulation in their particular domain.  At the highest 
;;level, a coordinating function, or an engine, orders and composes the systems 
;;into a comprehensive state transition function that maps one simulation 
;;context to the next.  

;;This core namespace started as a dumping ground for shared or duplicate 
;;functionality from the original object oriented design.  It continues to 
;;evolve as the source is reorganized along clearer lines, and serves as a 
;;staging ground for general pending tasks and observations.  To that end, the 
;;casual reader may skim the rest of this section without losing a great deal of
;;insight.  
;;Even for intrepid readers, the section header __Developer Notes__ may be 
;;largely ignored.
(ns marathon.sim.core
  (:require [clojure    [inspector :as inspect]]
            [spork.util [metaprogramming :as util]
                        [general :as gen]
                        [tags :as tag]
                        [table :as tbl]
                        [reducers]
                        [cellular :as cells]]
            [spork.cljgraph [jungapi :as jung]]
            [spork.sketch :as sketch]                        
            [spork.entitysystem.store]
            [spork.sim.simcontext :as sim]
            [marathon.data.simstate :as simstate]            
            [clojure.core.reducers :as r]))

;;Possible use of dynamic context for later....
;;Dunno if I want to do this yet..
;(def ^:dynamic *ctx* nil)
;; (defmacro defcontextual 
;;   "Define functions that work with or without a name context, ctx.
;;    Yields functions that an extra arity fun, taking ctx as a final argument.
;;    If no contextual function is provided, uses the var bound to marathon.sim.core/*ctx*
;;    Designed to eliminate boilerplate, and allow us to maintain contextual functions 
;;    that can use dynamic binding.  Note -> the main reason to use this, in lieu of."
;;   [name args & expr]
;;   (assert (vector? args) "arguments must be a vector")
;;   (let [rawargs (if (= (last args) 'ctx)
;;                     (subvec args 0 (dec (count args)))
;;                     args)]                                
;;   `(defn ~name ([~@rawargs] ~@expr)
;;                ([~@(conj rawargs 'ctx)] ~@expr))))

;;#Providing Common Access to the State in the Simulation Context
;;The simulation context contains the simulation state - a large nested map of 
;;information that is 'typically' partitioned by a particular domain.  
;;Some systems, like the fill function, need access to multiple domains to
;;fulfill their purpose.  In response, we maintain an open, broad structure for 
;;the state portion of the context, but provide a set of common paths or 
;;mappings to resources embedded in the state.  

;;As a result, subsystems can query resources by predefined paths - a higher 
;;level of abstraction and clarity - OR they may dip down to low level 
;;operations on the raw state.  

;;This should maintain a balance between flexibility and clarity.

;;#Common Paths to Simulation Resources

;;In the previous implementation, the 'state' was implemented as a class, with 
;;concrete members to access each piece.  We retain that level of commonality
;;via the paths, but the underlying representation is based on a dynamic map 
;;structure, so we can still add new data as needed in a flexible manner.

;;Creates a set of accessors for our simulation state.  This allows us to 
;;dissect our nested map of state a bit easier.  Each symbol in the defpath 
;;binding returns a function that, given a simulation context, points to the 
;;named resource using standard clojure map operations via clojure.core/get-in.
(util/defpaths [:state] 
  {parameters    [:parameters]
   supplystore   [:supplystore]
   demandstore   [:demandstore]
   policystore   [:policystore]
   fillstore     [:fillstore]
   fill-function [:fillstore :fillfunction]
   fillmap       [:fillstore :fillmap]
   behaviors     [:behaviormanager]
   supply-tags   [:supplystore :tags]
   demand-tags   [:demandstore :tags]})

(defn demands [ctx] (:demandmap (get-demandstore ctx)))
(defn units   [ctx] (:unitmap   (get-supplystore ctx)))
(defn periods [ctx] (:periods   (get-policystore ctx)))


(defmacro with-simstate 
  "Given a destructuring of [[path1 path2...] the-simstate], paired
   with an expression, evaluates the expression under a lexical context
   where the path symbols are bound to corresponding get-path variants 
   as defined for simstate."
  [[path-symbs state]  & expr]
  (let [symb->path {'parameters    get-parameters
                    'supplystore   get-supplystore
                    'demandstore   get-demandstore
                    'behaviors     get-behaviors
                    'policystore   get-policystore
                    'fillstore     get-fillstore
                    'fill-function get-fill-function
                    }]
    `(let [~@(reduce (fn [acc [p v]]
                       (-> acc (conj p) (conj v)))
                     []
                     (for [p path-symbs]
                       [p (if-let [res (get symb->path p)] 
                            `(~res ~state)                                   
                            (throw (Exception. (str "Unknown path " p))))]))]
       ~@expr)))



;;#Protocols 
;;Alias for entity protocol.  Helps us unify name access.
(defn entity-name [x]
  ;; (if (satisfies? spork.entitysystem.store/IEntity x)
  ;;   (spork.entitysystem.store/entity-name x)
    (get x :name))

;;#Operations for working with mutable references
;;particularly working with pieces of state in a nested associative
;;structure.


;;Imports from spork.util.cellular and simcontext
(util/import-vars 
 [spork.util.cellular
  assoc-any
  conj-any
  dissoc-any
  disj-any
  assoc-in-any
  update-in-any
  contains-any?
  with-cells
  with-transient-cells
  swap-cell!
  reset-cell!
  ->cell]
 [spork.sim.simcontext 
  merge-updates
  get-time])

;; (def  assoc-any  cells/assoc-any)
;; (def  conj-any   cells/conj-any)
;; (def  dissoc-any cells/dissoc-any)
;; (def  disj-any   cells/disj-any)
;; (def  assoc-in-any cells/assoc-in-any)
;; (def  update-in-any cells/update-in-any)
;; (def  contains-any? cells/contains-any?)
;; (def  with-cells cells/with-cells)
;; (def  with-transient-cells cells/with-transient-cells)
;; (def  swap-cell! cells/swap-cell!)


;(def merge-updates sim/merge-updates)

;;Experimental...
;;Overloading of simcontext/merge-updates, in that we short-circuit 
;;cells.  If an updated value is a cell, we do not merge.  Idiomatically, 
;;operations that modify simulation state use this merge/updates instead 
;;of simcontext/merge-updates.  We'll move this into spork in the 
;;future, for now it's a proof of principle.
;; (definline merge-updates  [m ctx] 
;;   `(if (map? ~m)
;;      (reduce-kv (fn [c# k# v#]
;;                   (cond (cell? v#) c# ;short-circuit cells
;;                         (= k# :trigger) (v# c#) 
;;                         :else   (sim/assoc-state k# v# c#)))
;;                 ~ctx ~m)
;;      (reduce (fn [c# [k# v#]]
;;                (cond (cell? v#) c#
;;                      (= k# :trigger) (v# c#) 
;;                      :else          (sim/assoc-state k# v# c#)))
;;             ~ctx ~m)))


(defn set-parameter    [s p v] (update-parameters s #(assoc % p v)))
(defn merge-parameters [s ps]  (update-parameters s #(merge % ps)))
  
;;#Empty Simulation Contexts
(def emptystate (simstate/make-simstate))
(def emptysim   (sim/add-time 0 (sim/make-context :state emptystate)))
;;A useful debugging context for us.  Prints out everything it sees.
(def ^:dynamic *debug* nil)
(def ^:dynamic *verbose* nil)
(def ^:dynamic *ignored* #{})


(defmacro debugging [& expr]
  `(binding [~'marathon.sim.core/*debug* true]
     ~@expr))

(defmacro ignoring [es & expr]
  `(binding [~'marathon.sim.core/*ignored*  (into ~'marathon.sim.core/*ignored* ~es)]
     ~@expr))

(defmacro noisy 
  ([es  expr]
     `(debugging 
       (ignoring ~es ~expr)))
  ([expr] `(debugging ~expr)))

(defn visible? [edata] 
  (and *debug*
     (not (*ignored* (spork.sim.data/event-type edata)))))

(def ^:dynamic *event-filter* visible?)
(defmacro with-event-filter [f & expr]
  `(binding [~'marathon.sim.core/*event-filter* ~f]
     ~@expr))

(defn debug-listener  [ctx edata name] 
  (do  (when (*event-filter* edata)
         (println (if *verbose* 
                    (sim/debug-msg  ":debugger saw " 
                                    {:type (spork.sim.data/event-type  edata) 
                                     :from (spork.sim.data/event-from edata)
                                     :to   (spork.sim.data/event-to edata)
                                     :msg  (sim/packet-message edata)
                                     :data (spork.sim.data/event-data  edata)})
                    (sim/debug-msg (sim/packet-message edata)))))
       ctx))

(def debugsim   
  (->> (-> (sim/make-debug-context 
            :debug-handler  
            debug-listener)
           (assoc :state emptystate))
       (sim/add-time 0)))

(defn debug! [ctx] 
  (if (contains?  (spork.sim.pure.network/get-event-clients ctx :all)
                  :debugger)
    ctx
    (sim/add-listener :debugger debug-listener [:all] ctx)))

;;#State-wide queries...
;;tbd
(defn features [ctx & {:keys [where] :or {where identity}}]
  [:simstate
   (for [[nm obj] (seq (:state ctx))
         :when (where nm)]
     [nm (if (map? obj) (keys obj) obj)]
     )])

;;TODO# port this over into the API in spork.sim
(defn events [ctx]   (spork.sim.data/event-seq ctx))
(defn times [ctx] (map :time (events ctx)))
(defn segments [ctx] 
  (->> (partition 2 1 (events ctx))
       (map (fn [[l r]]
              [(assoc l :duration (- (:time r) (:time l)))
               r]))
       (map first)
       (map (fn [r] (if (:duration r) r (assoc r :duration 1))))))
       
       
         
         

(defn rvals [kvs]
  (reify
    clojure.lang.Counted 
    (count [this] (count kvs))
    clojure.lang.Seqable 
    (seq [this] (seq kvs))
    clojure.core.protocols/CollReduce
    (coll-reduce [this f1]
      (reduce-kv (fn [acc k v] (f1 acc v)) (f1) kvs))
    (coll-reduce [_ f1 init]
      (reduce-kv (fn [acc k v] (f1 acc v)) init kvs))))

(defn rkeys [kvs]
  (reify
    clojure.lang.Counted 
    (count [this] (count kvs))
    clojure.lang.Seqable 
    (seq [this] (seq kvs))
    clojure.core.protocols/CollReduce
    (coll-reduce [this f1]
      (reduce-kv (fn [acc k v] (f1 acc k)) (f1) kvs))
    (coll-reduce [_ f1 init]
      (reduce-kv (fn [acc k v] (f1 acc k)) init kvs))))

(defn collect [fs xs]  
  (let [f (if (coll? fs) (apply juxt fs) fs)]
      (map f xs)))

;; (defn parse-collector [fs]
;;   (cond (map? fs) (fn [x] 
;;                     (reduce-kv (fn [m fld f] (assoc m fld (f x))) {} x))
;;         (vector? fs) 
;;             (reduce (fn [acc f]
;;                       (if (and (vector? f) (= (count f) 2))
;;                         (
           
;; (defn collect2 [fs xs]  
;;   (let [f (if (coll? fs) 
;;             (apply juxt fs) 
;;             fs)]
;;       (map f xs)))

;;Acts like juxt, except it returns the results in a map.
;;The map is implied by the args in kvps, where simple keys - numbers,
;;strings, keywords, are assumed to be field names. A map is built 
;;from the fields by getting the corresponding field in an input
;;record.  Vector keys are assumed to imply [key function-to-apply]
(defn juxtmap [& ks]
  (let [fs  (reduce (fn [acc x]
              (cond (or  (keyword? x)  (string? x) (number? x))   (assoc acc x #(get % x))
                    (vector? x)  
                      (let [fld (first x) 
                            getter (second x)]
                        (assoc acc fld 
                          (cond (fn? getter) getter 
                                (or  (keyword? getter)  (string? getter) (number? getter))   
                                 #(get % getter)
                                :else (throw (Exception. (str "unknown juxt-map getter " getter))))))
                      :else (throw (Exception. (str "unknown juxt-map arg " x)))))
                   {} ks)]
    (fn [x] 
      (reduce-kv (fn [m fld f] (assoc m fld (f x))) {} fs))))

(defmacro fields [xs]
  (cond (map? xs)
        `(marathon.sim.core/juxtmap ~@(:fields xs))
        (vector? xs) 
        `(juxt ~@xs)))

;;TODO maybe make this a reducer....dunno yet.
(defn collectr [fs xs]  
  (let [f (if (coll? fs) (apply juxt fs) fs)]
    (reify     
      clojure.core.protocols/CollReduce
      (coll-reduce [this f1]   (reduce f1 (f1) (r/map f xs)))        
      (coll-reduce [_ f1 init] (reduce f1 init (r/map f xs)))
      clojure.lang.Seqable 
      (seq [this]  (seq (map f xs))))))

;;This is a trivial proxy for the entity store.  We'll port this over
;;once the backend/state management is stable.
(defn entities [ctx]  
  (let [xs   (->> ctx  ((juxt demands units periods)))
        n    (reduce + (map count xs))
        ents (-> xs  (r/mapcat rvals))]
    (reify 
      clojure.lang.Seqable 
      (seq [obj] (concat (vals (units ctx)) (vals (demands ctx)) (vals (periods ctx))))
      clojure.lang.Counted 
      (count [obj] n)
      clojure.core.protocols/CollReduce
      (coll-reduce [this f1]   (reduce (fn [acc v] (f1 acc v)) (f1) ents))
      (coll-reduce [_ f1 init] (reduce (fn [acc v] (f1 acc v)) init ents)))))

;;#Useful Vizualizations of the simulation context
(defn demands->track [xs] 
   (sketch/->track (map (fn [r] (merge r {:start (:startday r)})) xs) :track-name name))

(defn demand->tracks  
  [xs & {:keys [keyf] :or {keyf (juxt :demandgroup :src)}}]
  (for [[g xs] (group-by keyf xs)]
    [g (map (fn [r] (merge r {:start (:startday r)})) xs)]))


(defn visualize-events [es track-keyf color-keyf]  
  (let [coloring (zipmap (map color-keyf es) (take (count es) (sketch/palette)))
        tracks   (demand->tracks es :keyf track-keyf)
        rendered-tracks (sketch/->tracks tracks)
        track-width     (:width (spork.graphics2d.canvas/shape-bounds rendered-tracks))
;        lgnd     (sketch/->legend coloring)
;        lwidth   (:width (spork.graphics2d.canvas/shape-bounds lgnd))
        ]
    (sketch/with-event->color (fn [e] (get coloring (color-keyf e)))
      (sketch/sketch-image
       (sketch/scale 1.0 1.5
                     (sketch/stack [(sketch/->tracks tracks)
                                   ; (sketch/translate 10 5 
                                   ;    (sketch/scale (float (/ track-width lwidth)) 2.0
                                   ;      lgnd))
                                    ]))))))

(defn visualize-demands [ctx  & {:keys [track-keyf color-keyf] :or {track-keyf (juxt :demandgroup :src)
                                                                    color-keyf (juxt :vignette)}}]
  (visualize-events (vals (demands ctx)) track-keyf color-keyf))

(defn visualize-supply [ctx]
  (->> (units ctx)
       (vals)
       (map (fn [u] (update-in u [:policy] :name)))
       (spork.util.table/records->table)
       (spork.util.table/visualize)))

(defn visualize-fillmap [ctx]
  (if-let [fm (:fillmap (get-fillstore ctx))]
    (->> (for [[parent children] fm
               [child cost] children]
           {:donor parent :recepient child :cost cost})
         (spork.util.table/records->table)
         (spork.util.table/select :fields [:donor :recepient :cost] :from)
         (spork.util.table/visualize))
    (throw (Exception. "No fill map to visualize!"))))

(defn visualize-fillgraph [ctx]
  (if-let [fg (:fillgraph (get-fillstore ctx))]
    (jung/view-graph fg jung/fr)
    (throw (Exception. "No fillgraph to visualize!"))))

(defn visualize-subscriptions [ctx] 
  (inspect/inspect-tree (:subscriptions (get-policystore ctx))))

;;TODO# define a visualization protocol, extend it to core datatypes...
(defn visualize-unit [u]   (inspect/inspect-tree u))    
(defn visualize-policy [p] (jung/view-graph (:positiongraph p) jung/fr))

       
;;#Shared Functions

;;These functions were extracted due to use across multiple domains.  I may 
;;refactor them into core services, but for now, relocating them in sim.core 
;;allows every system access to them.

(defn now
  "Consult the system clock for the current time.  Used for logging."
  [] 
  (System/currentTimeMillis))

;;The name here is a bit generic.  We're really trying to acquire the keys of 
;;a map that contains information about followon-eligible supply.  In this 
;;context, the keys are actually the followon-code of the unit, (typically a
;;demandgroup). 
(defn get-followon-keys
  "Returns a sequence of followon codes that are currently present in the 
   supply.  Used for constraining or preferring supply during the followon 
   fill phase."
  [supplystore] (keys (:followon-buckets supplystore)))

(defn update-unit
  "Associates unit u into the context's supply store, using the unit's name 
   as a key."
  [u ctx]
  (assoc-in ctx [:state :supplystore :unitmap (:name u)] u))

(defn ghost? [unit] (= (clojure.string/upper-case (:src unit)) "GHOST"))
(defn followon? [u] (:followoncode u))
(defn ghost-followon? [u] (and (ghost? u) (followon? u)))

;;#TODO get this working like it used to, right now it's not important.
;; (defn interval->date  [t ctx]
;;   (let [ps (get-parameters ctx)
;;         start-date (get ps :StartDate)
;;         time-scale (get ps :time-scale 1)] 
;;     (+ start-date (* time-scale t))))

;;interval->date is a simple stub, maybe unnecessary (although I like
;;having it around for records in the output)
(defn interval->date  [t ctx]  t)

(defn in-scope? [params src]
  (and (not (get-in params [:SRCs-Out-of-Scope src]))
       (get-in params [:SRCs-In-Scope src])))

;;#Tag Related Functions#
;;Another useful bit of higher order, or meta data, is the notion of simple 
;;tags.  We use tags as a simple mechanism to track all kinds of effects and 
;;information, without having to embed data in lower classes.  This way, when
;;an entity - or even a system - needs to be grouped or categorized we can 
;;use tags.  As a result, we see a lot of shared tags that represent common 
;;effects, like whether an entity is enabled or disabled.

;Generic tag-related....These apply equally to supplystore, demandstore, etc.
;The interface is identical.  Only the interpretation of the tags is different.
(defn enabled? [store item]  (tag/has-tag? (:tags store) :enabled item))
(defn disabled? [store item] (not (enabled? store item)))
(defn enable [store item]
  (update-in store [:tags] tag/tag-subject :enabled item))
(defn disable [store item]
  (update-in store [:tags] tag/untag-subject :enabled item))
(defn special-src? [tags src] (when tags (tag/has-tag? tags src "Special")))

;;#Fill Related Functions

;find-eligible-demands is implemented as multimethod that dispatches based on 
;type of the category that's passed in.  category-type provides a simple 
;dispatch mechanism.  Note-> could use built-in hierarchy functions to do this.
(defn category-type [x]
  (cond (or (keyword? x) (string? x))  :simple            
        (vector? x) :src-and-group 
        (map? x)    :rule-map
        :else (throw (Exception. "Unknown category type " (type x)))))

;;#Utility Functions and Macros

;;A collection of shared functions, might turn into protocols someday...
;;This should contain only core functionality shared across subsystems defined 
;;in other namespaces, to eliminate redunancy.
;;I started this due to the fact that several functions - primarily accessors, 
;;were bubbling up in each of the domain-specific modules.  As a result, we'll 
;;just shove them in here.  If we don't, we'll get circular dependencies.

;;We alias the more efficient make-string function, rather than 
;;using core/str.  This is commonly used for logging messages 
;;and other things.  Since there are lots of events flying 
;;around with string data, this is a serious bottleneck.
(def msg gen/make-string)

;;Stubs were used during the initial port to toss exceptions if an unimplemented
;;or deferred function was called.  
(defmacro stub [msg & empty-body]  
  `(~'fn ~'[& args] (~'throw (~'Exception. ~msg))))

;This is a general utility function, that allows us to derive predicates based
;on atomic values, sets, or maps.  Used for filtering (currently in demand 
;categories and supply categories).
;Can probably extend this to allow for arbitrary predicate functions (later).
(defn make-member-pred [g]
  (if (or (set? g) (map? g))
    #(contains? g %)
    #(= g %)))

;If function results in an empty map, contained within another map, 
;removes the entry associated with the empty map.
(defn prune-in [m ks f & args]
  (let [updated (apply update-in ks f args)]
    (if (empty? (get-in updated ks))
      (let [path   (butlast ks)
            parent (get-in m path)]            
        (assoc-in m path (dissoc parent (last ks))))
      updated)))

;;Predicate for determining if a map has a key equal to a val.
(defn key= [k v] (fn [m] (= (get m k) v)))

;;#TODO evaluate memoize here and see if we're paying a useless
;penalty.
(defn key-tag-maker
  "Creates a little keyword factory that allows to to define descriptive 
   tags using keywords efficiently and consistently."
  [base] (gen/memo-1
          (fn [tag] (keyword (str base tag)))))

;helper macro for defining key-building functions.
(defmacro defkey [name base] `(def ~name (key-tag-maker ~base)))

;;#Utils
(defn ensure-name
  "We tend to prefer unique names, and often times we accomplish that by concatenating the 
   count of a container onto a non-unique name.  ensure-names generalizes this stuff."
  [named names]                  
  (let [nm (entity-name named)]
    (if (contains? names nm)
      (assoc named :name 
             (msg nm "_" (count names)))
    named)))

(definline empty-string? [x] `(= ~x ""))
(defn debug-print [msg obj] (do (println msg) obj))

(defn as-records [record-source]
  (if (and (seq? record-source) (map? (first record-source))) record-source      
      (tbl/record-seq record-source)))

(let [idx (atom 0)]
  (defn next-idx 
    "Utility function for getting incrementing indices.  Used for 
     naming entities.  Passing an argument resets the index to the arg,
     and returns the arg.  Otherwise, a mutable counter is incremented 
     and the result is returned."
    ([] (let [i @idx]
          (do (swap! idx unchecked-inc)
              i)))
    ([new-idx] (do (reset! idx new-idx)
                   new-idx))))


;;Imported from spork.util.general
(util/import-vars 
 [spork.util.general  
  deep-assoc
  deep-get
  deep-update 
  deep-dissoc])

;;TODO de-duplicate this from marathon.data.protocols
;;look into replacing this with a universal constant, or upperbound
;;for longs
(def ^:constant +inf+ 9999999)

;;#Wrapper around spork.simcontext 
;;As it stands, update requests aren't events...
;;They're disconnected...since the updater is handling the event
;;separately...
;;Maybe we can clear that up at some point....
(defn request-update [tupdate requested-by request-type ctx]
  (->> ctx
       (sim/request-update tupdate requested-by request-type )
       (sim/trigger-event request-type requested-by :update-manager 
                          (msg requested-by " requested an " request-type " at " tupdate) nil)))



;;##Developer Notes

;;#Transitioning from Effectful Simulation and State Updating#
;;One problem area in the port from VBA->Clojure was the handling of 
;;decentralized state updates.  This primarily happened via event dispatch, or 
;;through side-effects called during management functions.  The solution for our
;;pure simulation is to formalize batch updates to the simulation by using 
;;simcontext/merge-updates.  This allows us to pass maps of updates around, and 
;;the updates are merged with the simulation state in a predefined manner.  
;;We may wish to formalize this as a language feature (i.e. macro) to define 
;;different types of update.

;;#Conventions for Notifications/Events/Messaging Functions
;;Message functions are suffixed by the \! character, by convention, to denote 
;;the possibility of side effects. The function signatures are pure, in that 
;;they return a context.  The naming convention will help identify when 
;;"messaging" is occuring.  While effects may happen, in the form of logging 
;;or display updating, the internal simulation mechanics are still pure.
;;__TODO__ -> implement defmessage macro....

;;#Cries for Better Abstractions
;;Note--> I'm noticing some patterns emerging that might make for nice 
;;abstractions...
;;We seem to be doing a lot of nested updates for particular bits of a system.
;;A lot of updates also appear to be existential in nature, i.e. if the 
;;update is a dissoc, and the resulting collection is empty, we want to 
;;remove the empty entry from the parent container.

;;Also, we have a class of functions that specifically shovels context around.
;;By context, I mean the environment in which the simulation is happening, as 
;;in a simcontext from sim.simcontext.  We can clean up some of the function 
;;signatures by allowing macros to operate in this context, possibly even a 
;;special evaluator.

;;On the topic of special evaluators, in each namespace, we end up defining 
;;pure functions that act to update specific bits of a context, sometimes 
;;acting on isolated structures within the context.  There's typically a 
;;nested structure in the context's :state, which destructure and update in 
;;pieces.  It might be nice to have something like #'doto. 

;;It might be nice to have a macro that lets us define ways to address or query
;;the context's state.  This leads directly to the entity-store stuff I already 
;;built, and facilitates a component-based architecture.

;;Finally, we probably want some kind of language for specifying different 
;;types of updates to the context.  For instance, we end up -inside the local 
;;namespaces - defining functions that operate on a specific area of the context
;;often with deep nesting paths.  I already introduced the notion of updates and
;;merge-updates in the simcontext.  It might be as simple as defining
;;multimethods, or a protocol, that implements the merge protocol.

;;what would a nice abstraction look like? 
;;adjust-max-utilization!, from marathon.sim.supply is a great candidate..
;;there are a couple of bread-n-butter items of interest...
;;1) we're updating a unit functionally, storing the result of the update, 
;;   a new unit
;;2) we're updating the supplystore, to reflect the updated unit from 1).
;;3) we're merging the new supplystore into the updated context, by 
;;   passing it to a generic "update" handler that knows how to interpret 
;;   the key :supplystore, the new supply, and a context, to transition to 
;;   a new context.
;;One improvement is to wrap the operation behind a function to provide a clean
;;API....namely, update-unit 
;;This would fetch the appropriate unit, apply a function to it (like update-in),
;;and return a new supplystore with the unit updated.
;;  In this sense, we're lifting a specific function, updating the policyq of 
;;  a unit, into the context of a container for units. 
;;  In haskell speak, the supplystore is a functor, we're applying an update to 
;;  an element of the container.  It's the container's job to understand how 
;;  to lift the updating function into the proper context, and return the new 
;;  container.  monads/monoids anyone? 

;;#Policy Management Notes
;;Policy changes were encapsulated in the IRotationPolicy implementations.
;;This assumed that units would change policies, regardless of event-context.
;;That's actually a decent assumption.
;;However, when we tell unitdata to change policy, it evokes a change state 
;;evaluation.
;;Under the decoupled architecture, this requires simulation context.

;;I'm going to have the policy ops define a function (really just adapted from 
;;policymanager), that passes the context needed.  This is in-line with other 
;;decoupled, functional representations.
;;I have to rewire the IRotationPolicy implementation.....specifically taking 
;;out the onperiodchange event handling.
;;Rather, we'll let policy ops take care of changing units' composite policies.  
;;The good news is, all the bits are here.  Just need to re-organize the code.

;;Is there a way we can create shallow maps?  For instance, if we're 
;;trying to update a nested map, and we don't want to make the whole 
;;thing transient, can we pass around a temporarily mutable version? 

;;one idea here, is to have something that wraps the map and creates 
;;a map of transient info...
;;When we do reads, we use the transient, when we do writes, we 
;;use the transient.  Then, when we want to reify, 
;;we merge the transient with the original.  Ideally, this 
;;keeps the copy paths nice and small.

;;the simplest way is to have a mutable collection, i.e. 
;;a hashtable, with the values along the path.
;;Then for get-in and assoc-in, we can look at the 
;;path cache and see if the path exists there.  Then, we 
;;can apply the ops to the pathcache. 


;;Operations optimized for speed.  the -in and friends 
;;are not sufficient...

