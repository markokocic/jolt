(ns jolt.loader
  "Per-context loading: a loader owns source roots, a delegate, and the
  namespaces, vars, classes and resources linked through it. Two contexts can
  hold different versions of one library, a context can be hermetic, and
  unloading one stops new loads through it without pulling definitions out from
  under code that already resolved them.

  Shaped like java.lang.ClassLoader, not compatible with it. `find` is
  findClass/getResource — it locates and reads nothing. `resolve` is
  findLoadedClass — the link table, no I/O. `load` is loadClass, and is the only
  method that reads. `as-classloader` is the host view, and the only place the
  word ClassLoader appears.

  Resolution is generic and the same for every loader: already linked, then the
  delegate, then this loader's own roots, then a miss. The delegate is a slot
  rather than a fixed parent, so the policy combinators (`allow`, `deny`,
  `self-first`, `pool`, `isolated`) compose, and `->loader` lifts a plain
  function into one.

  Requests are typed because real loaders order and gate the kinds differently:

      {:kind :ns|:var|:class|:resource   ; required
       :name \"clojure.string\"            ; required, a string
       :load? false}                     ; :ns only — load like `require`,
                                         ; or load the one namespace alone

  `find` answers an ordered vector of hits, first-wins for the singular forms,
  `[]` on a miss, and throws on a denial. Hits are data — no open streams, no
  closures, no compiled artifacts — so they print, compare and travel:

      {:kind :ns       :file \"/roots/a/b.clj\"    :loader l}
      {:kind :var      :cell <var cell>}
      {:kind :class    :registration {...}       :loader l}
      {:kind :resource :url \"file:/roots/a/x.edn\" :loader l}

  A `:var` hit carries the cell, so sharing across contexts is by reference and
  `identical?` holds. A resource hit names a location; `open-hit` turns it into
  an open handle on demand, so nothing holds a file handle between calls.

  Two tiers decide which loader answers. Linkage follows the DEFINING loader:
  code compiled for a context resolves through that context for its whole life,
  whoever calls it and on whatever thread. Dynamic loading — `require` reached
  through a value, `eval`, REPL forms, framework resource probes — follows the
  AMBIENT loader, the thread parameter `with-loader` binds, which is inherited
  by threads and fibers.

  ── Implementation status ────────────────────────────────────────────────
  Implemented: the protocol, the generic resolution body, the link table and
  in-flight marks, the policy combinators, the ambient tier, the host root, a
  code backend over source roots that reads and evaluates namespaces in the
  process, and the classloader facade. The conformance suite
  (test/chez/loaderconf-test.clj, run by `make loaderconf`) is the spec, and it
  passes in full — test/chez/loaderconf-known-failures.txt is empty.

  The substrate today is one global namespace registry (rt.ss's var-table), so a
  context is built out of it rather than beside it:

    * a namespace located on a loader's OWN roots is private to that context.
      Before it is evaluated any previously installed version of the name is
      evicted (`remove-ns`), so the evaluation makes fresh var cells; compiled
      references are direct links, so the evicted cells stay live for the code
      that already holds them. The root loader hides an owned name, so a second
      context resolving it does not see the first context's version through the
      host. `unload!` drops the owner, unmaps the namespace while it is still
      the one installed, and leaves the cells live.
    * a namespace resolved through the DELEGATE stays wherever the delegate
      installed it: shared by reference, which is what `identical?` on a
      delegated var means.
    * loading a namespace pre-loads its `(ns … (:require …))` dependencies
      through the loader first, so the compiler resolves the context's own
      version of every dependency.
    * a call through `require` / `use` / `refer` / `resolve` / `ns-resolve` /
      `find-var` in evaluated source compiles to its context-carrying form
      (`__require-in` and friends): the compiler's var-call hook
      (jolt.host/*invoke-rewrite*) rewrites the call after macro expansion and
      only when its head resolves to the clojure.core var — a local named
      `resolve` or a parameter named `load` is that local's call — so a
      function loads, requires, refers or aliases in the context that DEFINED
      it, not the one that calls it. A libspec's `:as`, `:as-alias`, `:refer`,
      `:only`, `:exclude` and `:rename` are applied in the defining namespace,
      and `:reload` re-reads through the loader instead of the host's require.
      `load` and `load-file` are refused: a context reads namespaces through
      its loader and resources through io/resource, never host files.
    * per-context data readers are NOT supported: the runtime's reader
      resolves `#tag` against the host's `*data-readers*` before the loader
      sees the form. A source using an unresolved tag fails with the tag named
      and the reason spelled out (and says so when the context's roots ship a
      data_readers.clj).

  The host seams that make the facade real: `clojure.java.io/resource`'s
  two-arity resolves through whatever loader object answers `getResource`
  (java/io.ss), and `clojure.lang.RT/baseLoader` returns the ambient loader's
  facade inside `with-loader` (registered here over the host singleton captured
  at load).

  Limits, stated rather than discovered later. The process-global registry is
  why private loads serialize on a per-name claim: two contexts loading the
  same name at once both complete — in one order or the other — each with its
  own cells. That claim covers the evict/evaluate window, not a reader's view
  of a name another context reloads while unrelated code compiles; a name one
  context reloaded is the name the global registry holds. `:class` requests
  have no backend on this host yet. A root may be a directory or a jar; a jar
  is read in place through its central directory, as the global roots read
  one, and never extracted.

  One registry also means one slot per name. A private load EVICTS whatever
  the process has under the name — including a host namespace the context is
  shadowing (a hermetic context, or a shared delegate that does not carry the
  name, can both reach that). The context gets its own cells and works; the
  host's live code keeps its cells too, but the name is gone from the host's
  view, and the runtime's loaded mark survives `remove-ns`, so a plain
  `require` will not restore it (`:reload` will). A host-side in-place reload
  of a name a context owns reuses that context's namespace object and cells:
  one namespace, two writers. Both are the price of one registry; per-context
  var tables are what retire them.

  Namespace sources are read and compiled directly at `load`, deliberately not
  through the AOT cache: a compiled artifact bakes its cross-namespace links
  (verified — code compiled against a var keeps the cell it saw when its file
  was compiled), so an artifact is only reusable by a context whose cells are
  the ones it was compiled against. The host-root path does use the cache —
  the root loads through `jolt.host/load-namespace` — so every namespace shared
  by reference gets AOT; only a context-private namespace pays compilation, and
  a sound per-context artifact cache belongs with per-context var tables.

  One bookkeeping note: `loaders-by-id` — what evaluated source uses to find
  the loader that owns it — keeps every loader ever constructed reachable, so
  a process that mints a context per request accumulates them. A loader's
  facade lives on the loader, not in a side table, so it adds no retention of
  its own."
  (:refer-clojure :exclude [find resolve load])
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jolt.fs :as fs]))

;; ─── Requests, hits, state ─────────────────────────────────────────────────

(def ^:private hit-payload-keys
  "Keys that make a map a hit rather than a request — a hit must carry at
   least one, a request none."
  [:cell :file :url :handle :class :registration])

(def ^:private request-kinds #{:ns :var :class :resource})

(defn- hit-map?
  [x]
  (and (map? x) (boolean (some #(contains? x %) hit-payload-keys))))

(defn- req-map
  "Validate and normalize X as a request; `:ns` requests get `:load?`
   defaulted to true (require semantics — load the namespace with its
   dependencies; false asks for the one namespace alone). The source-roots
   backend loads the dependencies either way: compiling a namespace needs
   them, so the flag is carried for a backend that can honor it, and
   preload-dep! passes false to say so."
  [x]
  (when-not (map? x)
    (throw (ex-info "loader request must be a map"
                    {:type :loader/bad-request :request x})))
  (let [kind (:kind x)
        nm (:name x)]
    (when-not (contains? request-kinds kind)
      (throw (ex-info (str "invalid loader request kind: " (pr-str kind))
                      {:type :loader/bad-request :request x})))
    (when-not (string? nm)
      (throw (ex-info "loader request :name must be a string"
                      {:type :loader/bad-request :request x})))
    (cond-> {:kind kind :name nm}
      (= :ns kind) (assoc :load? (:load? x true)))))

(defn- req->k
  "The link-table key of a request or hit: [kind name]."
  [req]
  [(:kind req) (:name req)])

(defn- normalize-hit
  "Fill request-derived keys (:kind, :name, `:ns` :load?) and stamp
   `:loader` provenance on a resolver's HIT. A hit is data and must carry
   its request `:name` to be loadable; resolvers may omit it when the
   locating request supplies it. nil in, nil out."
  [l req hit]
  (when hit
    (let [hit (cond-> hit
                (and req (not (contains? hit :kind))) (assoc :kind (:kind req))
                (and req (not (contains? hit :name))) (assoc :name (:name req))
                (and req (= :ns (or (:kind hit) (:kind req))) (contains? req :load?))
                (assoc :load? (:load? req)))]
      (cond-> (if (symbol? (:name hit)) (update hit :name str) hit)
        (not (contains? hit :loader)) (assoc :loader l)))))

(defn- hits-seq
  "Normalize a resolver result — nil, one hit, or a seq of hits — to a seq
   of hits, or nil when empty."
  [x]
  (cond
    (nil? x) nil
    (map? x) (if (hit-map? x)
               [x]
               (throw (ex-info "resolver returned a map that is not a hit"
                               {:type :loader/bad-hit :value x})))
    (sequential? x) (seq x)
    :else (throw (ex-info "resolver must return a hit, a seq of hits or nil"
                          {:type :loader/bad-hit :value x}))))

(defn- link-of
  "The link entry {[kind name] {:hit … :value …}} of L, or nil."
  [l k]
  (when-let [state (:state l)]
    (get @(:links state) k)))

(defn- linked-entry [l req] (link-of l (req->k req)))

(defn unloaded?
  "Has `l` been unloaded? The one behavioral predicate about a loader's state."
  [l]
  (boolean (some-> l :state :unloaded? deref)))

(defn- check-live!
  [l]
  (when (unloaded? l)
    (throw (ex-info (str "loader " (:id l) " is unloaded")
                    {:type :loader/unloaded :loader-id (:id l)}))))

(defn- denied!
  [l req reason]
  (throw (ex-info (str "loader " (:id l) " denied " (:kind req) " " (:name req))
                  (cond-> {:loader/denied true :kind (:kind req) :name (:name req)}
                    (:id l) (assoc :loader-id (:id l))
                    reason (assoc :reason reason)))))

(defn- gate!
  [l req]
  (when-let [f (:gate-fn l)]
    (f req))
  nil)

(defn- miss!
  [l req]
  (ex-info (str "loader " (:id l) " cannot resolve " (:kind req) " " (:name req))
           {:type :loader/miss :kind (:kind req) :name (:name req) :loader-id (:id l)}))

;; ─── In-flight marks ───────────────────────────────────────────────────────
;; Never call a resolver under a lock (it may require back into the same
;; loader). `*loading*` detects a nested load of the same key on this
;; thread/fiber; the per-loader `:in-flight` map makes a concurrent load of
;; the same key wait for the owner's promise (the JVM getClassLoadingLock
;; analogue, promise-based so nothing blocks while holding loader state).

(def ^:dynamic *loading*
  "Keys — [loader [kind name]] — of loads in flight on this thread/fiber.
   Nested loads of the same key raise instead of deadlocking. Inherited by
   bound-fn-conveying threads/futures spawned inside a load."
  #{})

(defn- claim!
  "Claim K for loading in L. Returns :circular when this thread is already
   loading K, :claimed when the caller owns the claim, else {:wait promise}
   for a concurrent load to wait on."
  [l k]
  (if (contains? *loading* [l k])
    :circular
    (let [p (promise)
          st (:in-flight (:state l))]
      (loop []
        (let [m @st]
          (if (contains? m k)
            {:wait (:promise (get m k))}
            (if (compare-and-set! st m (assoc m k {:promise p}))
              :claimed
              (recur))))))))

(defn- finish-claim!
  [l k]
  (let [st (:in-flight (:state l))
        p (:promise (get @st k))]
    (swap! st dissoc k)
    (when p (deliver p :done))))

;; ─── The protocol ──────────────────────────────────────────────────────────
;; The arity split is the semantics: probe, look up, read.
(defprotocol Loader
  (find [l req]
    "Ordered hits for `req`, [] on a miss. Locates only — reads nothing,
    compiles nothing, opens nothing, installs nothing. Throws ex-info carrying
    :loader/denied when a policy denies the request; a denial is not a miss and
    does not fall through to this loader's own roots.")
  (resolve [l req]
    "The definition already linked in this loader, or nil. Reads the link table
    and nothing else — no I/O, no delegation to roots.")
  (load [l req]
    "Resolve (or accept a hit from `find`), read, initialize, link, and return
    the handle. The only method that reads. Passing a hit skips re-resolution
    and closes the window between locating and reading.")
  (parent [l]
    "This loader's delegate, or nil at the root.")
  (unload! [l]
    "Stop new loads through this loader and release the host resources it
    acquired. Idempotent, never blocks, and never throws for a teardown
    failure — it reports one. Definitions already resolved stay live; a
    subsequent find/resolve/load throws (ex-info, :loader/unloaded). Returns

        {:unloaded true :already false :in-flight 0 :raced false
         :released {:namespaces 0 :resources 0 :registrations 0}
         :errors []}"))

;; ─── The generic resolution body ───────────────────────────────────────────

(defn- delegate-hits
  [l req]
  (if-let [f (:delegate-fn l)]
    (hits-seq (f req))
    (when-let [p (:parent l)]
      (let [hs (find p req)]
        (when (seq hs) hs)))))

(defn- locate-hits
  [l req]
  (when-let [f (:locate-fn l)]
    (hits-seq (f req))))

(defn- find*
  [l req]
  (if-let [e (linked-entry l req)]
    [(:hit e)]
    (into []
          (keep #(normalize-hit l req %))
          (concat (delegate-hits l req) (locate-hits l req)))))

(defn- file-url-path
  "The filesystem path inside a file: URL string, or the string itself."
  [s]
  (if (and (string? s) (str/starts-with? s "file:"))
    (subs s 5)
    s))

(def ^:private host-base-loader
  "The host singleton classloader, captured at load — BEFORE this namespace
   registers its own `RT/baseLoader`, so it is always the host's, never a
   context's."
  (clojure.lang.RT/baseLoader))

(defn- host-resource
  "The HOST resolver's answer for NM. A loader's own host view must not go
   through the ambient 1-arity `io/resource`: that one follows the bound loader
   (conformance case 20), so a host locate that called it would ask the very
   loader it is serving, forever."
  [nm]
  (io/resource nm host-base-loader))

(defn- default-open
  "Open a resource hit. A file: location opens the file, a jar:file: location
   streams the entry out of its archive; anything else — the jar:-classed
   embedded resource a built binary hands out — is re-resolved by name through
   the host's resolver, which is what produced the location."
  [hit]
  (when-let [url (:url hit)]
    (cond
      (str/starts-with? url "file:") (io/input-stream (file-url-path url))
      (str/starts-with? url "jar:file:") (io/input-stream url)
      :else (io/input-stream (host-resource (:name hit))))))

(defn- hit-url
  "The URL a resource hit names: the file URL for a file: location, the jar:
   URL for an entry of a jar root, the host resolver's answer (an embedded
   resource object, as the host's own ClassLoader.getResource answers) for
   anything else."
  [hit]
  (let [url (:url hit)]
    (cond
      (str/starts-with? url "file:") (io/as-url (java.io.File. (file-url-path url)))
      (str/starts-with? url "jar:file:") (java.net.URL. url)
      :else (host-resource (:name hit)))))

(defn- open*
  [l hit]
  (let [home (:loader hit)]
    (cond
      (:open-fn home) ((:open-fn home) home hit)
      (:open-fn l) ((:open-fn l) l hit)
      :else (default-open hit))))

(defn open-hit
  "A resource hit -> an open handle, or nil. The loader owns opening, so a hit
   stays comparable data and no handle is held between calls."
  [l hit]
  (check-live! l)
  (when (= :resource (:kind hit))
    (open* l hit)))

(defn- bad-hit!
  [hit msg]
  (throw (ex-info (str "invalid loader hit: " msg)
                  {:type :loader/bad-hit :hit hit})))

(defn- initialize
  "Produce the loaded value for HIT, READ + initialize running in HIT's
   home loader; L is the loader doing the loading."
  [l hit req]
  (case (:kind hit)
    :var (if (contains? hit :cell)
           (:cell hit)
           (bad-hit! hit "a :var hit needs :cell"))
    :class (cond
             (contains? hit :class) (:class hit)
             (contains? hit :registration) (:registration hit)
             :else (bad-hit! hit "a :class hit needs :class or :registration"))
    :ns (if (contains? hit :handle)
          (:handle hit)
          (let [home (or (:loader hit) l)
                f (or (:ns-load-fn home) (:ns-load-fn l))]
            (if f
              (f home hit (or req (select-keys hit [:kind :name :load?])))
              (throw (ex-info (str "no source reader for namespace " (:name hit)
                                   " in loader " (:id (or home l)))
                              {:type :loader/unreadable
                               :kind :ns :name (:name hit)})))))
    (bad-hit! hit (str "unknown :kind " (pr-str (:kind hit))))))

(defn- link-ns-vars!
  "Install the [kind name] links for the vars a loaded namespace interns, so
   `resolve` answers a var as soon as its namespace is linked. Runs for every
   loader the load passes through (own or delegated), so a shared namespace
   resolves identically in each."
  [l hit value]
  (let [home (or (:loader hit) l)
        snapshot (or (:ns-vars-fn home) (:ns-vars-fn l))]
    (when (and (= :ns (:kind hit)) snapshot)
      (doseq [[sym cell] (snapshot home value)]
        (let [nm (str (:name hit) "/" sym)
              vhit {:kind :var :name nm :cell cell :loader home}]
          (swap! (:links (:state l)) assoc [:var nm] {:hit vhit :value cell}))))
    value))

(defn- install-link!
  "Record {:hit hit :value v} for K in L's link table (and in the link tables
   of the OTHER loaders the load passed through — see install-claim!)."
  [l k hit v]
  (swap! (:links (:state l)) assoc k {:hit hit :value v})
  v)

(defn- install-claim!
  "Run HIT's initialization under the claim for K and install the link. A hit
   located by a DELEGATE is linked at its home loader too: the definition
   belongs to the loader that located it, so the next loader reaching the same
   delegate re-uses the handle instead of re-reading the source. (The home is
   not under its own claim here — the value is already computed and the write
   is one atomic assoc.)"
  [l hit req k]
  (try
    (let [home (:loader hit)
          v (binding [*loading* (conj *loading* [l k])]
              (link-ns-vars! l hit (initialize l hit req)))]
      (install-link! l k hit v)
      (when (and home
                 (not (identical? home l))
                 (:state home)
                 (nil? (link-of home k)))
        ;; the home gets the namespace link AND its var links: a delegate that
        ;; only held the namespace would answer `resolve` for none of its vars
        (install-link! home k hit v)
        (link-ns-vars! home hit v))
      v)
    (finally
      (finish-claim! l k))))

(defn- load-hit
  "Link HIT into L and return its value; resources are opened, not linked.
   Initialization runs in HIT's home loader; closed loaders refuse new
   loads here too. Recursive loads of the same key raise; concurrent ones
   wait for the in-flight owner."
  [l hit req]
  (if (= :resource (:kind hit))
    (or (open* l hit)
        (throw (ex-info (str "loader " (:id l) " cannot open resource " (:name hit))
                        {:type :loader/unreadable :kind :resource :name (:name hit)})))
    (let [k (req->k hit)
          home (:loader hit)]
      (when (and home (not (identical? home l)) (unloaded? home))
        (throw (ex-info (str "home loader " (:id home) " of " (:name hit) " is unloaded")
                        {:type :loader/unloaded :loader-id (:id home)})))
      (loop []
        (if-let [e (link-of l k)]
          (:value e)
          (let [claim (claim! l k)]
            (cond
              (= :circular claim)
              (throw (ex-info (str "circular load of " (:kind hit) " " (:name hit)
                                   " in loader " (:id l))
                              {:type :loader/circular :kind (:kind hit)
                               :name (:name hit) :loader-id (:id l)}))

              (= :claimed claim)
              (install-claim! l hit req k)

              :else
              (do
                (deref (:wait claim))
                (recur)))))))))

;; ─── Loading values, teardown, the impl record ─────────────────────────────

(defn- teardown
  [l base]
  (if-let [f (:release-fn l)]
    (try
      (let [r (f l)]
        [(merge base (when (map? r) r)) []])
      (catch :default e
        [base [{:message (ex-message e) :exception e}]]))
    [base []]))

(declare ^:private root-loader)

(defn- unload-run
  "The generic teardown. The host root is refused: it is the host's global
   world, not a disposable context — closing it would break every later load
   through `(root)` for the rest of the process."
  [l]
  (when (identical? l @root-loader)
    (throw (ex-info "the host root is not unloadable" {:type :loader/bad-request})))
  (let [state (:state l)]
    (if (compare-and-set! (:unloaded? state) false true)
      (let [in-flight (count @(:in-flight state))
            links @(:links state)
            base {:namespaces (count (filter #(= :ns (ffirst %)) links))
                  :registrations (count (filter #(= :class (ffirst %)) links))}
            [released errors] (teardown l base)]
        {:unloaded true
         :already false
         :released released
         :in-flight in-flight
         :raced (pos? in-flight)
         :errors errors})
      {:unloaded true
       :already true
       :released {}
       :in-flight (count @(:in-flight state))
       :raced false
       :errors []})))

(defrecord LoaderImpl [id parent delegate-fn locate-fn gate-fn open-fn
                       release-fn ns-load-fn ns-vars-fn info state]
  Loader
  (find [l req]
    (check-live! l)
    (let [req (req-map req)]
      (gate! l req)
      (find* l req)))
  (resolve [l req]
    ;; The link table and nothing else: no gate, no I/O, no delegation. A
    ;; denied name that is not linked answers nil here; find/load are where
    ;; the policy raises.
    (check-live! l)
    (some-> (linked-entry l (req-map req)) :value))
  (load [l x]
    (check-live! l)
    (if (hit-map? x)
      (let [hit (normalize-hit l nil x)]
        (when-not (contains? request-kinds (:kind hit))
          (bad-hit! hit "missing or invalid :kind"))
        (when-not (string? (:name hit))
          (bad-hit! hit "missing :name — hits carry the request name"))
        (gate! l hit)
        (load-hit l hit nil))
      (let [req (req-map x)]
        (gate! l req)
        (if-let [e (linked-entry l req)]
          (:value e)
          (if-let [hit (first (find* l req))]
            (load-hit l hit req)
            (throw (miss! l req)))))))
  (parent [l] (:parent l))
  (unload! [l] (unload-run l)))

;; ─── Constructor seam for backends ─────────────────────────────────────────

;; Every loader is registered by id: evaluated source proves its context by
;; carrying the id its compiler was handed (see __require-in below).
(defonce ^:private loaders-by-id (atom {}))

(defn- loader-by-id
  [id]
  (or (get @loaders-by-id id)
      (throw (ex-info (str "no loader registered as " (pr-str id))
                      {:type :loader/bad-context :loader-id id}))))

(def ^:private id-counter (atom 0))

(defn- next-id [prefix]
  (str prefix "#" (swap! id-counter inc)))

(defn- make-loader
  "Backend seam: build a loader from raw steps. The code backend below and
   the host root are built through this; application code uses the
   constructors and combinators.

   Opts (all optional):
   - :id          diagnostics id (default generated)
   - :parent      delegate loader
   - :delegate-fn (fn [req]) → hits — overrides the parent walk
   - :locate-fn   (fn [req]) → hits — this loader's own roots
   - :gate-fn     (fn [req]) — throws :denied (`allow` / `deny` build on it)
   - :open-fn     (fn [loader hit]) → opened resource handle
   - :release-fn  (fn [loader]) → extra released counts for `unload!`
   - :ns-load-fn  (fn [home hit req]) → namespace handle (READ is allowed)
   - :ns-vars-fn  (fn [home handle]) → {sym cell} the handle interned; linked
                  as :var entries when the namespace's link is installed
   - :roots       [string] for `status`
   - :delegates   [Loader] for `status`
   - :members     [Loader] for `status`
   - :context     host object for `(context l)`
   - :classloader host view — value or (fn [l]) — for `as-classloader`"
  [{:keys [id parent delegate-fn locate-fn gate-fn open-fn release-fn ns-load-fn
           ns-vars-fn roots delegates members context classloader]}]
  (let [l (->LoaderImpl
           (or id (next-id "loader"))
           parent delegate-fn locate-fn gate-fn open-fn release-fn ns-load-fn
           ns-vars-fn
           (cond-> {}
             roots (assoc :roots (vec roots))
             delegates (assoc :delegates (vec delegates))
             members (assoc :members (vec members))
             (some? context) (assoc :context context)
             (some? classloader) (assoc :classloader classloader))
           {:links (atom {}) :in-flight (atom {}) :unloaded? (atom false)
            :facade (atom nil)})]
    (swap! loaders-by-id assoc (:id l) l)
    l))

;; ─── Host root ─────────────────────────────────────────────────────────────
;; The host's own world: namespaces the process has loaded (minus the ones a
;; context made private), resolved vars, resources on the host source roots.
;; Locating (reading/evaluating) host sources is `jolt.host/load-namespace`'s
;; job and happens at `load`, never at `find`.

(defonce ^:private private-ns-owners
  ;; Namespaces a context located on its own roots -> the loader ids that own
  ;; them. The root loader hides a name while it has owners: the host did not
  ;; put it there, and a second context asking the host for the same name must
  ;; not receive the first context's version. `unload!` drops an owner, and the
  ;; last owner out unmaps the namespace (release-private!).
  (atom {}))

(defn- private-ns? [nm] (boolean (seq (get @private-ns-owners nm))))

;; The evict-evaluate-snapshot window mutates the PROCESS-GLOBAL registry under
;; one name, so it is serialized by name across every loader — the per-loader
;; claim in load-hit only orders loads within one loader. A loader never holds
;; this claim while waiting for another, and waiting is a promise deref, so the
;; design stays lock-free and fiber-friendly. Same-thread re-entry for the same
;; name raises :loader/circular instead of deadlocking.
(defonce ^:private private-load-claims (atom {}))

(def ^:private ^:dynamic *reload-in-place*
  "The namespace NAMES whose next load is a :reload: the installed namespace is
   KEPT and re-evaluated, so its defs re-intern the vars earlier compiled code
   links to (Clojure's reload semantics) instead of a fresh namespace's cells.
   Keyed by name, not by loader: whoever serves the namespace — the context's
   own roots or a delegate's — does the reload. Bound by `preload-for!` around
   the load; internal, so the public request shape stays
   {:kind :name [:load?]}."
  nil)

(defn- reloading?
  [ns-name]
  (contains? (or *reload-in-place* #{}) ns-name))

(def ^:dynamic *private-loads*
  "Names whose private load is in flight on this thread/fiber."
  #{})

(defn- claim-private-load!
  [nm]
  (if (contains? *private-loads* nm)
    :circular
    (let [p (promise)]
      (loop []
        (let [m @private-load-claims]
          (if (contains? m nm)
            {:wait (:promise (get m nm))}
            (if (compare-and-set! private-load-claims m (assoc m nm {:promise p}))
              :claimed
              (recur))))))))

(defn- finish-private-load!
  [nm]
  (let [p (:promise (get @private-load-claims nm))]
    (swap! private-load-claims dissoc nm)
    (when p (deliver p :done))))

(defn- with-private-load-claim
  "Serialize the private load of NM across loaders; F runs under the claim."
  [nm f]
  (loop []
    (let [claim (claim-private-load! nm)]
      (cond
        (= :claimed claim)
        (try
          (binding [*private-loads* (conj *private-loads* nm)]
            (f))
          (finally
            (finish-private-load! nm)))

        (= :circular claim)
        (throw (ex-info (str "circular private load of " nm
                             " — the namespace is already being built on this thread")
                        {:type :loader/circular :kind :ns :name nm}))

        :else
        (do (deref (:wait claim))
            (recur))))))

(def ^:private source-exts
  "The runtime's own source extensions (loader.ss `ldr-source-exts`), in order."
  ["jolt" "clj" "cljc"])

(defn- ns-relative-path
  "NS-NAME as the runtime maps it to a file: split on '.', munge '-'->'_' per
   segment, join with '/' (loader.ss `ns-seg-munge`, shared with the class
   graph). A dashed namespace therefore lives at an underscored path, as in
   Clojure — the extension system depends on it."
  [ns-name]
  (->> (str/split ns-name #"\.")
       (map #(str/replace % "-" "_"))
       (str/join "/")))

(defn- ns-source-paths
  "Strict ns-path candidates for NS-NAME, relative to a root."
  [ns-name]
  (let [base (ns-relative-path ns-name)]
    (mapv #(str base "." %) source-exts)))

(defn- host-ns-location
  "Where the host's own loader would find NS-NAME — embedded resources and the
   global source roots, in the runtime's extension order — or nil. Locating
   only: nothing is read."
  [ns-name]
  (let [base (ns-relative-path ns-name)]
    (some (fn [ext]
            (when-let [u (host-resource (str base "." ext))]
              (str u)))
          source-exts)))

(defn- host-locate
  [req]
  (case (:kind req)
    ;; A namespace a context owns is the context's, not the host's: the root
    ;; answers neither the name nor a var in it. A name the host has not
    ;; loaded yet but CAN load locates as a source location, so the load goes
    ;; through the host's own loader (AOT cache included) instead of leaking
    ;; to whatever `require` the evaluated form calls.
    :ns (when-not (private-ns? (:name req))
          (if-let [n (find-ns (symbol (:name req)))]
            [{:kind :ns :handle n}]
            (when-let [u (host-ns-location (:name req))]
              [{:kind :ns :file u}])))
    :var (let [[ns-name var-name] (str/split (:name req) #"/" 2)]
           (when (and ns-name var-name (not (private-ns? ns-name)))
             (let [ns-sym (symbol ns-name)]
               ;; ns-resolve also answers a CLASS for a capitalized name
               ;; (clojure.core/String); a :var hit carries a cell, so only a
               ;; var is one
               (when (find-ns ns-sym)
                 (let [v (ns-resolve ns-sym (symbol var-name))]
                   (when (var? v)
                     [{:kind :var :cell v}]))))))
    :resource (when-let [u (host-resource (:name req))]
                [{:kind :resource :url (str u)}])
    nil))

(defn- host-ns-vars
  [home ns-obj]
  (when ns-obj (ns-interns ns-obj)))

(defn- host-load-ns
  "The root's reader: a namespace the host does not have loaded yet loads
   through the host's own loader (global source roots, AOT cache and all)."
  [home hit req]
  (or (find-ns (symbol (:name hit)))
      (do
        (jolt.host/load-namespace (:name hit))
        (or (find-ns (symbol (:name hit)))
            (throw (ex-info (str "the host has no namespace " (:name hit))
                            {:type :loader/unreadable
                             :kind :ns :name (:name hit)}))))))

(defonce ^:private root-loader
  (delay (make-loader {:id "root"
                       :locate-fn host-locate
                       :ns-load-fn host-load-ns
                       :ns-vars-fn host-ns-vars})))

(defn root
  "The host itself as a loader: today's global namespaces, vars, classes and
   install roots. Its `parent` is nil."
  []
  @root-loader)

;; ─── Combinators ───────────────────────────────────────────────────────────

(defn ->loader
  "Lift a plain resolve-fn — (fn [req] hits-or-nil) — into a delegate loader."
  [f]
  (when-not (fn? f)
    (throw (ex-info "->loader needs a resolve-fn" {:type :loader/bad-resolve-fn :value f})))
  (make-loader {:delegate-fn f}))

(defn isolated
  "A delegate that answers nothing, so a context composed over it sees only its
  own roots. The root loader is not visible through it."
  []
  (make-loader {:id (next-id "isolated")}))

(defn delegating
  "Try `a`, then `b`."
  [a b & more]
  (let [members (into [a b] more)]
    (make-loader
     {:id (next-id "delegating")
      :parent a
      :delegates members
      :delegate-fn (fn [req]
                     (some (fn [m]
                             (let [hs (find m req)]
                               (when (seq hs) hs)))
                           members))})))

(defn pool
  "Sibling sharing: ask each of `loaders` in order."
  [loaders]
  (when-not (seq loaders)
    (throw (ex-info "pool needs at least one loader" {:type :loader/bad-pool})))
  (make-loader
   {:id (next-id "pool")
    :parent (first loaders)
    :members (vec loaders)
    :delegate-fn (fn [req]
                   (some (fn [m]
                           (let [hs (find m req)]
                             (when (seq hs) hs)))
                         loaders))}))

(defn- name-matches?
  "Prefix match over a name: exact, or boundary-prefixed with \".\" (a
   namespace prefix like 'kmet.tui) or \"/\" (a resource directory)."
  [prefixes nm]
  (boolean
   (some (fn [p]
           (let [p (str p)]
             (or (= p nm)
                 (str/starts-with? nm (str p "."))
                 (str/starts-with? nm (str p "/")))))
         prefixes)))

(defn allow
  "`l`, restricted to `names` — a request outside the whitelist is denied."
  [l names]
  (make-loader
   {:id (next-id "allow")
    :parent l
    :delegates [l]
    :gate-fn (fn [req]
               (when-not (name-matches? names (:name req))
                 (denied! l req "not in allow list")))}))

(defn deny
  "`l`, minus `names` — a request inside the blacklist is denied. A denial
  raises; it never falls through."
  [l names]
  (make-loader
   {:id (next-id "deny")
    :parent l
    :delegates [l]
    :gate-fn (fn [req]
               (when (name-matches? names (:name req))
                 (denied! l req "denied by policy")))}))

(defn self-first
  "`l` with its own roots consulted BEFORE its delegate, for `prefixes` only —
   the parent-first default inverted for the names a context means to shadow.
   The delegate is still consulted for everything else, and for these names when
   the roots miss. A namespace located through L's roots keeps L as its home, so
   that namespace's own requires resolve through L: the prefix policy applies to
   loads made through this wrapper, not to the internals of what L serves."
  [l prefixes]
  (let [own-hits (fn [req]
                   (seq (keep #(normalize-hit l req %) (locate-hits l req))))
        full-hits (fn [req]
                    (let [hs (find l req)]
                      (when (seq hs) hs)))]
    (make-loader
     {:id (next-id "self-first")
      :parent l
      :delegates [l]
      :delegate-fn (fn [req]
                     (if (name-matches? prefixes (:name req))
                       (or (own-hits req) (full-hits req))
                       (full-hits req)))})))

;; ─── Ambient loader (thread/fiber parameter) ───────────────────────────────

(def ^:dynamic *current-loader*
  "The ambient loader for requests with no lexical home. nil means the host
   root."
  nil)

(defn current-loader
  "The ambient loader — the one dynamic loading follows on this thread or
   fiber. The root loader unless `with-loader` bound another."
  []
  (or *current-loader* (root)))

(defn with-loader*
  "`with-loader`'s function form: call `f` with `l` ambient."
  [l f]
  (binding [*current-loader* l]
    (f)))

(defmacro with-loader
  "Evaluate `body` with `l` as the ambient loader. The binding conveys the way
   a dynamic binding does — into futures, agents, go blocks and bound-fn'd
   threads, not a bare Thread — and survives a fiber parking and resuming on
   another carrier."
  [l & body]
  `(with-loader* ~l (fn [] ~@body)))

(defn context
  "The host object underneath `l` — the jolt tables it owns. An escape hatch;
   nothing portable should need it."
  [l]
  (get-in l [:info :context]))

(defn- status*
  [l depth]
  (if-let [state (:state l)]
    (let [links @(:links state)]
      (cond-> {:loader-id (:id l)
               :roots (vec (:roots (:info l) []))
               :parent-loaded? (boolean (and (:parent l)
                                             (not (unloaded? (:parent l)))))
               :loaded-namespaces (count (filter #(= :ns (ffirst %)) links))
               :loaded (count links)
               :in-flight (count @(:in-flight state))
               :unloaded? (boolean @(:unloaded? state))}
        (and (:parent l) (pos? depth))
        (assoc :delegate (status* (:parent l) (dec depth)))
        (seq (:delegates (:info l))) (assoc :delegates (mapv :id (:delegates (:info l))))
        (seq (:members (:info l))) (assoc :members (mapv :id (:members (:info l))))))
    {:loader-id (or (:id l) "foreign") :unloaded? false}))

(defn status
  "A diagnostics map: loader id, roots, loaded-namespace count, in-flight
   loads, unloaded?, and one level of delegate. Implementation-visible — keys
   are added freely and no behavior may depend on one. Use `unloaded?` for
   anything that decides."
  [l]
  (status* l 1))

;; ─── The source-roots code backend ─────────────────────────────────────────

(defn- jar-root?
  "Is ROOT a jar — a .jar or .zip that is a file? The host reads such a root
   in place (jolt.host/root-file); every other root is a directory."
  [root]
  (let [r (str/lower-case (str root))]
    (and (or (str/ends-with? r ".jar") (str/ends-with? r ".zip"))
         (not (fs/directory? (fs/file (str root)))))))

(defn- validate-root!
  "Construction is the eager-validation point: a root that is missing, not a
   directory or a whole jar, or not readable fails here, not at the first
   load."
  [root]
  (let [f (fs/file (str root))]
    (when-not (fs/exists? f)
      (throw (ex-info (str "loader root does not exist: " root)
                      {:type :loader/bad-root :root (str root)})))
    (when-not (fs/readable? f)
      (throw (ex-info (str "loader root is not readable: " root)
                      {:type :loader/bad-root :root (str root)})))
    (cond
      (fs/directory? f) nil
      (jar-root? root)
      (when-not (jolt.host/zip-archive? (str root))
        (throw (ex-info (str "loader root is not a whole zip archive: " root)
                        {:type :loader/bad-root :root (str root)})))
      :else
      (throw (ex-info (str "loader root is neither a directory nor a jar: " root)
                      {:type :loader/bad-root :root (str root)})))))

(defn- root-file
  "The location NAME resolves to on ROOT, or nil: an absolute file path under
   a directory root, a jar: path into a jar root (jolt.host/root-file)."
  [root name]
  (when-let [p (jolt.host/root-file (str root) name)]
    (if (str/starts-with? p "jar:file:") p (str (fs/absolutize (fs/file p))))))

(defn- roots-locate
  "Locate ns sources and resources under ROOTS, in order, without reading
   them — ns hits carry a file path (a jar: path for a jar root), resource
   hits a URL."
  [roots]
  (fn [req]
    (case (:kind req)
      :ns (into []
                (for [root roots
                      rel (ns-source-paths (:name req))
                      :let [f (root-file root rel)]
                      :when f]
                  {:kind :ns :file f}))
      :resource (into []
                      (for [root roots
                            :let [f (root-file root (:name req))]
                            :when f]
                        {:kind :resource
                         :url (if (str/starts-with? f "jar:file:") f (str "file:" f))}))
      nil)))

;; --- reading and evaluating a namespace source ----------------------------

(defn- read-forms
  "Every top-level form in FILE, in order. Metadata (line/column) is kept on
   the forms the reader put it on."
  [file]
  (with-open [r (java.io.PushbackReader. (io/reader file))]
    (let [eof (Object.)]
      (loop [xs []]
        (let [f (read r false eof)]
          (if (identical? eof f)
            xs
            (recur (conj xs f))))))))

(defn- ns-form?
  [f]
  (and (seq? f) (= 'ns (first f)) (symbol? (second f))))

(defn- libspec-ns
  "The namespace a `:require` libspec names: a symbol or [ns & opts]."
  [spec]
  (cond
    (symbol? spec) (str spec)
    (vector? spec) (when (symbol? (first spec)) (str (first spec)))
    :else nil))

(defn- prefix-list-ns
  "Expand a prefix list — (prefix [sub :as a] [other]) — to qualified names."
  [spec]
  (let [prefix (str (first spec))]
    (keep (fn [item]
            (when-let [nm (libspec-ns item)]
              (if (str/includes? nm ".")
                nm
                (str prefix "." nm))))
          (rest spec))))

(defn- expand-libspec
  "A require/use/refer libspec expanded to [[lib opts] …]: a bare symbol, a
   [lib & opts] vector, or a prefix list (prefix [sub :as a] [other])."
  [spec]
  (cond
    (symbol? spec) [[spec {}]]

    (and (vector? spec) (symbol? (first spec)))
    [[(first spec) (apply hash-map (rest spec))]]

    (and (seq? spec) (symbol? (first spec)))
    (let [prefix (str (first spec))]
      (into []
            (keep (fn [item]
                    (when-let [[lib opts] (first (expand-libspec item))]
                      (if (str/includes? (str lib) ".")
                        [lib opts]
                        [(symbol (str prefix "." (str lib))) opts]))))
            (rest spec)))

    :else nil))

(defn- required-ns-names
  "The namespaces the ns form of FORMS requires, in order: the direct
   `(:require …)` clauses plus the prefix-list expansions."
  [forms]
  (let [ns-form (first (filter ns-form? forms))]
    (when ns-form
      (vec
       (distinct
        (for [clause (drop 2 ns-form)
              :when (and (seq? clause) (contains? #{:require :use} (first clause)))
              spec (rest clause)
              nm (if (and (seq? spec) (symbol? (first spec)))
                   (prefix-list-ns spec)
                   [(libspec-ns spec)])
              :when nm]
          nm))))))

(defn- claim-private!
  "This loader becomes an owner of NS-NAME: a name read from its own roots is
   private to it (the root hides an owned name)."
  [l ns-name]
  (swap! private-ns-owners update ns-name (fnil conj #{}) (:id l)))

(defn- mark-private!
  "Claim NS-NAME and evict any version installed under it, so the evaluation
   makes fresh cells (compiled code already holding the old cells keeps them).
   A `:reload` skips the eviction — see *reload-in-place*."
  [l ns-name]
  (claim-private! l ns-name)
  (when (find-ns (symbol ns-name))
    (remove-ns (symbol ns-name))))

(defn- release-private!
  "`unload!` teardown for a source-roots loader: unmap the namespaces this
   loader installed — and only while they are still the ones installed, since
   another context may have replaced them — drop its ownership, and answer how
   many were unmapped. Definitions already resolved stay live: compiled code
   holds direct links to the cells, so removing the registry entry stops new
   name-based loads without pulling anything out from under running code.
   Never throws: `unload!` reports teardown failures as data."
  [l]
  (let [unmapped (reduce
                  (fn [n [k entry]]
                    (if (and (= :ns (first k))
                             (identical? l (:loader (:hit entry))))
                      (let [nm (second k)
                            h (:value entry)
                            live (find-ns (symbol nm))]
                        (if (and (map? h) (identical? live (:handle h)))
                          (do (remove-ns (symbol nm))
                              (inc n))
                          n))
                      n))
                  0
                  @(:links (:state l)))]
    ;; Ownership is a superset of the links — a load that FAILED still claimed
    ;; its name (and can leave a partial namespace, which the next load evicts).
    ;; Drop this loader's claims wholesale so a failed load cannot keep a name
    ;; hidden from the root for the process's life.
    (doseq [[nm owners] @private-ns-owners
            :when (contains? owners (:id l))]
      (swap! private-ns-owners update nm disj (:id l))
      (when (empty? (get @private-ns-owners nm))
        (swap! private-ns-owners dissoc nm)))
    {:namespaces unmapped}))

(defn- unreadable!
  [l ns-name msg]
  (throw (ex-info (str "loader " (:id l) " cannot read namespace " ns-name ": " msg)
                  {:type :loader/unreadable :kind :ns :name ns-name})))

(defn- preload-dep!
  "Load DEP through L so the context's own version is installed before the
   compiler sees the `(:require …)` clause. A miss is left to the runtime's
   require — except when the name is another context's private namespace, which
   must never be shared implicitly."
  [l dep]
  (try
    (load l {:kind :ns :name dep :load? false})
    (catch :default e
      (cond
        (not= :loader/miss (:type (ex-data e))) (throw e)
        (and (private-ns? dep) (nil? (resolve l {:kind :ns :name dep})))
        (throw (ex-info (str "namespace " dep " is private to another loader; "
                             "add its root to this loader or share it through a delegate")
                        {:type :loader/unreadable :kind :ns :name dep}
                        e))
        :else nil))))

;; --- the context-carrying forms ------------------------------------------
;; A compiled function keeps its defining context by calling these: while a
;; context's source is evaluated, the compiler's var-call hook
;; (jolt.host/*invoke-rewrite*, bound by eval-namespace-source) rewrites a call
;; through clojure.core's `require`, `use`, `refer`, `load`, `load-file`,
;; `resolve`, `ns-resolve` or `find-var` to the `__…-in` spelling below,
;; carrying the id of the loader that owns the source. The hook sees a call
;; AFTER macro expansion and only when its head resolves to the var — a local
;; named `resolve` (a promise callback) or a parameter named `load` is that
;; local's call, a quoted form is data, and the `ns` form's own
;; (clojure.core/require …) is a var call like any other — none of which a walk
;; over the source forms could tell apart. A quoted target of `resolve` or
;; `find-var` is qualified with the defining namespace while the form is still
;; data: at call time *ns* is the caller's, so leaving it bare would resolve in
;; the wrong place. Linkage then follows the value, not the caller — what case
;; 9 of the suite requires.

(def ^:private context-ops
  {'require '__require-in
   'use '__use-in
   'refer '__refer-in
   'load '__load-in
   'load-file '__load-file-in
   'resolve '__resolve-in
   'ns-resolve '__ns-resolve-in
   'find-var '__find-var-in})

(def ^:private ns-carrying-ops
  "The internal forms that take the defining namespace after the loader id:
   the loading/aliasing ops act IN a namespace, the resolve family only reads."
  '#{__require-in __use-in __refer-in __load-in __load-file-in})

(defn- qualify-symbol
  "The quoted target of `resolve`/`find-var` in rewritten source, qualified with
   the DEFINING namespace. At call time `*ns*` is the caller's namespace, so a
   bare symbol would resolve in the wrong place. A computed argument is left
   alone — __resolve-in falls back to its runtime path for those."
  [arg ns-name]
  (if (and (seq? arg)
           (contains? '#{quote clojure.core/quote} (first arg))
           (symbol? (second arg))
           (nil? (namespace (second arg))))
    (list 'quote (symbol (str ns-name) (str (second arg))))
    arg))

(defn- context-rewriter
  "The var-call hook for a context's source: LOADER-ID owns it, NS-NAME is the
   namespace it defines. The hook is handed the resolved var's namespace and
   name and the call form, and answers the context-carrying form or nil.

   The binding is dynamic, so it is also in force while a host namespace the
   evaluation pulls in transitively compiles — a requiring-resolve at load
   time, an :import of an unloaded deftype — and that code, which the AOT tee
   captures, must stay the host's: a call is rewritten only while the compile
   namespace is NS-NAME, which a transitive load's never is."
  [loader-id ns-name]
  (fn [var-ns var-name form]
    (when (and (= "clojure.core" var-ns)
               (= ns-name (str (clojure.core/ns-name *ns*))))
      (when-let [op (get context-ops (symbol var-name))]
        (let [args (rest form)
              args (if (contains? '#{__resolve-in __find-var-in} op)
                     (map #(qualify-symbol % ns-name) args)
                     args)]
          (with-meta
            (apply list
                   (concat [(symbol "jolt.loader" (name op)) (str loader-id)]
                           (when (contains? ns-carrying-ops op) [ns-name])
                           args))
            (meta form)))))))

(defn- declares-data-readers?
  "Do L's own roots ship a data_readers.clj? (Its tags cannot work: the
   runtime's reader resolves #tag against the host's *data-readers* before the
   loader ever sees the form — see the docstring.)"
  [l]
  (boolean (some #(fs/exists? (fs/file % "data_readers.clj"))
                 (get-in l [:info :roots]))))

(defn- unresolved-reader-tags
  "The unresolved reader tags among FORMS, as strings: values the runtime's
   reader left as tagged literals (only the failure path asks — see
   eval-namespace-source). Detection is deliberately loose: a false positive
   costs nothing where it is used, which is an error already being reported."
  [forms]
  (let [seen (atom #{})]
    ((fn walk [x]
       (cond
         (and (not (map? x)) (not (coll? x)) (:tag x) (:form x))
         (swap! seen conj (str (:tag x)))

         (map? x) (doseq [[k v] x] (walk k) (walk v))
         (coll? x) (doseq [y x] (walk y))
         :else nil))
     forms)
    @seen))

(defn- eval-namespace-source
  "Read FILE, evaluate it as NS-NAME in the host namespace space, and answer
   {:handle <namespace object> :vars {sym cell}}."
  [l ns-name file]
  ;; a context's data-reader vars must be loaded through IT (the host's require
  ;; has no business finding them), then its file is bound over the host table
  ;; while the source is read.
  (let [forms (read-forms file)]
    (when (empty? forms)
      (unreadable! l ns-name (str file " is empty")))
    (doseq [dep (required-ns-names forms)]
      (when (not= dep ns-name)
        (preload-dep! l dep)
        ;; The runtime `require` inside the ns form would load from the GLOBAL
        ;; roots whatever this loader could not serve, silently compiling the
        ;; source against definitions the context cannot see (a hermetic
        ;; context's clojure.string, say). Resolving only through the loader is
        ;; what makes the policies and the delegate chain mean anything.
        (when-not (resolve l {:kind :ns :name dep})
          (throw (ex-info (str "namespace " ns-name " requires " dep
                               ", which this loader cannot serve; add its root,"
                               " delegate it, or inject it")
                          {:type :loader/unreadable :kind :ns :name dep})))))
    (with-private-load-claim
      ns-name
      (fn []
        (if (reloading? ns-name)
          (claim-private! l ns-name)
          (mark-private! l ns-name))
        (try
          (try
            ;; *file* is the source being evaluated, as load binds it, so a
            ;; def that reads it (a resource path relative to its own file, a
            ;; jar entry's spelling) sees the context's file and not the
            ;; program that opened the context
            (binding [*ns* *ns*
                      *file* file
                      jolt.host/*invoke-rewrite* (context-rewriter (:id l) ns-name)]
              (doseq [f forms]
                (eval f)))
            (catch :default e
              ;; an unresolved #tag is the one failure the runtime reports as a
              ;; bare "cannot compile this value" — name the tag and the reason
              (if-let [tags (seq (unresolved-reader-tags forms))]
                (throw (ex-info (str "the source of " ns-name " uses " (count tags)
                                     (if (= 1 (count tags)) " reader tag " " reader tags ")
                                     (str/join " " (map #(str "#" %) (sort tags)))
                                     (if (declares-data-readers? l)
                                       (str ": its roots ship a data_readers.clj, but"
                                            " per-context data readers are not supported"
                                            " — register the tag in the host's"
                                            " *data-readers* (or read such data with"
                                            " clojure.edn/read-string and a :readers map)")
                                       ": no reader is registered for it"))
                                  {:type :loader/unreadable :kind :ns :name ns-name
                                   :tags (vec (sort tags))}
                                  e))
                (throw e))))
          (let [n (find-ns (symbol ns-name))]
            (when-not n
              (unreadable! l ns-name (str "the source did not define it (" file ")")))
            {:handle n :vars (ns-interns n)})
          (catch :default e
            ;; A failed load leaves no partial namespace behind: the claim is
            ;; held, so whatever is installed under the name was installed by
            ;; this evaluation. A failed :reload is the exception — it was
            ;; re-evaluating the INSTALLED namespace in place, and that
            ;; namespace is what already-linked code is holding, so dropping
            ;; the registration would be the destructive choice.
            (when (and (not (reloading? ns-name))
                       (find-ns (symbol ns-name)))
              (remove-ns (symbol ns-name)))
            (throw e)))))))

(defn- source-roots-ns-load
  "The backend's namespace reader: a namespace already linked through the home
   loader is re-used, never re-evaluated — sharing by reference is the point of
   a delegate. A :reload bypasses that reuse (the link is what a reload is
   meant to move past) but keeps the namespace itself: eval-namespace-source
   re-evaluates in place."
  [home hit req]
  (or (when-not (reloading? (:name hit))
        (when-let [h (resolve home {:kind :ns :name (:name hit)})]
          h))
      (eval-namespace-source home (:name hit) (:file hit))))

(defn- source-roots-ns-vars
  [home handle]
  (:vars handle))

;; --- seams for evaluated source -------------------------------------------
;; Called from rewritten source, never by an application. The string ids keep
;; compiled code free of loader objects; the registry maps them back.

(defn- spec-flags
  "Split :reload/:reload-all out of a require/use argument list →
   {:reload? bool :specs […]}. The flag is honored by re-reading through the
   loader and must not reach the runtime's own require, which would read the
   host's roots."
  [specs]
  (let [flags (filter #{:reload :reload-all} specs)]
    {:reload? (boolean (seq flags))
     :specs (vec (remove #(contains? #{:reload :reload-all} %) specs))}))

(defn- ensure-servable!
  [l ns-name dep]
  (when-not (resolve l {:kind :ns :name dep})
    (unreadable! l ns-name (str "requires " dep ", which this loader cannot serve;"
                                " add its root, delegate it, or inject it"))))

(defn- forget-dep!
  "Drop what a previous load of DEP linked in L — the namespace link and its
   var links — so `:reload` re-reads the source and makes fresh cells."
  [l dep]
  (let [links (:links (:state l))
        prefix (str dep "/")]
    (swap! links
           (fn [m]
             (reduce-kv (fn [acc [kind nm] v]
                          (if (or (and (= :ns kind) (= nm dep))
                                  (and (= :var kind) (str/starts-with? nm prefix)))
                            acc
                            (assoc acc [kind nm] v)))
                        {} m)))))

(defn- refer-filters
  "The filter pairs (:only/:exclude/:rename) of a use/refer libspec."
  [opts]
  (mapcat identity (select-keys opts [:only :exclude :rename])))

(defn- refer-lib!
  "Refer LIB in the defining namespace NS-NAME, with a use/refer spec's filters
   when it has any."
  [ns-name lib opts]
  (binding [*ns* (the-ns (symbol ns-name))]
    (if (seq (refer-filters opts))
      (apply refer lib (refer-filters opts))
      (refer lib))))

(defn- alias-lib!
  [ns-name lib a]
  (binding [*ns* (the-ns (symbol ns-name))] (alias a lib)))

(defn- preload-for!
  "Load DEP through L, honoring a forced :reload, and refuse what the loader
   cannot serve (the hermeticity rule the ns-form path already enforces). A
   reload re-reads the source INTO the installed namespace, so definitions
   other code already links to pick up the new roots."
  [l ns-name dep reload?]
  (if reload?
    (do (forget-dep! l dep)
        (binding [*reload-in-place* (conj (or *reload-in-place* #{}) dep)]
          (preload-dep! l dep)))
    (preload-dep! l dep))
  (ensure-servable! l ns-name dep))

(defn __require-in
  "The `require` evaluated source sees: load SPECS through the loader that owns
   the source, apply their :as/:as-alias/:refer/:rename in the DEFINING
   namespace, and honor :reload by re-reading through the loader. Answers nil
   like `require`.

   :reload re-reads into the INSTALLED namespace so definitions already linked
   from it pick up the new roots. It reaches wherever the namespace is served
   from — this loader's own roots, or a delegate's, which reloads it in place
   because the intent is keyed by name. The host root is the one exception: it
   loads through jolt.host's own loaded-mark, so a host namespace's :reload
   re-links and re-applies the effects without a re-read."
  [loader-id ns-name & specs]
  (let [l (loader-by-id loader-id)
        {:keys [reload? specs]} (spec-flags specs)]
    (doseq [spec specs
            [lib opts] (expand-libspec spec)
            :let [dep (str lib)]]
      (if (contains? opts :as-alias)
        (alias-lib! ns-name lib (:as-alias opts))
        (do
          (preload-for! l ns-name dep reload?)
          (when-let [a (:as opts)] (alias-lib! ns-name lib a))
          (when-let [r (:refer opts)]
            (refer-lib! ns-name lib (merge (select-keys opts [:exclude :rename])
                                           (if (= :all r) {} {:only (vec r)})))))))
    nil))

(defn __use-in
  "The `use` evaluated source sees: load SPECS through the loader that owns the
   source and refer them in the DEFINING namespace, a spec's :only/:exclude/
   :rename included."
  [loader-id ns-name & specs]
  (let [l (loader-by-id loader-id)
        {:keys [reload? specs]} (spec-flags specs)]
    (doseq [spec specs
            [lib opts] (expand-libspec spec)
            :let [dep (str lib)]]
      (preload-for! l ns-name dep reload?)
      (when-let [a (:as opts)] (alias-lib! ns-name lib a))
      (refer-lib! ns-name lib (dissoc opts :as)))
    nil))

(defn __refer-in
  "The `refer` evaluated source sees: load NS-SYM through the loader that owns
   the source, then refer it in the DEFINING namespace with the given filters."
  [loader-id ns-name ns-sym & filters]
  (let [l (loader-by-id loader-id)
        dep (str ns-sym)]
    (preload-for! l ns-name dep false)
    (binding [*ns* (the-ns (symbol ns-name))]
      (apply refer ns-sym filters))
    nil))

(defn- host-file-refused!
  [op ns-name path]
  (throw (ex-info (str op " is a host-file operation; the context of " ns-name
                       " reads namespaces through its loader and resources"
                       " through io/resource — use (require 'ns) or"
                       " (io/resource \"name\") instead (" op " "
                       (pr-str path) ")")
                  {:type :loader/unreadable :kind :resource :name (str path)})))

(defn __load-in
  "`load` in evaluated source: refused — a host-file path would step outside
   the context's roots (see host-file-refused!)."
  [loader-id ns-name path & _args]
  (host-file-refused! "load" ns-name path))

(defn __load-file-in
  "`load-file` in evaluated source: refused (see host-file-refused!)."
  [loader-id ns-name path & _args]
  (host-file-refused! "load-file" ns-name path))

(defn __resolve-in
  "The `resolve` evaluated source sees: SYM (`ns/name` or a plain name in the
   defining context's current namespace) resolved through LOADER-ID."
  [loader-id sym]
  (let [l (loader-by-id loader-id)
        nm (if (namespace sym)
             (str sym)
             (str (ns-name *ns*) "/" sym))]
    (when (namespace sym)
      (preload-dep! l (namespace sym)))
    (or (resolve l {:kind :var :name nm})
        (clojure.core/resolve sym))))

(defn __ns-resolve-in
  "The `ns-resolve` evaluated source sees: the var NS-SYM/NAME in the defining
   context."
  [loader-id ns-sym sym]
  (let [l (loader-by-id loader-id)]
    (preload-dep! l (str ns-sym))
    (or (resolve l {:kind :var :name (str ns-sym "/" sym)})
        (ns-resolve ns-sym sym))))

(defn __find-var-in
  "The `find-var` evaluated source sees: the var SYM in the defining context."
  [loader-id sym]
  (let [l (loader-by-id loader-id)]
    (when (namespace sym)
      (preload-dep! l (namespace sym)))
    (or (resolve l {:kind :var :name (str sym)})
        (find-var sym))))

;; --- constructors ---------------------------------------------------------

(defn- roots-loader
  [roots opts]
  (let [{:keys [parent id]} opts
        roots (mapv str roots)]
    (doseq [r roots] (validate-root! r))
    (make-loader
     {:id id
      :parent parent
      :roots roots
      :locate-fn (roots-locate roots)
      :ns-load-fn source-roots-ns-load
      :ns-vars-fn source-roots-ns-vars
      :release-fn release-private!})))

(defn classpath
  "A loader over `roots`, searched in order. `opts` may carry :parent (the
   delegate, nil for none) and :id (a name for `status` and diagnostics). A
   loader IS roots plus a delegate; every combinator below builds a delegate to
   hand to :parent, except `self-first`, which reorders the two.

   Validation is eager: an unreadable root or jar fails here, not at the first
   load."
  ([roots] (classpath roots nil))
  ([roots opts] (roots-loader roots opts)))

(defn url-search
  "The URLClassLoader analogue — `classpath` under the name the delegate table
   uses."
  ([roots] (classpath roots))
  ([roots opts] (classpath roots opts)))

;; ─── Classloader facade ────────────────────────────────────────────────────
;; The host view of a loader (`as-classloader`): a jolt tagged table carrying
;; the loader, with java.lang.ClassLoader's resource methods registered against
;; its tag. The methods are `find` + `open-hit`, so the facade is exactly the
;; context's resource view and holds no handle between calls. One facade per
;; loader, so identity holds and getParent chains.
;;
;; The one facade lives in the loader's own state — keyed by the loader, never
;; by its reusable `:id`. An id-keyed cache served a stale facade to a context
;; minted with an id that was used before (a reload, a per-request context):
;; the facade wrapped the previous, now unloaded loader, and every resource
;; read through it threw "loader <id> is unloaded".

(declare as-classloader)

(defonce ^:private facade-methods-registered? (atom false))

(defn- ensure-facade-methods!
  []
  (when (compare-and-set! facade-methods-registered? false true)
    (clojure.core/__register-class-methods!
     :classloader-facade
     {"getResource"
      (fn [self name]
        (let [l (jolt.host/ref-get self :loader)]
          (when-let [hit (first (find l {:kind :resource :name (str name)}))]
            (hit-url hit))))
      "getResources"
      (fn [self name]
        (let [l (jolt.host/ref-get self :loader)]
          (mapv hit-url (find l {:kind :resource :name (str name)}))))
      "getResourceAsStream"
      (fn [self name]
        (let [l (jolt.host/ref-get self :loader)]
          (when-let [hit (first (find l {:kind :resource :name (str name)}))]
            (open-hit l hit))))
      "getParent"
      (fn [self]
        (when-let [p (parent (jolt.host/ref-get self :loader))]
          (as-classloader p)))
      "close"
      (fn [self]
        (unload! (jolt.host/ref-get self :loader))
        nil)
      "toString"
      (fn [self]
        (str "jolt.loader<" (:id (jolt.host/ref-get self :loader)) ">"))})))

(defn- mint-facade
  [l]
  (doto (jolt.host/tagged-table :classloader-facade)
    (jolt.host/ref-put! :class "java.lang.ClassLoader")
    (jolt.host/ref-put! :loader l)))

(defn as-classloader
  "`l` as a host java.lang.ClassLoader: loadClass is find + load,
   getResource/getResources/getResourceAsStream are find + open-hit, getParent
   is the delegate's facade, close is `unload!`. Accepted by the 2-arity of
   clojure.java.io/resource."
  [l]
  (ensure-facade-methods!)
  (if-let [slot (:facade (:state l))]
    (if-let [f @slot]
      f
      (let [f (mint-facade l)]
        ;; compare-and-set!, not reset!: two racers must not walk away with
        ;; two different facades for one loader.
        (if (compare-and-set! slot nil f) f @slot)))
    ;; a loader not built through make-loader (no state slot): nothing to
    ;; cache on, mint per call.
    (mint-facade l)))

;; clojure.lang.RT/baseLoader must follow the ambient loader the way the JVM's
;; returns the classloader of the calling class: bound inside `with-loader`, it
;; is that context's facade; unbound it is the host singleton captured here
;; (before the override below — asking through the var afterwards would
;; recurse). The host's static table MERGES, so every other RT static stays.
(clojure.core/__register-class-statics!
 "clojure.lang.RT"
 {"baseLoader" (fn []
                  (if-let [l *current-loader*]
                    (as-classloader l)
                    host-base-loader))})

(defn reset-context-state!
  "Drop every loader minted so far (the host root's registration stays) and
   clear the private-namespace ownership table: the host test
   harness (run-case-isolation.ss) calls this between rows, whose namespaces it
   prunes from the registry anyway, so one row cannot see the previous row's
   contexts. Harness bookkeeping, not part of the loader contract — contexts
   minted by the caller stop resolving after it."
  []
  (let [root-id (some-> @root-loader :id)]
    (swap! loaders-by-id (fn [m] (if root-id (select-keys m [root-id]) {})))
    (swap! private-ns-owners
           (fn [m]
             (if root-id
               (into {} (filter (fn [[_ owners]] (contains? owners root-id))) m)
               {})))
    (reset! private-load-claims {})
    nil))
