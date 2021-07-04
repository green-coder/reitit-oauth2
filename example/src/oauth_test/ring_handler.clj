(ns oauth-test.ring-handler
  (:require [integrant.core :as ig]
            [clojure.pprint :refer [pprint]]
            [ring.util.response :as response]
            [ring.middleware.defaults :as defaults]
            [ring.middleware.session :refer [wrap-session]]
            [ring.middleware.session.memory :refer [memory-store]]
            [reitit.ring :as rr]
            [reitit.oauth2 :as oauth2]
            [clj-http.client :as http-client]
            [clojure.data.json :as json]
            [camel-snake-kebab.core :as csk]
            [hiccup.page :as hp]))

(def host-root-url "https://clojure.404.taipei")

(def session-store
  (memory-store))

(def ring-config
  (-> defaults/site-defaults
      (assoc-in [:session :store] session-store)
      (assoc-in [:session :cookie-attrs :same-site] :lax)))

(def oauth2-profiles
  {:gitlab {:authorize-uri    "https://gitlab.com/oauth/authorize"
            :access-token-uri "https://gitlab.com/oauth/token"
            :client-id        "ca9188cc4ff2d4b26bd7b765f01896cf17d81e2157f3c3d14f2294570b1d98ad"
            :client-secret    "<redacted>"
            :scopes           ["read_user"]
            :launch-uri       "/oauth2/gitlab/login"
            :redirect-uri     (str host-root-url "/oauth2/gitlab/callback")
            :landing-uri      "/"}
   :github {:authorize-uri    "https://github.com/login/oauth/authorize"
            :access-token-uri "https://github.com/login/oauth/access_token"
            :client-id        "67d616eaabc9d4f8f4af"
            :client-secret    "<redacted>"
            :scopes           ["user:email"]
            :launch-uri       "/oauth2/github/login"
            :redirect-uri     (str host-root-url "/oauth2/github/callback")
            :landing-uri      "/"}
   :google {:authorize-uri    "https://accounts.google.com/o/oauth2/v2/auth"
            :access-token-uri "https://oauth2.googleapis.com/token"
            :client-id        "35368375375-jjve46noq8ol4r44v7qna6cp6ov07ml8.apps.googleusercontent.com"
            :client-secret    "<redacted>"
            :scopes           ["https://www.googleapis.com/auth/userinfo.email"
                               "https://www.googleapis.com/auth/userinfo.profile"]
            :launch-uri       "/oauth2/google/login"
            :redirect-uri     (str host-root-url "/oauth2/google/callback")
            :landing-uri      "/"}})

(defn fetch-user-data [access-tokens]
  (let [gitlab-access-token (get-in access-tokens [:gitlab :token])
        github-access-token (get-in access-tokens [:github :token])
        google-access-token (get-in access-tokens [:google :token])]
    (cond
      gitlab-access-token
      (let [response (http-client/get "https://gitlab.com/api/v4/user"
                                      {:headers {:authorization (str "Bearer " gitlab-access-token)}})]
        (when (= (:status response) 200)
          (let [user-data (json/read-str (:body response) :key-fn csk/->kebab-case-keyword)]
            {:id (:id user-data)
             :login (:username user-data)
             :name (:name user-data)
             :picture (:avatar-url user-data)
             :source :gitlab})))

      github-access-token
      (let [response (http-client/get "https://api.github.com/user"
                                      {:headers {:authorization (str "token " github-access-token)}})]
        (when (= (:status response) 200)
          (let [user-data (json/read-str (:body response) :key-fn csk/->kebab-case-keyword)]
            {:id (:id user-data)
             :login (:login user-data)
             :name (:name user-data)
             :picture (:avatar-url user-data)
             :source :github})))

      google-access-token
      (let [response (http-client/get "https://www.googleapis.com/oauth2/v2/userinfo"
                                      {:headers {:authorization (str "Bearer " google-access-token)}})]
        (when (= (:status response) 200)
          (let [user-data (json/read-str (:body response) :key-fn csk/->kebab-case-keyword)]
            {:id (:id user-data)
             :login (:email user-data)
             :name (:name user-data)
             :picture (:picture user-data)
             :source :google}))))))

(defn app-handler [request]
  (let [user-info    (or (-> request :session :user-info)
                         (fetch-user-data (-> request :session :oauth2/access-tokens)))
        response     {:status  200
                      :headers {"Content-Type" "text/html"}
                      :body    (hp/html5
                                 [:head
                                  [:meta {:charset "UTF-8"}]]
                                 [:body
                                  [:main
                                   (when user-info
                                     [:div
                                      [:p "Logged in via " (name (:source user-info))]
                                      [:p "Login: " (:login user-info)]
                                      [:p "Name: " (:name user-info)]
                                      [:img {:src (:picture user-info)}]])
                                   [:h1 "Clojure Ecosystem Meta"]
                                   [:p
                                    [:a {:href "/"} "Home"]]
                                   [:p
                                    (if user-info
                                      "Login via Gitlab"
                                      [:a {:href (-> oauth2-profiles :gitlab :launch-uri)} "Login via Gitlab"])]
                                   [:p
                                    (if user-info
                                      "Login via Github"
                                      [:a {:href (-> oauth2-profiles :github :launch-uri)} "Login via Github"])]
                                   [:p
                                    (if user-info
                                      "Login via Google"
                                      [:a {:href (-> oauth2-profiles :google :launch-uri)} "Login via Google"])]
                                   [:p
                                    (if user-info
                                      [:a {:href "/logout"} "Logout"]
                                      "Logout")]]])}
        session (-> (:session request)
                    (assoc :user-info user-info))]
    (-> response
        (assoc :session session))))

(defn logout-handler [landing-uri]
  (fn [request]
    (let [session (-> (:session request)
                      (dissoc :oauth2/access-tokens :user-info))]
      (-> (response/redirect landing-uri)
          (assoc :session session)))))

(def not-found-handler
  (constantly
    (response/not-found
      (hp/html5
        [:head
         [:meta {:charset "UTF-8"}]]
        [:body
         [:h1 "Not found, sorry"]]))))

(def full-handler
  (rr/ring-handler
    (rr/router
      (into (oauth2/reitit-routes oauth2-profiles)
            [["/logout" {:get {:handler (logout-handler "/")}}]
             ["/" {:get {:handler app-handler}}]]))
    not-found-handler
    {:middleware [[wrap-session (:session ring-config)]]}))

(comment
  (full-handler {:request-method :get
                 :uri "/oauth2/github/login"})
  (full-handler {:request-method :get
                 :uri "/logout"}))

(defmethod ig/init-key :ring/handler [_ _]
  #'full-handler)
