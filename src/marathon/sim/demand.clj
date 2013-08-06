;;The demand system provides functions for scheduling demands, as well as 
;;defining the order in which demands are filled.  The namespace contains 
;;primitive functions for operating on demandstores, as well as demand-related
;;notifications and a high-level demand management API.
;Functions for creating, initializing, resetting, and updating state related to
;the demand simulation are also found here.
(ns marathon.sim.demand
  (:require  [marathon.demand [demanddata :as d]
                             [demandstore :as store]]
            [marathon.sim [core :as core] [supply :as supply] [policy :as policy]
                          [unit :as u][fill :as fill]]           
            [sim [simcontext :as sim]]
            [util [tags :as tag]]))

;;Undefined as of yet...
;(defn new-demand [name tstart duration overlap primary-unit quantity 
;      priority demandstore policystore ctx operation vignette source-first]
;

;;#Primitive Demand and DemandStore Operations#
(defn can-simulate? [demandstore]
  (> (count (tag/get-subjects (:tags demandstore) :enabled)) 0))

(defn add-fillable [fillrule demandstore]
  (assert (not (contains? (-> demandstore :fillables) fillrule))  
          "Tried to add fillrule multiple times")
  (update-in demandstore [:fillables] conj fillrule))

(defn remove-fillable [fillrule demandstore]
  (assert (contains? (-> demandstore :fillables) fillrule)  
          "Tried to remove non-existent fillrule")
  (update-in demandstore [:fillables] disj fillrule))

;TOM ADDED 30 MAy 2013 
(defn add-demand [demandstore demand]
  (assoc-in demandstore [:demand-map (:name demand)] demand))

(defn manage-changed-demands [day state] ;REDUNDANT
  (assoc-in state [:demand-store :changed] {}))

(defn clear-changes [demandstore] (assoc demandstore :changed {}))

(defn register-change [demandstore demandname]
  (if (contains? (:changed demandstore) demandname)
    demandstore 
    (assoc-in demandstore [:changed] demandname 0)))

;TOM Note 20 May 2013 -> need to abstract fillrule, etc. behind a function, 
;preferably one that uses keywords.
;inject appropriate tags into the GenericTags
(defn tag-demand [demand demandstore & {:keys [extras]}]
  (update-in demandstore [:tags] tag/multi-tag 
     (concat [(str "FILLRULE_" (:primaryunit demand)) ;USE KEYWORD
              (str "PRIORITY_" (:priority demand)) ;USE KEYWORD
              :enabled] 
             extras)))

(defn tag-demand-sink [demandstore sink]
  (update-in demandstore [:tags] tag/tag-subject  :Sinks sink))

(defn get-demand-sinks [demandstore] 
  (tag/get-subjects (:tags demandstore) :Sinks))

(defn clear-demands [demandstore] ;REDUNDANT?
  (merge demandstore
         {:demandmap {}
          :tlastdeactivation nil
          :unfilledq {}
          :activations {}
          :active-demands {}
          :fillables {}
          :tags (tag/tag-subject (tag/empty-tags) :Sinks)}))

;;__TODO__ Possibly move unit-related functionality to supply/unit simulations..

(defn set-followon [unit code] (assoc unit :followoncode code))
(defn ghost? [unit] (= (:src unit) "Ghost"))
(defn ungrouped? [g] (= g "UnGrouped"))
(defn unit-count [d] (count (:units-assigned d)))
(defn demand-filled? [d] (= (count (:units-assigned d)) (:quantity d)))
(defn empty-demand? [d] (zero? (unit-count d)))
(defn priority-key [demand] [(:name demand) (:priority demand)]) 
          
;We can break the fill into a couple of simple queries...
;
;we've got a category of demand...
;This is a priorityq of unfilled demands.
;We traverse the priorityq in priority order, trying to fill.
;If we can't fill, or only partially fill the next demand, we stop traversing.
  ;enforces the hierchical fill constraints. 
;so...filling a category implies traversing a sorted map of unfilled demands. 
;note -> we can modify the sorting key to include followon information, to make 
;things homogenous, just adding a special comparison function. 

(defn unfilled-categories [demandstore] 
  (keys (:unfilledq demandstore)))
(defn unfilled-demands [category demandstore] 
  (get-in demandstore [:unfilledq category]))
(defn get-demand [demandstore name] (get-in demandstore [:demandmap name]))
;Simple api function to group active demands from the store by their src. 
(defn demands-by-src [store src] (get-in store [:unfilledq src]))
 
;procedure that allows us to process a set of tags indicating associated demands
;that should be disabled.  if removal is true, the demands will be 
;removed from memory as well. in cases where there are a lot of demands, this 
;may be preferable.
(defn scope-demand [demandstore disable-tags & {:keys [removal]}]
  (let [tags    (:tags demandstore)
        f       (if removal #(remove-demand %1 %2) (fn [m k] m))]     
    (reduce (fn [store demand-name] 
              (let [demands (:demand-map store)]
                (if (contains? demands demand-name)
                  (f (core/disable store demand-name) demand-name) store)))
      demandstore (mapcat (partial tag/get-subjects tags) disable-tags))))

;1) Tom Note 20 May 2013 -> It would be nice to have a function or macro for 
;   defining nested updates like this, as it will probably happen quite a bit.
(defn remove-demand [demandstore demandname]
  (if (contains? (:demand-map demandstore) demandname)
    (let [{:keys [activations deactivations demand-map]} demandstore
          demand (get demand-map demandname)
          dname  (:name demand)
          tstart (:startday demand)
          tfinal (+ tstart (:duration demand))]
      (-> demandstore                                                        ;1)
        (update-in [:demand-map] dissoc demand-map demandname)
        (update-in [:activations] update-in activations [tstart] dissoc dname)
        (update-in [:deactivations]  
                   update-in deactivations [tfinal] dissoc dname)))
    demandstore)) 

;Simple wrapper for demand update requests.  
(defn request-demand-update! [t demandname ctx]
  (sim/request-update t demandname :demand-update ctx))

;;#Demand Notifications#
(defn request-fill! [demandstore category d ctx]
  (sim/trigger-event :RequestFill (:name demandstore) (:name demandstore)
     (str "Highest priority demand in Category " category " is " (:name d) 
          " with priority " (:priority d)) nil ctx))

(defn trying-to-fill! [demandstore category ctx] 
  (sim/trigger-event :RequestFill (:name demandstore) (:name demandstore) 
     (str "Trying to Fill Demand Category " category) nil ctx))

(defn fill-demand! [demandstore demandname ctx]
   (sim/trigger-event :FillDemand (:name demandstore) (:name demandstore)
      (str "Sourced Demand " demandname) nil ctx ))

(defn can-fill-demand! [demandstore demandname ctx]
  (sim/trigger-event :CanFillDemand (:name demandstore) 
       demandname (str "Filled " demandname) nil ctx))

(defn demand-fill-changed! [demandstore demand ctx]
  (sim/trigger-event :DemandFillChanged (:name demandstore) (:name demand) 
     (str "The fill for " (:name demand) " changed.") demand ctx))

(defn sourced-demand! [demandstore demand ctx]
  (sim/trigger-event :FillDemand (:name demandstore) (:name demandstore) 
     (str "Sourced Demand " (:name demand)) nil ctx))

(defn activating-demand! [demandstore demand t ctx]
  (sim/trigger-event :ActivateDemand (:name demandstore) (:name demand)
     (str "Activating demand " (:name demand) " on day " t) nil ctx)) 

(defn deactivating-demand! [demandstore demand t ctx]
  (let [dname (:name demand)]
    (sim/trigger-event :DeActivateDemand (:name demandstore) dname
       (str "DeActivating demand " dname " on day " t) dname ctx)))

;Look into unifying this with deactivating-unfilled....seems redundant.
(defn deactivating-empty-demand! [demand t ctx]
  (sim/trigger-event :DeActivateDemand :DemandStore (:name demand)  
       (str "Demand " (:name demand) " Deactivated on day " t
            " with nothing deployed ") nil ctx))    

(defn deactivating-unfilled! [demandstore demandname ctx] 
  (sim/trigger-event :DeActivateDemand (:name demandstore) demandname 
       (str "Demand " demandname " was deactivated unfilled") nil ctx))

(defn removing-unfilled! [demandstore demandname ctx] 
  (sim/trigger-event :FillDemand (:name demandstore) demandname
     (str "Removing demand " demandname " from the unfilled Q") nil ctx))

(defn adding-unfilled! [demandstore demandname ctx] 
  (sim/trigger-event :RequestFill (:name demandstore) demandname  ;WRONG                   
     (str "Adding demand " demandname " to the unfilled Q") nil ctx))

(defn ghost-returned! [demand unitname ctx]
  (sim/trigger-event :GhostReturned (:src demand) unitname 
     (str "Ghost for src " (:src demand) " left deployment.") nil ctx))

(defn sending-home! [unitname ctx] 
  (sim/trigger-event :supply-update :DemandStore unitname 
     (str "Send Home Caused SupplyUpdate for " unitname) nil ctx))

(defn disengaging! [demand unitname ctx]
  (sim/trigger-event :DisengageUnit :DemandStore unitname 
     (str "Disengaging unit" unitname " from de-activated demand" 
        (:name demand)) nil ctx))

(defn disengaging-home! [demandstore demand unit ctx]
  (sim/trigger-event :DisengageUnit (:name demandstore)  (:name unit) ;WRONG
     (str "Sending unit" (:name unit) 
          "home from demand" (:name demand)) unit ctx))

(defn overlapping! [demandstore demand unit ctx]
  (sim/trigger-event :overlapping-unit (:name demandstore) (:name unit)
        (str "Overlapping unit" (:name unit) " in demand" 
             (:name demand)) unit ctx))

(defn registering-demand! [demand ctx]
  (sim/trigger-event :added-demand "DemandStore" "DemandStore" 
       (str "Added Demand " (:name demand)) nil ctx))

;;#Demand Registration and Scheduling#
(defn get-activations   [dstore t]   (get-in dstore [:activations t] #{}))
(defn set-activations   [dstore t m] (assoc-in dstore [:activations t] m))
(defn get-deactivations [dstore t]   (get-in dstore   [:deactivations t] #{}))
(defn set-deactivations [dstore t m] (assoc-in dstore [:deactivations t] m))


;TOM note 27 Mar 2011 ->  I'd like to factor these two methods out into a single 
;function, discriminating based on a parameter, rather than having two entire 
;methods.
;Register demand activation for a given day, given demand.
;TOM Change 7 Dec 2010
(defn add-activation [t demandname dstore ctx]
  (let [actives (get-activations dstore t)]
    (->> (request-demand-update! t demandname ctx) ;FIXED - fix side effect
      (sim/merge-updates 
        {:demandstore (set-activations dstore t (conj actives demandname))}))))

;Register demand deactviation for a given day, given demand.
;1)Tom Note 20 May 2013 -> Our merge-updates function looks alot like entity 
;  updates in the component-based model.  Might be easy to port...
(defn add-deactivation [t demandname demandstore ctx]
  (let [inactives (get-deactivations demandstore t)
        tlast     (max (:tlastdeactivation demandstore) t)]
    (->> (request-demand-update! t demandname ctx) 
         (sim/merge-updates                                                  ;1)
           {:demandstore 
            (-> (assoc demandstore :tlastdeactivation tlast)
                (set-deactivations t (conj inactives demandname)))}))))

;Schedule activation and deactivation for demand. -> Looks fixed.
(defn schedule-demand [demand demandstore ctx]
  (let [{:keys [startday demand-name duration]} demand]
    (->> (add-activation startday demand-name demandstore ctx)
         (add-deactivation (+ startday duration) demand-name demandstore))))

(defn register-demand [demand demandstore policystore ctx]
  (let [dname    (:name demand)
        newstore (tag-demand demand (add-demand demandstore demand))]
    (->> (registering-demand! demand ctx)
      (sim/merge-updates 
        {:demand-store  newstore 
         :policy-store  (policy/register-location dname policystore)})
      (schedule-demand demand newstore)))) 

;utility function....
(defn pop-priority-map [m]
  (if (empty? m) m (dissoc m (first (keys m)))))


;;#Managing the Fill Status of Changing Demands#
;;Unfilled demands exist in a priority queue, UnfilledQ, ordered by the demand's 
;;priority field value - typically an absolute, static ordering, set at 
;;construction.
;;UnfilledQ partitions the set of active demands that are unfilled.
;;When we go to look for demands that need filling, we traverse keys in 
;;priority order. 
;;We use a priority queue to effeciently respond to changes in a demand's fill 
;;status, and to only try to fill demands when we need to.  Older algorithms 
;;naively polled the demands, and led to quadratic complexity.  In this case, 
;;the priority queue lets us implement a push algorithm, and if we're filling 
;;hierarchically, we can terminate the fill process early if we fail to satisfy
;;a demand.  
 
;;Basic demand events are Activation, Deactivation, Fill, Unfill.
;;Activation and unfill events manifest in demandnames being added to the 
;;unfilledQ. Deactivation and filled events manifest in demandnames being 
;;removed from the unfilledQ. We want to effeciently find out if 
;;there are unfilled demands,  what the most important unfilled demand is, 
;;and what happens when we fill the demand (take the demand off the q or not?)
;;Using the fill queue, the update-fill function provides a general hub to 
;;enforce these invariants.  You'll see it get used a bit when we make changes
;;to a demand, either filling, activating, etc.  It applies the appropriate 
;;processing to keep a demand's fill status consistent.

(defn update-fill
  "Derives a demand's fill status based on its current data.  Satisfied demands 
   are removed from the unfilled queue, unsatisfied demands are kept or added to
   the unfilled queue.  Deactivating unfilled demands are detected as well.
   Propogates notifications for each special case."
  [demandstore demandname ctx]
  (let [demand (get-in demandstore [:demandmap demandname])
        fill-key (priority-key demand)
        unfilled (:unfilledq demandstore)]
    (assert (not (nil? (:src demand))) (str "NO SRC for demand" demandname))
    (assert (not (nil? demandname)) "Empty demand name!")
    (let [required (:required demand)
          src      (:src demand)]
      (if (= required 0) ;demand is filled, remove it
        (if (contains? unfilled src) ;either filled or deactivated
          (let [demandq (dissoc (get unfilled src) fill-key)
                nextunfilled (if (= 0 (count demandq)) 
                                 (dissoc unfilled src) 
                                 (assoc unfilled src demandq))]
            (->> (removing-unfilled! demandstore demandname ctx)
                 (sim/merge-updates 
                   {:demandstore (assoc demandstore :unfilled nextunfilled)})))              
          (deactivating-unfilled! demandstore demandname ctx))     ;notification
        ;demand is unfilled, make sure it's added
        (let [demandq (get unfilled src (sorted-map))]  
          (if (contains? demandq fill-key) ctx ;pass-through
            (->> (sim/merge-updates ;add to unfilled 
                   {:demandstore (assoc-in demandstore [:unfilled src] 
                       (assoc demandq fill-key demand))} ctx) ;WRONG?
                 (adding-unfilled! demandstore demandname)))))))) 

;;#Describing Categories of Demand To Inform Demand Fill Rules#
;;Demands have typically been binned into gross categories, based on the type 
;;of capability required to meet a demand.  Additional complexities arose as
;;subcategories - such as the notion of follow-on demands - became a necessity.
;;After a lengthy thinking cycle, I resolved to define 
;;a useful little language for describing categories, that is, elements of 
;;demand and the contextual information for how they may be filled. 

;;This should eliminate a slew of duplication and complexity from the VBA 
;;implementation, since we can use the category of fill to select eligible 
;;demands, and to inform suitable supply.  

;;Examples of categories follow:

;;(fill-demands "SRC1" "Group2") ; => find all eligible demands for SRC1 that 
;;have group2 as a demandgroup, if there's any grouped supply, try using that to
;;fill the demands for SRC1 in order.

;;=> find all eligible demands for SRC1 that have group2 as a demandgroup, if 
;;there's any grouped supply, try using that to fill the demands for SRC1 in 
;;order.

;;We use category as a little query language, which the fill system can 
;;interpret as query to find supply. 

;;In the simple case, when the category is a string, or a key, we act like 
;;normal filling..
;;(fill-category "SRC2") ;=> find all eligible demands for SRC2.

;;In a complex case, when the category is a sequence, we parse it appropriately.
;;Pairs define a more constrained category, which is interpreted as a category 
;;of SRC and a grouping.  

;(fill-category ["SRC2" "Group1"]) ;=> find the eligible demands for SRC2, where 
;the demand group is group1.

;;Detailed categories come in the forms of maps, which are interpreted as a 
;;filter by default.  

;;(fill-category {:demand {:src "SRC1" :demandgroup "Group2"}
;;                :supply {:followoncode "Group2"}})

;;categories serve to link supply AND demand, since categories are parsed by 
;;ISupplier functions into supply ordering queries.
;;For example,  from the supply-side, we determine which supply is eligible to 
;;fill an ["SRC1" "Group2"] category of demand.
;;By default, the query should look for supply matching "SRC1", and all feasible
;;substitutes for "SRC1", where the constraint that the supply followon code 
;;also equals "Group2".

;;Categories can be interpreted an arbitrary number of ways, but the preceding
;;conventions should cover both the 90% use cases, for simple SRC matches, and 
;;almost unlimited extensibility via encoding categories as maps, to be 
;;interpreted by an ISupplier.

;;#Demand Category Methods#
;;Categories are used by three new functions: find-eligible-demands, 
;;defined here, and find-supply, defined in marathon.sim.fill.

;;Note -> find-eligible demands is...in a general sense, just a select-where 
;;query executed against the demandstore...        
(defmulti category->demand-rule core/category-type)

;;Interprets a category as a rule that finds demands based on an src.
;;Note -> we should probably decouple the get-in part of the definition, and 
;;hide it behind a protocol function, like get-demands.  
(defmethod category->demand-rule :simple [src]
  (fn [store] (get-in store [:unfilledq src]))) ;priority queue of demands.

;;Interprets a category as a composite rule that matches demands based on an 
;;src, and filters based on a specified demand-group.
;;[src demandgroup|#{group1 group2 groupn}|{group1 _ group2 _ ... groupn _}]  
(defmethod category->demand-rule :src-and-group [[src groups]]
  (let [eligible? (core/make-member-pred groups)] ;make a group filter
    (->> (seq (find-eligible-demands store src)) ;INEFFICIENT
         (filter (fn [[pk d]] (eligible? (:demand-group d))))
         (into (sorted-map))))) ;returns priority-queue of demands.

;;matches {:keys [src group]}, will likely extend to allow tags...
;;Currently, provides a unified interface for rules, so we can just use simple 
;;maps. Should probably migrate other calls to this simple format.
(defmethod category->demand-rule :rule-map [category]
  (let [has-every-key? (comp every? #(contains? category %))]
    (cond (has-every-key? [:src :groups]) 
             (category->demand-rule ((juxt :src :groups) category))  
          (has-every-key? [:src]) 
             (category->demand-rule (:src category))                  
          :else ;Future extensions.  Might allow arbitrary predicates and tags. 
             (throw (Exception. "Not implemented!")))))      

;;High-level API to find a set of eligible demands that match a potentially 
;;complex category.  Uses the category->demand-rule interpreter to parse the 
;;rule into a query function, then applies the query to the store.
(defn find-eligible-demands 
  "Given a demand store, and a valid categorization of the demand, interprets
   the category into a into a demand selection function, then applies the query
   to the store."
  [store category]
  ((category->demand-rule category) store))


;;__TODO__ Check implementation in simcontext.
(defn merge-fill-results [res ctx] 
  (throw (Exception. "merge-fill-results not implemented")))

;Since we allowed descriptions of categories to be more robust, we now abstract
;out the - potentially complex - category entirely.  This should allow us to 
;fill using 99% of the same logic.
;What we were doing using fill-followons and fill-demands is now done in 
;fill-category. In this case, we supply a more robust description of the 
;category of demand we're trying to fill. To restrict filling of demands that 
;can take advantage of existing followon supply, we add the followon-keys to the 
;category.  This ensures that find-eligible-demands will interpret the 
;[category followon-keys] to mean that only demands a demand-group contained by 
;followon-keys will work.  Note: we should extend this to arbitrary tags, since 
;demandgroup is a hardcoded property of the demand data.  Not a big deal now, 
;and easy to extend later.

;For each independent set of prioritized demands (remember, we partition based 
;on substitution/SRC keys) we can use this for both the original fill-followons 
;and the fill-normal demands. The difference is the stop-early? parameter.  If 
;it's true, the fill will be equivalent to the original normal hierarchical 
;demand fill. If stop-early? is falsey, then we scan the entire set of demands, 
;trying to fill from a set of supply.

;1)the result of trying to fill a demand should be a map with context
   ;we can be more flexible here, maybe pass info on the success of the fill too
   ;map of {:demand ... :demandstore ... :ctx ...}
   ;NOTE -> we pass the category to communicate with supply during fill...
;2)incorporate fill results.
;3)If we fail to fill a demand, we have no feasible supply, thus we leave it on 
;  the queue, stop filling. Note, the demand is still on the queue, we only 
;  "tried" to fill it. No state changed .
(defn fill-category [demandstore category ctx & {:keys [stop-early?] 
                                                 :or   {stop-early? true}}]
  ;We use our UnfilledQ to quickly find unfilled demands. 
  (loop [pending   (find-eligible-demands demandstore category)   
         ctx       (trying-to-fill! demandstore category ctx)]
    (if (empty? pending) ctx ;no demands to fill!      
      (let [demand      (val (first pending))                    
            demandname  (:name demand)           ;try to fill the topmost demand
            startfill   (unit-count demand)
            ctx         (request-fill! demandstore category ctx)
            fill-result (fill/source-demand demand category ctx)             ;1) 
            stopfill    (unit-count (:demand fill-result))
            can-fill?   (demand-filled? (:demand fill-result))
            ctx         (if (= stopfill startfill)  ;UGLY 
                          ctx 
                          (->> (demand-fill-changed! demandstore demand ctx) ;2)
                            (sim/merge-updates               ;UGLY 
                              {:demandstore 
                                  (register-change demandstore demandname)})
                            (merge-fill-results fill-result)))]
        (if (and stop-early? (not can-fill?)) ;stop trying if we're told to...
          ctx                                                                ;3)
          ;otherwise, continue filling!
          (recur (pop-priority-map pending) ;advance to the next unfilled demand
                 (->> (sourced-demand! demandstore demand ctx)     ;notification 
                   (update-fill     demandstore demandname)   ;update unfilledQ.
                   (can-fill-demand! demandstore demandname))))))));notification

