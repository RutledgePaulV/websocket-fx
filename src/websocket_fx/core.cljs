(ns websocket-fx.core
  (:require [re-frame.core :as rf]
            [clojure.string :as strings]
            [haslett.format :as formats]
            [haslett.client :as haslett]
            [cljs.core.async :as async]))


(defonce CONNECTIONS (atom {}))

(defn get-websocket-port []
  (str (aget js/window "location" "port")))

(defn get-websocket-host []
  (str (aget js/window "location" "hostname")))

(defn get-websocket-proto []
  (let [proto (str (aget js/window "location" "protocol"))]
    (get {"http:" "ws" "https:" "wss"} proto)))

(defn websocket-url []
  (let [proto (get-websocket-proto)
        host  (get-websocket-host)
        port  (get-websocket-port)
        path  "/ws"]
    (if (strings/blank? port)
      (str proto "://" host path)
      (str proto "://" host ":" port path))))


(defn dissoc-in [m [k & ks]]
  (if ks
    (if (map? (get m k))
      (update m k #(dissoc-in % ks))
      m)
    (dissoc m k)))

(def keyword->format
  {:edn                  formats/edn
   :json                 formats/json
   :transit-json         formats/transit
   :transit-json-verbose formats/transit})


;;; SOCKETS

(rf/reg-event-fx
  ::connect
  (fn [{:keys [db]} [_ socket-id command]]
    (let [data {:id      socket-id
                :status  :pending
                :options command}]
      {:db       (assoc-in db [::sockets socket-id] data)
       ::connect {:socket-id socket-id :options command}})))

(rf/reg-event-fx
  ::disconnect
  (fn [{:keys [db]} [_ socket-id]]
    {:db          (dissoc-in db [::sockets socket-id])
     ::disconnect {:socket-id socket-id}}))

(rf/reg-event-fx
  ::connected
  (fn [{:keys [db]} [_ socket-id]]
    {:db
     (assoc-in db [::sockets socket-id :status] :connected)
     :dispatch-n
     (for [sub (vals (get-in db [::sockets socket-id :subscriptions] {}))]
       [::subscribe (get sub :id) (get sub :data)])}))

(rf/reg-event-fx
  ::disconnected
  (fn [{:keys [db]} [_ socket-id cause]]
    (let [options (get-in db [::sockets socket-id :options])]
      {:db
       (assoc-in db [::sockets socket-id :status] :disconnected)
       :dispatch-n
       (for [request-id (keys (get-in db [::sockets socket-id :requests] {}))]
         [::request-failed socket-id request-id cause])
       :dispatch-later
       [{:ms 2000 :dispatch [::connect socket-id options]}]})))

;;; REQUESTS

(rf/reg-event-fx
  ::request
  (fn [{:keys [db]} [_ socket-id {:keys [message timeout on-success on-failure]}]]
    (let [payload {:id (random-uuid) :proto :request :data message}
          path    [::sockets socket-id :requests (get payload :id)]]
      {:db         (assoc-in db path {:message message :timeout timeout :on-success on-success :on-failure on-failure})
       :ws-message {:socket-id socket-id
                    :message   (assoc payload :timeout timeout)}})))

(rf/reg-event-fx
  ::request-success
  (fn [{:keys [db]} [_ socket-id request-id & more]]
    (let [path [::sockets socket-id :requests request-id]]
      (if-some [request (get-in db path)]
        {:db       (dissoc-in db path)
         :dispatch (vec (concat (:on-success request) more))}
        {}))))

(rf/reg-event-fx
  ::request-failed
  (fn [{:keys [db]} [_ socket-id request-id & more]]
    (let [path [::sockets socket-id :requests request-id]]
      (if-some [request (get-in db path)]
        (cond->
          {:db (dissoc-in db path)}
          (contains? request :on-failure)
          (assoc :dispatch (vec (concat (get request :on-failure) more))))))))


;;; SUBSCRIPTIONS

(rf/reg-event-fx
  ::subscribe
  (fn [{:keys [db]} [_ socket-id topic {:keys [message] :as command}]]
    (let [path [::sockets socket-id :subscriptions topic]]
      {:db          (assoc-in db path command)
       ::ws-message {:socket-id socket-id
                     :message   {:id    topic
                                 :proto :subscription
                                 :data  message}}})))

(rf/reg-event-fx
  ::subscription-message
  (fn [{:keys [db]} [_ socket-id subscription-id response]]
    (let [path [::sockets socket-id :subscriptions subscription-id]]
      (if-some [subscription (get-in db path)]
        (if-some [on-message (:on-message subscription)]
          {:dispatch (conj on-message response)}
          {})
        {}))))

(rf/reg-event-fx
  ::cancel-subscription
  (fn [{:keys [db]} [_ socket-id subscription-id & more]]
    (let [path [::sockets socket-id :subscriptions subscription-id]]
      (if-some [request (get-in db path)]
        (cond->
          {:db          (dissoc-in db path)
           ::ws-message {:socket-id socket-id
                         :message   {:id    subscription-id
                                     :proto :subscription
                                     :close true}}}
          (contains? request :on-close)
          (assoc :dispatch (vec (concat (get request :on-close) more))))))))

(rf/reg-event-fx
  ::subscription-canceled
  (fn [{:keys [db]} [_ socket-id subscription-id & more]]
    (let [path [::sockets socket-id :subscriptions subscription-id]]
      (if-some [request (get-in db path)]
        (cond->
          {:db (dissoc-in db path)}
          (contains? request :on-close)
          (assoc :dispatch (vec (concat (get request :on-close) more))))))))


;;; PUSH

(rf/reg-event-fx
  ::push
  (fn [_ [_ socket-id command]]
    {::ws-message
     {:socket-id
      socket-id
      :message
      {:id    (random-uuid)
       :proto :push
       :data  command}}}))


(rf/reg-fx
  ::connect
  (fn [{socket-id
        :socket-id
        {:keys [url format on-connect on-disconnect]
         :or   {format :edn
                url    (websocket-url)}}
        :options}]
    (async/go
      (let [{:keys [socket source sink close-status]}
            (async/<! (haslett/connect url {:format (keyword->format format)}))
            mult (async/mult source)]
        (async/go
          (when-some [closed (async/<! close-status)]
            (rf/dispatch [::disconnected socket-id closed])
            (when (some? on-disconnect)
              (rf/dispatch on-disconnect))))
        (when-not (async/poll! close-status)
          (let [sink-proxy (async/chan)]
            (async/go-loop []
              (when-some [{:keys [id proto data close timeout] :or {timeout 10000}} (async/<! sink-proxy)]
                (cond
                  (#{:request} proto)
                  (let [xform         (filter (fn [msg] (= (:id msg) id)))
                        response-chan (async/tap mult (async/chan 1 xform))
                        timeout-chan  (async/timeout timeout)]
                    (async/go
                      (let [[value _] (async/alts! [timeout-chan response-chan])]
                        (if (some? value)
                          (rf/dispatch [::request-success socket-id id (:data value)])
                          (rf/dispatch [::request-failed socket-id id :timeout])))))
                  (#{:subscription} proto)
                  (let [xform         (filter (fn [msg] (= (:id msg) id)))
                        response-chan (async/tap mult (async/chan 1 xform))]
                    (async/go-loop []
                      (when-some [msg (async/<! response-chan)]
                        (rf/dispatch [::subscription-message socket-id id (:data msg)])
                        (recur)))))
                (when (if (some? close)
                        (async/>! sink {:id id :proto proto :data data :close close})
                        (async/>! sink {:id id :proto proto :data data}))
                  (recur))))
            (swap! CONNECTIONS assoc socket-id {:sink sink-proxy :source source :socket socket}))
          (rf/dispatch [::connected socket-id])
          (when (some? on-connect)
            (rf/dispatch on-connect)))))))

(rf/reg-fx
  ::disconnect
  (fn [{:keys [socket-id]}]
    (when-some [{:keys [socket]} (get @CONNECTIONS socket-id)]
      (.close socket))))

(rf/reg-fx
  ::ws-message
  (fn [{:keys [socket-id message]}]
    (if-some [{:keys [sink]} (get @CONNECTIONS socket-id)]
      (async/put! sink message)
      (.error js/console "Socket with id " socket-id " does not exist."))))


;;; INTROSPECTION

(rf/reg-sub
  ::pending-requests
  (fn [db [_ socket-id]]
    (vals (get-in db [::sockets socket-id :requests]))))

(rf/reg-sub
  ::pending-subscriptions
  (fn [db [_ socket-id]]
    (vals (get-in db [::sockets socket-id :subscriptions]))))

(rf/reg-sub
  ::status
  (fn [db [_ socket-id]]
    (get-in db [::sockets socket-id :status])))