(ns marathon.sim.fill
  (:require [marathon.demand [demanddata :as d] [demandstore :as store]]
            [marathon.supply [unitdata :as udata]]
            [marathon.sim [demand :as dem]   [policy :as pol]
                          [unit :as u]          [fill :as fill]]           
            [sim [simcontext :as sim] [updates :as updates]]
            [util [tags :as tag]]
            [cljgraph [graph :as graph]]))
;TEMPORARILY ADDED for marathon.sim.demand
(declare source-demand)

;TEMPORARILY ADDED - need to fit this somewhere in protocols or core.
(declare realize-fill promise-fill apply-fill)
;promise-fill::'a->simcontext->(simcontext->'a->[filldata,simcontext])
;realize-fill::promise-fill->simcontext->[filldata, simcontext]
;apply-fill::filldata->demand->ctx->ctx


;This is a decoupling of the original TimeStep_ManagerOfFill class.
;The class functions were pulled out and decoupled.  The old
;ManagerOfFill class actually handled the creation of a couple of
;dependent chunks of data; namely a FillFunction, a FillGraph,
;and a SupplyGenerator.  Functions in this module fill the role
;of creating and composing each of these elements in kind.
;
;                        ***About Filling
;The FillFunction provides a high-level wrapper that queries a
;set of rules about feasible and desired relations between elements
;of supply & demand, aka. Fill Rules, along with a candidate supply of
;units, to provide a prioritized sequence of units that can fill said
;demand.  Fill Rules are typically embodied in a Directed Acyclic Graph,
;known as the FillGraph, the topology of which encodes weighted paths
;from demand sinks to sources of supply.  These abstractions are necessary,
;because the concept of priority is highly variable (even time/event dependent),
;and may change from study to study or run to run.
;
;                        ***Fill Rules and The Fill Graph
;FillRules, encoded in a FillGraph, actually tell us a lot before we simulate.
;The FillGraph is generated, as a pre-process step, by analyzing the supply 
;entity records, the demand entity records, and the relation records for 
;Marathon.  Each source provides a unique element of the graph: supply populates
;the graph with source nodes, or terminal nodes that can supply units of a 
;specific type (usually encoded as an SRC).  Demand populates sink nodes of 
;Demand on the graph, which consume units of a specific type.  Relations add 
;nodes to the interior region between source and sink nodes, creating new paths 
;(via substitution and equivilancies), which further relate supply and demand.  
;Together, each dataset is parsed to derive a set of rules, which form the
;topology (or connections) of the graph.  We can then bash the graph with some
;useful algorithms that make it easy to search, scope out useless rules, and
;even do some error checking.
;
;When building the FillGraph, we actually create an implicit dependency graph
;that can tell us which elements of supply and demand are A) Reachable B)Not 
;Reachable. Nodes (usually coded as SRCs, but any string is valid) that are
;Reachable also provide information on how many other nodes they can reach.  
;In most cases, there will be a 1:1 match between a source node, say a Supply 
;of SRC1, and a sink node, say a Demand for SRC1.  In this case, we know that 
;both Supply and Demand contain SRC1, there will be a zero-cost path from 
;SINK_SRC1 -> SRC1 -> SOURCE_SRC1.  Since we allow the possibility of 
;substitutions in our ruleset, there may be more than 1 path from SINK_SRC1, 
;maybe to another source of substitutible supply like SRC2 
;(SINK_SRC1->SRC1_SRC2->SOURCE_SRC2).
;In this scenario, supply for both SRC1 and SRC2 are related, in that there's a 
;dependency introduced by the fill rules.  At a minumum, any simulation for SRC1
;must include SRC2, even if there's no demand for SRC2, because SRC2 "may" serve
;as supply for SRC1.  In this case, the set #{SRC1 SRC2} can be said to form an 
;equivalence class, or they form a strongly connected component.
;
;In the case where there is NO path from either supply or demand, we have 
;"islands", or nodes that are unreachable (class B from the paragraph above).  
;These islands are usually the result of data errors, and indicate missing 
;supply (in the case of unfillable demand), missing demand (in the case of 
;unusable supply), or missing relations (in either case). Pre-processing will 
;automatically find islands, and all equivalence classes / strongly connected
;components in the FillGraph.  One of the benefits of finding equivalence 
;classes is that we can, if we choose, only simulate SRCs that are dependent,
;that is, we can reduce the amount of work and divide the simulation into N 
;smaller simulations.  This can provide a big cost savings for certain analyses,
;and outside of VBA, enables performing runs in parallel.  Finally, 
;pre-processing the FillGraph actually reduces the interior nodes, and provides
;a simplified graph that is very quick to search.
;
;                    ***Querying Rules to Find the Most Suitable Supply
;The current/default scheme for prioritizing supply is to query the ruleset to
;find an ordered set of matches between sets, or buckets, of supply and the
;demand in need of filling.  Due to substitution and other criteria, the buckets
;of supply may be of lower "cost" to utilize for the demand than others.  This 
;corresponds to a weighted path in the FillGraph.  The query is essentially a 
;variation of the K-shortest paths algorithm, where the shortest path is found, 
;then the next, ... as needed.  These paths describe a context, or a 
;justification for selecting a sub set of supply, and serve to classify the 
;entire subset of supply as a certain class, with a uniform priority.  This is
;desirable, as it effectively partitions the search space and provides an 
;efficient means of selecting sets of units for possible deployment.
;
;                    ***Generating Deployable Supply
;A SupplyGenerator takes the possible paths defined by the query, and 
;effectively stitches them together in what appears to be a single sequence of 
;units.  Within each subset of units, a fine-grained prioritization function is
;applied to order the subset of units.  This fine-grained priority may be highly
;variable as well, but the default prioritization is based on a Unit's absolute
;position in its current rotational policy.  This position, or policy 
;coordinate, is a value between [0.0 1.0].  The policy coordinate is computed by
;comparing the unit's time in the current cycle, with the expected length of the
;cycle, or CycleTime / CycleLength, where CycleTime <= CycleLength.  Computing 
;the policy coordinate provides a normalized representation of each unit's 
;"progress" in its lifecycle.  Since typical rotational policies indicate a 
;positive relation between cycle time and readiness, the proportional 
;representation of the policy coordinate provides a convenient measure of 
;readiness as well.
;
;Note*, this assumption holds for known rotational policies, but may fail if 
;readiness is not a function of time in cycle.  Also, other unit prioritization 
;functions exist, including preferences by component (either AC or RC first).  
;Check the TimeStep_UnitComparer class for more.
;
;                    ***Providing a Total Ordering of Supply
;Using the policy coordinate as a comparator, the fill function orders each 
;subset of units so that their policy coordinate, and thus readiness, is sorted
;in descending order.  The total ordering then, is a sorting of units by Min 
;Path Length, then Max policy coordinate.  This provides a natural ordering that
;corresponds with rotational policy, in that units, regardless of component, are
;drawn evenly according to relative readiness, starting with units that directly
;match the capability demanded, and that have had the most time to increase 
;readiness (most capable, most ready), ending with units that least match the 
;capability demanded, with the least amount of time to increase readiness (least 
;capable, least ready). When units deploy, the context of the fill path is 
;annotated on their deployment record, in addition to other stats such as path 
;length.
;
;With a valid, prioritized order of units in hand, the FillFunction tries to 
;fill the demand by selecting units in order until demand is filled, or no more
;units exist.
;
;Constructors to create all three, independently, now exist in this module.  
;Along with decoupled construction, operations for sourcing demands, relative to
;rules specified in a FillFunction, from a supply to a demand, are provided.  
;sourceDemand is probably the most notable/used function, as it...sources 
;demand!
;
;Option Explicit
;
;'TOM Change 21 Sep 2011 -> Ran into the unique circumstance of not using 
;   follow-ons unexpectedly...Arises when we have a higher-priority (either by
;   priority value or by position in the q) demand to fill, but we don't have
;   any eligible supply to fill it.  By default, we short-circuit the fills,
;   because we obey the law that lower-priority fills cannot be filled before 
;   higher, thus higher priority fills will always get the supply.
;   This law no longer holds in the context of follow-on supply.
;      Immediately, we see that iff supply.followons.count = 0, this law 
;      holds.  We process as normal.
;      Iff supply.followns.count >0 then
;          We need to find out which demands have eligible follow-on supply 
;          (if ANY) and fill them first.
;   Demands with eligible follow-on supply will have the same SRC and 
;   demandgroup as the follow-ons.
;

