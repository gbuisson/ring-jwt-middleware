(ns ring-jwt-middleware.core-test
  (:require [clj-jwt.key :refer [public-key]]
            [clj-momo.lib.clj-time.core :as time]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [compojure.api
             [api :refer [api]]
             [sweet :refer [context POST]]]
            [ring-jwt-middleware.core :as sut]
            [schema.core :as s]))

(defn with-fixed-time
  [f]
  (with-redefs
    [time/now
     (constantly
      (time/date-time 2017 06 30 9 35 2))]
    (f)))

(defn make-jwt
  "a useful one liner for easy testing"
  [input-map]
  (-> input-map
      clj-jwt.core/jwt
      (clj-jwt.core/sign
       :RS256
       (clj-jwt.key/private-key
        "resources/cert/ring-jwt-middleware.key"
        "clojure"))
      clj-jwt.core/to-str))

(use-fixtures :once with-fixed-time)

(def input-jwt-token-1
  "a map for creating a sample token with clj-jwt"
  {:jti "r3e03ac6e-8d09-4d5e-8598-30e51a26dd2d"
   :exp (clj-time.coerce/from-long (* 1000 1499419023))
   :iat (clj-time.coerce/from-long (* 1000 1498814223)) ;; 2017-06-30T09:17:03Z
   :nbf (clj-time.coerce/from-long (* 1000 1498813923))
   :sub "foo@bar.com"
   :user-identifier "foo@bar.com"
   :user_id "f0010924-e1bc-4b03-b600-89c6cf52757c"
   :foo "bar"})

(def jwt-token-1
  "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.eyJ1c2VyLWlkZW50aWZpZXIiOiJmb29AYmFyLmNvbSIsInN1YiI6ImZvb0BiYXIuY29tIiwiZXhwIjoxNDk5NDE5MDIzLCJqdGkiOiJyM2UwM2FjNmUtOGQwOS00ZDVlLTg1OTgtMzBlNTFhMjZkZDJkIiwibmJmIjoxNDk4ODEzOTIzLCJmb28iOiJiYXIiLCJ1c2VyX2lkIjoiZjAwMTA5MjQtZTFiYy00YjAzLWI2MDAtODljNmNmNTI3NTdjIiwiaWF0IjoxNDk4ODE0MjIzfQ.PLNokPvuPz5t0Se3m2pjxzB97lJoWvGICXAia7mAxTiW8WBH0pOOm74ffHEeXGH1y8bRlfmH29eVKHq_IpZfYRIV0ydegQhbty5C35ij3Mqo0A3pAGOoezyp3XymHHE-JeEAgulxYy8BWN9zpij-zYO2uAZf4r7HIuNT5CJWTnmS4AYSrNeQl0ntTLUYwjDLwuJrL7VH4JeiwSEK-HBN1YkxLNPc22hyXKHz37vj4ERO1-GnEmdtOIntZ-BRj-qoX0q1Qx0BGyK-kJgRz_nHrbyX6GtuqYXzhU-uQ-S122K1s6Vek9GmtncchH2qkMaAtv7J4NTVnFU_3t_7LiOlRw")

(def decoded-jwt-1
  "jwt-token-1 decoded"
  {:jti "r3e03ac6e-8d09-4d5e-8598-30e51a26dd2d"
   :exp 1499419023
   :iat 1498814223 ;; 2017-06-30T09:17:03Z
   :nbf 1498813923
   :sub "foo@bar.com"
   :user-identifier "foo@bar.com"
   :user_id "f0010924-e1bc-4b03-b600-89c6cf52757c"
   :foo "bar"})