;NOTE...since categories are independent, we could use a parallel reducer here..
;filling all unfilled demands can be phrased in terms of fill-category...

;higher-order function for filling demands.
(defn fill-demands-with [ctx f]
  (reduce (fn [acc c] (f (core/get-demandstore acc) c acc))
      ctx (unfilled-categories (core/get-demandstore ctx))))

;implements the default hierarchal, stop-early fill scheme.
(defn fill-hierarchically [ctx] (fill-demands-with ctx fill-category))

;implements the try-to-fill-all-demands, using only follow-on-supply scheme.
(defn fill-followons [ctx]
  (if-let [groups (core/get-followon-keys ctx)] 
    (->> (fn [store category ctx] 
           (fill-category store [category groups] ctx :stop-early false))
      (fill-demands-with ctx))))

;Note -> we're just passing around a big fat map, we'll use destructuring in the 
;signatures to pull the args out from it...the signature of each func is 
;state->state

;Perform a prioritized fill of demands, heirarchically filling demands using 
;followon supply, then using the rest of the supply.
;TOM Note 20 May 2013 -> 't may not be necessary, since we can derive it from 
;context.  
(defn fill-demands [t ctx]
  (->> ctx
    (fill-followons ctx)
    (supply/release-max-utilizers) ;DECOUPLE, eliminate supply dependency...
    (fill-hierarchically)))

