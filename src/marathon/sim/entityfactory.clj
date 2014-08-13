
;;The entity factory is a port of legacy functions used to read 
;;entity information from tabular sets of data according to 
;;the marathon data schemas.  The factory's primary function is 
;;to project records onto entities (i.e. units and demands) 
;;as well as serve as a hub for registering entities and 
;;scheduling things like initial starting conditions.  
;;The entityfactory could probably be broken apart, 
;;but it's legacy functionality would have to go somewhere. 
;;For now, we'll keep it in a similar namespace, but as 
;;opportunities arise, we may be able to abastract out 
;;functionality.
(ns marathon.sim.entityfactory
  (:require [marathon        [schemas :as s]]
            [marathon.data.protocols :as generic] ;rename
            [marathon.demand [demanddata :as d]]
            [marathon.sim.demand :as demand]
            [marathon.sim.unit :as unitsim]
            [marathon.supply [unitdata :as u]]
            [marathon.sim.supply :as supply]
            [marathon.sim.policy :as plcy]
            [marathon.sim.engine :as engine]
            [spork.sim.simcontext :as sim]
            [marathon.sim.core :as core]
            [spork.util      [table :as tbl]]
            [clojure.core    [reducers :as r]]))


;;*Entity Orders
;;The entity factory used to be an object that maintained 
;;a reference to a parent simulation context.  This allowed 
;;it to interpret data, like  a set of demand 
;;records, into the act of creating demand entities, 
;;notifying the simulation context that the a new demand had been
;;registered, and registering the demand with the demandstore.
;;Most of this was accomplished via side effects, so creating, 
;;and loading entities, was fairly imperative and stateful.

;;Going forward, we're going to break up the distiction between
;;these responsibilities, and communicate the results via 
;;EntityOrders.  An EntityOrder is merely a record that 
;;the entity factory can intepret, given a context, to yield 
;;a new context.  In this case, the context is our stock 
;;simcontext.  Seen as an interpreter, our entityfactory 
;;evalutates orders relative to the simulation context, returning 
;;a new simulation context.  We define new kinds of EntityOrders 
;;to allow for different types of entities to be built.

;;In the scheme of our IO processes, this means we care about 
;;a lower layer of functions that read raw data - typically 
;;in the form of tab delimited text, and convert that data 
;;into some kind of EntityOrder that our factory can recognize.

;;Ideally, we end up with a simple interface for "Creating" entities
;;in the simulation, where entity creation may involve an arbitrarily 
;;complex sequence of updates.


;;*Basic Functions
;;Many of our functions are concerned with IO, that is, reading 
;;tab-delimited text records, and creating entity orders from them.

;;For now, we're using dynamic scope...
(def ^:dynamic *ctx* core/emptysim)


;;I think we may want to make the entityfactory functions operate 
;;on a dynamic var.  That should make the calling code clearer...
;;There are events where we trigger notifications and such, for 
;;logging purposes.  Maybe we we only check if the context is 
;;actually bound, and then do something.


;;The original procedure actually mutated the context 
;;as demand records were parsed.  We can decouple this step by 
;;creating a sequence of entity creation actions, i.e. entity 
;;orders, that can be processed into state changes.

;;For instance: 

(defn partition-dupes 
  "Returns a pair of [uniques, dupes] based on the key function"
  [keyfn xs]
  (let [known (java.util.HashSet.)
        res  (reduce (fn [acc x]              
                       (let [uniques (first acc)
                             dupes   (second acc)
                             k (keyfn x)]
                         (if (.contains known k)
                           [uniques (assoc! dupes k (cons x (get dupes k nil)))]
                           (do (.add known k)
                               [(conj uniques x) dupes]))))
                     [[]  (transient {})]
                     xs)]
    [(first res) (persistent! (second res))]))

(defn valid-record? 
  ([r params] 
     (and (:Enabled r) 
          (core/in-scope? params (:SRC r))))
  ([r] (valid-record? r (core/get-parameters *ctx*))))

(defn demand-key 
  ([{:keys [SRC Vignette Operation Priority StartDay Duration]}]
     (demand-key SRC Vignette Operation Priority StartDay Duration))
  ([SRC Vignette Operation Priority StartDay Duration] 
     (clojure.string/join "" [Priority "_"  Vignette "_" SRC "["  StartDay "..."  (+ StartDay Duration) "]"])))

