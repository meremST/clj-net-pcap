;;;
;;; Copyright (C) 2012 Ruediger Gad
;;;
;;; This file is part of clj-net-pcap.
;;;
;;; clj-net-pcap is free software: you can redistribute it and/or modify
;;; it under the terms of the GNU Lesser General Public License (LGPL) as
;;; published by the Free Software Foundation, either version 3 of the License,
;;; or (at your option any later version.
;;;
;;; clj-net-pcap is distributed in the hope that it will be useful,
;;; but WITHOUT ANY WARRANTY; without even the implied warranty of
;;; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
;;; GNU Lesser General Public License (LGPL) for more details.
;;;
;;; You should have received a copy of the GNU Lesser General Public License (LGPL)
;;; along with clj-net-pcap.  If not, see <http://www.gnu.org/licenses/>.
;;;

(ns
  ^{:author "Ruediger Gad",
    :doc "Main class and method for launching a simple clj-net-pcap based sniffer
          that prints some information about the captured packets to stdout.
          This is primarily intended for testing and documentation purposes."}
  clj-net-pcap.main
  (:use clojure.pprint
        [clojure.string :only [join split]]
        clojure.tools.cli
        clj-net-pcap.byte-array-extraction-dsl
        clj-net-pcap.core
        clj-net-pcap.dsl.transformation
        clj-net-pcap.native
        clj-net-pcap.packet-gen
        clj-net-pcap.pcap-data
        clj-net-pcap.self-adaptive-dsl-adjustment
        clj-assorted-utils.util)
  (:gen-class))

(defn- parse-args [args]
  (cli args
    ["-a" "--self-adaptation"
     "Interval for self-adaptation of DSL expressions in ms."
     :default -1
     :parse-fn #(Integer. ^java.lang.String %)]
    ["-b" "--bulk-size"
     "The bulk size to use."
     :default 1
     :parse-fn #(Integer. ^java.lang.String %)]
    ["-d" "--duration"
     "The duration in seconds how long clj-net-pcap is run."
     :default -1
     :parse-fn #(Integer. ^java.lang.String %)]
    ["-e" "--dsl-expression"
     (str "Configure a DSL expression for extracting data from raw packet data in form of byte arrays."
          "Pre-configured DSL expressions are:\n"
          "ipv4-udp-be-dsl-expression, ipv4-udp-le-dsl-expression")
     :default ""]
    ["-f" "--filter"
     (str "Pcap filter to be used."
          " Defaults to the empty String which means that all packets are captured.")
     :default ""]
    ["-h" "--help" "Print this help." :flag true]
    ["-i" "--interface"
     "Interface on which the packets are captured"
     :default "lo"]
    ["-r" "--raw"
     (str "Emit raw data instead of decoded packets."
          " Be careful, not all transformation and forwarder functions support this.")
     :flag true]
    ["-s" "--stats"
     (str "Print stats to stderr in a regular interval."
          " The interval is given as parameter in milliseconds."
          " Values smaller equal 0 mean that no stats are printed.")
     :default 0
     :parse-fn #(Integer. ^java.lang.String %)]
    ["-t" "--dynamic-transformation-fn"
     (str "If set, the transformation-fn can be changed dynamically at runtime.")
     :flag true]
    ["-w" "--write-to-file"
     "Write output to file with the given name."
     :default nil]
    ["-A" "--self-adaptation-opts"
     "Options for self-adaptive adjustment of DSL expressions."
     :default {:threshold 0.01, :interpolation 2, :inactivity 1}
     :parse-fn #(read-string %)]
    ["-B" "--buffer-size"
     "The buffer size to use."
     :default (int (Math/pow 2 26))
     :parse-fn #(Integer. ^java.lang.String %)]
    ["-D" "--debug" "Enable additional debugging." :flag true]
    ["-F" "--forwarder-fn"
     (str "Use the specified function as forwarder function for processing packets.\n"
          "Available function names are:\n"
          "stdout-combined-forwarder-fn, stdout-byte-array-forwarder-fn, "
          "stdout-forwarder-fn, no-op-converter-forwarder-fn, "
          "counting-no-op-forwarder-fn, calls-per-second-no-op-forwarder-fn")
     :default "stdout-forwarder-fn"]
    ["-S" "--snap-len"
     (str "The snaplen to use."
          " This determines how many bytes of data will be captured from each packet.")
     :default 128
     :parse-fn #(Integer. ^java.lang.String %)]
    ["-T" "--transformation-fn"
     (str "Use the specified function for transforming the raw packets."
          " Available function names are:\n"
          "  pcap-packet-to-bean, pcap-packet-to-map, pcap-packet-to-nested-maps,\n"
          "  pcap-packet-to-bean-ipv4-udp-only, pcap-packet-to-map-ipv4-udp-only,\n"
          "  packet-byte-buffer-extract-map-ipv4-udp-single, packet-byte-buffer-extract-map-ipv4-udp-bulk,\n"
          "  packet-byte-buffer-extract-bean-ipv4-udp-single, packet-byte-buffer-extract-bean-ipv4-udp-bulk, no-op")
     :default "pcap-packet-to-bean"]
    ["-R" "--read-file"
     "Read from a pcap file instead of performing a live capture."
     :default ""]
    ["-W" "--write-arff-header"
     "Prefix write output with ARFF header: http://weka.wikispaces.com/ARFF+%28stable+version%29"
     :flag true]))

(defn -main [& args]
  (let [cli-args (parse-args args)
        arg-map (cli-args 0)
        help-string (cli-args 2)]
    (if (arg-map :help)
      (println help-string)
      (do
        (println "Starting clj-net-pcap using the following options:")
        (pprint arg-map)
        (let [pcap-file-name (arg-map :read-file)
              dsl-expr-string (arg-map :dsl-expression)
              bulk-size (arg-map :bulk-size)
              cap-if (arg-map :interface)
              dsl-expression (let [expr (resolve (symbol (str "clj-net-pcap.byte-array-extraction-dsl/" dsl-expr-string)))]
                               (if expr
                                 (var-get expr)
                                 (if (not= "" dsl-expr-string)
                                   (read-string dsl-expr-string))))
              _ (println "DSL expression from command line args:" dsl-expression)
              get-dsl-fn (fn [dsl-expr]
                           (let [extraction-fn (create-extraction-fn dsl-expr)]
                             (if (> bulk-size 1)
                               (partial process-packet-byte-buffer-bulk extraction-fn)
                               (partial process-packet-byte-buffer extraction-fn))))
              get-transformation-fn (fn []
                                      (if dsl-expression
                                        (get-dsl-fn dsl-expression)
                                        (resolve (symbol (str "clj-net-pcap.pcap-data/" (arg-map :transformation-fn))))))
              static-transformation-fn (get-transformation-fn)
              dynamic-transformation-fn (atom (get-transformation-fn))
              dynamic-dsl-expression (atom nil)
              sa-opts (arg-map :self-adaptation-opts)
              self-adapt-ctrlr (create-self-adaptation-controller dsl-expression dynamic-dsl-expression
                                                                  (sa-opts :threshold) (sa-opts :interpolation)
                                                                  (sa-opts :inactivity) (= "lo" cap-if))
              _ (add-watch dynamic-dsl-expression :dsl-fn-update-watch
                           (fn [k r old-val new-val]
                             (println "Dynamic DSL updated.")
;                             (println Updating dynamic transformation fn:" new-val)
                             (let [dsl-fn (get-dsl-fn new-val)]
                               (reset! dynamic-transformation-fn dsl-fn))))
              output-file (arg-map :write-to-file)
              file-output-forwarder (when (not (nil? output-file))
                                      (println "Writing data to file:" output-file)
                                      (create-file-out-forwarder output-file
                                                                 (> bulk-size 1)
                                                                 (if (arg-map :write-arff-header)
                                                                   (get-arff-header dsl-expression)
                                                                   "")))
              processing-fn (let [f-tmp (resolve (symbol (str "clj-net-pcap.pcap-data/" (arg-map :forwarder-fn))))
                                  f (cond
                                      (not (nil? file-output-forwarder)) file-output-forwarder
                                      (= 'packet (first (first (:arglists (meta f-tmp))))) f-tmp
                                      :default (f-tmp bulk-size))]
                              (println "Resolved forwarder fn:" f)
                              (if (arg-map :dynamic-transformation-fn)
                                (do
                                  (println "Using dynamic transformation-fn:" @dynamic-transformation-fn)
                                  #(let [o (@dynamic-transformation-fn %)]
                                     (if o
                                       (f o))))
                                (do
                                  (println "Using static transformation-fn:" static-transformation-fn)
                                  #(let [o (static-transformation-fn %)]
                                     (if o
                                       (f o))))))
              cljnetpcap (binding [clj-net-pcap.core/*bulk-size* bulk-size
                                   clj-net-pcap.core/*emit-raw-data* (arg-map :raw)
                                   clj-net-pcap.core/*forward-exceptions* (arg-map :debug)
                                   clj-net-pcap.pcap/*snap-len* (arg-map :snap-len)
                                   clj-net-pcap.pcap/*buffer-size* (arg-map :buffer-size)]
                           (if (= "" pcap-file-name)
                             (create-and-start-online-cljnetpcap
                               processing-fn
                               cap-if
                               (arg-map :filter))
                             (process-pcap-file
                               pcap-file-name
                               processing-fn)))
              stat-interval (arg-map :stats)
              stat-out-executor (executor)
              shutdown-fn (fn [] (do
                                   (println "clj-net-pcap is shuting down...")
                                   (when (> stat-interval 0)
                                     (println "Stopping stat output.")
                                     (shutdown stat-out-executor))
                                   (get-stats cljnetpcap)
                                   (when (not (nil? file-output-forwarder))
                                     (println "Closing file output forwarder...")
                                     (file-output-forwarder))
                                   (stop-cljnetpcap cljnetpcap)
                                   (println "Removing temporarily extracted native libs...")
                                   (remove-native-libs)))
              run-duration (arg-map :duration)
              sa-interval (arg-map :self-adaptation)
              sa-executor (executor)
              shutdown-timer-executor (executor)]
          (if (not= "" pcap-file-name)
            (println "clj-net-pcap standalone executable started.\n"))
          (when (> stat-interval 0)
            (println "Printing stats to stderr in intervalls of" stat-interval "ms.")
            (run-repeat stat-out-executor #(print-err-ln (get-stats cljnetpcap)) stat-interval))
          (when (> sa-interval 0)
            (println "Enabling self-adaptivity with interval:" sa-interval)
            (run-repeat sa-executor #(self-adapt-ctrlr (get-stats cljnetpcap)) sa-interval))
          (cond
            (not= "" pcap-file-name)
              (do
                (println "Finished reading from pcap file.")
                (sleep stat-interval))
            (> run-duration 0)
              (do
                (println "Will automatically shut down in" run-duration "seconds.")
                (run-once shutdown-timer-executor shutdown-fn (* 1000 run-duration)))
            :default
              ;;; Running the main from, e.g., leiningen results in stdout not being properly accessible.
              ;;; Hence, this will not work when run this way but works when run from a jar via "java -jar ...".
              (do
                (println "Type \"quit\" or \"q\" to quit: ")
                (loop [line ""]
                  (if-not (or (= line "q") (= line "quit"))
                    (let [split-input (split line #"\s")
                          cmd (first split-input)
                          args (join " " (rest split-input))]
                      (try
                        (cond
                          (or
                            (= cmd "af")
                            (= cmd "add-filter")) (try
                                                    (add-filter cljnetpcap args)
                                                    (catch Exception e
                                                      (println "Error adding filter:" e)
                                                      (.printStackTrace e)))
                          (or
                            (= cmd "gf")
                            (= cmd "get-filters")) (pprint (get-filters cljnetpcap))
                          (or
                            (= cmd "rlf")
                            (= cmd "rm-last-filter")) (remove-last-filter cljnetpcap)
                          (or
                            (= cmd "raf")
                            (= cmd "rm-all-filters")) (remove-all-filters cljnetpcap)
                          (= cmd "replace-filter") (let [filters (split args #" with-filter ")]
                                                     (replace-filter cljnetpcap (first filters) (second filters)))
                          (or
                            (= cmd "gp")
                            (= cmd "gen-packet")) (binding [*read-eval* false]
                                                    (println (vec (generate-packet-data (read-string args)))))
                          (or
                            (= cmd "sp")
                            (= cmd "send-packet")) (let [read-data (binding [*read-eval* false] (read-string args))]
                                                     (if (map? read-data)
                                                       (cljnetpcap :send-packet-map read-data)
                                                       (cljnetpcap :send-bytes-packet (byte-array (map byte read-data)))))
                          (or
                            (= cmd "sdtf")
                            (= cmd "set-dsl-tr-fn")) (let [read-data (binding [*read-eval* false] (read-string args))
                                                                       new-dsl-t-fn (get-dsl-fn read-data)]
                                                                   (reset! dynamic-transformation-fn new-dsl-t-fn))
                          (or
                            (= cmd "?")
                            (= cmd "help")) (println
                                              (str "clj-net-pcap help:\n"
                                                   "add-filter (af)\t\tAdd a new pcap filter.\n"
                                                   "               \t\tTwo situations have to be distinguished:\n"
                                                   "               \t\tthe initial filter addition and subsequent additions.\n"
                                                   "               \t\tE.g. (initial filter): \"af tcp\"\n"
                                                   "               \t\tE.g. (subsequent filter): \"af or udp\"\n"
                                                   "               \t\tNote the \"or\" (also possible \"and\") statement for chaining the filter expressions.\n\n"
                                                   "get-filter (gf)\t\tReturns the currently active filter(s).\n\n"
                                                   "rm-last-filter(rlf)\tRemoves the last filter expression.\n\n"
                                                   "rm-all-filter(rlf)\tRemove all filter expressions.\n\n"
                                                   "replace-filter\t\tReplace an existing filter with another one.\n"
                                                   "              \t\tE.g.: replace-filter or udp with or icmp\n\n"
                                                   "gen-packet (gp)\t\tGenerates a vector with raw packet data.\n"
                                                   "               \t\tThe input is a packet description as clojure map.\n"
                                                   "               \t\tE.g.: gp {\"len\" 20, \"ethSrc\" \"01:02:03:04:05:06\", \"ethDst\" \"FF:FE:FD:F2:F1:F0\"}\n"
                                                   "               \t\tE.g.: gp {\"len\" 54, \"ethSrc\" \"01:02:03:04:05:06\", \"ethDst\" \"FF:FE:FD:F2:F1:F0\", \"ipVer\" 4, \"ipDst\" \"252.253.254.255\", \"ipId\" 3, \"ipType\" 1, \"ipTtl\" 7, \"ipSrc\" \"1.2.3.4\", \"icmpType\" 8, \"icmpId\" 123, \"icmpSeqNo\" 12, \"data\" \"abcd\"}\n\n"
                                                   "send-packet (sp)\tSend a generated packet via the current capture device.\n"
                                                   "                \tThe packet to be sent can be either defined as map like shown for \"gen-packet\"\n"
                                                   "                \tor can be a raw packet data vector like emitted by \"gen-packet\".\n\n"
                                                   "set-dsl-tr-fn (sdtf)\tSet the transformation function based on the provided DSL expression.\n"
                                                   "                    \tPlease note: this requires DSL-based processing\n"
                                                   "                    \t             AND the dynamic transformation function to be enabled.\n"
                                                   "                    \tE.g.: sdtf {:type :json-str :rules [[udpSrc (int16 udp-src)] [udpDst (int16 udp-dst)]]}\n"
                                                   "                    \tE.g.: sdtf {:type :csv-str :rules [[udpSrc (float (/ (int16 udp-src) 65535))] [udpDst (float (/ (int16 udp-dst) 65535))]]}\n"
                                                   "                    \tE.g. (old syntax): sdtf {:type :clj-map :rules [{:offset :udp-src :transformation :int16 :name :udpSrc} {:offset :udp-dst :transformation :int16 :name :udpDst}]}\n"))
                          :default (when (not= cmd "")
                                     (println "Unknown command:" cmd)
                                     (println (str "Valid commands are: "
                                                   "quit (q), help (?), "
                                                   "add-filter (af), get-filters (gf), rm-last-filter (rlf), "
                                                   "rm-all-filters (raf), replace-filter <OLD> with-filter <NEW>, "
                                                   "gen-packet (gp), send-packet (sp), set-dsl-tr-fn (sdtf)"))))
                        (catch Exception e
                          (println "Error while processing input:" (.getMessage e))))
                      (print "cljnetpcap=> ")
                      (flush)
                      (recur (read-line)))))
                (shutdown-fn)))
          (println "Leaving (-main [& args] ...)."))))))