;Its purpose is to maintain a running list of demands (ActiveDemands dictionary)
;which will help us only fill active demands. How do demands get added to the 
;list? Upon initialization, we schedule their activation day and deactivation 
;day (start + duration). During the course of the simulation, we have a 
;listener that checks to see if the current day is a day of interest, 
;specifically if it's an activation day. 

;Note that this idea, partitioning our days into "days of interest", while 
;localized to handle demand activations and deactivations, could easily be 
;extended to a general "days of interest" manager, with subscribed handlers and 
;subroutines to run. In fact, this is exactly what an event step simulation 
;does. We;re just copping techniques and modifying them for use in a timestep 
;sim to make it more efficient.

(defn activations? [t demandstore] 
  (contains? (:activations demandstore) t))
(defn deactivations? [t demandstore] 
  (contains? (:deactivations demandstore) t))

(defn  get-activations  [demandstore t] (get (:activations demandstore) t))
(defn  get-deactivations [demandstore t] (get (:deactivations demandstore) t))

(defn activate-demand [demandstore t d ctx]
  (let [store (-> (assoc-in demandstore [:activedemands (:name d)] d)
                  (register-change (:name d)))]               
    (->> (activating-demand! store d t ctx)
      (update-fill store (:name d)))))    

;This could be refactored....REPETITIVE!
(defn activate-demands [t ctx]
  (let [demandstore (core/get-demandstore ctx)]
	  (reduce (fn [ctx dname] 
	            (let [store (core/get-demandstore ctx)]
	              (activate-demand store t (get-demand store dname) ctx))) 
	          ctx 
	          (get-activations demandstore))))