(defn sink-label   [x] [x :sink])   ;memoize!
(defn source-label [x] [x :source]) ;memoize!

;;Refactor -> we don't need a separate rule here really, just wrapping 
;sink-label
(defn derive-rule [demand fillstore] (sink-label (:src demand)))

;account for the fill in the fillstore, basically just conj it to the history.
(defn record-fill [fillstore fill] 
  (let [fillcount (count (:fills fillstore))]
    (assoc-in fillstore [:fills] (inc fillcount) fill)))

;notify everyone that we've filled a demand...
(defn filled-demand! [demand-name unit-name ctx] 
  (sim/trigger :FillDemand demand-name unit-name
    "Filled Demand" nil ctx))  
;ghosts raise special attention when they deploy.
(defn ghost-deployed! [demand-src ctx]
  (sim/trigger :GhostDeployed demand-src demand-src "Filled demand with  ghost" 
               :normal ctx))
;ghosts raise special attention if they followon.
(defn ghost-followed! [demand-src ctx]
  (sim/trigger :GhostDeployed demand-src demand-src 
     "Ghost followed on to another demand" :followon ctx))


(defn followon? [u] (:followoncode u))
(defn ghost-followon? [u] (and (ghost? u) (followon? u)))

;Sourcing a demand is really the composition of three smaller functions: 
;find-supply, take n items from the supply, fill the demand with the n items.

