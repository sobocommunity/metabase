(ns metabase.api.metabot
  (:require
   [clojure.string :as str]
   [compojure.core :refer [POST]]
   [metabase.api.common :as api]
   [metabase.metabot :as metabot]
   [metabase.models :refer [Card Collection Database Field FieldValues Table]]
   [metabase.util.malli.schema :as ms]
   [toucan2.core :as t2]))

(set! *warn-on-reflection* true)

#_{:clj-kondo/ignore [:deprecated-var]}
(api/defendpoint-schema POST "/model/:model-id"
  "Ask Metabot to generate a SQL query given a prompt about a given model."
  [model-id :as {{:keys [question fake] :as body} :body}]
  ;{model-id ms/PositiveInt
  ; question string?}
  (tap> {:model-id model-id
         :request  body})
  (let [model (api/check-404 (t2/select-one Card :id model-id :dataset true))]
    (or
     (metabot/generate-dataset-from-prompt model question fake)
     (throw
      (let [message (format
                     "Query '%s' didn't produce any SQL. Perhaps try a more detailed query."
                     question)]
        (ex-info
         message
         {:status-code 400
          :message     message}))))))

#_{:clj-kondo/ignore [:deprecated-var]}
(api/defendpoint-schema POST "/database/:database-id"
  "Ask Metabot to generate a SQL query given a prompt about a given database."
  [database-id :as {{:keys [question fake] :as body} :body}]
  ;{database-id ms/PositiveInt
  ; question string?}
  (tap> {:database-id database-id
         :request     body})
  (let [{:as database} (api/check-404 (t2/select-one Database :id database-id))]
    (if-some [model (metabot/find-best-model database question)]
      (metabot/generate-dataset-from-prompt model question fake)
      (throw
       (let [message (format
                      (str/join
                       " "
                       ["Query '%s' didn't find a good match to your data."
                        "Perhaps try a query that mentions the model name or columns more specificially."])
                      question)]
         (ex-info
          message
          {:status-code 400
           :message     message}))))))

(api/define-routes)