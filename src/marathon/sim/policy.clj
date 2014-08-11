;;This is the source of functionality for the policy system in Marathon.
;;Policies define ordered transitions that entities follow, and usually map 
;;directly to a state transition graph for a finite state machine.  Each 
;;entity may follow a unique policy, or participate in a global policy, or 
;;alternate as needed.  Some policies are scheduled to change at discrete times
;;in the simulation, with a sub policy assigned to various periods of simulation
;;time.  The policy system manages the definitions for a library of policies, 
;;tracks the current scheduled or conditional periods in the simulation, and 
;;handles system-wide policy changes for relevant policies.  

;;The primary functions contained herein surround the management of an abstract 
;;policy context in the simulation, which is embodied in a policystore.  Most 
;;of the functions here deal with creating policies, composing policies, 
;;registering policies with the policystore, registering simulation periods with 
;;the policystore, and managing policies and periods during the simulation.

(ns marathon.sim.policy
  (:require [marathon.data [protocols :as protocols] 
                           [period :as period]]
            [marathon.policy [policydata :as p] [policystore :as pstore]]
            [marathon.sim  [missing :as missing]    
                           [core :as core]    
                           [unit :as u]]            
            [spork.util [tags :as tag]]
            [spork.sim  [simcontext :as sim]]))

;----------TEMPORARILY ADDED for marathon.sim.demand!
(declare register-location atomic-name find-period)

;Missing, likely from marathon.policydata
;NOTE -> THERE IS A DUPLICATE FUNCTION IN sim.policy.policydata
(declare get-policy) 

;;__TODO__ Replace references to TimeStep_
;;__TODO__ Replace util.graph with either cljgraph or the topograph from cljgui.

;;##Policy Simulation Overview
;;Policies are the backbone of the entity behavioral system and provide a 
;;high level mechanism for parameterizing entity behavior.  Policies 
;;are the scripts that entities consult to determine where to go, how long to 
;;stay, and how to transition between states.  The other significant component
;;of the behavioral system are entity behaviors, which act as policy 
;;interpreters, and actually implement (or ignore) state transitions described 
;;by a unit's policy structure.  Behaviors are detailed in another namespace.


;#What is a policy context?
;Policy is vital because it determines the criteria for both the eligibility and 
;suitability of supply to fill demand.  A conservative policy may provide few 
;opportunities for units to fill demand, limiting the deployable supply 
;(presumably for some benefit like reduced costs), while a liberal policy may
;allow units to deploy almost any time, eliminating the previous limitations on 
;supply (likely at some cost).  Marathon generally examines policies across the 
;spectrum, from liberal to conservative. Policy contexts may not be homogenous 
;(applicable to every unit); there may be individual, custom policies for each
;unique unit entity.  Additionally, policy contexts may change in response to
;conditions in the simulation;  It may be appropriate to have a liberal policy
;when demand is low, presumably minimizing the cost to maintain a large 
;available supply, while shifting to a liberal policy during periods of higher
;demand.  In some cases, there are known strategic events that prompt the change
;of rotational policy, typically due to increased demand for units, which
;prompts a shift to a different, less constrained policy.  Conversely, after 
;demand has receded, the simulation typically tries to return to the 
;pre-existing policy context, or an intermediate policy that re-establishes 
;stability in the rotational supply.  All of this behavior is scriptable, and
;can be modified by changing policy data rather than rewiring logic.  As such,
;policies provide a simple, yet powerful scripting mechanism.  

;Marathon has historically maintained a notion of an overarching policy context
;throughout the simulation.  In fact, the simulation timeline is typically 
;viewed as a mapping of policy periods to segments of time.  Periods typically 
;serve to segment the timeline into epochs that are easily categorized, and may
;trigger changes in the policy context.  For instance, the most common period
;set is a sequential #{Pre-Surge, Surge, Post-Surge} set, where Pre-Surge covers
;the simulation time in which demand is "low", Surge is a particularly high 
;demand that occurs over a short time, and Post-Surge is the remainder of the 
;time, categorized as a lower demand but more intense than the Pre-Surge period.
;Technically, a timeline in Marathon can have any different number of periods,
;not just the common Pre/Surge/Post periods.  The ability to specify periods, 
;and then dictate policy changes in reponse to active periods, is a powerful 
;mechanism for parameterizing the simulation and managing varied policy 
;contexts.  Marathon is currently structured with one default timeline, which is
;composed of N contiguous periods.  As the periods change, any policies that 
;depend upon a period being active are engaged, and units following to these
;policies alter their behavior accordingly.  This is how Marathon manages a 
;policy context that can either be simple and uniform (i.e. one policy, one 
;period), or very unique (N different policies, K periods).  