;TOM Change 14 Mar 2011 -> Re-writing this from scratch.  We no longer use 
;baked-in rules. Assuming we have a fillfunction, given a demand, we try to fill
;the demand.  Should be a simple call to the fillfunction object, mostly.
;TOM Change 7 Dec 2010 - Removed parameter for priority scheme, since it DOES
;NOTHING.
;Change from Sub to function, sourceDemand returns a boolean to indicate 
;success or failure. Excised the functionality for handling deactivated demands 
;with units supplied, basically the sub to send units home upon demand 
;deactivation, into a separate sub called SendHome.

(defn source-demand 
  [supplystore parameters fillstore ctx policystore t demand sourcetype 
   & [supplybucket phase]]
  (let [rule     (derive-rule demand fillstore) ;refactor!
        fillfunc (:fillfunction fillstore)] ;rename from fillfunction?
    (if (has-rule? fillfunc rule)
      (let [generator (query fillfunc rule (:demandgroup demand) (:name demand) 
                             supplybucket phase)]
        (if-let [fill-list (generate generator (:required demand) phase)]
          (->> fill-list 
            (reduce record-fill fillstore))))))
  ;INCOMPLETE
  ) 

;We can break source-demand (originally a largish function) into a few smaller
;functions and a reduction:
;  find-supply 'given a fill 
;  

;All a fill-function is, in the old object model, is a partially applied 
;function that closes over a fillgraph, and a supplygenerator.  Since we only 
;had one type of supply generator, the supply generator is really just a 
;function that uses the fill graph to provide an ordered list of supply
;on demand.

;All the fill function did was wrap both the fill graph and a mutable generator.
;It provided an interface to initialize queries and maintain state.  Since we 
;were pulling from multiple "buckets" of supply, according to an ordering 
;dictated by shortest paths, we had the "generator" automatically pointing at 
;multiple buckets....

;The ultimate purpose of a fill function is to provide the answer to a simple 
;query:  Given a demand (or a rule that describes the demand), and a supplystore
;which entities (in order of suitability) are eligible to fill the demand?
;So it's really a very simple, isolated querying mechanism...
;We will actually NEED a reference to a supplystore in this case...
;The mutable version maintained a reference to the supply internally in the 
;fillfunction,  in this case, we are NOT.  So we MAY NEED to provide the 
;supplystore as an extra argument.

;Decoupling the fill process....
;-------------------------------
;First: "find the most suitable supply".

;return an ordered sequence of actions that can result in supply...
;this effectively applies the suitability function related to fillfunc to the 
;rule, the demand, and the supplybucket/supplystore.  The result is a sequence
;of potential fills....where potential fills are data structures that include 
;the context of the fill (i.e. the unit, the actions required to realize the 
;fill, and other meta data).  This represents an ordered sequence of candidate
;fills....we may not, in fact, utilize every candidate.  A better description
;is that find-supply provides a list of  fill-promises, which are realized as
;needed.  A fill-promise is a function that consumes the current context and 
;returns a pair of [promised-unit, new-context].
(defn find-supply [fillfunc rule demand & [supplybucket phase]]
  (when (has-rule? fillfunc rule)
    (query fillfunc rule (:demandgroup demand) (:name demand) 
           supplybucket phase)))

;Second: Allocate a candidate fill against a demand.
;-Assuming we have a candidate fill, and a demand that needs filling
;-the demand is assumed to have inspired the list of candidates, but it's not 
;-consequential for allocation purposes--

;we reduce the sequence of candidates by allocating them to the demand, 
;returning a resulting context that represents the allocated demand.
;--we need a supplementary function here, to realize the candidate and 
;--incorporate it into the context.  In some cases we're actually generating 
;--new, random units, so realizing a promised fill may require substantial 
;--changes to the context (like creating an entity, adding the entity to supply
;--logging the fact, etc).  

(defn check-ghost [unit ctx]
  (if (not (ghost? unit)) ctx
      (if (followon? unit) 
        (ghost-followed! unit ctx) 
        (ghost-deployed! unit ctx))))

