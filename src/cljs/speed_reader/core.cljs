(ns speed-reader.core
  (:require [cljs.core.async :refer [<! >! chan close! go]]
            [domina :refer [by-id
                            remove-attr!
                            sel
                            set-attr!
                            set-styles!
                            set-text!
                            set-value!
                            text
                            value]]
            [domina.events :refer [listen! target]])
  (:require-macros [cljs.core.async.macros :refer [go alt!]]))

;; Util ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn log [s]
  (.log js/console s))

(defn calc-delay
  "Returns the delay in milliseconds needed to maintain given WPM."
  [wpm chunk-size]
  (let [ticks-per-min (/ wpm chunk-size)]
    (/ (* 60 1000) ticks-per-min)))

(defn calc-chunk-idx
  "Returns the idx of the chunk that contains this word."
  [word-idx chunk-size]
  (quot word-idx chunk-size))

;; Iterator ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn stop-ticker
  "Turns off ticker. Returns nil."
  [ticker]
  (js/clearInterval ticker)
  nil)

(defn restart-ticker
  "Returns ticker that will tick at given delay."
  [c ticker delay]
  (stop-ticker ticker)  ; Only allow a single ticker to exist.
  (js/setInterval #(go (>! c [:tick])) delay))

(defn iterator
  [words]
  (let [in (chan)
        out (chan)
        word-count (count words)]
    ;; Loop state sorta grew out of control in an effort to isolate recalculation.
    ;; It feels ugly lugging around both chunk-idx and word-idx, but maintaining
    ;; word-idx makes it less surprising for user when they change chunk-size.
    (go (loop [chunks (partition 1 words)
               chunk-idx 0
               chunk-size 1
               word-idx 0
               wpm 300
               ticker nil]
          (if (< chunk-idx (count chunks))
            (>! out [:tick {:chunk (nth chunks chunk-idx)
                            :chunk-idx chunk-idx
                            :chunk-count (count chunks)
                            :chunk-size chunk-size
                            :ticker ticker
                            :word-count word-count
                            :word-idx word-idx
                            :wpm wpm}])
            (do (log "Out of range") (stop-ticker ticker)))

          (let [[cmd & args] (<! in)]
            (condp = cmd
              ;; [:start wpm chunk-size]
              :start (let [[wpm chunk-size] args
                           delay (calc-delay wpm chunk-size)]
                       (recur (partition chunk-size words)
                              chunk-idx
                              chunk-size
                              word-idx
                              wpm
                              (restart-ticker in ticker delay)))
              ;; [:stop]
              :stop (recur chunks
                           chunk-idx
                           chunk-size
                           word-idx
                           wpm
                           (stop-ticker ticker))
              ;; [:reset]
              :reset (recur chunks
                            0
                            chunk-size
                            0
                            wpm
                            (stop-ticker ticker))
              ;; [:tick]
              :tick (recur chunks
                           (inc chunk-idx)
                           chunk-size
                           (+ word-idx chunk-size)
                           wpm
                           ticker)

              ;; [:tick new-word-idx]
              :scrub (let [[new-word-idx] args]
                       (recur chunks
                              (calc-chunk-idx new-word-idx chunk-size)
                              chunk-size
                              new-word-idx
                              wpm
                              (stop-ticker ticker)))

              ;; [:chunk-size new-chunk-size]
              :chunk-size (let [[new-chunk-size] args]
                            (recur chunks
                                   (calc-chunk-idx word-idx new-chunk-size)
                                   new-chunk-size
                                   word-idx
                                   wpm
                                   (stop-ticker ticker)))

              ;; [:wpm new-wpm]
              :wpm  (let [[new-wpm] args]
                      (recur chunks
                             chunk-idx
                             chunk-size
                             word-idx
                             new-wpm
                             (stop-ticker ticker)))))))
    {:in in :out out}))

(defn parse-content-box
  "Returns vector of words from the content box."
  []
  (let [content (value (by-id "content"))]
    (remove empty? (vec (.split content #"\s+")))))

;; TODO: Decide on how to organize my events. Haven't given it much thought.

(declare attach-input-ui attach-output-ui)

(defn ^:export main []
  ;; TODO: Improve the split and handle weird cases. Strip various punctuation. etc.
  (let [words (parse-content-box)
        {in :in out :out} (iterator words)]
    (attach-input-ui in)
    (attach-output-ui out))

  ;; The rest of these events are UI events that aren't attached to the iterator.

  ;; (listen! (by-id "load")
  ;;          :click
  ;;          #(attach-ui (iterator (parse-content-box))))

  ;; Update font size of $sheet

  (listen! (by-id "font-size-slider")
           :change
           (fn [evt]
             (let [new-size (-> evt target value)]
               (set-styles! (by-id "sheet") {:font-size (str new-size "%")}))))

  ;; Update the slider display UI when user changes slider.

  (listen! (by-id "wpm-slider")
           :change
           #(set-text! (by-id "wpm-slider-display") (-> % target value)))

  (listen! (by-id "chunk-slider")
           :change
           #(set-text! (by-id "chunk-slider-display") (-> % target value)))

  (listen! (by-id "font-size-slider")
           :change
           #(set-text! (by-id "font-size-slider-display") (-> % target value))))

(defn attach-input-ui
  "Attaches events to an iterator's command channel."
  [in]
  (listen! (by-id "start")
           :click
           (fn []
             (let [wpm (int (value (by-id "wpm-slider")))
                   chunk-size (int (value (by-id "chunk-slider")))]
               (go (>! in [:start wpm chunk-size])))))

  (listen! (by-id "stop")
           :click
           (fn []
             (go (>! in [:stop]))))
  
  (listen! (by-id "reset")
           :click
           (fn []
             (go (>! in [:reset]))))

  ;; User is scrubbing to a new chunk.
  (listen! (by-id "progress-slider")
           :change
           (fn [evt]
             (let [new-word-idx (-> evt target value int)]
               (go (>! in [:scrub new-word-idx])))))

  ;; User is changing the chunk-size.
  (listen! (by-id "chunk-slider")
           :change
           (fn [evt]
             (let [new-chunk-size (-> evt target value int)]
               (go (>! in [:chunk-size new-chunk-size])))))

  ;; User is changing WPM.
  (listen! (by-id "wpm-slider")
           :change
           (fn [evt]
             (let [new-wpm (-> evt target value int)]
               (go (>! in [:wpm new-wpm]))))))


(defn attach-output-ui
  "Hook up iterator `out` channel to UI"
  [out]
  ;; This loop consumes data from the iterator's `out` channel and
  ;; updates the UI.
  (let [;; Cache element lookup
        $sheet (by-id "sheet")
        $progress-slider (by-id "progress-slider")
        $progress-chunk-idx (by-id "progress-chunk-idx")
        $progress-chunk-count (by-id "progress-chunk-count")
        $read-time-seconds (by-id "read-time-seconds")
        $chunk-time-ms (by-id "chunk-time-ms")]
    (go (loop []
          (let [[cmd & args] (<! out)]
            ;;(log (str "OUT: [" cmd " " (clojure.string/join " " args) "]"))
            (condp = cmd
              ;; I ended up cramming all of the iterator's state into a single
              ;; :tick command, but I may want to split up the state into
              ;; multiple commands.
              :tick (let [[{:keys [chunk
                                   chunk-count
                                   chunk-idx
                                   chunk-size
                                   ticker
                                   word-count
                                   word-idx
                                   wpm]}] args]

                      ;; Current chunk
                      (set-text! $sheet (clojure.string/join " " chunk))
                      (set-value! $progress-slider chunk-idx)
                      (set-text! $progress-chunk-idx chunk-idx)

                      ;; Chunk count
                      (set-text! $progress-chunk-count chunk-count)
                      (set-attr! $progress-slider "max" (dec chunk-count))

                      ;; Time calculations
                      (set-text! $read-time-seconds (int (* (/ word-count wpm) 60)))
                      (set-text! $chunk-time-ms (int (calc-delay wpm chunk-size)))

                      ;; Debug
                      (set-text! (by-id "word-idx") word-idx)
                      (set-text! (by-id "chunk-idx") chunk-idx)

                      ;; Start/Stop buttons
                      (if ticker
                        (do (set-attr! (by-id "start") "disabled" true)
                            (remove-attr! (by-id "stop") "disabled"))
                        (do (remove-attr! (by-id "start") "disabled")
                            (set-attr! (by-id "stop") "disabled" true))))))
          (recur)))))
