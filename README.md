
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

Fortunately, dnolen on #clojure wrote my a [snippet using clojure/core.async](https://gist.github.com/swannodette/6330038):

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

I didn't really have a high-level concept in mind as I was coding this app since I was faking it til I made it.

But now that I've got it to a working state, I have an idea of how it should work after some refactorings.

### The dream

The app logic should be contained to a single process that exposes no state. The whole of it runs asynchronously in a `go` block.

```
(defn iterator [text] 
  (go ...))
```

The UI is hooked up to the iterator through a channel. The iterator returns this channel when you instantiate it on page load.

```
(defn iterator [text] 
  (let [c (chan)]
    (go ...)
    c))
```

Your UI sends commands to this channel in the form of [cmd & args]: `[:start]`, `[:stop]`, `[:scrub 42]` (seek to the 42nd chunk), etc.

``` clojure
(listen! (by-id "start") :click #(go (>! c [:start])))
(listen! (by-id "start") :click #(go (>! c [:stop])))
(listen! (by-id "scrub") :change #(go (>! c [:scrub (-> % target value int)])))
```

Right now the iterator calls an `update-ui` each loop that violates the above premise, so I'm thinking of adding a second channel to the iterator for iterator's output.

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

Something like that.

In other words, the iterator doesn't need to know about the UI, and the UI doesn't need to know about the iterator beyond its simple command API.

### The reality

Right now it's not quite implemented that way but it's not far off. 

For instance, not everything is encapsulated in `iterator` and UI updates are unfocused and aimless.

## Issues

- Poor performance. Completely unoptimized. Not even DOM lookups are cached and I have extraneous computation that doesn't need to be calculated each loop.

