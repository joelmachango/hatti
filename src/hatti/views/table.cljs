(ns hatti.views.table
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [chimera.js-interop :refer [safe-regex]]
            [chimera.urls :refer [last-url-param url]]
            [chimera.seq :refer [in? add-element remove-element]]
            [chimera.om.state :refer [transact!]]
            [chimera.string :refer [escape-for-type]]
            [clojure.string :refer [escape]]
            [cljs.core.async :refer [<! chan put! timeout]]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]
            [sablono.core :as html :refer-macros [html]]
            [hatti.constants :refer [_id _rank]]
            [hatti.ona.forms :as forms
             :refer [get-label format-answer get-column-class]]
            [hatti.views :refer [action-buttons
                                 actions-column
                                 table-page table-header table-search
                                 label-changer submission-view]]
            [hatti.views.record]
            [hatti.shared :as shared]
            [hatti.utils :refer [click-fn generate-html hyphen->camel-case]]
            [chimera.core :refer [any?]]
            [cljsjs.slickgrid-with-deps]))

(def default-num-displayed-records 25)

;; DIVS
(def table-id "submission-grid")
(def pager-id "pager")

;; FIELDS
(defn all-fields
  "Given a (flat-)form, returns fields for table display.
   Puts extra fields in the beginning, metadata at the end of the table,
   and drops fields that have no data (eg. group/note)."
  [form]
  (->> (concat (vector forms/id-field forms/submission-time-field)
               (forms/non-meta-fields form)
               (forms/meta-fields form :with-submission-details? true))
       (filter forms/has-data?)
       (distinct)))

;; SLICKGRID HELPER FUNCTIONS
(defn compfn
  "Comparator function for the slickgrid dataview.
   args.sortCol is the column being sorted, a and b are rows to compare.
   Claims a is bigger (return 1) if value for a > b, or a is null / empty."
  [args]
  (fn [a b]
    (let [col (aget args "sortCol")
          lower #(if (string? %) (clojure.string/lower-case %) %)
          aval (lower (aget a (aget col "field")))
          bval (lower (aget b (aget col "field")))]
      (if (or (nil? aval) (> aval bval)) 1 -1))))