(def jwt-signed-with-wrong-algorithm
  "eyJhbGciOiJub25lIiwidHlwIjoiSldUIn0.eyJqdGkiOiJyM2UwM2FjNmUtOGQwOS00ZDVlLTg1OTgtMzBlNTFhMjZkZDJkIiwiZXhwIjoxNDk5NDE5MDIzLCJpYXQiOjE0OTg4MTQyMjMsIm5iZiI6MTQ5ODgxMzkyMywic3ViIjoiZm9vQGJhci5jb20iLCJ1c2VyLWlkZW50aWZpZXIiOiJmb29AYmFyLmNvbSIsInVzZXJfaWQiOiJmMDAxMDkyNC1lMWJjLTRiMDMtYjYwMC04OWM2Y2Y1Mjc1N2MiLCJmb28iOiJiYXIifQ.")

(def decoded-jwt-2
  {:user-identifier "bar@foo.com",
   :iat 1487168050 ;; 2017-02-15T14:14:10Z
   :exp (+ 1487168050 (* 7 24 60 60))
   :nbf (- 1487168050 (* 5 24 60 60))})

(deftest decode-test
  (is (= decoded-jwt-1
         (sut/decode jwt-token-1
                     (public-key "resources/cert/ring-jwt-middleware.pub"))))
  (is (nil?
       (sut/decode jwt-signed-with-wrong-algorithm
                   (public-key "resources/cert/ring-jwt-middleware.pub")))))

(deftest validate-errors-test
  (is (nil? (sut/validate-jwt decoded-jwt-1 86400)))
  (is (= '("This JWT doesn't contain the following fields #{:exp :nbf :iat}")
         (sut/validate-jwt {} 86400)))
  (is (= '("This JWT doesn't contain the following fields #{:exp :nbf}")
         (sut/validate-jwt {:user-identifier "foo@bar.com"
                            :iat 1487168050} 86400)))
  (with-redefs
    [time/now (constantly (time/date-time 2017 02 16 14 14 11))]
    (is (= '("This JWT has expired since 1s")
           (sut/validate-jwt decoded-jwt-2 86400))))

  (with-redefs
    [time/now (constantly (time/date-time 2017 02 16 15 14 10 0))]
    (is (= '("This JWT has expired since 1h")
           (sut/validate-jwt decoded-jwt-2 86400))))

  (with-redefs
    [time/now (constantly (time/date-time 2017 02 17 15 14 10 0))]
    (is (= '("This JWT has expired since 1 day 1h")
           (sut/validate-jwt decoded-jwt-2 86400))))

  (with-redefs
    [time/now (constantly (time/date-time 2017 02 18 15 14 10 0))]
    (is (= '("This JWT has expired since 2 days 1h")
           (sut/validate-jwt decoded-jwt-2 86400))))

  (with-redefs
    [time/now (constantly (time/date-time 2019 04 03 8 24 5 123))]
    (is (= '("This JWT has expired since 2 years 45 days 18h 9min 55s")
           (sut/validate-jwt decoded-jwt-2 86400)))))

(deftest get-jwt-test
  (testing "get-jwt requests containing a JWT"
    (is (= "foo"
           (sut/get-jwt {:headers {"authorization" "Bearer foo"}}))))
  (testing "get-jwt requests without no JWT"
    (is (nil? (sut/get-jwt {:headers {"authorization" "Bearer"}})))
    (is (nil? (sut/get-jwt {:headers {"bad" "Bearer foo"}})))))

