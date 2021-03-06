(ns ring-jwt-middleware.core
  (:require [clj-jwt
             [core :refer :all]
             [key :refer [public-key]]]
            [clj-momo.lib.clj-time
             [coerce :as time-coerce]
             [core :as time]]
            [clojure
             [set :as set]
             [string :as str]]
            [clojure.tools.logging :as log]
            [compojure.api.meta :as meta]
            [ring.util.http-response :refer [unauthorized]]))

(defn get-jwt
  "get the JWT from a ring request"
  [req]
  (some->> (get-in req [:headers "authorization"])
           (re-seq #"^Bearer\s+(.*)$")
           first
           second))

(defn decode
  "Given a JWT return an Auth hash-map"
  [token pubkey]
  (try
    (let [jwt (str->jwt token)]
      (if (verify jwt :RS256 pubkey)
        (:claims jwt)
        (do (log/warn "Invalid signature")
            nil)))
    (catch Exception e
      (log/warn "JWT decode failed:" (.getMessage e)) nil)))

(defn hr-duration
  "Given a duration in ms,
   return a human readable string"
  [t]
  (let [second     1000
        minute     (* 60 second)
        hour       (* 60 minute)
        day        (* 24 hour)
        year       (* 365 day)
        nb-years   (quot t year)
        nb-days    (quot (rem t year) day)
        nb-hours   (quot (rem t day) hour)
        nb-minutes (quot (rem t hour) minute)
        nb-seconds (quot (rem t minute) second)
        nb-ms      (rem t second)]
    (->> (vector
          (when (pos? nb-years)
            (str nb-years " year" (when (> nb-years 1) "s")))
          (when (pos? nb-days)
            (str nb-days " day" (when (> nb-days 1) "s")))
          (when (pos? nb-hours)
            (str nb-hours "h"))
          (when (pos? nb-minutes)
            (str nb-minutes "min"))
          (when (pos? nb-seconds)
            (str nb-seconds "s"))
          (when (pos? nb-ms)
            (str nb-ms "ms")))
         (remove nil?)
         (str/join " "))))

(defn jwt-expiry-ms
  "Given a JWT and a lifetime,
   calculate when it expired"
  [jwt-created jwt-max-lifetime-in-sec]
  (- (time-coerce/to-epoch (time/now))
     (+ jwt-created
        jwt-max-lifetime-in-sec)))

(defn check-jwt-expiry
  "Return a string if JWT expiration check fails, nil otherwise"
  [jwt jwt-max-lifetime-in-sec]
  (let [required-fields #{:nbf :exp :iat}
        jwt-keys (set (keys jwt))]
    (if (set/subset? required-fields jwt-keys)
      (let [now (time-coerce/to-epoch (time/now))
            expired-secs (- now (+ (:iat jwt 0)
                                   jwt-max-lifetime-in-sec))
            before-secs (- (:nbf jwt) now)
            expired-lifetime-secs (- now (:exp jwt 0))]
        (cond
          (pos? before-secs)
          (format "This JWT will be valid in %s"
                  (hr-duration (* 1000 before-secs)))

          (pos? expired-secs)
          (format "This JWT has expired since %s"
                  (hr-duration (* 1000 expired-secs)))

          (pos? expired-lifetime-secs)
          (format "This JWT max lifetime has expired since %s"
                  (hr-duration (* 1000 expired-lifetime-secs)))))
      (format "This JWT doesn't contain the following fields %s"
              (pr-str (set/difference required-fields jwt-keys))))))

(defn log-and-refuse
  "Return an `unauthorized` HTTP response
  and log the error along debug infos"
  [error-log-msg error-msg]
  (log/debug error-log-msg)
  (log/errorf "JWT Error(s): %s" error-msg)
  (unauthorized error-msg))

(def default-jwt-lifetime-in-sec 86400)

(defn no-revocation-strategy
  [_]
  false)

(defn validate-jwt
  "Run both expiration and user checks,
  return a vec of errors or nothing"
  ([jwt
    jwt-max-lifetime-in-sec
    jwt-check-fn]
   (let [exp-vals [(check-jwt-expiry jwt (or jwt-max-lifetime-in-sec
                                             default-jwt-lifetime-in-sec))]
         checks (if (fn? jwt-check-fn)
                  (or (jwt-check-fn jwt) [])
                  [])]
     (seq (remove nil?
                  (concat checks exp-vals)))))

  ([jwt jwt-max-lifetime-in-sec]
   (validate-jwt jwt jwt-max-lifetime-in-sec nil)))

(defn wrap-jwt-auth-fn
  "wrap a ring handler with JWT check"
  [{:keys [pubkey-path
           jwt-max-lifetime-in-sec
           is-revoked-fn
           jwt-check-fn]
    :or {jwt-max-lifetime-in-sec default-jwt-lifetime-in-sec
         is-revoked-fn no-revocation-strategy}}]

  (let [pubkey (public-key pubkey-path)]
    (fn [handler]
      (fn [request]
        (if-let [raw-jwt (get-jwt request)]
          (if-let [jwt (decode raw-jwt pubkey)]
            (if-let [validation-errors
                     (validate-jwt jwt jwt-max-lifetime-in-sec jwt-check-fn)]
              (log-and-refuse (pr-str jwt)
                              (format "(%s) %s"
                                      (:user-identifier jwt "Unkown User ID")
                                      (str/join ", " validation-errors)))
              (if (is-revoked-fn jwt)
                (log-and-refuse
                 (pr-str jwt)
                 (format "JWT revoked for %s"
                         (:user-identifier jwt "Unkown User ID")))
                (handler (assoc request
                                :identity (:user-identifier jwt)
                                :jwt jwt))))
            (log-and-refuse (str "Bearer:" (pr-str raw-jwt))
                            "Invalid Authorization Header (couldn't decode the JWT)"))
          (unauthorized "No Authorization Header"))))))

;; compojure-api restructuring
;; add the :jwt-params in the route description
(defmethod meta/restructure-param :jwt-params [_ jwt-params acc]
  (let [schema  (meta/fnk-schema jwt-params)
        new-letks [jwt-params (meta/src-coerce! schema :jwt :string)]]
    (update-in acc [:letks] into new-letks)))

;; add the :jwt-filter in the route description
(defn sub-hash?
  "Return true if the 1st hashmap is a sub hashmap of the second.

    ~~~clojure
    > (sub-hash? {:foo 1 :bar 2} {:foo 1 :bar 2 :baz 3})
    true
    > (sub-hash? {:foo 1 :bar 2} {:foo 1})
    false
    > (sub-hash? {:foo 1 :bar 2} {:foo 1 :bar 3})
    false
    ~~~
  "
  [hashmap-to-match h]
  (= (select-keys h (keys hashmap-to-match))
     hashmap-to-match))

(defn check-jwt-filter! [required jwt]
  (when (and (some? required)
             (every? #(not (sub-hash? % jwt)) required))
    (log/errorf "Unauthorized access attempt: %s"
                (pr-str
                 {:text ":jwt-filter params mismatch"
                  :required required
                  :identity jwt}))
    (ring.util.http-response/unauthorized!
     {:msg "You don't have the required credentials to access this route"})))

;;
;; add the :jwt-filter
;; to compojure api params
;; it should contains a set of hash-maps
;; example:
;;
;; (POST "/foo" [] :jwt-filter #{{:foo "bar"} {:foo "baz"}})
;;
;; Will be accepted only for people having a jwt such that the value
;; for :foo is either "bar" or "baz"
(defmethod compojure.api.meta/restructure-param :jwt-filter [_ authorized acc]
  (update-in acc
             [:lets]
             into
             ['_ `(check-jwt-filter! ~authorized (:jwt ~'+compojure-api-request+))]))
