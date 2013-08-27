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

(defn update-debug-ui [word-idx chunk-idx]
  (set-text! (by-id "word-idx") word-idx)
  (set-text! (by-id "chunk-idx") chunk-idx))

;; Ticker ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn update-ticker-ui
  "Updates Start/Stop button state."
  [state]
  (if (= state :on)
    (do (set-attr! (by-id "start") "disabled" true)
        (remove-attr! (by-id "stop") "disabled"))
    (do (remove-attr! (by-id "start") "disabled")
        (set-attr! (by-id "stop") "disabled" true))))

(def ticker (atom nil))

(defn stop-ticker []
  (when @ticker
    (js/clearInterval @ticker)
    (update-ticker-ui :off)))

(defn restart-ticker [ch delay]
  (stop-ticker)  ; Only allow a single ticker to exist.
  (reset! ticker
          (js/setInterval (fn [] (go (>! ch [:tick])))
                          delay))
  (update-ticker-ui :on))

;; General UI ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn update-ui
  [chunks chunk-idx]

  ;; Update displayed chunks in #sheet
  (set-text! (by-id "sheet")
             (clojure.string/join " " (nth chunks chunk-idx)))

  ;; Update #progress-slider since it scrubs itself as the reader reads.
  (set-value! (by-id "progress-slider") chunk-idx)
  (set-attr! (by-id "progress-slider") "max" (dec (count chunks)))

  ;; Update #progress-slider-display
  (set-text! (by-id "progress-slider-display")
             (str (inc chunk-idx) "/" (count chunks)))

  )

;; Iterator process ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn iterator
  "The core idea is to have the iterator as a process that you hook up to your
   UI with core.async channel.

   Returns a channel that expects commands in form of [command & args]."
  [words]
  (let [c (chan)]
    (go (loop [chunk-size 1
               word-idx 0
               wpm 300]
          (let [chunk-idx (calc-chunk-idx word-idx chunk-size)
                chunks (partition chunk-size words)]


            ;; TODO: Have iterator send its iteration through a channel
            ;;       so view layer can update itself.
            (update-debug-ui word-idx chunk-idx)
            (set-text! (by-id "read-time-seconds")
                       (int (* (/ (count words) wpm) 60)))
            (set-text! (by-id "chunk-time-ms")
                       (int (calc-delay wpm chunk-size)))
            ;; /extralazy section

            (if (< chunk-idx (count chunks))
              (update-ui chunks chunk-idx)
              (do (log "Out of range") (stop-ticker)))

            (let [[cmd & args] (<! c)]
              (condp = cmd
                ;; [:start wpm chunk-size]
                :start (let [[wpm chunk-size] args
                             delay (calc-delay wpm chunk-size)]
                         (do (restart-ticker c delay)
                             (recur chunk-size
                                    word-idx
                                    wpm)))
                ;; [:stop]
                :stop (do (stop-ticker)
                          (recur chunk-size
                                 word-idx
                                 wpm))
                ;; [:reset]
                :reset (do (stop-ticker)
                           (recur chunk-size
                                  0
                                  wpm))
                ;; [:tick]
                :tick (recur chunk-size
                             (+ word-idx chunk-size)
                             wpm)

                ;; [:tick new-word-idx]
                :scrub (let [[new-word-idx] args]
                         (stop-ticker)
                         (recur chunk-size
                                new-word-idx
                                wpm))

                ;; [:chunk-size new-chunk-size]
                :chunk-size (let [[new-chunk-size] args]
                              (stop-ticker)
                              ;(restart-ticker c delay)
                              (recur new-chunk-size
                                     word-idx
                                     wpm))

                ;; [:wpm new-wpm]
                :wpm  (let [[new-wpm] args]
                        (stop-ticker)
                        (recur chunk-size
                               word-idx
                               new-wpm)))))))

    ;; Behold, the levitating c.
    
    c

    ))

(defn attach-ui
  "Attaches events to an iterator's command channel."
  [c]
  (listen! (by-id "start")
           :click
           (fn []
             (let [wpm (int (value (by-id "wpm-slider")))
                   chunk-size (int (value (by-id "chunk-slider")))]
               (go (>! c [:start wpm chunk-size])))))

  (listen! (by-id "stop")
           :click
           (fn []
             (go (>! c [:stop]))))
  
  (listen! (by-id "reset")
           :click
           (fn []
             (go (>! c [:reset]))))

  (listen! (by-id "progress-slider")
           :change
           (fn [evt]
             (let [new-word-idx (-> evt target value int)]
               (go (>! c [:scrub new-word-idx])))))

  (listen! (by-id "chunk-slider")
           :change
           (fn [evt]
             (let [new-chunk-size (-> evt target value int)]
               (go (>! c [:chunk-size new-chunk-size])))))

  (listen! (by-id "wpm-slider")
           :change
           (fn [evt]
             (let [new-wpm (-> evt target value int)]
               (go (>! c [:wpm new-wpm]))))))

(defn parse-content-box
  "Returns vector of words from the content box."
  []
  (let [content (value (by-id "content"))]
    (remove empty? (vec (.split content #"\s+")))))

;; TODO: Decide on how to organize my events. Haven't given it much thought.
(defn slider-updater
  "A lame abstraction."
  [input-id]
  (let [slider-el (by-id input-id)
        display-el (by-id (str input-id "-display"))]
    (listen! slider-el
             :change
             (fn [evt]
               (let [el-value (-> evt target value int)]
                 (log (str ":change " input-id " - " el-value))
                 (set-text! display-el el-value))))))

(defn ^:export main []
  ;; TODO: Improve the split and handle weird cases. Strip various punctuation. etc.
  (let [words (parse-content-box)
        c (iterator words)]
    (attach-ui c))

  ;; These are UI events that aren't attached to the iterator.

  (listen! (by-id "font-size-slider")
           :change
           (fn [evt]
             (let [new-size (-> evt target value)]
               (set-styles! (by-id "sheet")
                            {:font-size (str new-size "%")}))))

  (listen! (by-id "load")
           :click
           (fn [evt]
             (attach-ui (iterator (parse-content-box)))))

  (slider-updater "wpm-slider")
  (slider-updater "chunk-slider")
  (slider-updater "font-size-slider"))
