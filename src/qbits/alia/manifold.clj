(ns qbits.alia.manifold
  (:require
   [manifold.deferred :as d]
   [manifold.stream :as s]
   [qbits.alia.codec :as codec]
   [qbits.alia :refer [ex->ex-info query->statement set-statement-options!
                       default-executor]])
  (:import
   (com.datastax.driver.core
    Statement
    ResultSetFuture
    Session
    Statement)
   (com.google.common.util.concurrent
    Futures
    FutureCallback)))

(defn execute
  "Same as execute, but returns a promise and accepts :success
  and :error handlers via options, you can also pass :executor via the
  option map for the ResultFuture, it defaults to a cachedThreadPool
  if you don't.

  For other options refer to `qbits.alia/execute` doc"
  ([^Session session query {:keys [success error executor consistency
                                   serial-consistency routing-key
                                   retry-policy tracing? string-keys? fetch-size
                                   values]}]
     (let [^Statement statement (query->statement query values)]
       (set-statement-options! statement routing-key retry-policy tracing?
                               consistency serial-consistency fetch-size)
       (let [^ResultSetFuture rs-future
             (try
               (.executeAsync session statement)
               (catch Exception ex
                 (throw (ex->ex-info ex {:query statement :values values}))))
             deferred (d/deferred)]
         (d/on-realized deferred success error)
         (Futures/addCallback
          rs-future
          (reify FutureCallback
            (onSuccess [_ result]
              (d/success! deferred
                          (codec/result-set->maps (.get rs-future) string-keys?)))
            (onFailure [_ ex]
              (d/error! deferred
                        (ex->ex-info ex {:query statement :values values}))))
          (or executor @default-executor))
         deferred)))
  ([^Session session query]
     (execute session query {})))


(defn execute-buffered
  "Allows to execute a query and have rows returned in a
  manifold stream. Every value in the stream is a single
  row. By default the query `:fetch-size` inherits from the cluster
  setting, unless you specify a different `:fetch-size` at query level
  and the stream inherits fetch size, unless you
  pass your own `:stream` with its own properties.
  If you close the stream the streaming process ends.
  Exceptions are sent to the stream as a value, it's your
  responsability to handle these how you deem appropriate. For options
  refer to `qbits.alia/execute` doc"
  ([^Session session query {:keys [executor consistency serial-consistency
                                   routing-key retry-policy tracing?
                                   string-keys? fetch-size values
                                   stream]}]
     (let [^Statement statement (query->statement query values)]
       (set-statement-options! statement routing-key retry-policy tracing?
                               consistency serial-consistency fetch-size)
       (let [^ResultSetFuture rs-future (.executeAsync session statement)
             stream (or stream
                        (s/stream (or fetch-size (-> session .getCluster
                                                     .getConfiguration
                                                     .getQueryOptions
                                                     .getFetchSize))))]
         (Futures/addCallback
          rs-future
          (reify FutureCallback
            (onSuccess [_ result]
              (loop [rows (codec/result-set->maps (.get rs-future) string-keys?)]
                (when-let [row (first rows)]
                  (when (s/put! stream row)
                    (recur (rest rows)))))
              (s/close! stream))
            (onFailure [_ ex]
              (s/put! stream (ex->ex-info ex {:query statement :values values}))
              (s/close! stream)))
          (or executor @default-executor))
         stream)))
  ([^Session session query]
     (execute-buffered session query {})))