;;Could inline for speed, may be unnecessary...
(defn create-demand   
  "Produces a validated demand from the inputs.  We enforce invariants about 
   demanddata here to ensure that invalid values are caught and excepted."
  [DemandKey SRC  Priority StartDay Duration Overlap Category 
   SourceFirst Quantity  OITitle Vignette Operation  DemandGroup]  
  (let [empty-op  (core/empty-string? Operation)
        empty-vig (core/empty-string? Vignette)
        idx (if (or empty-op empty-vig) (core/next-idx) 0)]

    (d/->demanddata    ;unique name associated with the demand entity.
     (or DemandKey (demand-key SRC Vignette Operation Priority StartDay Duration)) 
     SRC ;demand-type, or other identifier of the capability demanded.
     Priority ;numerical value representing the relative fill priority.
     StartDay ;the day upon which the demand is activated and requiring fill.
     Duration ;the total time the demand is activated.
     Overlap  ;the demand-specific overlap requirement, if any
     Category ;descriptor for deployed unit behavior over-rides.
     SourceFirst  ;descriptor for supply preference. 
     Quantity  ;the total amount of entities required to fill the demand.
     OITitle   ;formerly OITitle.  Long-form description of the src capability.
     (if empty-vig (str "Anonymous" idx) Vignette)  ;Descriptor of the force list that generated this demand entity.
     (if empty-op  (str "Anonymous" idx) Operation) ;Fine-grained, unique description of the demand.
     DemandGroup ;Ket that associates a demand with other linked demands.  
     {} ;an ordered collection of all the fills recieved over time.                   
     {} ;map of the units currently associated with the demand.
     {} ;map of the units currently associated with this demand,
                                        ;that are not actively contributing toward filling the
                                        ;demand, due to a relief-in-place state.
      )))

(defn record->demand 
  "Basic io function for converting raw records to demanddata."
  [{:keys [DemandKey SRC  Priority StartDay Duration Overlap Category 
           SourceFirst Quantity  OITitle Vignette Operation  DemandGroup ] :as rec}]
  (create-demand DemandKey SRC  Priority StartDay Duration Overlap Category 
                 SourceFirst (if (pos? Quantity) Quantity 1) OITitle Vignette Operation  DemandGroup))