;CONTEXTUAL
;1) ASSUMES update-unit returns a context.
;2) ASSUMES change-state returns a context...
; ::unit->string->context->context 
(defn withdraw-unit [unit demand ctx]
  (let [demandgroup (:demandgroup demand)
        unitname    (:name unit)]
	  (cond 
	    (ungrouped? demandgroup) 
	      (->> (core/update-unit (set-followon unit demandgroup) ctx)          ;1)
	           (u/change-state unitname :AbruptWithdraw 0 nil))                ;2)      
	    (not (ghost? unit))
	      (u/change-state unitname :AbruptWithdraw 0 nil ctx)                  ;2) 
	    :else (->> (if (ghost? unit) (ghost-returned! demand unitname ctx) ctx)  
	            (u/change-state unitname :Reset 0 nil)))))                     ;2)

;This sub implements the state changes required to deactivate a demand, namely, 
;to send a unit back to reset. The cases it covers are times when a demand is 
;deactivated, and units are not expecting to overlap. If units are overlapping 
;at a newly-inactive demand, then they get sent home simultaneously. 
;TOM Change 14 Mar 2011 <- provide the ability to send home all units or one 
;unit...default is all units
;CONTEXTUAL
; :: float -> demand -> string -> context -> context
(defn send-home [t demand unit ctx] 
  (let [unitname  (:name unit)
        startloc  (:locationname unit)
        unit      (u/update unit (- t (sim/last-update unitname ctx))) ;WRONG?
        demandgroup (:demandgroup demand)]
    (->> (sim/trigger-event :supply-update :DemandStore unitname ;WRONG
              (str "Send Home Caused SupplyUpdate for " unitname) ctx) ;WRONG
         (withdraw-unit unit demand) 
         (disengaging! demand unitname)))) 