;#What are policies?
;Policies describe a set of states and durations that -usually- conform to a
;rotational policy. Policies are very important, as they serve as the 
;instruction set for unit entity behavior.  

;Unit entities "act" on policies with their unit behavior, in which the meaning
;of the states in the policy is interpreted and acted upon.  Additionally, unit
;behaviors consult a policy to figure out what the next state should be, how 
;long the unit will stay in said state, etc.
;In the language of Finite State Machines, policies describe the states that a
;machine can be in, as well as the state transitions that occur. Typical states
;include Bogging, Dwelling, Waiting, Moving, StartCycle, EndCycle, Spawning, 
;etc.  All the policy has to do is specify which state a unit is in at a 
;particular policy position, specify a starting and stopping position, and 
;indicate the transition time between state changes.  Policies can have at most
;one cycle, and are typically represented as a Directed Graph.  

;Note -> Unit behaviors are typically implemented statically, as functions
;where policies are much specified as data.  

;Next to supply and demand, policy is the most variable higher-order data.  As 
;such, we need a robust, flexible way to specify different policies easily, so
;that rather than changing code, an end-user can simply modify the data that
;describes the policy (not unlike a script) and affect a change in unit entity 
;behavior easily and transparently.  

;One way to provide flexibility is to use a template system, where users can
;derive a policy from an existing template, and then apply a set of parameters
;to transform or "mold" the policy into a desired form.  Marathon currently has
;several built-in templates, and a language for specifying policy templates is 
;very near on the horizon.  Existing policy templates are located in the
;MarathonPolicyCreation and MarathonPolicy modules.  The PolicyCreation module
;serves as a central dispatch for invoking policy templates, applying
;modifications, and deriving new policies.  The MarathonPolicy module contains 
;the actual structure for each policy template, as well as routines that provide
;default policies for multiple contexts (rather than reading data).  

;Technically, anything that implements the IRotationPolicy protocol can serve
;as a policy, so developers can extend policy responsibilities to many different
;implementations. Marathon provides two implementations of IRotationPolicy, 
;which work in tandem to fulfill a composite design pattern.  The current 
;implementation, TimeStep_Policy, and TimeStep_PolicyComposite, provide a 
;flexible mechanism for defining policies and composing multiple policies 
;together.  They both implement the IRotationPolicy protocol, albeit 
;differently.  The key to their flexibility is that composite policies are 
;defined in terms of Atomic policies.  So users can, without changing any logic,
;add new policy definitions by supplying data that describes how to combine
;pre-existing policies.  

;#Atomic Policies
;Normal TimeStep_Policy objects are effectively Atomic policies, in that they
;represent a single set of instructions that describe a rotational policy.  They
;do not change.  Therefore, if a unit entity subscribes to such an atomic 
;policy, it will always follow the same set of instructions.
;
;#Composite Policies
;Composite policies represent an association of one or more Atomic policies.  
;This allows us to capitalize on the atomic policies, and express new policies
;as simple compositions of N atomic policies.  

;The current implementation of composite policies was built under the notion of
;a simple labeling or association of a set of sub policies, where a period label
;serves as an index to an associated atomic policy.  Technically, periods are 
;just strings or text and can be any valid value.  By convention, periods refer 
;to a particular epoch in the simulation, and serve as a way of labeling the 
;simulation timeline.  As a consequence, we can define composite policies as a
;composition of atomic policies, labelled by period, which will then 
;automatically change the governing policy as the simulation period changes.  
;Note, if the periods that the composite policy are labelled by never occur in 
;the simulation, then only the periods that intersect the simulation will ever 
;be used. This is useful for describing possible behaviors, where the periods
;correspond to corner-cases that may be triggered by external events.

;;##Implementation
;;#Defining Time Periods

;Utility function.  
(defn flip [f] (fn [x y] (f y x)))

(defn policy-update! [t ctx] 
  (sim/request-update t :PolicyManager :policy-update nil ctx))  

(defn add-period  "Registers a period data structure with the policystore."
  [policystore per] 
  (assoc-in policystore [:periods (:name per)] per))

(defn add-periods  "Import a sequence of periods into the policystore"  
  [periods policystore] 
  (reduce add-period policystore periods))