;;Returns a set of demand data, derived from recs, with 
;;duplicate records attached as meta data.
(defn demands-from-records [recs ctx]  
  (let [[uniques dupes]  
            (->> recs 
                 (r/filter #(valid-record? % (core/get-parameters ctx)))
                 (partition-dupes demand-key))]
    (with-meta (mapv record->demand uniques)
      {:duplicates dupes})))

;;Broadcast the fact that we have duplicates.
(defn notify-duplicate-demands! [dupes ctx]
  (let [name (:name (core/get-demandstore *ctx*))]
    (reduce (fn [ctx dup]
              (sim/trigger-event :Initialize name name 
                     (str "Demand " (:DemandKey dup) " had duplicates in source data.") nil ctx))
            ctx
            dupes)))

;broadcast that a demand with initialized.
(defn initialized-demand! [ctx d]
  (let [msg (str (str "Demand" (:name d)) "Initialized")]
    (sim/trigger-event :Intialize :DemandStore :DemandStore msg nil ctx)))
  

;;Updates the demand index according to the contents of 
;;demandmanager, providing unique names for duplicate 
;;demands.  Threads the demand through the context 
;;and returns the resulting context.
(defn associate-demand [ctx demand]
  (core/with-simstate [[parameters demandstore policystore] ctx]    
    (let [demands      (:demandmap demandstore)
          demand-count (count demands)
          new-idx (+ (or (:demandstart parameters) 0) demand-count)]
      (-> (if (contains? demands (:name demand))
            (assoc demand :name 
                   (str (:name demand) "_" (inc demand-count)))
            demand)                     
          (assoc :index new-idx)
          (demand/register-demand demandstore policystore ctx)))))

;;Do we need the vestigial demandstore args, or can we assume it's 
;;all in context?
(defn demands-from-table 
  "Read in multiple demand records from anything implementing a generic 
   table protocol in spork.util.table"
  [demand-table demandstore ctx]
  (let [rs (demands-from-records (tbl/record-seq demand-table))
        dupes (get (meta rs) :duplicates)
        ctx   (assoc-in ctx [:state :demandstore] demandstore)]
      (->> (reduce (fn [acc d] 
                     (initialized-demand! (associate-demand acc d) d))
                   ctx
                   rs)
           (notify-duplicate-demands! dupes))))


(defn ungrouped? [grp] 
  (when grp 
      (or (core/empty-string? grp) 
          (= (clojure.string/upper-case grp) "UNGROUPED"))))


;;#Unit Entity Creation
;;Interpreting unit entities typically requires four steps:
;;Create the basic information about the unit
;;Assigning the unit a policy
;;Associating the unit with a supply (ensuring name compatibility)
;;Initializing the unit's cycle
;;Registering the unit with supply (inserting the entity into a supply store)


;;consider changing these to keywords.
;;We can probably push this off to a policy default table.  It used
;;to mean that we had this interpreted into policy objects, all
;;initialized in a singleton class at runtime initiaion.  Now that
;;it's pulled out of the class, we can probably interpret it easier 
;;and then use a policy parser to point at the correct policies in 
;;the policystore.
(def ^:constant +policy-defaults+ 
  {"Ghost" {:special "SpecialGhostPolicy"
            :default "DefaultGhostPolicy"}
   "AC"    {:special "SpecialACPolicy"
            :default "DefaultACPolicy"}          
   "RC"    {:special "SpecialRCPolicy"
            :default "DefaultRCPolicy"}})

;;Note -> we're not mutating anything here.  We can pass in a 
;;parameters.
(defn choose-policy 
  "Tries to fetch an associated policy, and upon failing, selects a 
   default policy based on the component/src and whether the SRC is 
   tagged as being special."
  [policyname component policystore parameters src]
  (let [policy-type  (if (or (core/empty-string? src) 
                             (not (core/special-src? (:tags parameters) src)))
                       :default
                       :special)]
    (if-let [p (get-in policystore [:policies policyname])]
      p 
      (if-let [res (get-in +policy-defaults+ [component policy-type])]
        res
        (throw (Exception. (str "Default Policy is set at "  
                                policyname  " which does not exist!")))))))

(defn assign-policy [unit policystore params]
  (assoc unit :policy 
     (choose-policy (:policy unit) (:component unit) policystore params (:src unit))))

;;Given a unit, initialize it's currentcycle based on policy information

;;Units maintain direct references to policies, but they do not
;;actively subscribe directly to the policy.  Rather, the policy 
;;store maintains a map of {policyname #{subscribers}}, we 
;;can even do this via tags in the tagbase, but it's already 
;;there in the policy store.  That way, when we invoke the 
;;policy subscription service, we should have an couple of updates: 
;;the unit now has a reference to the policy it subscribes to (
;;to make unit querying and updating easier), and the policystore 
;;has an association between the unit and the policy its 
;;subscribed to.

;;I think this guy should be elevated.
;;This really needs to happen in the context of the full sim, since
;;it's touching on several areas at once: policy, event-notification, supply.
;;We need access to multiple spheres of influence.  We used to just
;;mutate away and let the changes propgate via effects.  No mas.
;;Returns a policystore update and a unit update.
(defn initialize-cycle 
  "Given a unit's policy, subscribes the unit with said policy, 
   updates the unit's state to initial conditions, broadcasts 
   any movement via event triggers, returning the 
   new unit and a new policystore."
  [unit policy ghost ctx]
  (let [newpos (if (not ghost) 
                 (generic/get-position policy (:cycletime unit))
                 "Spawning")] 
    (-> unit 
        (assoc :policy policy)
        (assoc :positionpolicy newpos)
        (assoc :locationname "Spawning")  
        (unitsim/change-location newpos ctx))))

(def ^:constant +max-cycle-length+     10000)
(def ^:constant +default-cycle-length+ 1095)

;;Computes the intervals between units distributed along a lifecylce.
;;Used to uniformly disperse units in a deterministic fashion.
(defn compute-interval [clength unitcount]
  (if (or (zero? clength) (zero? unitcount)) 
    (throw (Error.  "Cannot have zero length cycle or 0 units"))
    (if (< unitcount clength) 
      (quot clength unitcount)
      (quot clength (dec clength)))))

;;take the unit seq and distribute the units evenly. Pass in a
;;collection of unit names, as well as the appropriate counts, and the
;;cycles are uniformly distributed (using integer division).
(defn distribute-cycle-times [units policy]
  (let [clength (generic/cycle-length policy)
        clength (if (> clength +max-cycle-length+) +default-cycle-length+)
        uniform-interval (atom (compute-interval clength (count units)))
        last-interval (atom (- uniform-interval))
        remaining     (atom (count units))
        next-interval (fn [] (let [nxt (long (+ @last-interval @uniform-interval))
                                   nxt (if (> nxt clength) 0 nxt)]
                               (do (when (< @remaining clength)
                                     (reset! @uniform-interval 
                                             (compute-interval clength  @remaining)))
                                   (reset! last-interval nxt)
                                   (swap! remaining dec)
                                   nxt)))]                                              
    (reduce (fn [acc  unit]
                 (let [cycletime (next-interval)
                       unit      (assoc unit :cycletime cycletime)]
                   (do (assert  (pos? cycletime) "Negative cycle time during distribution.")
                       (conj acc unit))))
           []
           units)))

;;create-unit provides a baseline, unattached unit derived from a set of data.
;;The unit is considered unattached because it is not registered with a supply "yet".  Thus, its parent is
;;nothing. parametrically create a new unit.

(defn create-unit [name src oititle component cycletime policy behavior]
  (u/->unitdata
   name ;unit entity's unique name. corresponds to a UIC 
   src ;unit entity's type, or capability it can supply.
   component ;unit entity's membership in supply.
   policy  ;the policy the entity is currently following.
   [] ;a stack of any pending policy changes.
   behavior ;the behavior the unit uses to interpret policy and messages.
   nil ;generic state data for the unit's finite state machine.
   cycletime ;the unit's current coordinate in lifecycle space.
   nil       ;description of the categories this unit serve as a followon to.
   :spawning ;the current physical location of the unit.
   :spawning ;the current position of the unit in its policy space.
    ;the current cycle data structure for the unit.
   [] ;an ordered collection of the cycles that the unit has completed.
   -1 ;the time in which the unit spawned.
   oititle ;the description of the unit.
   [] ;list of all the locations visited.
   0  ;dwell time before deployment
   ))

;;Derives a default unit from a record that describes unitdata.
;;Vestigial policy objects and behavior fields are not defined.  We
;;may allow different behaviors in the future, but for now they are
;;determined at runtime via the legacy processes (by component).
(defn record->unitdata [{:keys [Name SRC OITitle Component CycleTime Policy]}]
    (create-unit  Name SRC OITitle Component CycleTime Policy :default))

(defn generate-name 
  "Generates a conventional name for a unit, given an index."
  ([idx unit]
     (str idx "_" (:SRC unit) "_" (:component unit)))
  ([idx src compo]
     (str idx "_" src "_" compo)))

(defn check-name 
  "Ensures the unit is uniquely named, unless non-strictness rules are 
   applied."
  [name supply strictname]  
    (if (supply/get-unit name supply)
      (if strictname 
        (throw (Error. (str  "A unit already exists with the name " 
                             name " in SupplyManager " (:name supply)  
                             ", unit names must be unique")))
        (str name (count (:unit-map supply))))
      name))
        
;;This does not actually attach the unit inside the supplystore, only 
;;preps the unit's name.  It is associated, in the legacy sense that
;;the unit would gain a "pointer" to the parent supply, as well as
;;having its name preconditioned to ensure compatibility or catch 
;;errors.  In the newer version, we may consider composing this with 
;;marathon.sim.supply/register-unit , which actually attaches the 
;;unit to the supply store.  Note: we could handle parent-child 
;;assocation there as well...The unique functionality here is that 
;;we are either generating or altering the name of the unit to ensure 
;;it is unique in the supply.
(defn associate-unit   
  "Associate or attach a new unit to a particular supply. If the new unit's name is 
  not unique, it will be changed to accomodate uniqueness requirement."
  [unit supply strictname]
  (assoc unit :name (check-name (:name unit) supply strictname)))
        

;;Note -  register-unit is the other primary thing here.  It currently 
;;resides in marathon.sim.supply/register-unit 

;;This is just a stub.  We currently hardwire behaviors, 
;;but will allow more extension in the future.  The legacy 
;;behavior system required an inherited object model implementation, 
;;but now we don't have to worry about that.  We'll probably just 
;;have a map of functions, or types that can fulfill the unit behavior
;;protocol.
(defn get-default-behavior [supply] :default)

;;Adds multiple units according to a template.
;;Associates each unit with a

;;Breaking apart add-units into three discrete steps: 
;;1) create n units, with empty cycles, according to the demand
;;   record
;;2) push that batch of units through cycle distribution. 
;;3) Prep
;;4) Register