;there WILL be things happening to the ctx, possible mutations and such, that 
;we may need to carry forward (although we can discipline ourselves for now).

;change-state will have to return the unit that changed, as well as updated 
;supply, demand, etc, even context.  so change-state will have big changes...
;We need a way to dispatch based on the changes.
;Again, event handling could work....
;change-state is a high-level simulation transition function for either the 
;unit simulation, or the supply simulation.


;TEMPORARY.....until I figure out a better, cleaner solution.
;broke out uber function into a smaller pairing of disengagement functions, to 
;handle specific pieces of the contextual change.
;CONTEXTUAL
(defn- disengage-unit [demand demandstore unit ctx & [overlap]]
  (if overlap 
    (->> (overlapping! demandstore demand unit ctx)
         (d/send-overlap demand unit))
    (->> (send-home (sim/current-time ctx) demand unit ctx)
         (disengaging-home! demandstore demand unit))))

;This is also called independently from Overlapping_State.....
;Remove a unit from the demand.  Have the demand update its fill status.
;move the unit from the assigned units, to overlappingunits.
;CONTEXTUAL
(defn disengage [demandstore unit demandname ctx & [overlap]]
  (let [demand    (get-in demandstore [:demandmap demandname])
        nextstore (register-change demandstore demandname)
        ctx       (disengage-unit demand demandstore unit ctx overlap)]  
    (if (zero? (:required demand)) 
      (update-fill demandname (:unfilledq demandstore) demandstore ctx)
      ctx)))

