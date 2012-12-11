(ns marathon.port.data.demandstore)

(defrecord demandstore [name demandmap infeasible-demands 
                        unfilledq  activations deactivations 
                        eligbledemands changed demandtraffic 
                        tryfill loginfeasibles tags 
                        fillables verbose tlastdeactivation]) 
(defn empty-demandstore [] 
  (demandstore. "DemandStore" {} {} 
                 nil {} {} 
                 {} {} true 
                 false  {"Sinks" {}} {} 
                 false false -1))

;Option Explicit
;'TOM Change 17 DEC 2010
;'Relocated disparate demand functionality here.
;'Absorbed components from storage, initialize, and mainmodel (mostly mainmodel) .
;'Conceptually, the demand manager should contain all required state and methods for modifying
;'demand vectors during the simulation.
;Public name As String
;'TOM change 6 Dec 2010
;
;Public demandmap As Dictionary 'KVP mapping of demand names to indices in aDEFdata
;Public DemandIndex As Dictionary
;'TOM change 7 Dec 2010
;'Dictionary to capture the set infeasible (non substitutable) Demand SRCs.
;'This is initialized and maintained in Initialize.createEmpiricalDEFDemandArrays
;'and then utilized depending on whether ScopeToSupply is active.
;Public infeasibledemands As Dictionary
;
;'TOM change 8 Mar 2011...
;'Fill routine will be modifed to support advanced substitution rules....
;'Basically, the substitution rules form a directed graph that guides the search for fills.
;'In practice, this graph will be static (we can cache the results).
;'From a given start node (a fill rule associated with the demand), we can traverse the graph, trying to
;'find the shortest path to a universal terminal node called fill.
;'Actually....when we add supply, we can very quickly check to see if a fill is possible...
;'Given a fill rule, we see if there are any active paths to get to fill.
;'From there, we branch back to find a fill for the given demand rule.
;
;'TOM Change 6 Dec 2010
;'UnfilledQ is, on the surface, a dictionary of key,value pairs. The keys are simple strings (or integers even)
;'that define:
;    'A Heterogenous set of
;        '[Homogeneous Collections of
;            '[Substitutable/Interchangeable Resources] in Priority Order
;            
;'The point of all this is to map our notion of priority, specifically in the filling of demand, into something that
;'can fit with substitution, the idea that we can utilize multiple resources to fill a slot, given appropriate rules.
;'We want to be able to do all this really friggin' fast too.
;'TOM Change 6 Dec 2010
;'At the base, UnfilledQ's key's then, point to SortedDictionaries of DemandNames (strings)
;'The DemandNames are grouped or partitioned according to the substitution possibilities inherent in their SRC.
;'This allows us to enforce the mechanism of prioritization of demand, without stopping after the first unfilled demand.
;'We're using a heap to model this underneath, so if we separate the Demands into what appear to be homogeneous collections
;'(substitutible SRCs) , we can then judge them evenly on priority.
;'In the case of no substitution, we have purely independent demands, which are already homogeneous, which we process the
;'same way.
;'NOTE - * Right now, our categories are just the SRC string associated with the demand. This creates the latter environment:
;'a set of purely independent demands (heterogenous by SRC) , which are worked on in parallel.
;Public UnfilledQ As Dictionary
;Public activations As Dictionary 'Set of Days when demands are activated
;Public deactivations As Dictionary 'Set of Days when demands are deactivated
;Public activedemands As Dictionary 'Dynamic set of active demands, changes with t
;Public eligibleDemands As Dictionary 'Dynamic set of active demands eligible for follow-on supply.
;
;Public changed As Dictionary 'Dynamic set of active demands that changed fill count during the course of the day.
;
;'Tom Change 4 August 2011
;
;Public demandtraffic As Boolean
;Public TryFill As Boolean 'boolean flag that acts as a logic gate to determine if we should even try
;                          'to fill demands.  Triggered by the StockWatcher observer, upon recieving
;                          'New Stock events.
;
;'TOM Change 13 Jan 2011 -> HACK
;Public loginfeasibles As Boolean
;'Tom change 15 Mar 2011
;Public tags As GenericTags
;Public fillables As Dictionary
;
;Private tmptags As Dictionary
;Private msg As String
;
;Public Verbose As Boolean
;Public tlastdeactivation As Single
;
;Implements IVolatile
;
;Private Sub Class_Initialize()
;'Purpose of this collection is to serve as a Q (ideally a priority q, but for now just a collection)
;'We'll use this to capture unfilled demand. Drastically reduce our runtime because we won't LOOK
;'to fill any demand if unfillq.count = 0
;'Futhermore, we'll get information for only specific unfilled demands. No need to look wastefully.
;'TOM Change 18 Dec 2010 -> Moved to DemandManager Initialization
;
;Set UnfilledQ = New Dictionary
;Set demandmap = New Dictionary
;Set DemandIndex = New Dictionary
;Set activations = New Dictionary
;Set deactivations = New Dictionary
;Set activedemands = New Dictionary
;
;Set changed = New Dictionary
;
;loginfeasibles = True
;
;name = "DemandManager"
;
;Set tags = New GenericTags
;tags.addTag "Sinks"
;
;Set fillables = New Dictionary
;tlastdeactivation = -1
;End Sub