;; ;(if ghost (-> supply :behaviors :defaultGhostBehavior)
;; ;                            (-> supply :behaviors
;; ;                            :defaultACBehavior))

;;We can extract the supply dependencies, and the ghost arg, 
;;if we provide the behavior to be used.
(defn create-units [amount src oititle compo policy idx behavior]
  (let [bound     (+ idx (quot amount 1))]
    (if (pos? amount)
        (loop [idx idx
               acc []]
          (if (== idx bound)  
            (distribute-cycle-times acc policy)
            (let [nm       (generate-name idx  src  compo)
                  new-unit (create-unit nm src oititle compo 0 
                                        policy behavior policy)]                
              (recur (unchecked-inc idx)
                     (conj acc new-unit))))))))

;;Given a set of raw unit records, create a set of unitdata that has
;;all the information necessary for initialization, i.e. lifecycle, 
;;policy, behavior, name, etc.  We want to name the units according 
;;to the order in which they were generated, so we provide a
;;supplystore to derive the next index from.  We'll pass the output
;;from this onto a function that prepares the units.  From there 
;;we do all the minute prepatory tasks to "fill in the details", 
;;like associating the units with a supply, establishing unit 
;;subscriptions to policies, etc..
(defn units-from-records [recs supply]
  (let [unit-count (atom (-> supply :unitmap (count)))
        ghost-beh  (-> supply :behaviors :defaultGhostBehavior)
        normal-beh (-> supply :behaviors :defaultACBehavior)
        conj-units (fn [acc xs] (do (swap! unit-count + (count xs))
                                    (into acc xs)))
        conj-unit  (fn [acc x] (do (swap! unit-count inc)
                                   (conj acc x)))]
    (->> recs 
         (r/filter valid-record?)
         (reduce (fn [acc r]                    
                   (if (> (:Quantity r) 1) 
                     (conj-units acc 
                       (create-units (:Quantity r) 
                                     (:SRC r) 
                                     (:OITitle r) 
                                     (:Component r)  
                                     (:Policy r) @unit-count normal-beh))
                     (conj-unit  acc (record->unitdata r)))) []))))
        
