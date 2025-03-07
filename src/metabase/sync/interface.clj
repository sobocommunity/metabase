(ns metabase.sync.interface
  "Schemas and constants used by the sync code."
  (:require
   [metabase.models.database :refer [Database]]
   [metabase.models.field :refer [Field]]
   [metabase.models.interface :as mi]
   [metabase.models.table :refer [Table]]
   [metabase.util.schema :as su]
   [schema.core :as s]))

(def DatabaseMetadataTable
  "Schema for the expected output of `describe-database` for a Table."
  {:name                         su/NonBlankString
   :schema                       (s/maybe su/NonBlankString)
   ;; `:description` in this case should be a column/remark on the Table, if there is one.
   (s/optional-key :description) (s/maybe s/Str)})

(def DatabaseMetadata
  "Schema for the expected output of `describe-database`."
  {:tables                   #{DatabaseMetadataTable}
   (s/optional-key :version) (s/maybe su/NonBlankString)})

(def TableMetadataField
  "Schema for a given Field as provided in `describe-table`."

  {:name                                        su/NonBlankString
   :database-type                               (s/maybe su/NonBlankString) ; blank if the Field is all NULL & untyped, i.e. in Mongo
   :base-type                                   su/FieldType
   :database-position                           su/IntGreaterThanOrEqualToZero
   (s/optional-key :semantic-type)              (s/maybe su/FieldSemanticOrRelationType)
   (s/optional-key :effective-type)             (s/maybe su/FieldType)
   (s/optional-key :coercion-strategy)          (s/maybe su/CoercionStrategy)
   (s/optional-key :field-comment)              (s/maybe su/NonBlankString)
   (s/optional-key :pk?)                        s/Bool
   (s/optional-key :nested-fields)              #{(s/recursive #'TableMetadataField)}
   (s/optional-key :json-unfolding)             s/Bool
   (s/optional-key :nfc-path)                   [s/Any]
   (s/optional-key :custom)                     {s/Any s/Any}
   (s/optional-key :database-is-auto-increment) s/Bool
   (s/optional-key :database-required)          s/Bool
   ;; for future backwards compatability, when adding things
   s/Keyword                                    s/Any})

(def TableMetadata
  "Schema for the expected output of `describe-table`."
  {:name                         su/NonBlankString
   :schema                       (s/maybe su/NonBlankString)
   :fields                       #{TableMetadataField}
   (s/optional-key :description) (s/maybe s/Str)})