(defn added-period!
  "Notifies interested parties of the beginning and ending of a period.
   Schedules a policy update to coincide with the beginning and ending of the 
   period, so that policy management runs when a period changes."
  [per ctx]
  (sim/trigger-event :added-period (:name per) (:name per) 
               (str "Added period " per) nil ctx))

(defn schedule-period
  "Arranges for policy updates on the beginning and end of known periods."
  [per ctx]
  (->> ctx 
    (added-period! per)
    (policy-update! (:from-day per))
    (policy-update! (:to-day per))))

(defn get-periods [policystore] "Yield registered periods in the policystore." 
  (:periods policystore))

(defn get-period [policystore periodname] "Find a period in the policystore."
  (get (get-periods policystore) periodname))

(defn schedule-periods [policystore ctx] "Schedule multiple periods."
  (reduce (flip schedule-period) ctx (get-periods policystore))) 

(defn find-periods
  "Finds all periods in the policy store that intersect time t."
  [t policystore]
  (reduce-kv (fn [p pname per]
               (if (period/intersects-period? t per)
                 (conj p per)
                 p))
             []
             (get-periods policystore)))

(defn find-period 
  "Finds the first arbitrary period in the policy store that intersects time t."
  [t policystore]
  (first (find-periods t policystore))) 

;Returns the the period currently active in the policy store.  This may change 
;when I introduce multiple timelines....
(defn get-active-period [policystore] (:activeperiod policystore))

;;#Policy Location Queries
;;As we define policies, we parse their node labels to derive abstract policy 
;;locations.   

;;Defining abstract locations.  
(defn get-locations [policystore] (:locationmap policystore))

;This sub helps us to keep track of demand and policy locations.
;Conjoins a location to the set of known locations...
(defn register-location [locname policystore]
  (update-in policystore [:locationmap]  conj  locname)) 

;Register multiple locations in the locs collection with the policystore.
(defn register-locations [locs policystore] 
  (reduce (flip register-location) policystore locs))

;Register canonical ARFORGEN locations with the policystore.
(def default-locations [:available :ready :train :reset :deployed :overlapping])
(defn register-default-locations [policystore] 
  (register-locations default-locations policystore))  

;Derives locations from the policy.
;each location in a policy should be registered in the locations dictionary.
;__TODO__ Abstract out the call to graph, maybe have policy handle it...
(defn get-policy-locations [p] (protocols/get-locations p))

;__TODO__ formalize this...it's really general right now...
(defn composite-policy? [p] (contains? p :policies))
(defn atomic-policy?  [p]  (not (composite-policy? p)))

;Predicate to determine if a Rotation Policy is defined over a period.
(defn policy-defined? [period policy] 
  (or (atomic-policy? policy) (not (nil? (protocols/get-policy policy period))))) 

;Adds a composite policy to the policystore.  Special, because we keep track of 
;composite policies for special consideration during management of the policy 
;context.
;WEAK, but gets the job done...need a cleaner way to annotate composites.
(defn register-composite [p policystore]
  (if (composite-policy? p) 
    (assoc-in policystore [:composites (:name p)] p)
    policystore))

;method for adding atomic and composite policies to the policystore.
(defn add-policy [p policystore]
  (assert (not (nil? (:name p))) (str "Policy has no name " p))
  (->> (assoc-in policystore [:policies (:name p)] p)
       (register-composite p)
       (register-locations (get-policy-locations p))))

;adds a list of either atomic or composite policies to a policystore.
(defn add-policies [policies policystore]
  (reduce (flip add-policy) policystore policies)) 

;Shorthand for triggering a period change in the simulation context.
(defn period-change! [fromname toname ctx]
  (sim/trigger-event :periodChange :PolicyManager toname 
      (str "Period changed from " fromname " to " toname) nil ctx))

;Event notifying the need to update all units due to a change in the the period, 
;or epoch, of the simulation.
(defn period-driven-update! [fromname toname ctx]
  (sim/trigger-event :updateAllUnits :PolicyManager :PolicyManager 
     (str "Period change from " fromname 
          " to "  toname " caused all units to update.") toname ctx))


;;Acquisition means we have to find a value, or else we throw an
;;exception. We expect there to be a period associated. 
(defn acquire-period [policystore toname]
  (if-let [res (get-period policystore toname)]
    res
    (throw (Exception. (str "Period " toname " does not exist in the policystore!")))))

;;Swaps out the active period.  If the new period is the final period, then caps
;;the final period to the current day.
(defn update-period [day toname policystore]
  (->> (if (= toname :final) (period/->period :final day day) 
                             (acquire-period policystore toname))
       (assoc policystore :activeperiod)))