;;we have two methods of initializing unit cycles.
;;one is on a case-by-case basis, when we use create-unit
 

 ;; 435:   Public Function AddUnits(amount As Long, src As String, OITitle As String, _
 ;; 436:                               compo As String, policy As IRotationPolicy, _
 ;; 437:                                       supply As TimeStep_ManagerOfSupply, _
 ;; 438:                                               Optional extratags As Dictionary, _
 ;; 439:                                                   Optional ghost As Boolean) As Dictionary
 ;; 440:   Dim i As Long
 ;; 441:   Dim NewUnit As TimeStep_UnitData
 ;; 442:   Dim generated As Dictionary
 ;; 443:   Dim defbehavior As IUnitBehavior
 ;; 444:   Dim DeployableBuckets As Dictionary
 ;; 445:   Dim count As Long
 ;; 446:   Dim nm
 ;; 447:  
 ;; 448:  'TOM Note -> these need to be decoupled, they're dependent on supply...
 ;; 449:   If Not ghost Then
 ;; 450:       Set defbehavior = supply.behaviors.defaultACBehavior
 ;; 451:   Else
 ;; 452:       Set defbehavior = supply.behaviors.defaultGhostBehavior
 ;; 453:   End If

 ;; 457:   Set AddUnits = supply.unitmap 'Note this is in here for legacy reasons.  Return value is used.
 ;; 458:  
 ;; 459:   If amount > 0 Then
 ;; 460:       Set generated = New Dictionary
 ;; 461:       count = supply.unitmap.count + 1
 ;; 462:       For i = 1 To Fix(amount)
 ;; 463:           Set NewUnit = New TimeStep_UnitData
 ;; 464:           With NewUnit
 ;; 465:               Set .behavior = defbehavior
 ;; 466:              'Decouple
 ;; 467:               Set .parent = supply
 ;; 468:               .src = src
 ;; 469:               .OITitle = OITitle
 ;; 470:               .component = compo
 ;; 471:               .name = count & "_" & .src & "_" & .component
 ;; 472:               .index = count
 ;; 475:               
 ;; 476:              'I don't think we need this.
 ;; 477:              'TOM Change 18 Sep 2012
 ;; 478:              'initialize_cycle NewUnit, policy, ghost
 ;; 479:               If ghost Then
 ;; 480:                   .PositionPolicy = .policy.getPosition(.cycletime)'Tom Change 20 May 2011
 ;; 481:                   .changeLocation .PositionPolicy, state.context
 ;; 482:                  '.LocationName = .PositionPolicy 'Tom Change 20 May 2011
 ;; 483:                  'Decouple
 ;; 484:                   .location = state.policystore.locationID(.LocationName)
 ;; 485:               End If
 ;; 486:           End With
 ;; 487:           
 ;; 488:           
 ;; 489:           generated.add NewUnit.name, NewUnit
 ;; 490:  
 ;; 491:           Set NewUnit = Nothing
 ;; 492:           count = count + 1
 ;; 493:  
 ;; 494:       Next i
 ;; 495:       
 ;; 496:       If Not ghost Then distributeCycleTimeLocations generated, policy, supply
 ;; 497:       
 ;; 498:       For Each nm In generated
 ;; 499:  '        supply.registerUnit generated(nm)  'this handles all the registration items, refactoring.
 ;; 500:           MarathonOpSupply.registerUnit state.supplystore, state.behaviormanager, generated(nm), ghost, state.context
 ;; 501:       Next nm
 ;; 502:   End If
 ;; 503:  
 ;; 504:   End Function

