(ns marathon.updates.updatestore
  (:require [marathon.updates [updatepacket :as upacket]]))

;'All the update manager does is listen for update request traffic.
;'It records the time of the update request, who made the request, and the future update.
;'It's really just a nice way to decouple and centralize the updating information in the sim.
;'This represents a shift from just requesting a time to update the entire system, to a more
;'localized method of updating individual pieces of the system as needed.
;'It also provides a big hook for our observers....and for debugging purposes.
;'The simulation cannot progress without updates, else every bit of state remains static.
;'When we go to sample the state, we see no changes without updates.


(def default-updates {:supply {} :demand {} :policy {}})
(defrecord updatestore [name         ;the name of the manager  
                        updates      ;a map of all scheduled updates, by time.
                        lastupdate]) ;a map of the last update for each entity.

(def empty-updatestore (->updatestore "UpdateStore" default-updates {}))
(defn get-updates
  "Return a list of all requested updates from the updatestore, where 
   utype is a key for updates, and t is a time index."
  [store update-type  t]
  (get-in store [update-type t] {}))

(defn listen-updates
  "Register the update store as an event listener with the update source.
   Probably re-think this guy."  
  [store source] 
  (add-listener source (:name ustore) ustore :supply-update)
  (add-listener source (:name ustore) ustore :demand-update))

(defn request-update
  "Schedule an update for requestor, of type request, at"
  [store update-time requested-by update-type trequest]
  (let [pending-updates (get-in store [update-type update-time] {})] 
    (assoc-in store [update-type update-time]
      (assoc pending-updates requested-by
       (upacket/->update-packet update-time requested-by update-type trequest)))))
(defn record-update
  "Returns an update store that reflects the  sucessful updating of an entity x, 
   with an updated last know update time for the entity.."
  [store ent t]
  (let [lastupdate (:lastupdate store)]
    (if (contains? lastupdate ent)
      (let [tprev (get lastupdate ent t)
            tnext (if (> t tprev) t tprev)]  
          (assoc-in store [:lastupdate ent] tnext)))))


;Most managers will need a trigger function... 
;We need to find a way to establish "event trigger" behavior for these guys...
(defn trigger [store msgid {:keys [entity-to t]}]
  (record-update store entity-to  t))  
 
