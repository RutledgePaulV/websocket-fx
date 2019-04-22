(defproject org.clojars.rutledgepaulv/websocket-fx "0.1.0-SNAPSHOT"

  :description
  "A re-frame counterpart for websocket-layer"

  :url
  "https://github.com/rutledgepaulv/websocket-fx"

  :license
  {:name "MIT" :url "http://opensource.org/licenses/MIT"}

  :deploy-repositories
  [["releases" :clojars]
   ["snapshots" :clojars]]

  :dependencies
  [[org.clojure/clojure "1.10.0"]
   [org.clojure/clojurescript "1.10.520"]
   [haslett "0.1.4"]
   [org.clojure/core.async "0.4.490"]
   [com.cognitect/transit-cljs "0.8.256"]
   [re-frame "0.10.6"]]

  :source-paths
  ["src"]

  :repl-options
  {:init-ns websocket-fx.core}

  :cljsbuild
  {:builds
   [{:id           "test"
     :source-paths ["test" "src"]
     :compiler     {:preloads             [devtools.preload]
                    :external-config      {:devtools/config {:features-to-install [:formatters :hints]}}
                    :output-to            "run/compiled/browser/test.js"
                    :source-map           true
                    :output-dir           "run/compiled/browser/test"
                    :optimizations        :none
                    :source-map-timestamp true
                    :pretty-print         true}}
    {:id           "karma"
     :source-paths ["test" "src"]
     :compiler     {:output-to     "run/compiled/karma/test.js"
                    :source-map    "run/compiled/karma/test.js.map"
                    :output-dir    "run/compiled/karma/test"
                    :optimizations :whitespace
                    :main          "re_frame_async_flow_fx.test_runner"
                    :pretty-print  true}}]})
