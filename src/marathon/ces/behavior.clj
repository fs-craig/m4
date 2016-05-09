;;A namespace for simple entity behaviors.
;;We'll define core behaviors here.
(ns marathon.ces.behavior
  (:require [spork.ai.core :as ai :refer
             [deref! fget fassoc  push-message- map->entityctx debug ->msg]]
            [spork.ai.behavior 
             :refer [beval
                     success?
                     success
                     run
                     fail
                     behave
                     ->seq
                     ->elapse
                     ->not
                     ->do
                     ->alter
                     ->elapse-until
                     ->leaf
                     ->wait-until
                     ->if
                     ->and
                     ->and!
                     ->pred
                     ->or
                     ->bnode
                     ->while
                     ->reduce
                     always-succeed
                     always-fail
                     bind!
                     bind!!
                     merge!
                     merge!!
                     push!
                     return!
                     val!
                     befn
                     ] :as b]
            [marathon.data [fsm :as fsm]
                           [protocols :as protocols]
             ]
            [marathon.ces [basebehavior :as base :refer :all]
                          [core :as core]
                          [unit :as u]
                          [supply :as supply]
                          [demand :as d]
             ]
            
            [spork.util.general     :as gen]        
            [spork.data.priorityq   :as pq]
            [clojure.core.reducers  :as r]
            [spork.entitysystem.store :as store :refer :all :exclude [default]]
            [spork.sim.simcontext :as sim]
            [clojure.core.reducers :as r]
            )
  (:import [marathon.ces.basebehavior behaviorenv]))

;;__utils__

(defmacro try-get [m k & else]
  `(if-let [res# (get ~m ~k)]
     res# 
     ~@else))

(defn rconcat
  ([& colls]
   (reify clojure.core.protocols/CollReduce
     (coll-reduce [this f1]
       (let [c1   (first colls)
             init (reduce (fn [acc x] (reduced x)) (r/take 1 c1))
             a0   (reduce f1 init (r/drop 1 c1))]
         (if (reduced? a0) @a0
             (reduce (fn [acc coll]
                       (reduce (fn [acc x]
                                 (f1 acc x)) acc coll)) a0 (r/drop 1 colls)))))
     (coll-reduce [this f init]
       (reduce (fn [acc coll]
                (reduce (fn [acc x]
                          (f acc x)) acc coll)) init colls))
     clojure.lang.ISeq
     (seq [this] (seq (into [] (r/mapcat identity colls) )))
     )))

(defn pass 
  [msg ctx]  
  (->> (success ctx)
       (core/debug-print [:passing msg])))

(def ^:dynamic *interact* false)

(defmacro if-y [expr & else]
  `(if ~'*interact* (if (and  (= (clojure.string/upper-case (read)) "Y"))
                      ~expr 
                      ~@else)
       ~expr))

