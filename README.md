
# speed-reader

[Live Demo](http://danneu.com/speed-reader)

- A speed-reading app inspired by [Spreeder](http://spreader.com).
- An effort to learn client-side Javascript.
- Written with [ClojureScript](https://github.com/clojure/clojurescript).

## What is it?

The idea is that you can read text much faster by breaking it apart into small chunks of a few words and flashing them quickly onto the screen in one place. 

## How this came to be

I've never really written a client-side application before, and a few days ago I didn't even know the conventional way to go about managing a loop with a "Start" and "Stop" button in Javascript. 

My first naive idea was what I would've written five years ago: 

- A global `isRunning` boolean variable
- A `while (isRunning) { ... }` loop that prints text chunks to the screen
- "Start" and "Stop" buttons that manage the state of `isRunning`

But that is severely underwhelming. And after months of Clojure experience, it made me feel naughty.

Fortunately, dnolen on #clojure wrote me a [snippet using clojure/core.async](https://gist.github.com/swannodette/6330038):

``` clojure
(defn words-proc [words button interval interval-control scrub]
  (go (loop [idx 0]
        (show-word! words idx)
        (let [[v c] (alts! [button interval scrub])
          (condp = c
            button (do (>! interval-control v)
                    (recur idx))
            interval (recur (inc idx))
            scrub (do (>! interval-control :stop)
                    (recur v)))))))
```

While I've looked into `clojure/core.async` before (like my effort to port some Go routines to Clojure: https://gist.github.com/danneu/5941767), I'd never actually used them before.

His proposal looked awesome so I decided to jump in, use ClojureScript, and run with the inspiration that his example gave me.

## The implementation

I don't know any client-side best practices, so I attempted to come up with a high-level design that at least made sense.

The app logic should be contained to a single process that exposes no state. The whole of it runs asynchronously in a `go` block.

``` clojure
(defn iterator [text] 
  (go ...))
```

The UI is hooked up to the iterator through two channels:

- `input` channel for sending commands to the iterator
- `output` for getting real-time state of the iterator

``` clojure
(defn iterator [text] 
  (let [in (chan)
        out (chan)]
    (go ...)
    {:in in :out out))
```

Your UI sends commands to the `in` channel in the form of `[cmd & args]`. For example, here are some of them:

- `[:start]`
- `[:stop]`
- `[:scrub 42]` (seek to the 42nd chunk)

``` clojure
(listen! (by-id "start") :click #(go (>! in [:start])))
(listen! (by-id "start") :click #(go (>! in [:stop])))
(listen! (by-id "scrub") :change #(go (>! in [:scrub (-> % target value int)])))
```

Here's a more complete pseudocodey demonstration of the core idea I was going for:

``` clojure
(defn iterator [text] 
  (let [in (chan)
        out (chan)]
    (go (loop []
          (let [[cmd & args] (<! in)]
            (condp = cmd
              :start ...
              :stop ...
              :scrub ...))))
    {:in in :out out}))

(defn ^:export main []
  (let [{in :in out :out} (iterator "A short story...")]

    ;; Translate UI events to iterator commands
    (listen! (by-id "start") :click #(go (>! in [:start])))
    (listen! (by-id "start") :click #(go (>! in [:stop])))
    (listen! (by-id "scrub") :change #(go (>! in [:scrub (-> % target value int)])))

    ;; Reflect iterator output on UI.
    (go (loop []
          (let [chunk (<! out)]
            (set-text! (by-id "chunk-display") chunk)
            (recur))))))
```

In other words, the iterator doesn't need to know about the UI, and the UI doesn't need to know about the iterator beyond its simple command API.

## Issues

- Poor, unoptimized performance at high WPM and low chunk-size. This is due to extraneous calculations that happen every tick that should be refactored so that they only recalculate when necessary.

