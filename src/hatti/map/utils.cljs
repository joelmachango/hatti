(ns hatti.map.utils
  (:use [cljs.reader :only [read-string]])
  (:require [clojure.string :as string]
            [cljs.core.async :refer [put!]]
            [cljsjs.leaflet]
            [hatti.constants :refer [_id _rank
                                     mapboxgl-access-token tiles-endpoint]]
            [hatti.ona.forms :as f]
            [hatti.utils :refer [indexed]]
            [om.core :as om :include-macros true]))

;; STYLES

(def ona-styles
  {:point {:normal  #js {:radius 6
                         :fillColor "#f30"
                         :reset #js {:fillColor "#f30"}
                         :color "#fff"
                         :border 8
                         :opacity 0.5
                         :fillOpacity 0.9}
           :hover   #js {:fillColor "#631400"}
           :clicked #js {:fillColor "#ad2300"}}
   :shape {:normal  #js {:fillColor "#f30"
                         :color "#666"
                         :weight 3
                         :dashArray "3"
                         :fillOpacity 0.7
                         :opacity 0.8}
           :hover   #js {:fillColor "#631400"
                         :color "#222"}
           :clicked #js {:fillColor "#ad2300"
                         :color "#222"}}
   :line {:normal #js {:color "#f30"
                       :opacity 1
                       :weight 6
                       :reset #js {:color "#f30"}}
          :hover #js {:color "#631400"}
          :clicked #js {:color "#ad2300"}}})

(defn marker->geotype
  "Returns geotype (:point :line or :shape) based on marker."
  [marker]
  (-> marker
      (aget "feature") (aget "geometry") (aget "type")
      {"Point" :point "Polygon" :shape "LineString" :line}))

(defn get-ona-style
  "Appropriate style given style-type (:normal, :clicked, :hover), and
   either a leaflet marker or clojurescript keyword (one of :point or :shape)."
  [marker_or_keyword style-type]
  (let [kw (if (keyword? marker_or_keyword)
             marker_or_keyword
             (marker->geotype marker_or_keyword))]
    (-> ona-styles kw style-type)))

(defn equivalent-style [s t]
  "Checks that for all common properties, s and t are equivalent styles."
  (let [sc (js->clj s) tc (js->clj t)]
    (= (select-keys sc (keys tc)) (select-keys tc (keys sc)))))

;; MARKERS

(defn- get-id
  [marker]
  (-> marker (aget "feature") (aget "properties") (aget _id)))

(defn- get-rank
  [marker]
  (-> marker (aget "feature") (aget "properties") (aget _rank)))

(defn get-style
  "Get the style of a marker. Second arg specifies style attribute to get.
   eg. For marker m1, call like: (get-style m1) or (get-style m1 :fillColor)"
  ([marker] (aget marker "options"))
  ([marker kw] (kw (js->clj (get-style marker)))))

(defn- is-clicked?
  "Check whether a marker is clicked, so it's style can be preserved."
  [marker]
  (equivalent-style (get-style marker) (get-ona-style marker :clicked)))

(defn re-style-marker
  "Apply a style to a marker. Style comes from a function that takes marker."
  [marker->style marker]
  (let [style (marker->style marker)]
    (if (is-clicked? marker)
      ;; we only set reset style on clicked markers
      (.setStyle marker (clj->js {:reset style}))
      (.setStyle marker (clj->js (assoc style :reset style))))))

(defn reset-style
  "Reset styles pulls the 'reset' property from within a markers options,
   clearing styles to default if nothing found."
  [marker]
  (if-let [rstyle (aget (aget marker "options") "reset")]
    (.setStyle marker rstyle)
    (.setStyle marker (get-ona-style marker :normal))))

(defn- bring-to-top-if-selected
  "Looks at the _id in marker, and brings to top if (1) marker clicked or
   (2) marker's id is selected (via id-selected?)"
  [id-selected? marker]
  (when (or (is-clicked? marker) (id-selected? (get-id marker)))
    (.bringToFront marker)))

(defn apply-click-style [marker]
  (when marker
    (.bringToFront marker)
    (.setStyle marker (get-ona-style marker :clicked))))

(defn apply-unclick-style [marker]
  (when marker
    (reset-style marker)))

(defn apply-hover-style [marker]
  (when marker
    (.bringToFront marker)
    (.setStyle marker (get-ona-style marker :hover))))

;; MARKERS

(defn clear-all-styles
  "Sets the default style on a marker."
  [markers & {:keys [preserve-clicked?] :or {preserve-clicked? true}}]
  (doseq [marker markers]
    (if-not (and preserve-clicked? (is-clicked? marker))
      (.setStyle marker (get-ona-style marker :normal)))))

;; GEOJSON CONVERSION

(defn read-string-or-number
  [maybe-s]
  (if (string? maybe-s) (read-string maybe-s) maybe-s))

(defn- make-feature
  [geometry record-id index]
  {:type "Feature"
   :properties {(keyword _rank) (inc index)
                (keyword _id) record-id}
   :geometry geometry})

(defmulti get-as-geom
  (fn [record field & [repeat-child-index]]
    (cond
      (f/repeat? field) :repeat
      :else :default)))

(defmethod get-as-geom :repeat
  [record {:keys [children full-name] :as field}]
  (for [child-record (get record full-name)]
    (for [child (filter f/geofield? children)]
      (get-as-geom child-record child))))

(defmethod get-as-geom :default
  [record geofield]
  (let [geotype ({"geopoint" "Point"
                  "gps" "Point"
                  "geoshape" "Polygon"
                  "geotrace" "LineString"} (:type geofield))
        parse (fn [s] (when (and (seq s) (not= s "n/a"))
                        (for [coord-string (string/split s #";")]
                          (let [[lat lng _ _] (string/split coord-string #" ")]
                            [(read-string lng) (read-string lat)]))))
        coordfn (case geotype
                  "Point" #(first (parse %))
                  "LineString" parse
                  "Polygon" #(vector (parse %))
                  identity)

        value (get record (:full-name geofield))
        coords (coordfn value)]
    (if (f/osm? geofield)
      (:geom value)
      (when-not (or (nil? coords) (some nil? coords))
        {:type geotype :coordinates coords}))))

(defn as-geojson
  "Given the dataset, and the form schema, get out geojson.
   Optional specification of field will map that field data to the geom."
  ([dataset form]
   (as-geojson dataset form (f/default-geofield form)))
  ([dataset form geofield]
   (when geofield
     (let [features
           (for [[idx record] (indexed dataset)
                 :let [geom-or-geoms (get-as-geom record geofield)]
                 :when geom-or-geoms]
             (if (map? geom-or-geoms)
               (make-feature geom-or-geoms (record _id) idx)
               (->> geom-or-geoms
                    flatten
                    (remove nil?)
                    (map #(make-feature % (record _id) idx)))))]
       {:type "FeatureCollection"
        :features (flatten features)}))))

;;;;; MAP

(defn create-map
  "Creates a leaflet map, rendering it to the dom element with given id."
  [id {:keys [mapbox-tiles include-google-maps?]}]
  (let [layers (map #(.tileLayer js/L (:url %)) mapbox-tiles)
        nlayers (zipmap (map :name mapbox-tiles) layers)
        named-layers (if include-google-maps?
                       (clj->js (assoc nlayers
                                       "Google Satellite" (js* "new L.Google")))
                       (clj->js nlayers))
        m (.map js/L id #js {:layers (first layers) :zoomControl false})
        z ((.. js/L -control -zoom) #js {:position "bottomleft"})]
    ;; zoom control
    (.addTo z m)
    ;; layers control
    (.addTo
     ((js* "L.control.layers") named-layers nil #js {:position "bottomleft"})
     m)
    (.setView m #js [0 0] 1)
    m))

(defn- register-mouse-events
  "Mouse events for markers.
  On click or arrow, mapped-submission-to-rank events are generated.
  On hover, marker is brought to the front and color changed."
  [feature marker event-chan]
  (.on marker "click"
       #(when-not (is-clicked? marker)
          (put! event-chan {:mapped-submission-to-rank
                            (aget (aget feature "properties") _rank)})))
  (.on marker "mouseover"
       #(when-not (is-clicked? marker)
          (apply-hover-style marker)))
  (.on marker "mouseout"
       #(when-not (is-clicked? marker)
          (apply-unclick-style marker))))

(defn- re-render-map!
  "Re-renders map by invalidating leaflet size.
   If map is zoomed out beyond layer-bounds, re-zooms to layer."
  [leaflet-map feature-layer]
  (let [map-bounds (.getBounds leaflet-map)
        layer-bounds (.getBounds feature-layer)]
    (.invalidateSize leaflet-map false)
    ;; Re-fitting to bounds should happen if the map is more zoomed out
    ;; than the data. The 0 get-zoom is an additional constraint for
    ;; datasets that cross the axes (contain both -ve, +ve lat,lngs)
    (when (or (zero? (.getZoom leaflet-map))
              (not (.contains layer-bounds map-bounds)))
      (.fitBounds leaflet-map layer-bounds))))

(defn- load-geo-json
  "Create a map with the given GeoJSON data.
   Adds mouse events and centers on the geojson features."
  [m geojson event-chan & {:keys [rezoom?]}]
  (let [on-events #(register-mouse-events %1 %2 event-chan)
        geometry-type (-> geojson :features first :geometry :type
                          {"Point" :point "Polygon" :shape "LineString" :line})
        stylefn #(get-ona-style geometry-type :normal)
        point->marker #(.circleMarker js/L %2)
        feature-layer (.geoJson js/L
                                (clj->js geojson)
                                #js {:onEachFeature on-events
                                     :pointToLayer point->marker
                                     :style stylefn})
        ids (map #(get-in % [:properties (keyword _id)]) (:features geojson))
        markers (.getLayers feature-layer)]
    (when (seq (:features geojson))
      (when rezoom? (.fitBounds m (.getBounds feature-layer)))
      (.addTo feature-layer m))
    {:feature-layer feature-layer
     :markers markers
     :id->marker (zipmap ids markers)}))

;;MAPBOX GL
(defn create-mapboxgl-map
  "Creates a mapboxgl map, rendering it to the dom element with given id."
  [id]
  (set! (.-accessToken js/mapboxgl) mapboxgl-access-token)
  (let [Map (.-Map js/mapboxgl)
        Navigation (.-Navigation js/mapboxgl)
        m (Map. #js {:container id
                     :style "mapbox://styles/mapbox/streets-v9"})]
    (.addControl m (Navigation. #js {:position "bottom-left"}))))

(defn get-filter
  "Gets query filter and returns filters based on field type"
  [{:keys [column filter value]} flat-form]
  (let [field (first
               (cljs.core/filter
                (fn [{:keys [full-name]}]
                  (= full-name column)) flat-form))]
    (cond
      (f/numeric? field)
      (str "CAST(json->>'" column "' AS INT) " filter " '" value "'")
      (f/time-based? field)
      (str "CAST(json->>'" column "' AS AS TIMESTAMP) " filter " '" value "'")
      :else (str "json->>'" column "' " filter " '" value "'"))))

(defn generate-filter-string
  "Generates query params filters for filtered datasets "
  [query flat-form]
  (when (not-empty query)
    (str " and " (string/join " and " (map #(get-filter % flat-form) query)))))

(defn get-tiles-endpoint
  "Generates tiles url with appropriate filters as query params"
  [tiles-server formid fields flat-form & [query]]
  (str tiles-server tiles-endpoint
       "?where=deleted_at is null and xform_id =" formid
       (generate-filter-string query flat-form)
       "&fields=" (string/join ",", fields)))

(defn add-mapboxgl-source
  "Add map source. This is called with either tiles-url or geoson which
  determins the source type (Vector GeosJSON). "
  [map id_string {:keys [tiles-url geojson]}]
  (let [tiles #js [tiles-url]
        source (cond
                 geojson (clj->js {:type "geojson" :data geojson})
                 tiles #js {:type "vector" :tiles tiles})]
    (when-not (.getSource map id_string)
      (.addSource map id_string source))))

(defn add-mapboxgl-layer
  "Add map layer from available sources."
  [map id_string layer-type & [layer-id paint-properties]]
  (let [l-id (or layer-id id_string)
        layer-def {:id l-id
                   :type layer-type
                   :source id_string
                   :source-layer "logger_instance_geom"}
        layer (clj->js (if paint-properties
                         (assoc layer-def :paint paint-properties)
                         layer-def))]
    (when-not (.getLayer map l-id)
      (.addLayer map layer id_string))))

(defn generate-stops
  "Generates a collection of input - output value pairs known
   as stops. These stops are used by get-styles function to decide the style
   output based on an input vaue from the dataset. e.g. _id. By defauly this
    function purely generates color stops."
  [selected-id selected-color]
  [[0 "#f30"]
   [selected-id selected-color]])

(defn generate-size-stops
  "Generates cirlce size property stops base on values from dataset."
  [selected-id selected-color]
  [[0 4]
   [selected-id selected-color]])

(defn get-styles
  "Gets predefined styles for diffent layer types and states."
  [& [selected-id stops size-stops]]
  {:point {:normal [["circle-color" (clj->js
                                     {:property "id"
                                      :type "categorical"
                                      :stops (if stops
                                               stops
                                               [[0 "#f30"]])})]
                    ["circle-radius" 4]]
           :hover [["circle-color" (clj->js
                                    {:property "id"
                                     :type "categorical"
                                     :stops (generate-stops
                                             selected-id "#631400")})]]
           :clicked [["circle-color" (clj->js
                                      {:property "id"
                                       :type "categorical"
                                       :stops (generate-stops
                                               selected-id "#ad2300")})]]
           :sized [["circle-radius" (clj->js
                                     {:property "id"
                                      :type "categorical"
                                      :stops (if size-stops
                                               size-stops
                                               [[0 6]])})]
                   ["circle-opacity" (clj->js
                                      {:stops
                                       [[3, 0.2] [15, 0.8]]})]]}
   :fill {:normal [["fill-color" (clj->js
                                  {:property "_id"
                                   :type "categorical"
                                   :stops (if stops
                                            stops
                                            [[0 "#f30"]])})]
                   ["fill-opacity" 0.9]
                   ["fill-outline-color" "white"]]
          :hover [["fill-color" (clj->js
                                 {:property "_id"
                                  :type "categorical"
                                  :stops (generate-stops
                                          selected-id "#631400")})]]
          :clicked [["fill-color" (clj->js
                                   {:property "_id"
                                    :type "categorical"
                                    :stops (generate-stops
                                            selected-id "#ad2300")})]]}})

(defn get-style-properties
  "Get style properties for layer."
  [style-type style-state & {:keys [selected-id stops]}]
  (-> (get-styles selected-id stops) style-type style-state))

(defn set-mapboxgl-paint-property
  "Sets maps paint properties given layer-id and list of properties to set.
  properties should be a list of properties that contains the propery name
  and value in a vector. e.g. [[property1 value1] [property2 value2]"
  [map layer-id properties]
  (doseq [[p v] properties] (.setPaintProperty map layer-id p v)))

(defn get-id-property
  [features]
  (let [properties (-> features first (aget "properties"))]
    (or (aget properties "id") (aget properties _id))))

(defn register-mapboxgl-mouse-events
  "Register map mouse events."
  [owner map event-chan id_string style]
  (.off map "mousemove")
  (.off map "click")
  (.on map "mousemove"
       (fn [e]
         (let [layer-id id_string
               features
               (.queryRenderedFeatures
                map (.-point e) (clj->js {:layers [layer-id]}))
               no-of-features (.-length features)
               view-by (om/get-props owner [:map-page :view-by])
               selected-id (om/get-props
                            owner [:map-page :submission-clicked :id])]
           (set! (.-cursor (.-style (.getCanvas map)))
                 (if (pos? (.-length features)) "pointer" ""))
           (when-not view-by
             (if (= no-of-features 1)
               (set-mapboxgl-paint-property
                map layer-id
                (get-style-properties
                 style :hover :selected-id (get-id-property features)))
               (do
                 (set-mapboxgl-paint-property
                  map layer-id
                  (get-style-properties style :normal))
                 (when selected-id
                   (set-mapboxgl-paint-property
                    map layer-id
                    (get-style-properties
                     style :clicked :selected-id selected-id)))))))))
  (.on map "click"
       (fn [e]
         (let [layer-id id_string
               features
               (.queryRenderedFeatures
                map (.-point e) (clj->js {:layers [layer-id]}))
               no-of-features (.-length features)
               view-by (om/get-props owner [:map-page :view-by])]
           (when (pos? no-of-features)
             (let [feature-id (get-id-property features)]
               (put! event-chan {:mapped-submission-to-id feature-id})
               (when-not view-by
                 (set-mapboxgl-paint-property
                  map layer-id
                  (get-style-properties
                   style :clicked :selected-id feature-id)))))))))

(defn fitMapBounds
  "Fits map boundaries on rendered features."
  [map layer-id]
  (let [LngLatBounds (.-LngLatBounds js/mapboxgl)
        bounds (LngLatBounds.)
        features (.queryRenderedFeatures map (clj->js {:layers [layer-id]}))]
    (doseq [feature features]
      (.extend bounds (.-coordinates (.-geometry feature))))
    (let [[[Lng1 Lat1] [Lng2 Lat2]] (.toArray bounds)
          valid-bounds? (not (and (= Lat1 Lat2)
                                  (= Lng1 Lng2)))]
      (when (and (-> features count pos?) valid-bounds?)
        (.fitBounds map bounds #js {:padding "10"
                                    :linear true})))))

(defn geotype->marker-style
  "Get marker style for field type."
  [field]
  (if (f/geoshape? field)
    {:layer-type "fill" :style :fill}
    {:layer-type "circle" :style :point}))

(defn map-on-load
  "Functions that are called after map is loaded in DOM."
  [map event-chan id_string & {:keys [geofield owner] :as map-data}]
  (let [{:keys [layer-type style]} (geotype->marker-style geofield)
        stops (om/get-state owner :stops)]
    (add-mapboxgl-source map id_string map-data)
    (add-mapboxgl-layer map id_string layer-type)
    (register-mapboxgl-mouse-events owner map event-chan id_string style)
    (set-mapboxgl-paint-property
     map id_string (get-style-properties style :normal :stops stops))
    (when (= :point style)
      (add-mapboxgl-layer map id_string layer-type
                          "point-casting"
                          {:circle-color "#fff" :circle-radius 6}))
    (om/set-state! owner :style style)))

(defn clear-map-styles
  "Set default style"
  [owner]
  (set-mapboxgl-paint-property
   (om/get-state owner :mapboxgl-map) (om/get-state owner :layer-id)
   (get-style-properties (om/get-state owner :style) :normal)))
