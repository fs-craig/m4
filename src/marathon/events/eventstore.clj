(ns marathon.events.eventstore
  (:use [util.record :only [defrecord+ with-record]]
       [util.metaprogramming :only [defmany keyvals->constants]]))

;Constants used for policy definition, among other things.  Imported from the 
;original VBA implementation for the policystore. 
;Might re-think this, for now it's a way of porting the existing implementation
(def policyconstants 
  {:Bogging "Bogging"
   :Dwelling "Dwelling"
   :BogDeployable "BoggingDeployable"
   :DwellDeployable "DwellingDeployable"
   :Deployable "Deployable"
   :AC12 "AC12" 
   :AC13 "AC13" 
   :RC14 "RC14" 
   :RC15 "RC15" 
   :AC11 "AC11"
   :RC11 "RC11"
   :RC12 "RC12"
   :GhostPermanent12 "GhostPermanent12"
   :GhostPermanent13 "GhostPermanent13"
   :GhostTransient12 "GhostTransient12"
   :GhostTransient13 "GhostTransient13"   
   :reset "Reset"
   :train "Train"
   :ready "Ready"
   :available "Available"
   :deployed "Deployed"
   :Overlapping "Overlapping"
   :SubSymbol  "{>"
   :EquivSymbol "="})
(keyvals->constants policyconstants) ;make the constants first class symbols.
;inherited from substitution rules, may be vestigial.
(keyvals->constants {:Equivalence :Equivalence :Substitution :Substitution})

;TOM Note 6 April 2011 ->
    ;I re-factored much of the guts of this class into a GenericEventStream 
    ;class, which handles all of the subscribing, cataloging, etc., and wraps 
    ;an observer to provide communications.
;This class serves as a proxy for encapsulating what appear to be classic
;;;event;; handlers. Basically, I want to standardize the generation of events 
;(mostly log events in our case), but also to begin to meld the notion of 
;event-driven simulation within the time-step model. The goal is to eventually 
;turn this into an actual event-driven sim. We just manually "trigger" events 
;from calling objects, and have this class handle them. The events are actually 
;methods contained in the class. The only difference between this and the 
;event-step framework, is that we;re calling handlers from a class directly, 
;rather than queuing events in a priorityQ (lose some robustness, since we can 
;have multiple handlers with their own state subscribing to events.
  
;Creating a bandwith for message enumerations.  Right now, each message has a 
;bandwith of ~100. from the msgbands, by convention, all simulation messages 
;should be between 100 and 199
;supply msgs -> 200 -> 299
;demand msgs -> 300 -> 399
;if we need to increase the bandwith later, we can (total messages are actually 
;vast, 2^32)  Right now, I arbitrarily allocated 99 messages worth of space for 
;each gross category off messages.  This aids when we;re trying to categorize 
;messages later, for instance, when adding a bunch of loggers, I want to 
;associate a particular logger only with "Simulation" messages/events.
;As a result, I can keep a sub up to date that will filter out the appropriate 
;messages for it to subscribe to.

;NOTE -> While events appear to be explicitly enumerated here, their actual
;representation is really just a long integer and a dictionary of data.  All of 
;the association of events with names, msgbands, etc. is really local to this 
;object.  The end result is that the library of events can be easily extended 
;(even via modules or scripts) to accomodate custom events.

;Users can define their own ad-hoc events (and should!) that will get assigned 
;to be well beyond the existing bandwidth.
;I will implement a custom-event API for this functionality.  Currently, one 
;could add a custom event using the AddEvent method.

;Really, this is just a method library. We pull the methods from other classes
;(particularly where the method calls are manipulating state across classes, 
;and logging events) . Since we;re working with objects and references, we can 
;do this now.

;May be vestigial.
(def msgbands {:simulation 1 :supply 2 :demand 3 :policy 4 :trending 5})

;ported from VBA (no...I did not do it by hand...not entirely :) )
(def default-events
  {:Simulation
   {:Terminate "Terminating",
    :WatchPolicy "WatchPolicy",
    :sample "Sampling",
    :WatchParameters "WatchParameters",
    :WatchGUI "WatchGUI",
    :UpdateRequest "UpdateRequest",
    :Initialize "Initializing",
    :WatchDemand "WatchDemand",
    :EndofDay "End of Day",
    :WatchTime "WatchTime",
    :BeginDay "Begin Day",
    :WatchFill "WatchFill",
    :PauseSimulation "PauseSimulation",
    :WatchSupply "WatchSupply"}
   :Generic {:all "Generic"}
   :Supply
   {:spawnunit "Spawning Unit",
    :neverDeployed "Never Deployed",
    :supplyUpdate "Supply Update",
    :CycleCompleted "Cycle Completed",
    :MoveUnit "Moving Unit",
    :firstDeployment "First Deployment",
    :UnitChangedPolicy "Unit Changed Policy",
    :outofstock "out of Stock",
    :NewFollowOn "New Follow On",
    :NewDeployable "New Deployable Stock",
    :Deployable "Deployable",
    :updateAllUnits "Update All Units",
    :AwaitingPolicyChange "Awaiting Policy Change",
    :CycleStarted "Cycle Started",
    :MoreSRCAvailable "More SRC Available",
    :FollowingOn "Follow On",
    :deploy "Deploying Unit",
    :ScopedSupply "Scoped Supply",
    :NewSRCAvailable "New SRC Stock",
    :PositionUnit "Positioned Unit",
    :NotDeployable "Not Deployable",
    :UnitPromoted "Unit Promoted",
    :UnitMoved "Unit Moved",
    :AddedUnit "Added Unit",
    :FillDemand "Filling Demand",
    :SpawnGhost "Spawn Ghost",
    :SpawnedTransient "Spawned Transient Unit"}
   :Demand
   {:extendedBOG "Extended BOG",
    :scheduleDemand "Scheduling Demand",
    :DemandFillChanged "Demand Fill Changed",
    :RequestFill "Requesting Fill",
    :StockRequired "Stock Required",
    :CannotFillDemand "Cannot Fill Demand",
    :OverlappingUnit "Overlapping Unit",
    :InfeasibleDemand "Infeasible Demand",
    :ActivateDemand "Activating Demand",
    :demandupdate "DemandUpdate",
    :DeActivateDemand "DeActivating Demand",
    :AddedDemand "Added Demand",
    :ScopedDemand "Scoped Demand",
    :CanFillDemand "Can Fill Demand",
    :DisengageUnit "Sending Unit Home"}
   :Policy
   {:periodChange "Period Change",
    :AddedPeriod "Added Period",
    :policychange "Policy Change"}
   :Trending
   {:UnitNotUtilized "Unit Not Utilized",
    :NewHighWaterMark "New High Water Mark",
    :GetCycleSamples "Getting Cycle Samples",
    :GhostReturned "Ghost Returned",
    :GhostDeployed "Ghost Deployed",
    :LocationSwap "Location Swap",
    :LocationDecrement "Location Decrement",
    :LocationIncrement "Location Increment"}})

(defrecord+ managerofevents [[name "EventManager"]
                             [userevents default-events] 
                             evtstream 
                             streams])  