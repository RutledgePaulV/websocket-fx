(ns re-frame-websocket-fx.websocket-fx
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

(defn get-websocket [url format]
  (let [open (delay
               (async/go
                 (let [{:keys [source] :as ws}
                       (async/<! (haslett/connect url {:format format}))]
                   (assoc ws :mult (async/mult source)))))]
    (force (swap! CONNECTIONS update url #(or % open)))))

(defn websocket-request
  [{:keys [url id format message on-success on-failure timeout]
    :or   {id      (random-uuid)
           url     (websocket-url)
           format  formats/json
           timeout 10000}}]
  (letfn [(response? [{transaction :id proto :proto}]
            (and (= proto :request) (= id transaction)))]
    (async/go
      (let [{:keys [close-status sink mult]}
            (async/<! (get-websocket url format))
            timeout-chan  (async/timeout timeout)
            response-chan (async/tap mult (async/chan 1 (filter response?)))]
        (async/>! sink {:id id :proto :request :data message})
        (async/alts!
          timeout-chan
          ([event]
            (rf/dispatch on-failure))
          response-chan
          ([event]
            (if (some? event)
              (rf/dispatch (conj on-success (:data event)))
              (rf/dispatch on-failure)))
          close-status
          ([event]
            (rf/dispatch on-failure)))))))

(defn websocket-subscription
  [{:keys [url id format message on-message on-close]
    :or   {id     (random-uuid)
           url    (websocket-url)
           format formats/json}}]
  (letfn [(response? [{transaction :id proto :proto}]
            (and (= proto :subscription) (= id transaction)))]
    (async/go
      (let [{:keys [sink mult]}
            (async/<! (get-websocket url format))
            response-chan (async/tap mult (async/chan 1 (filter response?)))]
        (async/>! sink {:id id :proto :subscription :data message})
        (async/go-loop []
          (if-some [msg (async/<! response-chan)]
            (if (true? (:close msg))
              (rf/dispatch (conj on-close (:data msg)))
              (do (rf/dispatch (conj on-message (:data msg))) (recur)))
            (rf/dispatch on-close)))))))

(defn websocket-push
  [{:keys [url id format message]
    :or   {id     (random-uuid)
           url    (websocket-url)
           format formats/json}}]
  (async/go
    (let [{:keys [sink]} (get-websocket url format)]
      (async/>! sink {:id id :proto :push :data message}))))

(rf/reg-cofx :ws-push websocket-push)
(rf/reg-cofx :ws-request websocket-request)
(rf/reg-cofx :ws-subscription websocket-subscription)