(defn send-home-units [demand t ctx]
  (if (empty-demand? demand)
    (deactivating-empty-demand! demand t ctx)
    (->> (:units-assigned demand) ;otherwise send every unit home.
      (reduce (fn [ctx u] (send-home t demand u ctx)) ctx)
      (sim/merge-updates
        {:demandstore 
         (assoc-in (core/get-demandstore ctx) [:demandmap (:name demand)]
                   (assoc demand :units-assigned {}))}))))
  
(defn deactivate-demand [demandstore t d ctx]
  (assert (contains? (:activedemands demandstore) (:name d)) 
    (throw (Exception. (str "DeActivating an inactive demand: " (:name d))))) 
  (let [store (-> (update-in demandstore [:activedemands] dissoc (:name d))
                  (register-change (:name d)))]
    (->> (deactivating-demand! store d t ctx)
         (send-home-units d t)
         (update-fill store (:name d)))))    

;This could be refactored....REPETITIVE!
(defn deactivate-demands [t ctx]
  (let [demandstore (core/get-demandstore ctx)]
    (reduce (fn [ctx dname] 
              (let [store (core/get-demandstore ctx)]
                (deactivate-demand store t (get-demand store dname) ctx))) 
            ctx 
            (get-deactivations demandstore))))

;The big finale...
;CONTEXTUAL
(defn manage-demands [t ctx]
  (->> ctx
    (activate-demands t)
    (deactivate-demands t))) 