(deftest wrap-jwt-auth-fn-test
  (testing "basic usage"
    (let [wrapper (sut/wrap-jwt-auth-fn
                   {:pubkey-path "resources/cert/ring-jwt-middleware.pub"})
          ring-fn-1 (wrapper (fn [req] {:status 200
                                       :body (:identity req)}))
          ring-fn-2 (wrapper (fn [req] {:status 200
                                       :body(:jwt req)}))
          req-1 {:headers {"authorization"
                           (str "Bearer " jwt-token-1)}}
          req-2 {:headers {"authorization"
                           (str "Bearer x" jwt-token-1)}}]
      (with-redefs [time/now (constantly (time/date-time 2017 06 30 11 32 10))]
        (let [response-1 (ring-fn-1 req-1)
              response-2 (ring-fn-2 req-1)]
          (:status (ring-fn-1 req-1))
          (is (= 200 (:status response-1)))
          (is (= "foo@bar.com" (:body response-1)))

          (is (= decoded-jwt-1 (:body response-2)))
          (is (= 401
                 (:status (ring-fn-1 req-2))))))
      (testing "The JWT should be expired after 24h"
        (is (= 401
               (with-redefs [time/now (constantly (time/date-time 2017 07 1 9 17 4))]
                 (:status (ring-fn-1 req-1)))))
        (is (= 200
               (with-redefs [time/now (constantly (time/date-time 2017 07 1 9 17 3))]
                 (:status (ring-fn-1 req-1))))))))
  (testing "revocation test"
    (let [always-revoke (fn [_] true)
          wrapper-always-revoke (sut/wrap-jwt-auth-fn
                                 {:pubkey-path "resources/cert/ring-jwt-middleware.pub"
                                  :is-revoked-fn always-revoke})
          never-revoke (fn [_] false)
          wrapper-never-revoke (sut/wrap-jwt-auth-fn
                                {:pubkey-path  "resources/cert/ring-jwt-middleware.pub"
                                 :is-revoked-fn never-revoke})
          ring-fn-1 (wrapper-always-revoke
                     (fn [req] {:status 200
                               :body (:identity req)}))

          ring-fn-2 (wrapper-never-revoke
                     (fn [req] {:status 200
                               :body (:identity req)}))
          req {:headers {"authorization"
                         (str "Bearer " jwt-token-1)}}]
      (is (= 401 (:status (ring-fn-1 req))))
      (is (= 200 (:status (ring-fn-2 req))))
      (is (= "foo@bar.com"
             (:body (ring-fn-2 req)))))))

(deftest compojure-api-restructuring-test
  (let [api-1
        (api {}
             (context "/test" []
                      (POST "/test" []
                            :return {:foo s/Str
                                     :user_id s/Str}
                            :body-params  [{lorem :- s/Str ""}]
                            :summary "Does nothing"
                            :jwt-params [foo :- s/Str
                                         user_id :- s/Str
                                         exp :- s/Num
                                         {boolean_field
                                          :- s/Bool "false"}]
                            {:status 200
                             :body {:foo foo
                                    :user_id user_id}})))]

    (is (= {:foo "bar",
            :user_id "f0010924-e1bc-4b03-b600-89c6cf52757c"}
           (read-string
            (slurp (:body (api-1 {:request-method :post
                                  :uri "/test/test"
                                  :headers {"accept" "application/edn"
                                            "content-type" "application/edn"}
                                  :body-params {:lorem "ipsum"}
                                  :jwt-params {}
                                  :jwt decoded-jwt-1}))))))

    (is (= {:errors
            {:boolean_field
             "(not (instance? java.lang.Boolean \"not a boolean\"))"}}
           (read-string
            (slurp (:body (api-1 {:request-method :post
                                  :uri "/test/test"
                                  :headers {"accept" "application/edn"
                                            "content-type" "application/edn"}
                                  :body-params {:lorem "ipsum"}
                                  :jwt-params {}
                                  :jwt (assoc decoded-jwt-1
                                              "boolean_field"
                                              "not a boolean")}))))))))

(deftest check-jwt-filter-test
  (is (nil? (sut/check-jwt-filter! nil {:foo "quux"}))
      "JWT should alway pass when there is no filter")

  (is (nil? (sut/check-jwt-filter! #{{:foo "bar"} {:foo "baz"}}
                                   {:foo "bar"})))

  (is (nil? (sut/check-jwt-filter! #{{:foo "bar"} {:foo "baz"}}
                                   {:foo "bar"
                                    :bar "baz"})))

  (is (try (sut/check-jwt-filter! #{{:foo "bar"} {:foo "baz"}}
                                  {:foo "quux"})
           false
           (catch Exception e
             true)))

  (is (try (sut/check-jwt-filter! #{{:foo "bar"} {:foo "baz"}}
                                  {:foo "quux"
                                   :baz "bar"})
           false
           (catch Exception e
             true))))
