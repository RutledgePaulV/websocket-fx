(defproject org.clojars.rutledgepaulv/re-frame-websocket-fx "0.1.0-SNAPSHOT"

  :dependencies
  [[org.clojure/clojure "1.10.0"]
   [org.clojure/clojurescript "1.10.520"]
   [haslett "0.1.2"]
   [re-frame "0.10.6"]]

  :source-paths
  ["src"]

  :repl-options
  {:init-ns re-frame-websocket-fx.core}

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
