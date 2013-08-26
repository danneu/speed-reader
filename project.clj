(defproject speed-reader "0.1.0-SNAPSHOT"
  :description "A simple speed reading app."
  :url "http://github.com/danneu/speed-reader"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [org.clojure/core.async "0.1.0-SNAPSHOT"]
                 [org.clojure/clojurescript "0.0-1859"]
                 [domina "1.0.2-SNAPSHOT"]]
  ;; For core.async
  :repositories {"sonatype-oss-public" "https://oss.sonatype.org/content/groups/public/"}
  :plugins [[lein-cljsbuild "0.3.2"]]
  :cljsbuild {:builds [{:source-paths ["src/cljs"]
                        :compiler {:output-to "resources/public/speed_reader.js"}
                        :notify-command ["terminal-notifier"
                                         "-title" "cljsbuild"
                                         "-message"]
                        :optimizations :advanced
                        :pretty-print false}]})