(def NestedFCMetadata
  "Schema for the expected output of `describe-nested-field-columns`."
  (s/maybe #{TableMetadataField}))

(def FKMetadataEntry
  "Schema for an individual entry in `FKMetadata`."
  {:fk-column-name   su/NonBlankString
   :dest-table       {:name   su/NonBlankString
                      :schema (s/maybe su/NonBlankString)}
   :dest-column-name su/NonBlankString})

(def FKMetadata
  "Schema for the expected output of `describe-table-fks`."
  (s/maybe #{FKMetadataEntry}))

;; These schemas are provided purely as conveniences since adding `:import` statements to get the corresponding
;; classes from the model namespaces also requires a `:require`, which `clj-refactor` seems more than happy to strip
;; out from the ns declaration when running `cljr-clean-ns`. Plus as a bonus in the future we could add additional
;; validations to these, e.g. requiring that a Field have a base_type

(def DatabaseInstance             "Schema for a valid instance of a Metabase Database." (mi/InstanceOf Database))
(def TableInstance                "Schema for a valid instance of a Metabase Table."    (mi/InstanceOf Table))
(def FieldInstance                "Schema for a valid instance of a Metabase Field."    (mi/InstanceOf Field))
(def ResultColumnMetadataInstance "Schema for result column metadata."                  su/Map)


;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                            SAMPLING & FINGERPRINTS                                             |
;;; +----------------------------------------------------------------------------------------------------------------+

(def Percent
  "Schema for something represting a percentage. A floating-point value between (inclusive) 0 and 1."
  (s/constrained s/Num #(<= 0 % 1) "Valid percentage between (inclusive) 0 and 1."))

(def GlobalFingerprint
  "Fingerprint values that Fields of all types should have."
  {(s/optional-key :distinct-count) s/Int
   (s/optional-key :nil%)           (s/maybe Percent)})

(def NumberFingerprint
  "Schema for fingerprint information for Fields deriving from `:type/Number`."
  {(s/optional-key :min) (s/maybe s/Num)
   (s/optional-key :max) (s/maybe s/Num)
   (s/optional-key :avg) (s/maybe s/Num)
   (s/optional-key :q1)  (s/maybe s/Num)
   (s/optional-key :q3)  (s/maybe s/Num)
   (s/optional-key :sd)  (s/maybe s/Num)})

(def TextFingerprint
  "Schema for fingerprint information for Fields deriving from `:type/Text`."
  {(s/optional-key :percent-json)   (s/maybe Percent)
   (s/optional-key :percent-url)    (s/maybe Percent)
   (s/optional-key :percent-email)  (s/maybe Percent)
   (s/optional-key :percent-state)  (s/maybe Percent)
   (s/optional-key :average-length) (s/maybe s/Num)})

(def TemporalFingerprint
  "Schema for fingerprint information for Fields deriving from `:type/Temporal`."
  {(s/optional-key :earliest) (s/maybe s/Str)
   (s/optional-key :latest)   (s/maybe s/Str)})

(def TypeSpecificFingerprint
  "Schema for type-specific fingerprint information."
  (s/constrained
   {(s/optional-key :type/Number)   NumberFingerprint
    (s/optional-key :type/Text)     TextFingerprint
    ;; temporal fingerprints are keyed by `:type/DateTime` for historical reasons. `DateTime` used to be the parent of
    ;; all temporal MB types.
    (s/optional-key :type/DateTime) TemporalFingerprint}
   (fn [m]
     (= 1 (count (keys m))))
   "Type-specific fingerprint with exactly one key"))

(def Fingerprint
  "Schema for a Field 'fingerprint' generated as part of the analysis stage. Used to power the 'classification'
   sub-stage of analysis. Stored as the `fingerprint` column of Field."
  (su/open-schema
    {(s/optional-key :global)       GlobalFingerprint
     (s/optional-key :type)         TypeSpecificFingerprint
     (s/optional-key :experimental) {s/Keyword s/Any}}))


;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                             FINGERPRINT VERSIONING                                             |
;;; +----------------------------------------------------------------------------------------------------------------+

;; Occasionally we want to update the schema of our Field fingerprints and add new logic to populate the additional
;; keys. However, by default, analysis (which includes fingerprinting) only runs on *NEW* Fields, meaning *EXISTING*
;; Fields won't get new fingerprints with the updated info.
;;
;; To work around this, we can use a versioning system. Fields whose Fingerprint's version is lower than the current
;; version should get updated during the next sync/analysis regardless of whether they are or are not new Fields.
;; However, this could be quite inefficient: if we add a new fingerprint field for `:type/Number` Fields, why should
;; we re-fingerprint `:type/Text` Fields? Ideally, we'd only re-fingerprint the numeric Fields.
;;
;; Thus, our implementation below. Each new fingerprint version lists a set of types that should be upgraded to it.
;; Our fingerprinting logic will calculate whether a fingerprint needs to be recalculated based on its version and the
;; changes that have been made in subsequent versions. Only the Fields that would benefit from the new Fingerprint
;; info need be re-fingerprinted.
;;
;; Thus, if Fingerprint v2 contains some new info for numeric Fields, only Fields that derive from `:type/Number` need
;; be upgraded to v2. Textual Fields with a v1 fingerprint can stay at v1 for the time being. Later, if we introduce a
;; v3 that includes new "global" fingerprint info, both the v2-fingerprinted numeric Fields and the v1-fingerprinted
;; textual Fields can be upgraded to v3.

(def fingerprint-version->types-that-should-be-re-fingerprinted
  "Map of fingerprint version to the set of Field base types that need to be upgraded to this version the next
   time we do analysis. The highest-numbered entry is considered the latest version of fingerprints."
  {1 #{:type/*}
   2 #{:type/Number}
   3 #{:type/DateTime}
   4 #{:type/*}
   5 #{:type/Text}})

(def latest-fingerprint-version
  "The newest (highest-numbered) version of our Field fingerprints."
  (apply max (keys fingerprint-version->types-that-should-be-re-fingerprinted)))