(defmacro log! [msg ctx]
  `(do (println ~msg)
       ~ctx))

(defn echo [msg]
  (fn [ctx] (do (debug msg) (success ctx))))

;;an alternative idea here...
;;use a closure to do all this stuff, and reify to give us implementations
;;for the object.  We can also just us a mutable hashmap behind the
;;scene if we want to...at some point, it's probably better to have
;;the shared-nothing approach and just leave entities in their
;;own mutable cells, isolated from other state.  We can
;;still maintain persistent history.  Everything becomes a lookup though;
;;we have to find the current value of the entity at time t;
;;More to think of here..

;;New
;;Environment for evaluating entity behaviors, adapted for use with the simcontext.
;;If we provide an address, the entity is pushed there.  So, we can have nested
;;updates inside associative structures.


;;__Utility functions__
;;Entity step operations...
(defn progress-cycle [x width]
  (if (>= x width)
    0
    (unchecked-inc x)))

;;testing function...
(defn deployment-over?
  [y]
  (or (>= y (* 15 31))
      (and (>= y 30) (<= (rand) 0.01))))

;;testing function...
(defn should-deploy? [t tmax]
  (and (>= t 365)
       (<= (rand) (* 0.005 (/ (double t) tmax)))))

(defn deployed? [e] (identical? (:state e) :deploying))
(defn should-reset? [t tmax] (>= t tmax))
(defn spawning?     [^marathon.data.fsm.statedata statedata]
  (identical?  (.curstate statedata) :spawning))

;;aux functions will most likely be plentiful.  We specifically
;;have a host of helper functions for unit-specific entity behaviors.
;;Most of them deal with how the units read their policies and stuff.
;;__Aux Functions__

;;#TODO   See if we can encode or derive a more meaningful semantics
;;from the indices currently associated with the states...for
;;instance, :deployable randomly came back with 7 as a state, we
;;either don't want this or we want to have it mean something.
;;Note: these are specific to unit, so could probably go into the unit
;;namespace; save on real estate.
(defn get-state [unit position] 
  (let [s (protocols/get-state (:policy unit) position)]
    (if (number? s)  :dwelling s)))
(defn get-next-position [unit position]
  (protocols/next-position (:policy unit) position))

(defn policy-wait-time [policy statedata position deltat]
  (let [frompos  (protocols/next-position policy position)
        topos    (protocols/next-position policy frompos)
        t (protocols/transfer-time policy frompos topos)
        ]
    (- t (- deltat (fsm/remaining statedata)))))

;;Could be a cleaner way to unpack our data, but this is it for now...
;;need to fix this...let's see where we use it.
(defn get-wait-time
  ([unit frompos topos {:keys [deltat statedata] :as benv}]
     (let [wt (protocols/transfer-time (:policy unit) frompos topos)
           deltat (or  deltat 0) ;allow the ctx to override us...
           ]
       (- wt (fsm/remaining statedata))))
  ([unit position {:keys [deltat statedata] :as benv}]
   (policy-wait-time (:policy unit) statedata position (or deltat 0)))
  ([position {:keys [entity] :as benv}] (get-wait-time @entity position benv))
  ([{:keys [wait-time] :as benv}] wait-time))

;;Basic API
;;=========
;;The rest of the simulation still relies on our pre-existing API, 
;;namely that we have "change-state", and "update"

;;note that change-state already exists in marathon.sim.unit/change-state, 
;;we're merely providing an interface to the unit's behavior for it.
;;Also note that change-state is only called (currently) from
;;marathon.sim.demand (for abrupt withdraws), and marathon.sim.supply
;;(for deployments).

;;might ditch these....
(declare change-state-beh update-state-beh update-state 
         roll-forward-beh
         check-overlap
         check-deployable
         finish-cycle
         spawning-beh
;         age-unit
         moving-beh
         )

;;API
;;===
;;These are the entry points that will be called from the outside.
;;Under the legacy implementation, they delegated to a hard coded
;;finite state machine that interpreted rotational policy to infer
;;state transitions.  The general mechanism is to augment the
;;simulation context.  We may want to define a single function
;;load-context and unload-context the clears up any augmented
;;contextual items we put in.  That, or manage the simulation
;;context separate from the behavior context.  For now, managing
;;the simcontext along with the behavior context (treating it
;;as a huge blackboard) seems like the simplest thing to do.

;;__update-entity!__
;;Similarly, we'll have update take the context last.
;;update will depend on change-state-beh, but not change-state.
;;change-state is a higher-level api for changing things.
;;Note: this is covered by step-entity!  We need to
;;include the roll-forward-beh though, to ensure we're
;;consistent.

;;we can wrap these up and just pass a generic message for the
;;behavior to interpret.
;;change-state becomes
;;load-entity
;;add-message {:to-state to-state :deltat deltat :ctx ctx}


;;Move this out to marathon.ces.unit?
;;auxillary function that helps us wrap updates to the unit.
(defn traverse-unit [u t from to]   
  (-> u 
      (assoc :positionpolicy  to)
      (u/add-traversal t from to)))

;;this is kinda weak, we used to use it to determine when not to
;;perform updates via the global state, but it's probably less
;;important now...we can actually codify this structurally
;;in the behavior tree now...
;;special states just diverted the fsm update function to
;;a different path (bypassing the global state, i.e. not
;;aging/advancing).  Where we had direct method calls to
;;other state handler functions, we can now just directly
;;encode the transition in the tree...
(defn special-state? [s] (or (identical? s :spawning)
                             (identical? s :abrupt-withdraw)))

(defn just-spawned?
  "Determines if the entity recently spawned, indicated by a default
   negative spawn time or a spawntime in the present."
  [{:keys [entity ctx] :as benv}]
  (identical? (:state @entity) :spawning))
;  (let [st (:spawntime @entity)]
;    (or (neg? st)
;        (==  st    (core/get-time @ctx))))
  

(defn state-expired? [{:keys [deltat statedata] :as benv}] 
  (let [r (fsm/remaining statedata)
        dt (or deltat 0)
        ]
    (<=  r dt)))
       
;;debatable utility...
;;Not sure where we're using these guys....
(defn to-position?   [to   benv]  (identical? (:next-position benv)  to))
(defn from-position? [from  benv] (identical? (:from-position benv) from))

;;Capturing change information in a structure, rather than passing it
;;around willy-nilly in the environment.  If we have a pending
;;change, there will be changeinfo.  This only applies for instantaneous
;;changes....That way, we can communicate our state updates serially
;;by adding (and removing) changeinfo.
(comment 
(defrecord changeinfo [newstate duration followingstate])
)

;;Behaviors
;;=========

;;A lot of these behaviors operate on the concept of a blackboard.
;;The behavior environment, defined in marathon.ces.basebehavior,
;;is a map of lexical bindings that we use to evaluate the consequences
;;of a unit's behavior.  Certain behaviors may place or remove things
;;from the blackboard to communicate information with other behaviors
;;"down the line".  We can couple behaviors directly using the behavior
;;tree, or allow them to be indirectly coupled using the blackboard
;;as a form of simple event communication.  Many behaviors, like
;;update-after, and roll-forward-beh, will actually "consume"
;;items in the environment, like time.  It will be common to see
;;an ephemeral, or a transactional semantics with the behaviors.

(befn +nothing-state+ [entity deltat ctx]
     (->do (fn [_] (log! (str (:name @entity) " is doing nothing for " deltat) ctx)
             )))

;;note-we have a wait time in the context, under :wait-time
;;updates an entity after a specified duration, relative to the 
;;current simulation time + duration.
(befn update-after  ^behaviorenv [entity wait-time tupdate ctx]
   (when wait-time
     (->alter
      #(let [tfut (+ tupdate wait-time) 
             e                       (:name @entity)
             _    (debug [e :requesting-update :at tfut])]
         (swap! ctx (fn [ctx] 
                      (core/request-update tfut
                                           e
                                           :supplyupdate
                                           ctx)))
         (dissoc % :wait-time) ;remove the wait-time from further consideration...
         ))))

;;our idioms for defining behaviors will be to unpack 
;;vars we're expecting from the context.  typically we'll 
;;just be passing around the simulation context, perhaps 
;;with some supplementary keys.
;;Let's think about what it means to change state....
;;Are we in fact changing the root of the behavior?
;;This is where the transition from FSM to behavior tree
;;comes in....
(befn change-state-beh!  {:keys [entity ctx statedata state-change deltat] 
                         :or   {deltat 0} :as benv}
     (when state-change
       (let [{:keys [newstate duration followingstate timeinstate] :or {timeinstate 0}} state-change
             followingstate (or followingstate newstate)
             ;;we change statedata here...
             wt (- duration timeinstate)
             _ (debug [:changing-state state-change :wait-time wt])
             newdata (assoc (fsm/change-statedata statedata newstate duration followingstate)
                            :timeinstate timeinstate)
             benv    (merge (dissoc benv :state-change) {:statedata newdata
                                                         :duration duration
                                                         :timeinstate timeinstate
                                                         :wait-time wt})
             _       (swap! entity #(assoc % :state newstate )) ;;update the entity state, currently redundant.
             ]
         (beval update-state-beh benv))))

(def change-state-beh (->seq [(echo :<change-state-beh>)
                              change-state-beh!]))


;;Our default spawning behavior is to use cycle to indicate.
;;There will be times we alter the methods in which a unit 
;;spawns (initializes itself in the simulation).  It'd be nice
;;to break this out at some point, for now, we just let it go.

;;we can break the spawning behavior up into smaller tasks...
;;Find out where we're supposed to be. Do we have initial conditions? 
;;Initial conditions are currently derived from cycletime and policy. 
;;For instance, we narrowly assume that every unit exists somewhere in 
;;on a cycle at t=0, rather than setting them in arbitray deployments
;;to begin with.  This is limiting, we should be able to define
;;all kinds of initial conditions to perform upon spawn (like set
;;location, cycletime, etc.)  For now, we just replicate the
;;cycletime-derived automated initial conditions logic.

;;Given a cycletime, where should we be according to policy?
;;Behavior to control how a unit acts when it spawns.
;;We're trying to account for the unit's initial state...
;;We move from spawning to the initial location.
;;We account for having been at the initial location for 
;;timeinstate days (currently tied to cycletime - timetoposition).
;;So, what we really want to do is update the unit initially, possibly 
;;with a negative time, and advance it forward to time 0 via the
;;deltat being the timeinstate.
(befn spawning-beh ^behaviorenv {:keys [to-position cycletime tupdate statedata entity ctx]
                                 :as  benv}
  (when (spawning? statedata)     
    (let [ent @entity
          {:keys [ positionpolicy policy]} ent
          {:keys [curstate prevstate nextstate timeinstate 
                  timeinstateprior duration durationprior 
                  statestart statehistory]} statedata
          cycletime (or cycletime (:cycletime ent))
          topos     (if  (not (or to-position positionpolicy))
                         (protocols/get-position (u/get-policy ent) cycletime)
                         positionpolicy)
          timeinstate   (- cycletime 
                           (protocols/get-cycle-time policy
                                                     positionpolicy))
          timeremaining (protocols/transfer-time policy
                                                 positionpolicy 
                                                 (protocols/next-position policy positionpolicy))
          newduration   (- timeremaining timeinstate)
          nextstate     (protocols/get-state policy positionpolicy)
          spawned-unit  (-> ent (u/initCycles tupdate) (u/add-dwell cycletime) (assoc :last-update tupdate)) ;;may not want to do this..
          _             (reset! entity spawned-unit)
          state-change {:newstate       nextstate
                        :duration       timeremaining
                        :followingstate nil
                        :timeinstate timeinstate
                        }
          _             (debug [:nextstate nextstate :state-change state-change :current-state (:state ent)])          
          ]
      (->>  (assoc benv :state-change state-change
                   :location-change {:from-location "Spawning"
                                     :to-location   topos}
                   :next-position   topos ;queue up a move...                 
                   )
            (log!  (core/msg "Spawning unit " (select-keys (u/summary spawned-unit)
                                                           [:name :positionstate :positionpolicy :cycletime])))
            (beval (->seq [(echo :change-state)
                           change-state-beh
                           (fn [benv]
                             (do (reset! ctx 
                                         (supply/log-move! tupdate :spawning (:positionpolicy @entity) @entity @ctx))
                                 (success benv)))]
                          ))))))

;;we want to update the unit to its current point in time.  Basically, 
;;we are folding over the behavior tree, updating along the way by 
;;modifying the context.  One of the bits of context we're modifying 
;;is the current deltat; assumably, some behaviors are predicated on 
;;having a positive deltat, others are instantaneous and thus expect 
;;deltat = 0 in the context.  Note, this is predicated on the
;;assumption that we can eventually pass time in some behavior....
(befn roll-forward-beh {:keys [deltat statedata] :as benv}
  (cond (pos? deltat)
    (loop [dt   deltat
           benv benv]
      (let [sd (:statedata    benv)            
            timeleft    (fsm/remaining sd)
            _  (debug [:sd sd])
            _  (debug [:rolling :dt dt :remaining timeleft])
            ]
        (if-y 
         (if (<= dt timeleft)
           (do (debug [:dt<=timeleft :updating-for dt])               
               (beval update-state-beh (assoc benv :deltat dt)))
           (let [residual   (max (- dt timeleft) 0)
                 res        (beval update-state-beh (assoc benv  :deltat timeleft))]
             (if (success? res) 
               (recur  residual ;advance time be decreasing delta
                       (val! res))
               res)))
         nil)))
    (spawning? statedata)
    spawning-beh
    :else nil))

;;So, at the high level, we have a simple behavior that checks to see
;;if it can move, finds where to move to, starts the process of
;;moving (maybe instantaneous), and waits...

;;We should consider move if our time in state has expired, or 
;;if we have a next-location planned.
(befn should-move? ^behaviorenv {:keys [next-position statedata] :as benv}
      (do (debug [:should?
                  {:next-position next-position
                   :remaining (fsm/remaining statedata)
                   :spawning? (spawning? statedata)
                   :wait-time (:wait-time benv)}])
          (when (or next-position
                    (zero? (fsm/remaining statedata)) ;;time is up...
                    (spawning? statedata))
            (success benv))))

;;after updating the unit bound to :entity in our context, 
;;we commit it into the supplystore.  This is probably 
;;slow....we may want to define a mutable version, 
;;or detect if mutation is allowed for a faster update
;;path.  For instance, on first encountering the unit,
;;we establish a mutable cell to its location and use that 
;;during the update process.

;;Given that we have the context for a move in place, 
;;we want to move as directed by the context.  If there 
;;is a wait time associated with the place we're moving 
;;to, we will add the wait-time to the context.  That way,
;;downstream behaviors can pick up on the wait-time, and 
;;apply it.
(befn move->statechange ^behaviorenv {:keys [entity next-position tupdate statedata ctx] :as benv}
    (when-let [nextpos next-position] ;we must have a position computed, else we fail.                                       
      (let [t        tupdate
            u        @entity 
            frompos  (get     u      :positionpolicy) ;;look up where we're coming from.
            wt       (or (:wait-time benv) (get-wait-time  u  nextpos benv))  ;;how long will we be waiting?            
            ]
        (if (= frompos nextpos)  ;;if we're already there...
          (do (debug [:no-movement frompos nextpos (type benv)])
              (success (dissoc benv :next-position))) ;do nothing, no move has taken place.  No change in position.
          (let [_            (debug [:moving frompos nextpos])
                newstate     (get-state u nextpos)
                state-change {:newstate       newstate
                              :duration       wt
                              :followingstate nil
                              :timeinstate 0
                              }
                _            (reset! entity  (traverse-unit u t frompos nextpos)) ;update the entity atom
                _            (reset! ctx (u/unit-moved-event! @entity nextpos @ctx)) ;ugly, fire off a move event.
                from-loc     (:locationname u)
                to-loc       nextpos  ;; (when (if (set? newstate)
                             ;;         (clojure.set/intersection
                             ;;          #{"Dwelling" "DeMobilizing" "Recovering"
                             ;;            :deployable :dwelling} newstate)
                                     
                              
                ;_            (println [from-loc to-loc])
                ]
            (bind!!  ;update the context with information derived
                                        ;from moving
             {:position-change {:from-position frompos ;record information
                                :to-position   nextpos}
              :state-change    state-change
              :location-change (when (not (identical? from-loc to-loc))
                                          {:from-location  from-loc
                                           :to-location    to-loc})
              :wait-time     nil
              :next-position nil}
             ))
          ))))

;;This hooks us up with a next-position and a wait-time
;;going forward.
(befn find-move ^behaviorenv {:keys [entity next-position wait-time] :as benv}      
  (let [e  @entity
        currentpos (:positionpolicy e)
        p  (or next-position
               (do (debug [:computing-position])
                   (get-next-position e  currentpos)))
        wt (if (and next-position wait-time) wait-time
               (do (debug [:computing-wait])
                   (get-wait-time @entity (:positionpolicy e) benv)))
        _ (debug [:found-move {:next-position p :wait-time wt}])]
    (bind!! {:next-position  p
             :wait-time      wt
             }  ;;have a move scheduled...
            )))

;;We know how to wait.  If there is an established wait-time, we
;;request an update after the time has elapsed using update-after.
(befn wait ^behaviorenv {:keys [wait-time] :as benv}          
  (when-let [wt wait-time] ;;if we have an established wait time...    
    (if (zero? wt)
       ;skip the wait, instantaneous.  No need to request an
       ;update.
      (do (debug [:instantly-updating])
          update-state-beh) 
      (do (debug [:waiting  wt])
          (update-after  benv)))))

;;Units starting cycles will go through a series of procedures.
;;Possibly log this as an event?
(befn start-cycle {:keys [entity deltat tupdate] :as benv}
   (let [unit   @entity
         pstack (:policystack unit)]
     (do  (swap! entity #(merge % {:cycletime 0
                                   :date-to-reset tupdate}))
          (if (pos? (count pstack))
            (bind!! {:next-policy (first pstack)
                     :policy-change true})
            (success benv)))))

;;We may not care about cycles....
;;Should be able to specify this in our collections logic, go faster...


;;Units ending cycles will record their last cycle locally.  We broadcast
;;the change...Maybe we should just queue this as a message instead..
(befn end-cycle {:keys [entity ctx tupdate] :as benv}
  (let [cyc (assoc (:currentcycle @entity) :tfinal tupdate)
        _  (swap! entity (fn [unit]
                           (->  unit
                                (assoc :currentcycle cyc)
                                (u/recordcycle tupdate))))
        ;;notify interested parties of the event...
        _  (swap! ctx (fn [ctx]
              (sim/trigger-event :CycleCompleted
                                 (:name @entity)
                                 :SupplyStore
                                 (str (:name @entity) " Completed A Cycle")
                                 nil ctx)))]
    (success benv)))

;;dunno, just making this up at the moment until I can find a
;;definition of new-cycle.  This might change since we have local
;;demand effects that can cause units to stop cycling.
;;Wow...just got burned on this..  strings are no good for identity
;;checks....since some are interned and some ore instances.  wow....
(defn new-cycle? [unit frompos topos]
  (= (protocols/start-state (:policy unit)) topos))

;;We check to see if there was a position change, and if so, if that
;;change caused us to finish a policy cycle.  Note: this only applies
;;in cyclical policies.
(befn finish-cycle ^behaviorenv {:keys [entity position-change] :as benv}      
  (when position-change
    (let [{:keys [from-position to-position]} position-change
          no-spawn? (not (just-spawned? benv))
          new-cyc?  (new-cycle? @entity from-position to-position)
          ;_ (println [:check-cycle no-spawn? new-cyc? (:tupdate benv)])
          ]
      (when (and no-spawn?
                 new-cyc?)
        (do (debug [:finishing-cycle (:name @entity) from-position])
            (->seq [start-cycle
                    end-cycle]))))))

;;this is really a behavior, modified from the old state.  called from overlapping_state.
;;used to be called check-overlap.
(befn disengage {:keys [entity position-change ctx] :as benv}
      (when position-change
        (let [{:keys [to-position from-position]}   position-change
              res   (cond (identical? to-position   :overlapping)   true
                          (identical? from-position :overlapping)   false
                          :else :none)]
          (when (not (identical? res :none)) ;ugh?
            (do (swap! ctx ;;update the context...
                       #(d/disengage (core/get-demandstore %) @entity (:locationname @entity) % res))
                (success benv))))))

;;used to be called check-overlap; 
(def  check-overlap disengage)
(befn check-deployable ^behaviorenv {:keys [entity position-change ctx] :as benv}
   (when position-change
     (let [{:keys [from-position to-position]} position-change
           u @entity
           p (:policy u)
           _ (debug [:checking-deployable  :from from-position :to to-position])]
       (when   (not= (protocols/deployable-at? p from-position)
                     (protocols/deployable-at? p to-position))
         (do (debug [:deployable-changed! from-position to-position])
             (swap! ctx #(supply/update-deploy-status u nil nil %))
             (success benv))))))

;;When there's a change in position, we want to do all these things.
(def change-position
  (->seq [check-overlap
          check-deployable
          finish-cycle
          (->alter  #(dissoc % :position-change))]))

;;if there's a location change queued, we see it in the env.
(befn change-location {:keys [entity location-change ctx] :as benv}
   (when location-change
     (let [{:keys [from-location to-location]} location-change]
       (let [_ (debug [:location-change location-change])            
             ;_  (swap! ctx    #(u/unit-moved-event! @entity to-location %))
             _  (reset! entity (u/push-location @entity to-location))] 
         ;;we need to trigger a location change on the unit...
         (success (dissoc benv :location-change))))))

;;with a wait-time and a next-position secured,
;;we can now move.  Movement may compute a statechange
;;in the process.
(def execute-move
  (->seq [(echo :<move->statechange>)
          move->statechange
          (echo :<change-position>)
          change-position
          (echo :<change-location>)
          change-location
          change-state-beh
          (echo :waiting)
          wait
          ]))

;;Movement is pretty straightforward: find a place to go, determine 
;;any changes necessary to "get" there, apply the changes, wait 
;;at the location until a specified time.
(def moving-beh 
  (->and [(echo :moving-beh)
          should-move? ;if there is a next position or our time in state expired.
          find-move    ;determine the wait-time, and possibly the next-position to wait at.         
          (echo :execute-move)
          execute-move
          ]))

;;State handler for generic updates that occur regardless of the state.
;;These are specific to the unit data structure, not any particular state.
;;Should we keep a timestamp with the unit? That way we can keep track
;;of how fresh it is.
(befn age-unit ^behaviorenv {:keys [deltat statedata entity ctx] :as benv}
      (let [dt (or deltat 0)]
        (if (zero? dt)
            (success benv) ;done aging.
            (let [_  (swap! entity #(u/add-duration  % dt)) ;;update the entity atom
                  _  (debug [:aging-unit deltat :cycletime (:cycletime @entity)]) 
                  ]
              (bind!! {:deltat 0 ;is this the sole consumer of time? 
                       :statedata (fsm/add-duration statedata dt)})))))
 
;;Dwelling just increments statistics..
(befn dwelling-beh ^behaviorenv {:keys [entity deltat] :as benv}
      (when (pos? deltat)
        (do (debug [:dwelling deltat])
            (swap! entity  #(u/add-dwell % deltat))
            (success benv))))

;;Bogging just increments stastistics..
(befn bogging-beh ^behaviorenv {:keys [entity deltat] :as benv}
      (when (pos? deltat)
        (do (debug [:bogging deltat])
            (swap! entity  #(u/add-bog % deltat))
            (success benv))))

(befn special-state {:keys [entity statedata] :as benv}
      (let [s (:state entity)
            ;_ (debug [:specialstate s])
            ]
        (case s
          :spawning spawning-beh
          :abrupt-withdraw (echo :abrupt-withdraw) ;abrupt-withdraw-beh
          (fail benv))))

;;Follow-on state is an absorbing state, where the unit waits until a changestate sends it elsewhere.
;;The only feasible state transfers are to a reentry state, where the unit re-enters the arforgen pool
;;in a dynamically determined position, or the unit goes to another demand compatible with the
;;followon code.
(befn followon-beh {:keys [entity ctx] :as benv}
      (when (:followoncode @entity) ;if the unit has a followon code
        (do ;register the unit as a possible followOn 
          (swap! ctx #(supply/add-followon (core/get-supplystore %) @entity %))
          age-unit)))

;;way to get the unit back to reset.  We set up a move to the policy's start state,
;;and rip off the followon code.
(befn reset-beh {:keys [entity] :as benv}
      (do (swap! entity #(assoc % :followoncode nil))
          (beval moving-beh (assoc benv :next-position
                                   (protocols/start-state (:policy @entity))))))

;;Function to handle the occurence of an early withdraw from a deployment.
;;when a demand deactivates, what happens to the unit?
;;The behavior will be guided by (the unit's) policy.
;;The default behavior is that a unit will check its policy to see if it CAN deploy.
;;If policy says it's okay, the unit will return to the point time of its current lifecycle.
;;We can parameterize the penalty it takes to get back into lifecycle from deployment.
;;    A usual penalty is a move to "90 days of recovery"
;;Note, we can also specify if the unit is instantly available to local demands.
;;Recovery should now be an option by default, not specifically dictated by
;;policy.

;;1)Consult policy to determine if entry back into available / ready pool is feasible.
;;TOM note 18 july 2012 -> this is erroneous.  We were check overlap....that's not the definition of
;;a unit's capacity to re-enter the available pool.

(befn recovery-beh []
      (echo :recovery-beh))
;;On second thought, this is sound.  If the unit is already in overlap, it's in a terminal state..
;;For followon eligibility, it means another unit would immediately be overlapping this one anyway,
;;and the demand would not be considered filled....It does nothing to alleviate the demand pressure,
;;which is the intent of followon deployments.  Conversely, if overlap is 0, as in typical surge
;;periods, then units will always followon.  I take back my earlier assessment, this is accurate.
(befn abrupt-withdraw-beh {:keys [entity deltat] :as benv}
      (let [_    (when (pos? deltat) (swap! entity #(u/add-bog % deltat)))
            unit @entity
            ;1)
            bogremaining (- (:bogbudget (:currentcycle unit))  
                            (protocols/overlap (:policy unit)))]
        (if (not (pos? bogremaining))
          ;makes no sense for the unit to continue BOGGING, send it home.
          reset-beh
          (->or 
             ;unit has some feasible bogtime left, we can possibly have it followon or extend its bog...
             ;A follow-on is when a unit can immediately move to fill an unfilled demand from the same
             ;group of demands.  In otherwords, its able to locally fill in.
             ;This allows us to refer to forcelists as discrete chunks of data, group them together,
             ;and allow forces to flow from one to the next naturally.         
           [followon-beh
            recovery-beh]))))

;;entities have actions that can be taken in a state...
(def default-statemap
  {:reset            reset-beh
;   :global          
   :abrupt-withdraw  abrupt-withdraw-beh
   :recovered        (echo :recovered-beh)
   ;:end-cycle
;   :spawning        spawning-beh   
   :demobilizing     dwelling-beh
   "DeMobilizing"    dwelling-beh
   protocols/demobilization dwelling-beh

   :bogging           bogging-beh
   protocols/Bogging  bogging-beh
   
   :recovering      (echo :recovering-beh)
   "Recovering"     (echo :recovering-beh)
   
   :dwelling          dwelling-beh
   protocols/Dwelling dwelling-beh
   
   :overlapping       bogging-beh
   protocols/Overlapping  bogging-beh
   })

;;lookup what effects or actions should be taken relative to
;;the current state we're in.  This is kind of blending fsm
;;and behaviortree.
(befn do-current-state {:keys [entity statedata] :as benv}
      (let [state (:state @entity)
            state-map (or (:statemap entity) default-statemap)]
        (if (set? state)  ;entity has multiple effects...
          (let [stats (r/filter identity (r/map (fn [s] (get state-map s)) state))]
            (->seq stats))
          (get state-map state))))
;;the entity will see if a message has been sent
;;externally, and then compare this with its current internal
;;knowledge of messages that are happening concurrently.
(befn check-messages ^behaviorenv {:keys [entity current-messages ctx] :as benv}
   (if-let [old-msgs (fget (deref! entity) :messages)] ;we have messages
     (when-let [msgs   (pq/chunk-peek! old-msgs)]
       (let [new-msgs  (rconcat (r/map val  msgs) current-messages)
             _         (b/swap!! entity (fn [^clojure.lang.Associative m]
                                          (.assoc m :messages
                                                  (pq/chunk-pop! old-msgs msgs)
                                                 )))]
         (bind!! {:current-messages new-msgs})))
     (when current-messages
       (success benv))))

;;this is a dumb static message handler.
;;It's a simple little interpreter that
;;dispatches based on the message information.
;;Should result in something that's beval compatible.
;;we can probably override this easily enough.
;;#Optimize:  We're bottlnecking here, creating lots of
;;maps....

;;Where does this live?
;;From an OOP perspective, every actor has a mailbox and a message handler.
;;

;;so now we can handle changing state and friends.
;;we can define a response-map, ala compojure and friends.

;;type sig:: msg -> benv/Associative -> benv/Associative
;;this gets called a lot.
(defn message-handler [msg ^behaviorenv benv]
  (let [entity           (.entity benv)
        current-messages (.current-messages benv)
        ctx              (.ctx benv)]
    (do ;(ai/debug (str [(:name (deref! entity)) :handling msg]))
      (beval 
       (case (:msg msg)
         ;;allow the entity to invoke a state-change-behavior
         ;;We can always vary this by modifying the message-handler         
         :change-state           
           ;;generic update function.  Temporally dependent.
         ;;we're already stepping the entity.  Can we just invoke the change-state behavior?
         (let [state-change (:data msg)
               _            (debug [:state-change-message state-change msg])]
           (beval change-state-beh (assoc benv :state-change state-change
                                               :next-position (or (:next-position state-change)
                                                                  (:newstate state-change)))))
         :update (if (== (get (deref! entity) :last-update -1) (.tupdate benv))
                   (do (success benv)) ;entity is current
                   (->and [(echo :update)
                           (fn [^clojure.lang.Associative ctx]
                             (success (.assoc ctx :current-message msg)))]))
         :spawn  (->and [(echo :spawn)
                         (push! entity :state :spawning)                        
                         spawning-beh]
                        )
         ;;allow the entity to change its behavior.
         :become (push! entity :behavior (:data msg))
         :do     (->do (:data msg))
         :echo   (->do  (fn [_] (println (:data msg))))
         (do ;(println (str [:ignoring :unknown-message-type (:msg msg) :in  msg]))
           (sim/trigger-event msg @ctx) ;toss it over the fence
                                        ;(throw (Exception. (str [:unknown-message-type (:msg msg) :in  msg])))
           (success benv)
           ))
       benv))))

;;we'd probably like to encapsulate this in a component that can be seen as a "mini system"
;;basically, it'd be a simple record, or a function, that exposes a message-handling
;;interface (could even be a generic fn that eats packets).  For now, we'll work
;;inside the behavior context.  Note, the entity is a form of continuation....at
;;least the message-handling portion of it is.

;;message handling is currently baked into the behavior.
;;We should parameterize it.

;;handle the current batch of messages that are pending for the
;;entity.  We currently define a default behavior.
(befn handle-messages ^behaviorenv {:keys [entity current-messages ctx] :as benv}
      (when current-messages
        (reduce (fn [acc msg]                  
                  (do (debug [:handling msg])
                    (message-handler msg (val! acc))))
                (success (assoc benv :current-messages nil))
                current-messages)))

;;The global sequence of behaviors that we'll hit every update.
;;These are effectively shared behaviors across most updates.
(def global-state
  (->seq [(echo :aging)
          age-unit          
          (echo :aged)
          moving-beh]))

;;The root behavior for updating the entity.
(def update-state-beh
  (->seq [(echo :<update-state-beh>)
          (->or [(->and [(echo :check-messages)
                         check-messages
                         handle-messages])
                 (echo :no-messages)])
          (->or [special-state
                 (->seq [(echo :<do-current-state>)
                         do-current-state
                         (echo :global-state)
                         (fn [ctx]
                           (if-y 
                            global-state
                            (fail ctx)))])
                 (echo :up-to-date)])]))

;;So, this precludes is from having shared behaviors
;;across states.  If we go the state-based route, we
;;end up partitioning our behavior and having
;;to duplicate it internally. 

;;states are identical to leaf behaviors, with 
;;the possibility for some states to invoke transitions.
;;we'll continue to port them.
(comment

  ;;these are state masks, effectively....
  
(def default-states 
  {:global          age-unit
   :reset            #(pass :spawning %)  
   :bogging          bogging-beh   ;simple updates f(t)
   :dwelling         dwelling-beh  ;simple updates f(t)
   ;Currently, we encode multiple states in the policy graph.  We may
   ;want to re-evaluate that...right now, we have multiple
   ;combinations of states to handle...that all correspond to dwelling.
   #{:dwelling}      dwelling-beh
   #{:deployable}    dwelling-beh
   #{:deployable :dwelling}  dwelling-beh
   :moving           moving-beh
   :start-cycle      #(pass :start-cycle     %)
   :end-cycle        #(pass :end-cycle       %)
   :overlapping      #(pass :overlapping     %)
   :demobilizing     #(pass :demobilizing    %)
   :policy-change    #(pass :policy-change   %)
   :recovering       #(pass :recovering      %)
   :recovered        #(pass :recovered       %) 
   :nothing          #(pass :nothing         %)
   :spawning         spawning-beh
   :abrupt-withdraw  #(pass :abrupt-withdraw %)
;   #{:deployable :dwelling} #(pass :deployable-dwelling)
   })

)

(comment
;;this is significantly different than the fsm approach we used before...
;;now, we handle each case as a specific branch of the tree...
;;note: we can parameterize this and build the tree dynamically to
;;define higher-order behaviors.

;;perform a simple update via the entity's FSM.
(defn update-current-state 
  ([states ctx] 
     (let [st (get (statedata ctx) :curstate)
           _ (log! st ctx)]
       (if-let [f (try-get states st +nothing-state+)]
         (do (log! [:updating-in st] ctx)
             (f ctx))
         (do (log! [:unknown-state st] ctx)
             (fail ctx)))))
  ([ctx] (update-current-state default-states ctx)))

;;this is another btree node:
;;(->case) or (->states ) 

)
                   

;;if we have a message, and the message indicates
;;a time delta, we should wait the amount of time
;;the delta indicates.  Waiting induces a change in the
;;remaining wait time, as well as a chang
(befn wait-in-state ^behaviorenv [entity current-message ctx]
  (let [;_ (println [:wait-in-state entity msg])
        msg    current-message
        t     (fget msg :t)
        delta (- t (fget (deref! entity) :t))]
    (when-let [duration (fget  (deref! entity) :wait-time)]
      (if (<= delta duration) ;time remains or is zero.
         ;(println [:entity-waited duration :remaining (- duration delta)])
        (merge!!  entity {:wait-time (- duration delta)
                          :tupdate t}) ;;update the time.
        (do ;can't wait out entire time in this state.
          (merge!! entity {:wait-time 0
                           :tupdate (- t duration)}) ;;still not up-to-date
           ;;have we handled the message?
           ;;what if time remains? this is akin to roll-over behavior.
           ;;we'll register that time is left over. We can determine what
           ;;to do in the next evaluation.  For now, we defer it.
          (bind!! {:current-message (.assoc ^clojure.lang.Associative msg :delta (- delta duration))}
                 )
          )))))

(defn up-to-date? [e ctx] (== (:tupdate e) (:tupdate ctx)))

;;This will become an API call...
;;instead of associng, we can invoke the protocol.
(befn schedule-update  ^behaviorenv {:keys [entity ctx new-messages] :as benv}
      (let [st       (deref! entity)
            nm       (:name st)
            duration (:wait-time st)
            tnow     (:tupdate (deref! ctx))
            tfut     (+ tnow duration)
            _        (debug 4 [:entity nm :scheduled :update tfut])
            ;_ (when new-messages (println [:existing :new-messages new-messages]))
            ]        
        (success (push-message- benv nm nm (->msg nm nm tfut :update)))))

;;wire in functionality here for a unit to invoke its own
;;deployment order...
;;From here on, the system will append a deployment order to
;;the unit, and send the unit a message to update.
;;The unit will handle the message by appending a
;;deployment order to its state and invoking an update.
;;This way, we handle messages first, which preps the
;;behavior environment to respond to stimulii (like
;;the presence of a deploy order)
(defn deploy-to [o benv]
  ;;stub
  (success benv))

(befn try-deploy ^behaviorenv {:keys [entity] :as benv}
      (when-let [o (:deploy-order @entity)]
        (deploy-to o)))

;;This is kind of weak, but I don't have a better solution at the moment...
(do (println [:setting-defaults])
    (reset! base/default-behavior roll-forward-beh))

(comment ;OBE

(defn update-unit 
  "Computes a new simulation context given a specific unit to update, 
   an elapsed time, and an optional time of update.  tupdate is inferred
   to be the current simulation time if none is supplied."
  ([unit deltat ctx]        
     (update-unit unit deltat (sim/current-time ctx) ctx))
  ([unit deltat tupdate ctx]
     (->>  ctx  
           (load-entity! unit deltat tupdate)
           (roll-forward-beh) ;update the unit according to the change in
                                        ;time.
           (error-on-fail)    ;unit updates should never fail.       
           (second  ;result is in terms of [:success|:fail ctx], pull out
                                        ;the ctx
            )
           (commit-entity!)
;           (clear-bb)
           )))

;;We'll replace these; for now the units will automatically
;;try to update themselves if possible.

;;Debatable...just invokes roll-forward-beh; I think we can ensure that
;;roll-forward is always invoked first...

;;Re-evaluate the need for this....can we synchronize from outside?
;;ideally, we just keep track of the unit's last update....
(defn sync
  "Utility function.  Synchronize the unit to the current simulation time.  
   If the last update occured before the current time, we roll the unit forward 
   by the delta between the last update and the current time."
  [unit ctx]
  (let [tprev (or (sim/last-update (:name unit) ctx) 0)
        tnow  (sim/current-time ctx)]
    (if (= tprev tnow)
      (log! (str "unit " (:name unit) "is up to date") ctx)
      (log! (str "Synchronizing unit " (:name unit) " from " tprev " to " tnow)
            (update-unit unit (- tnow tprev) tprev ctx)))))

;;Synchronizes the unit to the current time, then applies a time 
;;delta, then processes/records the unit's time of update.
(defn update 
  "Entry point for computing behavior-based unit updates.  Fundamental 
   API function for processing unit entities.  Synchronizes the unit to 
   the current simulation time, then computes the new simulation context 
   resulting from the entity behavior over an elapsed deltat (from current
   simulation time)."
  [unit deltat ctx]
  (let [nm (get unit :name)]
    (->> (sync unit ctx)
         (update-unit unit deltat)
         (u/unit-update! nm (core/msg "Updated " nm)))))




)





;; 'A state to handle reentry into the available pool....
;; Private Function ReEntry_State(unit As TimeStep_UnitData, deltat As Single) As TimeStep_UnitData

;; Dim newpolicy As IRotationPolicy
;; Dim wasDeployable As Boolean
;; Dim isDeployable As Boolean
;; Dim newduration As Single
;; Dim offset As Single
;; Dim timeinstate As Single
;; Dim timeremaining As Single
;; Dim nextstate As String
;; Dim newcycletime As Single
;; Dim cycletimeA As Single
;; Dim policynameA As String
;; Dim policynameB As String
;; Dim CycleProportionA As Single
;; Dim CycleTimeB As Single
;; Dim PositionA As String
;; Dim PositionB As String

;; With unit
    
;;     'TOM Change 20 April 2012
;;     'wasDeployable = .policy.isDeployable(.cycletime)
;;     wasDeployable = .policy.isDeployable(.cycletime)

;;     'HACK to account for negative cycle times.
;;     If .cycletime < 0 Then Err.Raise 101, , "Cycle Time Should not be negative!"
    
;;     If .PositionPolicy <> "Deployed" And .PositionPolicy <> "Overlapping" Then  'we can safely change the unit's policy.
       
;;         isDeployable = .policy.isDeployable(.cycletime)
;;         PositionA = .PositionPolicy
;;         If .PositionPolicy <> "Deployed" Then
;;             If .PositionPolicy = "Overlapping" Then Err.Raise 101, , "Forgot about overlap.."
;;             PositionB = .policy.getPosition(.cycletime) 'dwelling units can change positions.
;;         Else
;;             Err.Raise 101, , "Cannot be deployed!"
;;         End If
        
;;         'position policy is the SAME as positionB
;;         .PositionPolicy = PositionB
        
;;         timeremaining = .policy.TransferTime(PositionB, .policy.nextposition(PositionB)) 'time remaining in this state
        
;;         'How long will the unit have been in this state?
;;             'Since it's a policy change....do we zero it out?
;;             'Or do we assume that the unit has been in the state the exact amount of time required?
;;         'We assume that the unit has been in the state the exact amount of time required.
;;         'We also assume that the unit is not entering another cycle, merely extending or truncating.
;;             'Its current cycle is modified.
;;             'Does not get a cycle completion out of it.
            
;;         If .cycletime >= 0 Then
;;             If PositionB <> "Deployed" Then
;;                 timeinstate = .cycletime - .policy.GetCycleTime(PositionB) 'derived time in state upon spawning.
;;             Else
;;                 Err.Raise 101, , "Error, should not be deployed"
;;             End If
;;         End If
        
;;         newduration = timeremaining - timeinstate
;;         nextstate = getState(unit, PositionB)
        
;;         'TOM change 23 july 2011
;;         'Decoupled*
;;         'parent.LogPosition parent.getTime, PositionA, PositionB, unit
;;         MarathonOpSupply.LogPosition SimLib.getTime(simstate.context), PositionA, PositionB, unit, , simstate.context
        
;;         If nextstate = "Dwelling" Or nextstate = "DeMobilizing" _
;;                 Or nextstate = Recovering Or nextstate = Recovered Then
;;             'parent.LogMove parent.getTime, .LocationName, .PositionPolicy, unit
;;             .changeLocation .PositionPolicy, simstate.context
;;             '.LocationName = .PositionPolicy
;;         End If
        
;;         'Decoupled*
;;         '.parent.UpdateDeployStatus unit
;;         MarathonOpSupply.UpdateDeployStatus simstate.supplystore, unit, , , simstate.context

;;         'Decouple*
;;         'parent.parent.trigger supplyUpdate, .name, .name, "Unit ReEntering with " & .CurrentCycle.bogbudget & " BOGBudget & " & .name
;;         SimLib.triggerEvent supplyUpdate, .name, .name, "Unit ReEntering with " & .CurrentCycle.bogbudget & " BOGBudget & " & .name, , simstate.context
;;         Set ReEntry_State = ChangeState(unit, nextstate, 0, newduration)
;;     Else
;;         Err.Raise 101, , "Cannot handle during deployment or overlap"
;;     End If
;; End With
;; End Function

;;__CanRecover__

;; 'TOM Change 23 April 2012
;; 'Auxillary function to help us determine whether a unit should try to recover....
;; Private Function canRecover(unit As TimeStep_UnitData) As Boolean
;; Dim rtime As Single
;; Dim demobtime As Single 'Tom Change 18 July 2012

;; canRecover = False

;; With unit
;;     'Tom change 18 July 2012 ->
;;         'Allowing general recovery as a default behavior, although policies can change it...
;;             'I.e. RC14 remob is an example.
;;         'If the unit's policy doesn't have a recovery state, we set an abitrary recoverytime.
;;         'Derived from Parameters("DefaultRecoveryTime")
        
;;     'Tom Change 23 April 2012
;;     If .policy.PositionGraph.nodeExists(recovery) Then
;;         rtime = .policy.TransferTime(recovery, Recovered)
;;     Else 'Decoupled*
;;         'rtime = parent.parent.parameters.getKey("DefaultRecoveryTime")
;;         rtime = simstate.parameters.getKey("DefaultRecoveryTime")
;;         'Tom note -> I think this is a moot point, because our criteria for recovering is to not
;;         'exceed the expected duration of a cycle.  If we're already exceeding it, we don't have to
;;         'check demob.
;;         'if the policy includes demob, we need to account for demob time as well.
;; '        If .policy.PositionGraph.nodeExists(demobilization) Then
;; '            rtime = .policy.TransferTime(demobilization, .policy.nextposition(demobilization))
;; '        End If
;;     End If
    
;;     If .CurrentCycle.bogbudget > 0 And .cycletime + rtime < .CurrentCycle.DurationExpected Then
;;         canRecover = True
;;     End If
;; End With

;; End Function


;;__Recovering__

;; 'TOM Change 23 April 2012 -> Check to see if we should even try to recover....
;; 'Criteria: if cycletime + recoverytime > cycleduration then 'skip recovery....
;; 'go straight to recovered.  This will push us into whatever the post recovery state was...
;; Public Function Recovering_State(unit As TimeStep_UnitData, deltat As Single) As TimeStep_UnitData

;; Dim nm As String
;; If canRecover(unit) Then
;;     If deltat > 0 Then unit.AddDwell deltat
;;     Set Recovering_State = unit
;; Else
;;     nm = unit.name
;;     'decoupled*
;;     'parent.parent.trigger supplyUpdate, nm, nm, "Unit " & nm & " Skipping Recovery with " & unit.CurrentCycle.bogbudget & " BOGBudget"
;;     SimLib.triggerEvent supplyUpdate, nm, nm, "Unit " & nm & " Skipping Recovery with " & unit.CurrentCycle.bogbudget & " BOGBudget", , simstate.context
;;     'zero out the unit's ability to BOG, preventing recovery and re-Entry.
;;     unit.CurrentCycle.bogbudget = 0
;;     Set Recovering_State = Moving_State(unit, deltat) 'advance the unit forward in time.
;; End If

;; End Function



;;___Policy Change___



;; 'TOM Change 13 Jul 2011
;; 'Needed to implement the transition from one policy to another.  I chose to add a state to handle just this.
;; 'Visual analysis showed that PolicyChange looks a lot like Spawn, in that when a unit changes policies, it must change
;; 'a lot of its internal state to follow the new policy.  The result of the policy change is:
;; '   1: The unit's cycle time is normalized, and then transformed into the relevant cycletime in the new policy.
;; '   2: The unit's position "may" change to reflect its position in the new policy.
;; '   3: The unit's location "may" change to reflect its location in the new policy.
;; 'TOM Change 20 April:
;; '   4: The unit's BOGBudget "may" change to reflect either increased, or decreased, BOGBudget.
;; 'TOM Change 24 April:
;; '   5: The unit's BOGBudget and MAXBOG may only change (increase) as the result of a policy change.
;; '   6: Policy changes can NOT happen during terminal states:
;; '       [Deployed {Bogging, Overlapping}, Demobilizing]


;; 'If NOT deployed (bogging, overlapping) or in a terminal state (demobilizing), then entities can change policy immediately.
;; 'Otherwise, units change policy upon next reset (change is deferred).

;; 'Assumes that the new policy is already set for the unit (i.e. the unitdata is pointing toward the new policy).
;; 'Ideally, an outside agent will have modified the unit's policy, and subsequently told it to changestates to a policy-change
;; 'state.

;; 'Net effect is that policy changes to the same policy are idempotent.
;; 'State to control how a unit acts when it changes policy.
;; 'Note -> we extract the next policy from the unitdata's policy stack.

;; 'TOM note -> figure out how to change this for the deployed population...they have negative cycle
;; 'times.

;; 'Note -> this assumes we have compatible policies, or at least policies that have a cyclical
;; 'rotational lifecycle.
;; Function PolicyChange_State(unit As TimeStep_UnitData, deltat As Single) As TimeStep_UnitData

;; Dim newpolicy As IRotationPolicy

;; Dim wasDeployable As Boolean
;; Dim isDeployable As Boolean

;; Dim newduration As Single
;; Dim offset As Single
;; 'tom change
;; Dim tnow As Single
;; Dim timeinstate As Single
;; Dim timeremaining As Single
;; Dim nextstate As String

;; Dim newcycletime As Single

;; Dim cycletimeA As Single
;; Dim policynameA As String
;; Dim policynameB As String
;; Dim CycleProportionA As Single
;; Dim CycleTimeB As Single
;; Dim PositionA As String
;; Dim PositionB As String


;; tnow = SimLib.getTime(simstate.context)

;; With unit

;;     If .policyStack.count = 0 Then Err.Raise 101, , "no new policies on stack!"
               
;;     'fetch the new policy defined by the IRotationPolicy.
;;     'Note, for composite policies, the newpolicy defers to the active policy.
;;     'The active policy should have been modified for us.  That's a weak spot...
;;     Set newpolicy = .policyStack(.policyStack.count)
    
;;     If newpolicy.bogbudget = 0 Then Err.Raise 101, , "No bog budget!"
;;     policynameA = .policy.AtomicName 'points to the active atomic policy
;;     policynameB = newpolicy.AtomicName 'points to the active atomic policy
    
;;     'TOM Change 20 April -> We need to separate the unit's experienced
;;     'cycle length vs the NOMINAL cycle duration, which exists in
;;     'POLICY SPACE.  In composite rotational policies, the NOMINAL cycle duration
;;     'changes when Atomic policies change.  Specificallly, we map the unit's position
;;     'or coordinates in the current atomic policy to coordinates in the new policy.
;;     'The unit's actual experienced lifecycle, i.e. its cycletime property, is not
;;     'an accurate mapping between policies.  The implicit assumption is that when
;;     'mapping from one policy to another, if the policies have differing cycle lengths
;;     'then there is a discount or exchange rate between the policies, such that time
;;     'spent in one policy is NOT equal to time spent in another.  However, our
;;     'unit's cyclelength property is not subject to this, since it technically
;;     'exists OUTSIDE of the policy view of time.  The cyclelength property reflects the
;;     'actual time a unit has spent, under ANY policy, until it has reset or started a
;;     'new cycle.
    
;;     'Prior to 19 April 2012, The unit's ability to deploy, via the CanDeploy method,
;;     'depended on it's position in the current policy as a function of the cyclelength property.
;;     'We should prefer the duration of the current cycle record, which is an accurate reflection
;;     'of the relative time in the unit's current policy.
;;     cycletimeA = .cycletime
    
;;     'TOM Change 20 April 2012
;;     'cycletimeA = .CurrentCycle.duration

;;     PositionA = .PositionPolicy
;;     'TOM Change 20 April 2012
;;     'wasDeployable = .policy.isDeployable(.cycletime)
;;     wasDeployable = .policy.isDeployable(.cycletime)

    
;;     'HACK to account for negative cycle times.
;;     If .cycletime < 0 Then Err.Raise 101, , "Cycle Time Should not be negative!"
    
;;     CycleProportionA = .cycletime / .policy.cyclelength
;;     'TOM change 23 April 2012 -> No longer allow units that are De-mobilizing to enter into available pool.
    
;;     If CycleProportionA <= 1# And PositionA <> "Deployed" And _
;;         PositionA <> "Overlapping" And PositionA <> "DeMobilizing" Then   'we can safely change the unit's policy.

;;         'Tom Change -> fixed to integers.
;;         CycleTimeB = Fix(CycleProportionA * newpolicy.cyclelength)
;;         If CycleTimeB > newpolicy.cyclelength Then Err.Raise 101, , "Cyclelength is too long!"
        
;;         isDeployable = newpolicy.isDeployable(CycleTimeB)
        
;;         If .PositionPolicy <> "Deployed" Then
;;             If .PositionPolicy = "Overlapping" Then Err.Raise 101, , "Forgot about overlap.."
;;             PositionB = newpolicy.getPosition(CycleTimeB) 'dwelling units can change positions.
;;         Else
;;             PositionB = .PositionPolicy 'deployed units remain deployed.
;;         End If
        
;;         'Tom Note 12 July 2012
;;         'This actually updates the unit's policy....
;;         'What are the consequences?
;;         'We 're setting its IRotation policy to the new policy....
;;         'We should be setting it to the root policy....
;;         Set .policy = newpolicy
;;         'position policy is the SAME as positionB
;;         .PositionPolicy = PositionB
        
;;         timeremaining = .policy.TransferTime(PositionB, .policy.nextposition(PositionB)) 'time remaining in this state
        
;;         'How long will the unit have been in this state?
;;             'Since it's a policy change....do we zero it out?
;;             'Or do we assume that the unit has been in the state the exact amount of time required?
;;         'We assume that the unit has been in the state the exact amount of time required.
;;         'We also assume that the unit is not entering another cycle, merely extending or truncating.
;;             'Its current cycle is modified.
;;             'Does not get a cycle completion out of it.
;;         .cycletime = CycleTimeB
       
;;         If .cycletime >= 0 Then
;;             If PositionB <> "Deployed" Then
;;                 timeinstate = .cycletime - .policy.GetCycleTime(PositionB) 'derived time in state upon spawning.
                
;;                 'Decoupled*
;; '                unit.ChangeCycle parent.getTime
;;                 unit.ChangeCycle tnow
;;                 unit.ModifyCycle newpolicy
                
;;             Else 'how long have we been deployed?  Assume it's BOG for now....
;;                 timeinstate = .CurrentCycle.bog  'TOM Note This may change.
;;             End If
;;         Else
;;             unit.AddBOG timeinstate
;;             Err.Raise 101, , "Negative cycle times are not handled..."
;;         End If
        
;;         newduration = timeremaining - timeinstate
;;         nextstate = getState(unit, PositionB)
        
;;         'TOM change 23 july 2011
;;         If PositionA <> PositionB Then
;;             'Decoupled*
;; '           parent.LogPosition parent.getTime, PositionA, PositionB, unit
;;             MarathonOpSupply.LogPosition tnow, PositionA, PositionB, unit, , simstate.context

;;             If nextstate = "Dwelling" Or nextstate = "DeMobilizing" _
;;                     Or nextstate = Recovering Or nextstate = Recovered Then
;;                 'parent.LogMove parent.getTime, .LocationName, .PositionPolicy, unit
;;                 .changeLocation .PositionPolicy, simstate.context
;;                 '.LocationName = .PositionPolicy
;;             End If
;;         End If
        
        
;;         'If wasDeployable <> isDeployable Then .parent.UpdateDeployStatus unit
;;         'Decoupled*
;;         '.parent.UpdateDeployStatus unit
;;         MarathonOpSupply.UpdateDeployStatus simstate.supplystore, unit, , , simstate.context
        
;;         'Adopt Policy B.
;;         'Policy A ->
;;         '    Find relative CT = ct/CLengthA
;;         'Policy B ->
;;         '    Find relative positionB = pos(RelativeCT * CLengthB)
;;         'Movingstate from PositionA to relative PositionB.
;;         'Update with delta0.
;;         'TOM Change 2 Sep -> moved this north so that we can use the policy stack as a flag in unit's
;;         'ChangeCycle logic.  Check for sideeffects
;;         .policyStack.Remove 1
        
;;         'Decoupled*
;; '        parent.parent.trigger UnitChangedPolicy, .name, .policy.AtomicName, "Unit " & .name & " changed policies: " & _
;; '            policynameA & ":" & cycletimeA & "->" & policynameB & ":" & CycleTimeB
;;         SimLib.triggerEvent UnitChangedPolicy, .name, .policy.AtomicName, "Unit " & .name & " changed policies: " & _
;;             policynameA & ":" & cycletimeA & "->" & policynameB & ":" & CycleTimeB, , simstate.context

        
;;         'parent.parent.trigger SupplyUpdate, .name, .name, "Policy Change Caused Supply Update"
;;         Set PolicyChange_State = ChangeState(unit, nextstate, 0, newduration)
;;         'Decoupled*
;; '        parent.parent.trigger supplyUpdate, .name, .name, "Policy Change Caused Supply Update for unit " & .name
;;         SimLib.triggerEvent supplyUpdate, .name, .name, "Policy Change Caused Supply Update for unit " & .name, , simstate.context

;;         'NOTE -> I may need to consider changing location here.....
;;     Else 'The unit's cycle cannot project onto another cycle.  We need to defer policy change until reset.
;;         'leave the policy on the stack.  Catch it during reset.
;;         ''TOM change 2 Sep 2011 -> we modify the cyclerecord to reflect changes in expectations...
;;         'This is not a replacement...
        
;;         'TOM Change 23 April 2012 -> We do NOT modify the cycle.....screws with expectations?
;;         '.ModifyCycle newpolicy
        
;;         'Decoupled*
;; '        parent.parent.trigger AwaitingPolicyChange, .name, .policy.AtomicName, "Unit " & _
;; '                .name & " in position " & .PositionPolicy & " is waiting until reset to change policies"
;;         SimLib.triggerEvent AwaitingPolicyChange, .name, .policy.AtomicName, "Unit " & _
;;                 .name & " in position " & .PositionPolicy & " is waiting until reset to change policies", , simstate.context
;;         Set unit = RevertState(unit)
;;         'We updated the unit in the process
;;         'Decoupled*
;;         'parent.parent.trigger supplyUpdate, .name, .name, "Policy Change Attempt Caused Supply Update for unit " & .name
;;         SimLib.triggerEvent supplyUpdate, .name, .name, "Policy Change Attempt Caused Supply Update for unit " & .name, , simstate.context
;;     End If
;; End With
;; End Function