;wrapper for any tasks we need to perform in the final period.
(defn final-period [fromname toname ctx] 
  (period-driven-update! fromname toname ctx))

;;#Changing Policies in Response to Period Changes

;queue-policy-change could probably be in the unit level simulation.
;Note -> returns unit-updates....CONSUME WITH sim/merge-updates

(defn queue-policy-change
  "Queues a unit's status as having a pending policy change.  Right now, status 
   is maintained in unit data.  When the unit has an opportunity to change 
   policies, if it can't change immediately, it retains the policy change until
   the opportuntity arises."
  [unit newpolicy period ctx]
  (let [current-policy (protocols/get-active-policy (:policy unit))
        atomic-policy  (protocols/get-policy newpolicy period)
        unit           (u/change-policy atomic-policy ctx)]
    (if (= (:name (:policy unit)) (:name atomic-policy))
           {:unit-update (assoc unit :policy newpolicy)}
           {:unit-update (-> (assoc unit :policy current-policy)
                             (update-in  [:policystack] [newpolicy]))})))        

(defn update-policy [policystore p] 
  (assoc-in policystore [:policies (:name p)] p))


(defn alter-unit-policies
  "Affects a change in policy.  This is currently only caused when periods 
   change in a composite policy.  I'd really like to get more reactive behavior 
   involved."
  [subscribers period newpolicy ctx]
  (->> (map #(queue-policy-change %1 newpolicy period) subscribers) 
       (reduce #(sim/merge-updates %1 %2) ctx)))


(defn policy-name [p] (if (keyword? p) p (:name p)))

(defn get-subscribers [policy policystore] 
  (-> policystore 
      :subscriptions
      (policy-name policy)))

(defn update-subscribers 
  "Applies f to the subscriptions associated with policy in policystore, then
   stores the updated result, returning the policystore."
  [f policy policystore]
  (update-in policy-store [:subscriptions (policy-name policy)] f))
  
(defn change-policy
  "High level policy management.  For policies that are period-driven, enforces 
   a policy shift to the sub policy defined over the new period.  If the period
   is undefined, no change happens."
  [current-period new-period policy policystore ctx]
  (let [subscribers (get-subscribers policy policystore)
        new-policy  (protocols/on-period-change policy new-period)]
        (->> (alter-unit-policies subscribers new-period policy ctx)
             (sim/merge-updates 
               {:policystore (update-policy policystore new-policy)}))))

(defn subscribe-unit 
  "Subscribes a unit to policy by establishing a relation with the policy in
   the policy store.  Rather than storing subscriptions in the policy, we now
   keep them in the policystore."
  [unit policy policy-store]
  (let [old-policy (policy-name (:policy unit))
        new-policy (policy-name policy)
        nm         (:name unit)
        s          (:subscriptions policystore)
        oldsubs    (disj (get s old-policy)  nm)
        newsubs    (conj  (get s new-policy) nm)]
    (->> (-> s
             (assoc old-policy oldsubs)
             (assoc new-policy newsubs))
         (assoc policy-store :subscriptions))))

(defn unsubscribe-unit 
  "Subscribes a unit to policy by establishing a relation with the policy in
   the policy store.  Rather than storing subscriptions in the policy, we now
   keep them in the policystore."
  [unit policy policy-store]
  (let [old-policy (policy-name policy)
        nm         (:name unit)
        s          (:subscriptions policystore)
        oldsubs    (disj (get s old-policy)  nm)]
    (->> (-> s (assoc old-policy oldsubs))
         (assoc policy-store :subscriptions))))

;We define change in a composite policy by the existence of both the new period 
;and the old period in the composite policy.  Atomic policies are defined across 
;all periods.  So the only ones that should show up here are policies that 
;contained both the old and new, or current and new periods. 

;This function is a predicate that effectively filters out composite policies 
;that are undefined over both periods.

(defn get-changed-policies
  "Returns a filtered sequence of all the composite policies that have changed."
  [current-period new-period candidates]
  (if (= current-period :Initialization) nil
      (filter (fn [p] (and (policy-defined? current-period p)
                           (policy-defined? new-period p))) candidates)))

;Transcription Note -> in the original design, we delegated a lot of control 
;to each of the policies associated with the unit.  Now, we're going to treat
;the policies more like pure data, and record the policy associated with each
;entity.  When we change policies, we do it from the policystore in a \
;controlled fashion. 

;This routine informs subscribers of the need to try to change their policies
;at the next available opportunity.  Only happens for composite policies, when 
;a new, valid period has been engaged.

;Tom Change 12 July 2011
;tell each policy to have its subscriber change to a new policy.
;Simple algorithm -> fetch the new policy associated with the period.
;Tell each policy to change to the new policy.

(defn change-policies
  "High level system that propogates policy changes all the way down to entities 
   that participate in the affected policies.  Typically called in response to 
   period changes."
  [current-period new-period ctx]  
  (let [policystore (core/get-policystore ctx)]
    (if (= current-period :Initialization) ctx ;short-circuit 
        (->> (get-changed-policies current-period new-period
                                   (:composites policystore))
             (reduce #(change-policy current-period new-period %2 
                                     (core/get-policystore %1) %1) ctx)))))
  
(defn has-subscribers? [policy] (pos? (count (:subscribers policy)))

;;#Managing Policies and Periods
;This is the primary routine to manage policies for a policy store, which are 
;driven by period changes.  Many policies are defined over abstract periods, 
;so if periods are scheduled to change, they will propogate a change in entity
;policies.  One flaw with the existing design is that there is, assumably, a 
;single overarching period in effect.  A nice extension would be to allow 
;any number of concurrent periods, or time-lines, which would facilitate 
;sophisticated behaviors, or policy-states, for unique elements of the supply.
;TODO -> extend from a single-active period to multiple active periods.

(defn manage-policies
  "The policy system checks to see if we entered a new period, and changes 
   the governing policies to fit the new period.  High level entry point, 
   typically called by the simulation engine."
  ([day ctx toname]
     (let [policystore (core/get-policystore ctx)
           period      (:activeperiod policystore)
           fromname (:name period)]
       (if (= fromname toname) ctx
           (->> (if (= toname :final) (final-period fromname toname ctx) ctx)
                (sim/merge-updates {:policystore (update-period day toname policystore)})
                (period-change! fromname toname)
                (change-policies fromname toname)))))
  ([day ctx] (manage-policies day ctx 
                 (find-period day (core/get-policystore  ctx)))))

;Fetches a policy, by name, from the policystore.
;TODO -> add in a policy does not exist exception...
(defn get-policy [policy-name policystore] 
  (get-in policystore [:policies policy-name]))

;get all pending policy updates for time t.
(defn get-policy-updates [t ctx] (sim/get-updates :policy t ctx))

;MIGHT BE OBSOLETE
;clear all the locations from the policystore.
(defn clear-locations [policystore] (assoc policystore :locationmap #{}))

;TODO -> THIS IS WEAK, SHOULD CONSULT TAGS, REFACTOR.
;Simple predicate to determine if a location is actually a demand.
;Demands, upon creation, have their start and duration encoded as
;a textual range: [1..blah], so a predicate only needs to check for
;a left bracket.  This is somewhat brittle, and dependent upon the
;demand representation never changing, but it works for now and
;is relatively easy to patch.
(defn demand-location? [location-name] (= (first location-name) \[))

;;#Policy Construction and Composition

;Primitive wrapper for appending atomic policies to composite policies.
;TODO -> extend this to incorporate the semantics for generalized rotation 
;policies....specifically, allow composite policies to be defined over composite
;policies as the intersection of periods across the policies.
(defn append-composite [composite period atomic-policy] 
  (add-policy composite atomic-policy period))

;REDUNDANT
;This is the typical append operation.  When we compose an atomic policy with a 
;composite, we simply register the atomic as a sub policy under the key period.
(defn append-composite-atomic [composite period sub-policy]
  (add-policy composite sub-policy period)) 

;Currently, composite policies are built purely from atomic policies.  In 
;theory, one could compose a new policy from atomic OR composite policies.  
;This is currently not implemented or tested.

;REVISIT 
;Primitive constructore for composite policy.  To define a composite, we need at
;least one period and one atomic policy.
(defn create-composite [policyname period atomic-policy]
  (-> (assoc p/empty-composite-policy :name policyname) 
      (append-composite  period atomic-policy))) 

;TODO -> Add an existence check for the child policies...
;creates a composite policy from n policies defined in periodpolicymap 
;(a dictionary). periodpolicymap is assumed to be a map, where the keys are the 
;names of periods over which the associated policy values are defined.  Using a 
;dictionary/hashmap ensures that only unique values for period names are entered
;We only need a dictionary<string,string>, or ::string->string
(defn compose-policies [policyname period-policy-map child-policies]
  (reduce (fn [acc [period childname]] 
            (append-composite-atomic acc period (get child-policies childname)))
          (-> p/empty-composite-policy (assoc :name policyname)) 
          (seq period-policy-map)))

;TODO -> add existence checks for child policies.
;A constructor for building a sequential policy from a collection of policies. 
;The sequential policy will represent an ordered list of policies, drawn from 
;child policies.  At this point, only atomic policies may be members of a 
;sequence.
(defn sequence-policies [policyname names child-policies]
  (reduce (fn [acc child-name] (add-policy acc (get child-policies child-name)))
          (-> p/empty-composite-policy (assoc :name policyname)) names))


(defn sequential-policy? [p] (coll? p))

;Given a set of composite policy descriptions, and a set of child policies, 
;produces a list of composite policies derived from the compositions.
(defn compositions->composites [compositions child-policies]
  (let [policy-func #(if (sequential-policy? %) 
                         sequence-policies 
                         compose-policies)] 
    (reduce (fn [acc [policy-name p]]
              (conj acc ((policy-func p) policy-name p)))  [] compositions)))

(defn permanent-record? [r] (= (get r :Period) "Permanent"))
(defn equivalence-key [delim recepient donor] (keyword recepient delim donor))
;accesor for equivalency relations in a policystore
(defn get-equivalencies [policystore] 
  (get-in policystore [:rules :equivalencies]))
;accessor for substitution relations in a policystore
(defn get-subs [policystore] (get-in policystore [:rules :substitutions]))
;Adds an equivalence relationship to the policystore
(defn add-equivalence [recepient donor policystore]
  (let [delim (:ruledelim policystore)] 
    (assoc-in policystore 
        [:rules :equivalencies (equivalence-key delim recepient donor)] 0))) 
;Adds a substitution relationship to the policystore
(defn add-substitution [recepient donor cost policystore]
  (let [delim (:ruledelim policystore)]
    (assoc-in policystore 
        [:rules :substitutions (equivalence-key delim recepient donor)] cost)))
;determine if the policystore has a registered rule
(defn has-rule? [rule policystore] 
  (or (contains? (get-subs policystore) rule)
      (contains? (get-equivalencies policystore) rule)))
;Function to add relations to a policystore.  dispatches based on relation type.
(defn add-relation [policystore relation recepient donor & [cost]]
  (case relation 
    :equivalence (add-equivalence recepient donor policystore)   
    :sub      (add-equivalence recepient donor cost policystore)
    (throw (Exception. (str "unknown relation " relation)))))
;Assuming a list of (relation, recepient, donor, cost) entries, maps 
;add-relation to each entry.
(defn add-relations [relations policystore] 
  (reduce (fn [acc [x y z w]] (add-relation acc x y z w)) 
          policystore relations))
;Accessor function for the policies in the policystore
(defn get-policies [policystore] (:policies policystore))
;Get a list of the names of policies in the policy store.
(defn policy-names [policystore] (keys (get-policies policystore)))
;Get a policy associated with Pname, relative to the policystore.
(defn find-policy [pname policystore] 
  (-> (get-policies policystore) (get pname)))

;Return the set of policy graphs
(defn get-policy-graphs [policystore]
  (into {} (for [p (vals (get-policies policystore))]
             [(protocols/policy-name p) (protocols/position-graph p)])))


;;#Policystore Creation
;'TODO -> get this constructor back online.
;'Rewire this....
;'What we're really doing is building a policymanager from several sources...
;'relations::     list<(relation, recepient, donor, cost)>
;'periods::       list<genericperiod>
;'atomicpolicies::list<TimeStep_Policy>

(defn make-policystore 
  [relations periods atomic-policies & [composite-policies store]]
  (->> (or store pstore/empty-policystore)
       (add-relations relations)
       (add-periods periods)
       (add-policies atomic-policies)
       (add-policies composite-policies)))      

;'TOM Change 10 SEP 2012
(defn initialize-policystore [policystore ctx]
  (schedule-periods policystore ctx))

;Since composite policy loading is dependent on atomic policy loading, we 
;provide an auxillary function to ensure the order is correct, and specify 
;inputs. atomics is a map of policyname->Timestep_Policy, compositions is a map
;of policyname->(map of periodname->(either policyname or Timestep_Policy)
(defn add-dependent-policies [atomics compositions policystore]
  (->> policystore 
       (add-policies (vals atomics))
       (add-policies (compositions->composites compositions atomics))))