(defn prep-cycle [unit ctx]
  (initialize-cycle (:policy unit) (core/ghost? unit) ctx))

(defn prep-unit 
  "Given a raw-unit, ensures its name is good with the supplystore, 
   assigns a policy (typically read from the existing policy field) 
   and initializes its cycle relative to the current cycletime."
  [unit supplystore policystore parameters ctx]
  (-> unit       
      (associate-unit supplystore true)  ;;may move this into supply/register-unit...
      (assign-policy policystore parameters)      
      (prep-cycle ctx)))

;;All we need to do is eat a unit, returning the updated context.
;;A raw unit is a unitdata that is freshly parsed, via create-unit.
(defn process-unit [raw-unit extra-tags parameters behaviors ctx]
  (core/with-simstate [[supplystore policystore] ctx] ;could get expensive 
    (let [prepped   (-> raw-unit       
                        (associate-unit supplystore true)
                        (assign-policy policystore parameters)      
                        (prep-cycle))]
      (-> (supply/register-unit supplystore behaviors prepped nil extra-tags ctx)
          (core/set-policystore 
           (plcy/subscribe-unit prepped (:policy prepped) prepped policystore))))))  

;;At the highest level, we have to thread a supply around 
;;and modify it, as well as a policystore.
(defn process-units [raw-units ctx]
  (core/with-simstate [[parameters] ctx]
    (reduce (fn [acc unit]
                (process-unit unit nil parameters  nil acc))
            ctx 
            raw-units)))

;;Flesh this out, high level API for adding units to supply.  
;;Convenience function.
;;We really want to add prepped units.


;; (defn add-units [amount src oititle compo policy supply ghost ctx]
;;   (if (== amount 1))

;;#Entity Initialization        
(defn start-state [supply ctx]
  (core/with-simstate [[parameters] ctx]
    (let [ctx  (assoc-in ctx [:state :parameters] 
                   (assoc parameters :TotalUnits (count (:unitmap supply))))]
      (reduce-kv (fn [acc nm unit] (unitsim/change-state unit :Spawning 0 nil ctx))
                 ctx 
                 (:unitmap supply)))))





(comment ;testing
  (require '[marathon.sim.sampledata :as sd])
  (def testctx (assoc-in *ctx* [:state :parameters :SRCs-In-Scope] {"SRC1" true "SRC2" true "SRC3" true}))
)