(defn filterfn
  "Filter function for the slickgrid dataview, use as (partial filterfn form)
   item is the row, args contains query.
   Return value of false corresponds with exclusion, true with inclusion."
  [form item args]
  (if-not args
    true ; don't filter anything if args is undefined
    (let [indexed-form (zipmap (map :full-name form) form)
          query (aget args "query")
          fmt-subitem (fn [[fname answer]]
                        (format-answer (get indexed-form fname)
                                       answer
                                       :compact? true))
          filtered (->> item
                        js->clj
                        (map fmt-subitem)
                        (map #(re-find (safe-regex query) (str %)))
                        (remove nil?))]
      (seq filtered))))

(defn formatter
  "Formatter for slickgrid columns takes row,cell,value,columnDef,dataContext.
   Get one with (partial formatter field language)."
  [field language field-key row cell value columnDef dataContext]
  (let [clj-value (js->clj value :keywordize-keys true)
        media-count (aget dataContext "_media_count")
        total-media (aget dataContext "_total_media")]
    (format-answer field
                   (escape-for-type clj-value)
                   :language language
                   :compact? true
                   :field-key field-key
                   :media-count media-count :total-media total-media)))

(defmethod action-buttons :default
  [owner]
  (fn [row cell value columnDef dataContext]
    (let [{:keys [owner project formid]} (:dataset-info @shared/app-state)
          form-owner (last-url-param owner)
          project-id (last-url-param project)
          edit-link (url form-owner project-id formid
                         (str "webform?instance-id=" value))]
      (generate-html
       (when value
         [:ul
          [:li
           [:input.delete-record {:type "checkbox"
                                  :data-id value}]]
          [:li.tooltip.middle-right
           [:span.tip-info "View"]
           [:a.view-record
            [:i.fa.fa-eye {:data-id value}]]]
          [:li.tooltip
           [:span.tip-info "Edit"]
           [:a.edit-record {:data-id value
                            :target "_blank"
                            :href edit-link}
            [:i.fa.fa-pencil]]]])))))

(defn column-name-html-string
  "The html needed for a column name as a string.
   String, String, Bool -> String"
  [column-class label hxl]
  (str "<div class=\"" column-class "\">" label "</div>"
       (when hxl (str "<div class=\"hxl-row\">" hxl "</div>"))))

(def select-unselect-all-records-id "select-unselect-all-records")
(def select-unselect-all-records-element
  (str "<input type=\"checkbox\" id=\"" select-unselect-all-records-id "\">"))
(def delete-record-class "delete-record")

(defmethod actions-column :default
  [owner has-hxl?]
  {:id "actions"
   :field _id
   :type "text"
   :name select-unselect-all-records-element
   :toolTip ""
   :sortable false
   :formatter (action-buttons owner)
   :headerCssClass (str (when has-hxl? "hxl-min-height ")
                        "record-actions header")
   :cssClass "record-actions"
   :maxWidth 100})

(defn- flat-form->sg-columns
  "Get a set of slick grid column objects when given a flat form."
  [form & {:keys [hide-actions-column?
                  get-label?
                  language
                  owner]
           :or {get-label? true}}]
  (let [has-hxl? (any? false? (map #(nil? (-> % :instance :hxl)) form))
        columns (for [field (all-fields form)]
                  (let [{:keys [name type full-name]
                         {:keys [hxl]} :instance} field
                        label (if get-label? (get-label field language) name)
                        column-class (get-column-class field)
                        field-key (if get-label? :name :label)]
                    {:id name
                     :field full-name
                     :type type
                     :name (column-name-html-string column-class label hxl)
                     :toolTip label
                     :sortable true
                     :formatter (partial formatter field language field-key)
                     :headerCssClass (when has-hxl? "hxl-min-height")
                     :cssClass column-class
                     :minWidth 50
                     :hxl hxl}))]
    (clj->js (cond-> columns
               (not hide-actions-column?)
               (conj (actions-column owner has-hxl?))))))

(defn init-sg-pager [grid dataview]
  (let [Pager (.. js/Slick -Controls -Pager)]
    (Pager. dataview
            grid
            (js/jQuery (str "#" pager-id)))))

(defn resizeColumns [grid]
  (.registerPlugin grid (.AutoColumnSize js/Slick)))

(def sg-options
  "Options to feed the slickgrid constructor."
  #js {:autoHeight true
       :enableColumnReorder false
       :enableTextSelectionOnCells true
       :rowHeight 40
       :syncColumnCellResize false})

(defn bind-external-sg-grid-event-handlers
  [grid event-handlers]
  (doall
   (for [[handler-key handler-function] event-handlers
         :let [handler-name (hyphen->camel-case (name handler-key))
               event (aget grid handler-name)]]
     (.subscribe event handler-function))))

(defn bind-external-sg-grid-dataview-handlers
  [dataview event-handlers]
  (doall
   (for [[handler-key handler-function] event-handlers
         :let [handler-name (hyphen->camel-case (name handler-key))
               event (aget dataview handler-name)]]
     (.subscribe event handler-function))))

(defn update-data-to-be-deleted-vector
  [checked? data-id]
  (let [{:keys [data-to-be-deleted]} @shared/app-state
        fn (if checked? add-element remove-element)]
    (transact! shared/app-state
               [:data-to-be-deleted]
               #(fn data-to-be-deleted data-id))))

(defn get-elements-count-by-selector
  [selector]
  (.. js/document (querySelectorAll selector) -length))

(defn check-select-unselect-all-records-element?
  []
  (let [delete-record-class-selector (str "." delete-record-class)
        total-rows-count (get-elements-count-by-selector
                          delete-record-class-selector)
        ; TODO: test using (:data-to-be-deleted @shared/app-state)
        selected-rows-count (get-elements-count-by-selector
                             (str delete-record-class-selector ":checked"))]
    (= selected-rows-count total-rows-count)))

(defn get-checkbox-selector
  [data-id]
  (str "." delete-record-class "[data-id=\"" data-id "\"]"))

(defn get-delete-checkbox-by-data-id
  [data-id]
  (let [selector (get-checkbox-selector data-id)]
    (.querySelector js/document selector)))

(defn select-rows-marked-to-be-deleted
  []
  (doseq [data-id (:data-to-be-deleted @shared/app-state)]
    (let
     [element (get-delete-checkbox-by-data-id data-id)]
      (set! (.-checked element) true))))

(defn higlight-rows-marked-to-be-deleted
  [grid]
  (let [checkboxes (.getElementsByClassName js/document delete-record-class)
        indexes-of-selected-checkboxes (->> (map-indexed
                                             (fn [index checkbox]
                                               (when (.-checked checkbox)
                                                 index))
                                             checkboxes)
                                            (remove nil?)
                                            vec)]
    (transact! shared/app-state
               [:selected-table-rows]
               #(identity indexes-of-selected-checkboxes))
    (.setSelectedRows grid (clj->js indexes-of-selected-checkboxes))))

(defn sg-init
  "Creates a Slick.Grid backed by Slick.Data.DataView from data and fields.
   Most events are handled by slickgrid. On double-click, event is put on chan.
   Returns [grid dataview]."
  [data form current-language owner
   {:keys [grid-event-handlers dataview-event-handlers]}]
  (let [{{{:keys [num-displayed-records
                  total-page-count]} :paging
          :keys [hide-actions-column?]} :table-page} @shared/app-state
        columns (flat-form->sg-columns
                 form
                 :language current-language
                 :hide-actions-column? hide-actions-column?
                 :owner owner)
        SlickGrid (.. js/Slick -Grid)
        DataView (.. js/Slick -Data -DataView)
        dataview (DataView.)
        grid (SlickGrid. (str "#" table-id) dataview columns sg-options)]
    ;; dataview / grid hookup
    (bind-external-sg-grid-event-handlers grid grid-event-handlers)
    (bind-external-sg-grid-dataview-handlers dataview dataview-event-handlers)
    (.setSelectionModel grid
                        (js/Slick.RowSelectionModel.
                         (clj->js {:selectActiveRow false})))

    (.subscribe (.-onRowCountChanged dataview)
                (fn [e args]
                  (.updateRowCount grid)
                  (.render grid)))
    (.subscribe (.-onRowsChanged dataview)
                (fn [e args]
                  (.invalidateRows grid (aget args "rows"))
                  (.render grid)
                  (select-rows-marked-to-be-deleted)
                  (higlight-rows-marked-to-be-deleted grid)))
    (.subscribe (.-onHeaderClick grid)
                (fn [e args]
                  (let [target (.-target e)
                        id (.-id target)
                        checked? (.-checked target)
                        delete-record-class-selector (str
                                                      "."
                                                      delete-record-class)
                        total-rows-count (get-elements-count-by-selector
                                          delete-record-class-selector)]
                    (when (= select-unselect-all-records-id id)
                      (let [records-to-be-deleted (.getElementsByClassName
                                                   js/document
                                                   delete-record-class)]

                        (doseq [record records-to-be-deleted]
                          (set! (.-checked record) checked?)
                          (update-data-to-be-deleted-vector
                           checked?
                           (js/parseInt
                            (.getAttribute record "data-id"))))

                        (transact! shared/app-state
                                   [:selected-table-rows]
                                   #(if checked?
                                      (range total-rows-count)
                                      []))
                        (.setSelectedRows
                         grid
                         (clj->js
                          (:selected-table-rows @shared/app-state))))))))
    ;; Double-click handlers
    (.subscribe (.-onDblClick grid)
                (fn [e args]
                  (let [rank (aget (.getItem dataview (aget args "row"))
                                   _rank)]
                    (put! shared/event-chan {:submission-to-rank rank}))))
    (.subscribe (.-onClick grid)
                (fn [e args]
                  (let [elem (.-target e)
                        class-name (.-className elem)
                        checked? (.-checked elem)
                        row (.getItem dataview (aget args "row"))
                        elem-data-id (.getAttribute elem "data-id")
                        data-id (when elem-data-id
                                  (js/parseInt (.getAttribute elem "data-id")))
                        id  (aget row _id)
                        rank (aget row _rank)
                        select-unselect-all-records-chkbox
                        (.getElementById js/document
                                         select-unselect-all-records-id)
                        {:keys [selected-table-rows]} @shared/app-state]
                    (if (= class-name delete-record-class)
                      (let [fn (if checked? add-element remove-element)
                            row (js/parseInt (.. grid
                                                 (getCellFromEvent e)
                                                 -row))]
                        (update-data-to-be-deleted-vector checked? data-id)
                        (set! (.-checked select-unselect-all-records-chkbox)
                              (check-select-unselect-all-records-element?))
                        (transact! shared/app-state
                                   [:selected-table-rows]
                                   #(fn selected-table-rows row))
                        (.setSelectedRows
                         grid
                         (clj->js (:selected-table-rows @shared/app-state))))

                      (when (= id data-id)
                        (put! shared/event-chan
                              {:submission-to-rank rank}))))))
    ;; page, filter, and data set-up on the dataview
    (init-sg-pager grid dataview)
    (.setPagingOptions dataview
                       #js {:pageSize (or num-displayed-records
                                          default-num-displayed-records)
                            :totalPages total-page-count})
    (.setFilter dataview (partial filterfn form))
    (.setItems dataview (clj->js data) _id)
    (resizeColumns grid)
    [grid dataview]))

    ;; EVENT LOOPS
(defn handle-table-events
  "Event loop for the table view. Processes a tap of share/event-chan,
   and updates app-state/dataview/grid as needed."
  [app-state grid dataview]
  (let [event-chan (shared/event-tap)]
    (go
      (while true
        (let [{:keys [submission-to-rank
                      submission-clicked
                      submission-unclicked
                      filter-by
                      new-columns
                      re-render]} (<! event-chan)
              update-data! (partial om/update! app-state
                                    [:table-page :submission-clicked :data])
              get-submission-data (fn [field value]
                                    (first
                                     (filter #(= value (get % field))
                                             (get-in @app-state [:data]))))]
          (when submission-to-rank
            (let [rank submission-to-rank
                  submission (get-submission-data _rank rank)]
              (update-data! submission)))
          (when submission-clicked
            (update-data! submission-clicked))
          (when submission-unclicked
            (update-data! nil))
          (when new-columns
            (go (<! (timeout 20))
                (.setColumns grid new-columns)
                (resizeColumns grid)
                (.render grid)
                (select-rows-marked-to-be-deleted)
                (higlight-rows-marked-to-be-deleted grid)))
          (when filter-by
            (.setFilterArgs dataview (clj->js {:query filter-by}))
            (.refresh dataview))
          (when (= re-render :table)
            ;; need tiny wait (~16ms requestAnimationFrame delay) to re-render
            ;; table
            (go (<! (timeout 20))
                (.resizeCanvas grid)
                (.invalidateAllRows grid)
                (resizeColumns grid)
                (.render grid)
                (init-sg-pager grid dataview))))))))

(defn- render-options
  [options owner colset!]
  (let [choose-display-key (fn [k] (om/set-state! owner :field-key k)
                             (colset! k))]
    (for [[k v] options]
      [:li [:a {:on-click (click-fn #(choose-display-key k)) :href "#"} v]])))

;; OM COMPONENTS
(defmethod label-changer :default
  [_ owner]
  (reify
    om/IInitState
    (init-state [_] {:field-key :label})
    om/IRenderState
    (render-state [_ {:keys [field-key language]}]
      (let [options {:label [:strong "Label"]
                     :name  [:strong "Name"]}
            {:keys [flat-form]} (om/get-shared owner)
            new-language (:current (om/observe owner (shared/language-cursor)))
            colset! #(put! shared/event-chan
                           {:new-columns
                            (flat-form->sg-columns flat-form
                                                   :get-label? (= :label %)
                                                   :language   new-language
                                                   :owner owner)})]
        (when (not= new-language language)
          (om/set-state! owner :language new-language)
          (colset! field-key))
        (html
         [:div.label-changer
          [:span.label-changer-label "Show:"]
          [:div#header-display-dropdown.drop-hover
           [:span (options field-key) [:i.fa.fa-angle-down]]
           [:ul.submenu.no-dot (render-options options owner colset!)]]])))))

(defn delayed-search
  "Delayed search fires a query-event on event-chan if the value of the input
   doesn't change within 150 ms (ie, user is still typing).
   Call on-change or on-key-up, with (.-target event) as first argument."
  [input query-event-key]
  (let [query (.-value input)]
    ;; Wait 150 ms for input value to stabilize. Empirically felt good, plus
    ;; 200 ms wait is when people start noticing change in interfaces
    (go (<! (timeout 150))
        (when (= query (.-value input))
          (put! shared/event-chan {query-event-key query})))))

(defmethod table-search :default
  [_ owner]
  (om/component
   (html
    [:div.table-search
     [:i.fa.fa-search]
     [:input {:type "text"
              :placeholder "Search"
              :on-change #(delayed-search (.-target %) :filter-by)}]])))

(defmethod table-header :default
  [cursor owner]
  (om/component
   (html
    [:div.topbar
     [:div {:id pager-id}]
     (om/build label-changer cursor)
     (om/build table-search cursor)
     [:div {:style {:clear "both"}}]])))

(defn- init-grid!
  "Initializes grid + dataview, and stores them in owner's state."
  [data owner slick-grid-event-handlers]
  (when (seq data)
    (let [{:keys [flat-form]} (om/get-shared owner)
          current-language (:current
                            (om/observe owner (shared/language-cursor)))
          [grid dataview] (sg-init data
                                   flat-form
                                   current-language
                                   owner
                                   slick-grid-event-handlers)]
      (om/set-state! owner :grid grid)
      (om/set-state! owner :dataview dataview)
      [grid dataview])))

(defn get-table-view-height
  []
  (- (-> "body"
         js/document.querySelector
         .-clientHeight)
     (-> ".tab-page"
         js/document.querySelector
         .getBoundingClientRect
         .-top)))

(defn set-window-resize-handler
  [owner]
  (let [resize-handler (fn [event]
                         (om/set-state! owner
                                        :table-view-height
                                        (get-table-view-height)))]
    (.addEventListener js/window
                       "resize"
                       resize-handler)
    (om/set-state! owner :resize-handler resize-handler)))

(defmethod table-page :default
  [{{:keys [active]} :views :as cursor}
   owner
   {:keys [slick-grid-event-handlers] :as opts}]
  "Om component for the table grid.
   Renders empty divs via om, hooks up slickgrid to these divs on did-mount.
   slick-grid-event-handlers is a map containing any of the following keys
   :on-scroll
   :on-sort
   :on-header-context-menu
   :on-header-click
   :on-mouse-enter
   :on-mouse-leave
   :on-click
   :on-dbl-click
   :on-context-menu
   :on-key-down
   :on-add-new-row
   :on-validation-error
   :on-viewport-changed
   :on-columns-reordered
   :on-columns-resized
   :on-cell-change
   :on-before-edit-cell
   :on-before-cell-editor-destroy
   :on-header-cell-rendered
   each of whose value is a function of the form (fn [event args]) as described
   in the SlickGrid documentation for event handlers.
   https://github.com/mleibman/SlickGrid/wiki/Getting-Started"
  (let [active? (in? active :table)]
    (reify
      om/IRenderState
      (render-state [_ {:keys [table-view-height]}]
        (let [{:keys [data dataset-info]
               {:keys [prevent-scrolling-in-table-view? submission-clicked]}
               :table-page}
              cursor
              {:keys [num_of_submissions]} dataset-info
              no-data? (empty? data)
              with-info #(merge cursor %)]
          (when active?
            (html
             [:div.table-view
              {:style (when prevent-scrolling-in-table-view?
                        {:height (or table-view-height
                                     (get-table-view-height))
                         :overflow "hidden"})}
              (when (:data submission-clicked)
                (om/build submission-view
                          (with-info submission-clicked)
                          {:opts (merge
                                  (select-keys opts #{:delete-record! :role})
                                  {:view :table})}))
              (om/build table-header cursor)
              [:div.slickgrid {:id table-id}
               (if (and no-data? (zero? num_of_submissions))
                 [:p.alert.alert-warning "No data"]
                 [:span [:i.fa.fa-spinner.fa-pulse] "Loading..."])]]))))
      om/IDidMount
      (did-mount [_]
        (when active?
          (set-window-resize-handler owner)
          (let [data (get-in cursor [:data])]
            (when-let [[grid dataview]
                       (init-grid! data owner slick-grid-event-handlers)]
              (handle-table-events cursor grid dataview)))))
      om/IWillUnmount
      (will-unmount [_]
        (.removeEventListener js/window
                              "resize"
                              (om/get-state owner :resize-handler)))

      om/IWillReceiveProps
      (will-receive-props [_ next-props]
        "Reset SlickGrid data if the table data has changed."
        (when active?
          (let [old-data (get-in (om/get-props owner) [:data])
                new-data (get-in next-props [:data])
                {:keys [grid dataview]} (om/get-state owner)]
            (when (not= old-data new-data)
              (if (empty? old-data)
                (when-let [[grid dataview]
                           (init-grid! new-data
                                       owner
                                       slick-grid-event-handlers)]
                  (handle-table-events cursor grid dataview))
                (do ; data has changed
                  (.invalidateAllRows grid)
                  (.setItems dataview (clj->js new-data) _id)
                  (.render grid))))))))))