;Temporary implementation of apply-fill, this should really be generic or 
;protocol-based.  apply-fill should represent the new context emerging from 
;applying a realized fill, in the form of filldata, to a demand.  Originally,
;this meant that fills would always result in a deployment at the end.  By 
;elevating apply-fill into the API, we can actually implement things that 
;would otherwise be difficult, i.e. delayed fills (pre-allocating units and 
;scheduling them to deploy at a later date, while nominally "filling" the 
;demand).  We could just change deploy-unit as well....
(defn apply-fill [filldata demand ctx]
  (let [unit (:unit filldata)
        fillstore   (get-fillstore ctx)
        supplystore (get-supplystore ctx)
        policystore (get-policystore ctx)
        params      (get-parameters ctx)]
    (->> ctx 
      (supply/deploy-unit supplystore ctx parameters policystore unit t
          quality demand (policy/get-maxbog unit) (count (:fills fillstore))
          filldata (params/interval->date t) (followon? unit))))

;Applies the act of filling a demand, by realizing a promised fill, logging if
;any ghosts were used to fill (may change this...) and updating the context 
;to reflect a.
(defn fill-demand [demand promised-fill ctx]
  (let [[filldata ctx] (realize-fill promised-fill ctx) ;reify our fill.
         unit    (:source filldata)
         quality (:quality filldata)] 
    (->> ctx 
         (filled-demand! (:name demand) (:name unit))
         (check-ghost unit)
         (apply-fill filldata demand)))) 

;Function sourceDemand
; (supplystore As TimeStep_ManagerOfSupply, parameters As TimeStep_Parameters, _
;   fillstore As TimeStep_ManagerOfFill, ctx As TimeStep_SimContext, _
;    policystore As TimeStep_ManagerOfPolicy, t As Single, _
;      demand As TimeStep_DemandData, sourcetype As String, _
;        Optional supplybucket As Dictionary, Optional phase As FillPhase) 
;          As Boolean
;
;Dim rule As String
;Dim fillList As Dictionary
;Dim fill As TimeStep_Fill
;Dim unit As TimeStep_UnitData
;Dim deployer
;
;rule = deriveRule(demand, fillstore)
;
;
;this is new, we now dispatch on the type of fill....
;With fillstore.fillfunction 
;    If .exists(rule) Then 'we can try to fill this demand.
;        .Query rule, demand.demandgroup, demand.name, supplybucket, phase, 
;           supplystore
;        'This will take more....i think context for sure.

;        'try to get the required fills
;        Set fillList = .take(demand.required, phase)  
;        If fillList.count > 0 Then
;            'found units to fill required...
;            If fillList.count = demand.required Then sourceDemand = True 
;            For Each deployer In fillList
                 (for [[deployer fill] fills]
                     
;                Set fill = fillList(deployer)
;               'this may get to be too big at some point, maybe not.....
;                recordFill fill, fillstore 
;                Set unit = fill.source
;                'Decoupled
;                SimLib.triggerEvent FillDemand, demand.name, unit.name, 
;                   "Filled Demand ", , ctx
                    
;                If unit.src = "Ghost" Then
;                    If unit.followoncode = vbNullString Then
;                        'Decoupled
;                    SimLib.triggerEvent GhostDeployed, demand.src,  demand.src, 
;                       "Filled demand with ghost", "normal", ctx
;                    Else
;                        'Decoupled
;                    SimLib.triggerEvent GhostDeployed, demand.src, demand.src, 
;                        "Ghost followed on to another demand", "followon", ctx
;                    End If
;                End If               
;                'TOM Change 30 Aug 2012
                          
;  MarathonOpSupply.deployUnit supplystore, ctx, parameters, policystore, _
;   unit, t, fill.quality, demand, unit.policy.maxbog, fillstore.Fills.count, _
;         fill, parameters.IntervalToDate(t), unit.followoncode <> vbNullString
;  Next deployer
; End If
;End If
;End With
;
;
;End Function

;TOM Change 27 SEp 2012 -> allow fencing of supply via tags...We pass 
;information to the comparer if the unit is fenced to the relative demand or 
;demand group.
;Public Function isInsideFence(uic As TimeStep_UnitData, demandname As String, 
;        followoncode As String, tags As GenericTags) As Boolean
;
;With tags
;    isInsideFence = .hasTag(uic.name, followoncode) Or 
;                    .hasTag(uic.name, demandname)
;End With
;
;End Function

;TOM Change 27 SEp 2012 -> allow fencing of supply via tags...We pass 
;information to the comparer if the unit is fenced to the relative demand or 
;demand group.  If a unit is fenced to a different demand or group, we return
;false.
;Public Function isOutsideFence(uic As TimeStep_UnitData, demandname As String, 
;   followoncode As String, tags As GenericTags) As Boolean
;
;With tags
;    If .hasTag("fenced", uic.name) Then
;        isOutsideFence = Not isInsideFence(uic, demandname, followoncode, tags)
;    Else
;        'by default, units are included if they have no fencing rules.
;        isOutsideFence = False 
;    End If
;End With
;End Function



;'Constructor for building fill stores from component pieces.
;'Note, the fill store has a fillgraph, a fill function, and a supply generator.
;Public Function makeFillStore(fillgraph As TimeStep_FillGraph, fillfunction As 
;   TimeStep_FillFunction, generator As TimeStep_SupplyGenerator) 
;         As TimeStep_ManagerOfFill
;
;Set makeFillStore = New TimeStep_ManagerOfFill
;With makeFillStore
;    Set .fillgraph = fillgraph
;    Set .fillfunction = fillfunction
;End With
;End Function
;
;
;Process that encapsulates creating a new fillstore from coredata, appending 
;the fillstore to the core data, and then returning a scoped set of core data, 
;where the supply and demand have been reduced according to the relations
;embodied by the fillgraph.
;Assumes simState has valid supply, demand, and policystore instances 
;(i.e. they've been initialzed, probably from tables). Returns updated simState.
;Public Function simStateToScopedSimState(simstate As TimeStep_SimState, 
;    Optional generator As TimeStep_SupplyGenerator) As TimeStep_ManagerOfFill
;
;Dim ff As TimeStep_FillFunction
;Dim fg As TimeStep_FillGraph
;Dim fs As TimeStep_ManagerOfFill
;
;With simstate
;    Set fg = composeFillGraph(.supplystore, .demandstore, .policystore)
;    If generator Is Nothing Then _
;        Set generator = makeSupplyGenerator(simstate, , , 
;             simstate.parameters.getKey("DefaultSupplyPriority") = "RCPreSurge")
;    Set ff = makeFillFunction("FillFunction", .supplystore, fg, .parameters, 
;                                .context, generator)
;    Set fs = makeFillStore(fg, ff, generator)
;End With
;
;Set simstate.fillstore = fs
;'Scopes the data as a final step, since we have a handle on the fillgraph.
;Set simstate = scopeSimState(fg, simstate)
;Set ff = Nothing
;Set fg = Nothing
;Set fs = Nothing
;
;End Function
;'Performs a large scoping operation on core data, using any fillgraph.
;Public Function scopeSimState(fillgraph As TimeStep_FillGraph, 
;                          simstate As TimeStep_SimState) As TimeStep_SimState
;scope fillgraph.reducedgraph, simstate.fillstore, simstate.parameters, 
;                simstate.supplystore, simstate.demandstore, simstate.context
;Set scopeSimState = simstate
;End Function

;'Composes pre-built stores of supply, demand, and policy into a fillgraph.
;Public Function composeFillGraph(supplystore As TimeStep_ManagerOfSupply, 
;   demandstore As TimeStep_ManagerOfDemand, 
;      policystore As TimeStep_ManagerOfPolicy) As TimeStep_FillGraph
;Set composeFillGraph = BuildFillGraph(New TimeStep_FillGraph, supplystore, 
;                           demandstore, policystore)
;End Function
;'Composes tables defining supply, demand, and relation records into a fillgraph
;Public Function tablesToFillgraph(sourcesTbl As GenericTable, 
;  sinksTbl As GenericTable, relationsTbl As GenericTable) As TimeStep_FillGraph
;Set tablesToFillgraph = FillGraphFromTables(New TimeStep_FillGraph, sourcesTbl,
;                             sinksTbl, relationsTbl)
;End Function
;
;'Produces a new fill function from inputs.
;Public Function makeFillFunction
; (nm As String, supplystore As TimeStep_ManagerOfSupply, _
;    graph As IGraph, parameters As TimeStep_Parameters, _
;       context As TimeStep_SimContext, _
;           generator As TimeStep_SupplyGenerator) As TimeStep_FillFunction
;
;Set makeFillFunction = New TimeStep_FillFunction
;With makeFillFunction
;    .name = nm
;    'Decoupled
;    Set .parent = supplystore
;    'Eh...this is dubious....TODO -> separate further.
;    Set .FillRules = .AddFill(graph)
;    'Decoupled
;    Set .generator = generator
;End With
;
;End Function
;
;'A constructor for supply generators.  Currently, there's only one kind of 
;'generator, based off the legacy supply system.  We'll have to fix this.  
;'Extensibility via new generators is pretty fundamental. Fergusonmode is a 
;'legacy hack that basically sets a flag to change the prioritization scheme for
;'pre surge periods.  It came about when we needed to use a different 
;'prioritization scheme.... Maybe we should have complex priotization schemes, 
;'just like we have composite policies.
;Public Function makeSupplyGenerator _
;    (simstate As TimeStep_SimState, Optional gpolicy As TimeStep_Policy, _
;        Optional gcompo As String, Optional fergusonmode As Boolean,
;           Optional comparer As IComparator) As TimeStep_SupplyGenerator
;
;Set makeSupplyGenerator = New TimeStep_SupplyGenerator
;
;With makeSupplyGenerator
; 'TODO -> this is just a temporary decoupling.  I need to extract out the 
; 'supply generator further.  Parent is just pointing at a supplystore now 
; '(not unlike a partially applied function).Still, the dependencies kinda suck.
;    'Decoupled
;    'Set .parent = supplystore
;    Set .simstate = simstate
;    'Decoupled
;    If gpolicy Is Nothing Then 
;       Set gpolicy = simstate.policystore.policies("Ghost365_45")
;    If gcompo = vbNullString Then gcompo = "Ghost" 'no component
;    Set .ghostpolicy = gpolicy
;    .ghostcompo = gcompo
;    'Decoupled
;    Set .tags = simstate.supplystore.tags
;    'Too coupled.  Need to fix unit comparison to allow general prioritization.
;    Set .comparer = New TimeStep_ComparerUnit 
;    If fergusonmode Then .comparer.RCpresurgePreference = True
;End With
;
;End Function
;'TODO ->  Do a better job separating concerns here... Building a fill graph and
;'viewing the intermediate results are likely orthogonal...
;'Accumulate a fill graph from supplymanager, policymanager and demands...
;Public Function BuildFillGraph
;    (sourcegraph As TimeStep_FillGraph, _
;       supplystore As TimeStep_ManagerOfSupply, _
;          demandstore As TimeStep_ManagerOfDemand, _
;             policystore As TimeStep_ManagerOfPolicy) As TimeStep_FillGraph
;
;Set BuildFillGraph = sourcegraph
;
;With sourcegraph
;    Set BuildFillGraph = .fromsupply(supplystore)
;    Set BuildFillGraph = .fromPolicy(policystore)
;    Set BuildFillGraph = .FromDemand(demandstore)
;End With
;
;'currently we build this into the fillgraph build process.  We want to know
;'where there are islands....
;sourcegraph.decompose

;End Function
;
;'TODO ->  Do a better job separating concerns here... Building a fill graph and
; viewing the intermediate results are likely orthogonal...
;'Accumulate a fill graph from supplymanager, policymanager and demands...
;Public Function FillGraphFromTables
;          (sourcegraph As TimeStep_FillGraph, _
;             supply As GenericTable, _
;                demand As GenericTable, _
;                   policy As GenericTable) As TimeStep_FillGraph
;
;Set FillGraphFromTables = sourcegraph
;
;With sourcegraph
;    Set FillGraphFromTables = .FromSourceTable(supply)
;    Set FillGraphFromTables = .fromRelationTable(policy)
;    Set FillGraphFromTables = .FromSinkTable(demand)
;End With
;
;'Tom note -> these are debug tools.
;'renderGraph "FullGraph", BuildFillGraph.graph
;'currently we build this into the fillgraph build process.  We want to know
;'where there are islands....
;sourcegraph.decompose
;
;End Function
;
;'This is the simplest initializer for building and initializing a fill store.  
;'Closest to the legacy stuff as well.
;Public Function fillStoreFromTables(simstate As TimeStep_SimState, 
;   sources As GenericTable, sinks As GenericTable, relations As GenericTable) 
;      As TimeStep_ManagerOfFill
;Dim fg As TimeStep_FillGraph
;Dim ff As TimeStep_FillFunction
;Dim sg As TimeStep_SupplyGenerator
;
;Set fg = New TimeStep_FillGraph
;Set fg = FillGraphFromTables(fg, sources, sinks, relations)
;
;Set sg = makeSupplyGenerator(simstate)
;Set ff = makeFillFunction("FillFunction", simstate.supplystore, fg.graph, 
;                              simstate.parameters, simstate.context, sg)
;Set fillStoreFromTables = makeFillStore(fg, ff, sg)
;
;Set sg = Nothing
;Set ff = Nothing
;Set fg = Nothing
;
;End Function
;Public Function staticGraph(supply As GenericTable, _
;                              demand As GenericTable, _
;                                policy As GenericTable) As TimeStep_FillGraph
;
;
;Set staticGraph = New TimeStep_FillGraph
;
;With staticGraph
;    Set staticGraph = .FromSourceTable(supply)
;    Set staticGraph = .fromRelationTable(policy)
;    Set staticGraph = .FromSinkTable(demand)
;End With
;
;'Tom note -> these are debug tools.
;'renderGraph "FullGraph", BuildFillGraph.graph
;'currently we build this into the fillgraph build process.  We want to know
;'where there are islands....
;staticGraph.decompose
;
;
;End Function
;'Sets flags on the fillstore to indicate all graphs should be rendered with 
;'GraphViz
;Public Sub renderAllGraphs(fillstore As TimeStep_ManagerOfFill)
;fillstore.rendergraphs = True
;fillstore.allgraphs = True
;End Sub
;'add the keys from outofscope to the outofscope in fillstore.
;Private Sub scopeOut(fs As TimeStep_ManagerOfFill, outofscope As Dictionary)
;Dim k
;Set fs.outofscope = SetLib.union(fs.outofscope, outofscope)
;End Sub
;'TOM change 24 Mar 2011 -> Utilize the reduced fillgraph to determine which 
;'elements of supply and demand should be scoped out of the study, remove these 
;'elements from demand and supply.
;Public Sub scope
;    (reduced As GenericGraph, fillstore As TimeStep_ManagerOfFill, 
;       parameters As TimeStep_Parameters, _
;          supplystore As TimeStep_ManagerOfSupply, 
;             demandstore As TimeStep_ManagerOfDemand, _
;                ctx As TimeStep_SimContext, Optional csv As Boolean)
;Dim island
;Dim islands As Dictionary
;Dim strm As IRecordStream
;Dim scoperec As GenericRecord
;Dim msg As String
;Dim src As String
;Dim isle
;Dim res As Dictionary
;
;'TOM change 26 Ovt 2012
;'Set islands = findIslands(reduced, fillstore)
;Set islands = findIslands(reduced)
;scopeOut fillstore, islands("OutOfScope")
;
;'Decoupled
;With parameters.outofscope
;
;    'todo....check this, seems hard coded.
;    If csv Then
;        Set strm = New Streamer_CSV
;    Else
;        Set strm = New Streamer_xl
;    End If
;    
;    Set scoperec = New GenericRecord
;    scoperec.AddField "TimeStamp", Now()
;    scoperec.AddField "SRC", vbNullString
;    scoperec.AddField "Reason", vbNullString
;
;    'TOM Change 20 April 2012
;    'Application.ScreenUpdating = False
;    DisableScreenUpdates
;
;    If csv Then
;        strm.init scoperec.fieldnames, "OutOfScope.csv"
;    Else
;        strm.init scoperec.fieldnames, "OutOfScope"
;    End If
;
;    For Each isle In islands("Supply")
;        src = replace(CStr(isle), "SOURCE_", vbNullString)
;        .add src, "No Demand"
;        scoperec.UpdateField "SRC", src
;        scoperec.UpdateField "Reason", "No Demand"
;        strm.writeGeneric scoperec
;    Next isle
;    
;    For Each isle In islands("Demand")
;        src = replace(CStr(isle), "FILLRULE_", vbNullString)
;        .add src, "No Supply"
;        scoperec.UpdateField "SRC", src
;        scoperec.UpdateField "Reason", "No Supply"
;        strm.writeGeneric scoperec
;        
;    Next isle
;    
;    strm.Terminate
;
;    If csv Then
;        Set strm = New Streamer_CSV
;        strm.init scoperec.fieldnames, "InScope.csv"
;
;    Else
;        Set strm = New Streamer_xl
;        strm.init scoperec.fieldnames, "InScope"
;    End If
;
;    Set res = islands("InScope")
;    For Each isle In res
;        If res(isle) = "Demand" Then
;            src = replace(CStr(isle), "FILLRULE_", vbNullString)
;            If Not parameters.SRCsInScope.exists(src) Then _
;                parameters.SRCsInScope.add src, "Demand"
;        ElseIf res(isle) = "Supply" Then
;            src = replace(CStr(isle), "SOURCE_", vbNullString)
;            If Not parameters.SRCsInScope.exists(src) Then _
;                parameters.SRCsInScope.add src, "Supply"
;        Else
;            Err.Raise 101, , "unknown characterization of fill validity"
;        End If
;        scoperec.UpdateField "SRC", src
;        scoperec.UpdateField "Reason", res(isle)
;        strm.writeGeneric scoperec
;    Next isle
;
;    strm.Terminate
;End With
;
;'TOM Change 20 April 2012
;EnableScreenUpdates
;'Application.ScreenUpdating = True
;
;Set strm = Nothing
;
;msg = "FillManager found " & islands("Supply").count & " Unused Supply Sources"
;'Decoupled
;SimLib.triggerEvent ScopedSupply, fillstore.name, supplystore.name, msg, , ctx
;'Tom change 16 June; inserted True into scope, removes units from supply.
;'Decoupled
;MarathonOpSupply.scopeSupply supplystore, islands("Supply"), True
;msg = "FillManager found " & islands("Demand").count & 
;          " Unfillable Demand Sinks"
;'Decoupled
;SimLib.triggerEvent ScopedDemand, fillstore.name, demandstore.name, msg, , ctx
;'Tom change; inserted True into scope, removes demands from demand.
;'Decoupled
;MarathonOpDemand.scopeDemand demandstore, islands("Demand"), True
;
;End Sub
;'Discovers islands in a graph, annotating them in the fillstore and in/out of 
;'scope rules.
;Public Function findIslands(source As GenericGraph, 
;                Optional fillstore As TimeStep_ManagerOfFill) As Dictionary
;Dim res As Dictionary
;Dim islands As Dictionary
;Dim outofscope As Dictionary
;Dim dependencies As Dictionary
;Dim nd
;Dim grph
;Dim subgraph As GenericGraph
;
;Set res = New Dictionary
;
;'TOM Change 26 Oct 2012 -> making this more generic.
;'Rather than needing a fillstore as an arg, all we really need is
;'to capture the items that are OutOfScope.  We can do that in the function
;'and return OutOfScope as an entry in the result, which can then be processed
;'externally in a fillstore.
;
;'res.add "Supply", New Dictionary
;'res.add "Demand", New Dictionary
;'res.add "InScope", New Dictionary
;Set outofscope = New Dictionary
;'res.add "OutOfScope", outofscope
;Set dependencies = New Dictionary
;
;Set res = newdict("Supply", New Dictionary, _
;                  "Demand", New Dictionary, _
;                  "InScope", New Dictionary, _
;                  "OutOfScope", outofscope, _
;                  "Dependencies", dependencies)
;
;'TOM Change 26 Oct 2012
;'With fillstore
;    For Each grph In source.subgraphs
;        Set subgraph = source.subgraphs(grph)
;        'add all the SRCs from this equivalence class
;        dependencies.add CStr(grph), getDependencies(subgraph)
;        If subgraph.nodes.count = 1 Then 'it's an island.
;            For Each nd In subgraph.nodes
;                Set islands = res(islandType(CStr(nd)))
;                islands.add CStr(nd), 0
;                'TOM change 26 OCt 2012
;                '.outofscope.add CStr(nd), 0
;                outofscope.add CStr(nd), 0
;                source.RemoveNode CStr(nd) 'eliminate the island
;                source.subgraphs.Remove (grph) 'eliminate the subgraph
;                Set subgraph = Nothing
;            Next nd
;        Else 'the source is in scope...
;            Set islands = res("InScope")
;            For Each nd In subgraph.nodes
;                islands.add CStr(nd), islandType(CStr(nd))
;            Next nd
;        End If
;    Next grph
;'End With
;
;Set findIslands = res
;Set res = Nothing
;
;End Function
;Private Function getDependencies(gr As IGraph) As Dictionary
;Dim nd
;Dim x As String
;
;Set getDependencies = New Dictionary
;For Each nd In GraphLib.getNodes(gr)
;    x = translateRule(CStr(nd))
;    If Not (getDependencies.exists(x)) Then
;        getDependencies.add x, 0
;    End If
;Next nd
;    
;End Function
;Public Function translateRule(ByRef inrule As String) As String
;Dim tmp
;tmp = Split(inrule, "_")
;If UBound(tmp, 1) = 1 Then
;    translateRule = tmp(1)
;Else
;    Err.Raise 101, , 
;      "Irregular rule :" & inrule & " should be delimited by a single _ "
;End If
;End Function
;'Aux function to describe the type of island, whether a source or a sink.
;Private Function islandType(nodename As String) As String
;If InStr(1, nodename, "SOURCE") > 0 Then
;    islandType = "Supply"
;ElseIf InStr(1, nodename, "FILLRULE") > 0 Then
;    islandType = "Demand"
;Else
;    Err.Raise 101, , "Island is neither supply nor demand"
;End If
;
;End Function
;
;'Public Sub fromExcel()
;''Decouple
;'init parent
;'End Sub
;
;'Public Sub FromTables(sources As GenericTable, sinks As GenericTable, 
;                            relations As GenericTable)
;''Decouple
;'init parent, True, sources, sinks, relations
;'End Sub
;'



