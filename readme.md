
[![Clojars Project](https://img.shields.io/clojars/v/org.clojars.rutledgepaulv/websocket-fx.svg)](https://clojars.org/org.clojars.rutledgepaulv/websocket-fx)

A Clojurescript library for use with re-frame and [websocket-layer](https://github.com/RutledgePaulV/websocket-layer). Adds
re-frame effect handlers to manage the lifecycle of a websocket (including reconnection) and multiplexing requests, 
subscriptions, and pushes over an individual socket.

### Install

```clojure
[org.clojars.rutledgepaulv/websocket-fx "0.1.2"]
```

### Connecting

This library supports multiple websocket connections at once so all calls
require an opaque id to identify the socket you want to interact with.

```clojure
(require '[websocket-fx.core :as wfx])

(def socket-id :default)

(def options 
  {; optional. defaults to /ws on the current domain. ws if http and wss if https
   :url    "ws://localhost:3000/ws"
   ; optional. defaults to :edn, options are #{:edn :json :transit-json}
   :format :transit-json
   ; optional. additional event to dispatch after the socket is connected
   :on-connect [::websocket-connected]
   ; optional. additional event to dispatch if the socket disconnects
   :on-disconnect [::websocket-disconnected]})

; start the connection process (will happen sometime later)
(rf/dispatch [::wfx/connect socket-id options])

; cleanup / disconnect that particular socket
(rf/dispatch [::wfx/disconnect socket-id])
```

### Sending pushes

Use pushes as a one-way message to the server that expects no message
in direct response.

```clojure
(def push 
  {; required. the actual message to the server
   :message    {:kind :user-visited-page :page "/"}})

(rf/dispatch [::wfx/push socket-id push])
```


### Sending requests

Use requests as an alternative for more traditional xhr calls. Requests
may receive at most one response. If no response is received in a timely
manner the request is considered to have failed. Requests are assumed not
to be idempotent and are not retried in the event of a disconnect.

```clojure
(def request 
  {; required. the actual message to the server
   :message    {:kind :get-users} 
   ; optional. the event to dispatch with the reply when received
   :on-response [::got-users]
   ; optional. the event to dispatch if no reply is received 
   ; within the allotted time after the request was sent
   :on-timeout [::failed-get-users]
   ; optional. defaults to 10000. the amount of time to wait for a reply
   ; to a request before considering the request as failed.
   :timeout    5000})

(rf/dispatch [::wfx/request socket-id request])
```


### Subscribing

Use a subscription when you want to observe something. Subscriptions may receive many
messages from the server over time. Subscriptions must be given IDs so that they can
be removed at a later point in time. Note that either the client or the server can close
a subscription. Subscriptions are assumed to be idempotent and are retried in the event of
a disconnect.

```clojure
(def subscription 
  {; required. the actual message to the server
   :message    {:kind :watch-users} 
   ; optional. the event to dispatch each time a message is received
   :on-message [::users-changed]
   ; optional. the event to dispatch when the subscription is closed (either by server or client)
   :on-close   [::users-watch-closed]})

(def subscription-id :user-watcher)

; notify server, start watching for return messages
(rf/dispatch [::wfx/subscribe socket-id subscription-id subscription])

; notify server you're no longer interested, stop watching for messages
(rf/dispatch [::wfx/unsubscribe socket-id subscription-id])
```

### Introspection

Sometimes you might want to know how many websocket requests / subscriptions are in flight
or what the current connection status of a socket is.

```clojure
(rf/subscribe [::wfx/pending-requests])
(rf/subscribe [::wfx/open-subscriptions])
(rf/subscribe [::wfx/status socket-id])
```

