package edu.brown.hstore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.log4j.Logger;
import org.voltdb.catalog.Database;
import org.voltdb.catalog.Procedure;

import edu.brown.hstore.callbacks.TransactionInitQueueCallback;
import edu.brown.hstore.conf.HStoreConf;
import edu.brown.hstore.txns.AbstractTransaction;
import edu.brown.hstore.txns.LocalTransaction;
import edu.brown.hstore.util.TransactionCounter;
import edu.brown.interfaces.Shutdownable;
import edu.brown.logging.LoggerUtil;
import edu.brown.logging.LoggerUtil.LoggerBoolean;
import edu.brown.logging.RingBufferAppender;
import edu.brown.pools.TypedPoolableObjectFactory;
import edu.brown.pools.TypedObjectPool;
import edu.brown.profilers.HStoreSiteProfiler;
import edu.brown.profilers.PartitionExecutorProfiler;
import edu.brown.profilers.ProfileMeasurement;
import edu.brown.profilers.TransactionProfiler;
import edu.brown.statistics.Histogram;
import edu.brown.utils.CollectionUtil;
import edu.brown.utils.EventObservable;
import edu.brown.utils.EventObserver;
import edu.brown.utils.ExceptionHandlingRunnable;
import edu.brown.utils.StringBoxUtil;
import edu.brown.utils.StringUtil;
import edu.brown.utils.TableUtil;

/**
 * 
 * @author pavlo
 */
public class HStoreSiteStatus extends ExceptionHandlingRunnable implements Shutdownable {
    private static final Logger LOG = Logger.getLogger(HStoreSiteStatus.class);
    private static final LoggerBoolean debug = new LoggerBoolean(LOG.isDebugEnabled());
    private static final LoggerBoolean trace = new LoggerBoolean(LOG.isTraceEnabled());
    static {
        LoggerUtil.attachObserver(LOG, debug, trace);
    }
    
    // ----------------------------------------------------------------------------
    // STATIC CONFIGURATION
    // ----------------------------------------------------------------------------
    
    private static final String POOL_FORMAT = "Active:%-5d / Idle:%-5d / Created:%-5d / Destroyed:%-5d / Passivated:%-7d";

    private static final Set<TransactionCounter> TXNINFO_COL_DELIMITERS = new HashSet<TransactionCounter>();
    private static final Set<TransactionCounter> TXNINFO_ALWAYS_SHOW = new HashSet<TransactionCounter>();
    private static final Set<TransactionCounter> TXNINFO_EXCLUDES = new HashSet<TransactionCounter>();
    static {
        CollectionUtil.addAll(TXNINFO_COL_DELIMITERS, TransactionCounter.EXECUTED,
                                                      TransactionCounter.MULTI_PARTITION,
                                                      TransactionCounter.MISPREDICTED);
        CollectionUtil.addAll(TXNINFO_ALWAYS_SHOW,    TransactionCounter.MULTI_PARTITION,
                                                      TransactionCounter.SINGLE_PARTITION,
                                                      TransactionCounter.MISPREDICTED);
        CollectionUtil.addAll(TXNINFO_EXCLUDES,       TransactionCounter.SYSPROCS);
    }
    
    // ----------------------------------------------------------------------------
    // RUNTIME VARIABLES
    // ----------------------------------------------------------------------------
    
    private final HStoreSite hstore_site;
    private final HStoreSite.Debug siteDebug;
    private final HStoreConf hstore_conf;
    private final int interval; // milliseconds
    private final TreeMap<Integer, PartitionExecutor> executors;
    
    private final Set<AbstractTransaction> last_finishedTxns;
    private final Set<AbstractTransaction> cur_finishedTxns;
    
    private Integer last_completed = null;
    private AtomicInteger snapshot_ctr = new AtomicInteger(0);
    
    private int inflight_min = Integer.MAX_VALUE;
    private int inflight_max = Integer.MIN_VALUE;
    
    private Integer processing_min = null;
    private Integer processing_max = null;
    
    private Thread self;
    private long startTime;
    
    private ProfileMeasurement lastNetworkIdle = null;
    private ProfileMeasurement lastNetworkProcessing = null;
    
    /**
     * The profiling information for each PartitionExecutor since
     * the last status snapshot 
     */
    private final Map<PartitionExecutor, ProfileMeasurement> lastExecTxnTimes = new IdentityHashMap<PartitionExecutor, ProfileMeasurement>();
    private final Map<PartitionExecutor, ProfileMeasurement> lastExecIdleTimes = new IdentityHashMap<PartitionExecutor, ProfileMeasurement>();
    private final Map<PartitionExecutor, ProfileMeasurement> lastExecNetworkTimes = new IdentityHashMap<PartitionExecutor, ProfileMeasurement>();
    private final Map<PartitionExecutor, ProfileMeasurement> lastExecUtilityTimes = new IdentityHashMap<PartitionExecutor, ProfileMeasurement>();

    /**
     * Maintain a set of tuples for the transaction profile times
     */
    private final Map<Procedure, LinkedBlockingDeque<long[]>> txn_profile_queues = new TreeMap<Procedure, LinkedBlockingDeque<long[]>>();
    private final Map<Procedure, long[]> txn_profile_totals = Collections.synchronizedSortedMap(new TreeMap<Procedure, long[]>());
    private TableUtil.Format txn_profile_format;
    private String txn_profiler_header[];
    
    private final Map<String, Object> header = new LinkedHashMap<String, Object>();
    
    private final TreeSet<Thread> sortedThreads = new TreeSet<Thread>(new Comparator<Thread>() {
        @Override
        public int compare(Thread o1, Thread o2) {
            return o1.getName().compareToIgnoreCase(o2.getName());
        }
    });
    
    // ----------------------------------------------------------------------------
    // CONSTRUCTOR
    // ----------------------------------------------------------------------------
    
    /**
     * Constructor
     * @param hstore_site
     * @param hstore_conf
     */
    public HStoreSiteStatus(HStoreSite hstore_site, HStoreConf hstore_conf) {
        this.hstore_site = hstore_site;
        this.siteDebug = hstore_site.getDebugContext();
        this.hstore_conf = hstore_conf;
        this.interval = hstore_conf.site.status_interval;
        
        // The list of transactions that were sitting in the queue as finished
        // the last time that we checked
        if (hstore_conf.site.status_check_for_zombies) {
            this.last_finishedTxns = new HashSet<AbstractTransaction>();
            this.cur_finishedTxns = new HashSet<AbstractTransaction>();
        } else {
            this.last_finishedTxns = null;
            this.cur_finishedTxns = null;
        }
        
        this.executors = new TreeMap<Integer, PartitionExecutor>();
        for (Integer partition : hstore_site.getLocalPartitionIds()) {
            this.executors.put(partition, hstore_site.getPartitionExecutor(partition));
        } // FOR

        // Print a debug message when the first non-sysproc shows up
        this.hstore_site.getStartWorkloadObservable().addObserver(new EventObserver<HStoreSite>() {
            @Override
            public void update(EventObservable<HStoreSite> arg0, HStoreSite arg1) {
//                if (debug.get())
                LOG.info(arg1.getSiteName() + " - " +HStoreConstants.SITE_FIRST_TXN);
                startTime = System.currentTimeMillis();
            }
        });
        
        // Pre-Compute Header
        this.header.put(String.format("%s Status", HStoreSite.class.getSimpleName()), hstore_site.getSiteName());
        this.header.put("Number of Partitions", this.executors.size());
        
        // Pre-Compute TransactionProfile Information
        this.initTxnProfileInfo(hstore_site.getCatalogContext().database);
    }
    
    @Override
    public void runImpl() {
        self = Thread.currentThread();
        // self.setName(HStoreThreadManager.getThreadName(hstore_site, HStoreConstants.THREAD_NAME_DEBUGSTATUS));
        // this.hstore_site.getThreadManager().registerProcessingThread();

        if (debug.get()) LOG.debug(String.format("Starting HStoreSite status monitor thread [interval=%d, kill=%s]", this.interval, hstore_conf.site.status_kill_if_hung));
        try {
            // Out we go!
            this.printStatus();
            
            // If we're not making progress, bring the whole thing down!
            int completed = TransactionCounter.COMPLETED.get();
            if (hstore_conf.site.status_kill_if_hung && this.last_completed != null &&
                this.last_completed == completed && siteDebug.getInflightTxnCount() > 0) {
                String msg = String.format("HStoreSite #%d is hung! Killing the cluster!", hstore_site.getSiteId()); 
                LOG.fatal(msg);
                this.hstore_site.getCoordinator().shutdownCluster(new RuntimeException(msg));
            }
            this.last_completed = completed;
        } catch (Throwable ex) {
            ex.printStackTrace();
        }
    }
    
    public void printStatus() {
        LOG.info("STATUS #" + this.snapshot_ctr.incrementAndGet() + "\n" +
                 StringBoxUtil.box(this.snapshot(hstore_conf.site.status_show_txn_info,
                                              hstore_conf.site.status_show_executor_info,
                                              hstore_conf.site.status_show_thread_info,
                                              hstore_conf.site.pool_profiling)));
    }
    
    // ----------------------------------------------------------------------------
    // SNAPSHOT PRETTY PRINTER
    // ----------------------------------------------------------------------------
    
    public synchronized String snapshot(boolean show_txns, boolean show_exec, boolean show_threads, boolean show_poolinfo) {
        // ----------------------------------------------------------------------------
        // Site Information
        // ----------------------------------------------------------------------------
        Map<String, Object> siteInfo = this.siteInfo();

        // ----------------------------------------------------------------------------
        // Executor Information
        // ----------------------------------------------------------------------------
        Map<String, Object> execInfo = (show_exec ? this.executorInfo() : null);
        
        // ----------------------------------------------------------------------------
        // Transaction Information
        // ----------------------------------------------------------------------------
        Map<String, String> txnInfo = (show_txns ? this.txnExecInfo() : null);

        // ----------------------------------------------------------------------------
        // Batch Planner Information
        // ----------------------------------------------------------------------------
        Map<String, String> plannerInfo = (hstore_conf.site.planner_profiling ? this.batchPlannerInfo() : null);
        
        // ----------------------------------------------------------------------------
        // Thread Information
        // ----------------------------------------------------------------------------
        Map<String, Object> threadInfo = null;
        Map<String, Object> cpuThreads = null;
        if (show_threads) {
            threadInfo = this.threadInfo();
            
            cpuThreads = new LinkedHashMap<String, Object>();
            for (Entry<Integer, Set<Thread>> e : hstore_site.getThreadManager().getCPUThreads().entrySet()) {
                TreeSet<String> names = new TreeSet<String>();
                for (Thread t : e.getValue())
                    names.add(t.getName());
                cpuThreads.put("CPU #" + e.getKey(), StringUtil.columns(names.toArray(new String[0])));
            } // FOR
        }

        // ----------------------------------------------------------------------------
        // Transaction Profiling
        // ----------------------------------------------------------------------------
        Map<String, String> txnProfiles = (hstore_conf.site.txn_profiling ? this.txnProfileInfo() : null);
        
        // ----------------------------------------------------------------------------
        // Object Pool Information
        // ----------------------------------------------------------------------------
        Map<String, Object> poolInfo = (show_poolinfo ? this.poolInfo() : null);
        
        String top = StringUtil.formatMaps(header,
                                           siteInfo,
                                           execInfo,
                                           txnInfo,
                                           threadInfo,
                                           cpuThreads,
                                           txnProfiles,
                                           plannerInfo,
                                           poolInfo);
        String bot = "";
        Histogram<Integer> blockedDtxns = hstore_site.getTransactionQueueManager().getDebugContext().getBlockedDtxnHistogram(); 
        if (hstore_conf.site.status_show_txn_info && blockedDtxns != null && blockedDtxns.isEmpty() == false) {
            bot = "\nRejected Transactions by Remote Identifier:\n" + blockedDtxns;
//            bot += "\n" + hstore_site.getTransactionQueueManager().toString();
        }
        
        return (top + bot);
    }
    
    // ----------------------------------------------------------------------------
    // SITE INFO
    // ----------------------------------------------------------------------------
    
    /**
     * 
     * @return
     */
    private Map<String, Object> siteInfo() {
        ProfileMeasurement pm = null;
        String value = null;
        
        LinkedHashMap<String, Object> siteInfo = new LinkedHashMap<String, Object>();
        if (TransactionCounter.COMPLETED.get() > 0) {
            siteInfo.put("Completed Txns", TransactionCounter.COMPLETED.get());
        }
        
        // ClientInterface
        ClientInterface ci = hstore_site.getClientInterface();
        if (ci != null) {
            siteInfo.put("# of Connections", ci.getConnectionCount());
            
            value = String.format("%d txns [limit=%d, release=%d] / " +
            		              "%d bytes [limit=%d, release=%d]%s",
                                  ci.getPendingTxnCount(),
                                  ci.getMaxPendingTxnCount(),
                                  ci.getReleasePendingTxnCount(),
                                  ci.getPendingTxnBytes(),
                                  ci.getMaxPendingTxnBytes(),
                                  ci.getReleasePendingTxnBytes(),
                                  (ci.hasBackPressure() ? " / *THROTTLED*" : ""));
            siteInfo.put("Client Interface Queue", value);
            siteInfo.put("BackPressure Counter", ci.getBackPressureCount());
        }
        
        if (hstore_conf.site.profiling && hstore_site.getProfiler() != null) {
            // Compute the approximate arrival rate of transaction
            // requests per second from clients
            HStoreSiteProfiler profiler = hstore_site.getProfiler();
            
            pm = profiler.network_processing;
            double totalTime = System.currentTimeMillis() - startTime;
            double arrivalRate = (totalTime > 0 ? (pm.getInvocations() / totalTime) : 0d);
            
            value = String.format("%.02f txn/sec [total=%d]", arrivalRate, pm.getInvocations());
            siteInfo.put("Arrival Rate", value);
            
            pm = profiler.network_backup_off;
            siteInfo.put("Back Pressure Off", formatProfileMeasurements(pm, null, true, false));
            
            pm = profiler.network_backup_on;
            siteInfo.put("Back Pressure On", formatProfileMeasurements(pm, null, true, false));
        }

        
        // TransactionQueueManager
        TransactionQueueManager queueManager = hstore_site.getTransactionQueueManager();
        TransactionQueueManager.Debug queueManagerDebug = queueManager.getDebugContext();
        
        int inflight_cur = siteDebug.getInflightTxnCount();
        int inflight_local = queueManagerDebug.getInitQueueSize();
        if (inflight_cur < this.inflight_min && inflight_cur > 0) this.inflight_min = inflight_cur;
        if (inflight_cur > this.inflight_max) this.inflight_max = inflight_cur;
        
        // Check to see how many of them are marked as finished
        // There is no guarantee that this will be accurate because txns could be swapped out
        // by the time we get through it all
        int inflight_finished = 0;
        int inflight_zombies = 0;
        if (this.cur_finishedTxns != null) this.cur_finishedTxns.clear();
        for (AbstractTransaction ts : siteDebug.getInflightTransactions()) {
           if (ts instanceof LocalTransaction) {
//               LocalTransaction local_ts = (LocalTransaction)ts;
//               ClientResponse cr = local_ts.getClientResponse();
//               if (cr.getStatus() != null) {
//                   inflight_finished++;
//                   // Check for Zombies!
//                   if (this.cur_finishedTxns != null && local_ts.isPredictSinglePartition() == false) {
//                       if (this.last_finishedTxns.contains(ts)) {
//                           inflight_zombies++;
//                       }
//                       this.cur_finishedTxns.add(ts);
//                   }
//               }
           }
        } // FOR
        
        siteInfo.put("InFlight Txns", String.format("%d total / %d queued / %d finished / %d deletable [totalMin=%d, totalMax=%d]",
                        inflight_cur,
                        inflight_local,
                        inflight_finished,
                        this.siteDebug.getDeletableTxnCount(),
                        this.inflight_min,
                        this.inflight_max
        ));
        
        if (this.cur_finishedTxns != null) {
            siteInfo.put("Zombie Txns", inflight_zombies +
                                      (inflight_zombies > 0 ? " - " + CollectionUtil.first(this.cur_finishedTxns) : ""));
//            for (AbstractTransaction ts : this.cur_finishedTxns) {
//                // HACK
//                if (ts instanceof LocalTransaction && this.last_finishedTxns.remove(ts)) {
//                    LocalTransaction local_ts = (LocalTransaction)ts;
//                    local_ts.markAsDeletable();
//                    hstore_site.deleteTransaction(ts.getTransactionId(), local_ts.getClientResponse().getStatus());
//                }
//            }
            this.last_finishedTxns.clear();
            this.last_finishedTxns.addAll(this.cur_finishedTxns);
        }
        
        if (hstore_conf.site.profiling) {
            HStoreSiteProfiler profiler = this.hstore_site.getProfiler();
            pm = profiler.network_idle;
            value = this.formatProfileMeasurements(pm, this.lastNetworkIdle, true, true);
            siteInfo.put("Network Idle", value);
            this.lastNetworkIdle = new ProfileMeasurement(pm);
            
            pm = profiler.network_processing;
            value = this.formatProfileMeasurements(pm, this.lastNetworkProcessing, true, true);
            siteInfo.put("Network Processing", value);
            this.lastNetworkProcessing = new ProfileMeasurement(pm);
        }
        
        if (hstore_conf.site.exec_postprocessing_threads) {
            int processing_cur = siteDebug.getQueuedResponseCount();
            if (processing_min == null || processing_cur < processing_min) processing_min = processing_cur;
            if (processing_max == null || processing_cur > processing_max) processing_max = processing_cur;
            
            String val = String.format("%-5d [min=%d, max=%d]", processing_cur, processing_min, processing_max);
            int i = 0;
            for (TransactionPostProcessor tpp : hstore_site.getTransactionPostProcessors()) {
                pm = tpp.getExecTime();
                val += String.format("\n[%02d] %d total / %.2fms total / %.2fms avg",
                                     i++,
                                     pm.getInvocations(),
                                     pm.getTotalThinkTimeMS(),
                                     pm.getAverageThinkTimeMS());
            } // FOR
            
            siteInfo.put("Post-Processing Txns", val);
        }
        
        // Last Deleted Txns
        Collection<String> lastDeleted = siteDebug.getLastDeletedTxns();
        if (lastDeleted.isEmpty() == false) {
            siteInfo.put("Last Deleted", StringUtil.join("\n", lastDeleted));
        }

        return (siteInfo);
    }

    // ----------------------------------------------------------------------------
    // EXECUTION INFO
    // ----------------------------------------------------------------------------
        
    private Map<String, Object> executorInfo() {
        LinkedHashMap<String, Object> m_exec = new LinkedHashMap<String, Object>();
        
        ProfileMeasurement pm = null;
        TransactionQueueManager queueManager = hstore_site.getTransactionQueueManager();
        TransactionQueueManager.Debug queueManagerDebug = queueManager.getDebugContext();
        HStoreThreadManager thread_manager = hstore_site.getThreadManager();
        
        ProfileMeasurement totalExecTxnTime = new ProfileMeasurement();
        ProfileMeasurement totalExecIdleTime = new ProfileMeasurement();
        ProfileMeasurement totalExecNetworkTime = new ProfileMeasurement();
        
        // EXECUTION ENGINES
        Map<Integer, String> partitionLabels = new HashMap<Integer, String>();
        Histogram<Integer> invokedTxns = new Histogram<Integer>();
        String status = null;
        Long txn_id = null;
        ProfileMeasurement last = null;
        for (Entry<Integer, PartitionExecutor> e : this.executors.entrySet()) {
            int partition = e.getKey().intValue();
            String partitionLabel = String.format("%02d", partition);
            partitionLabels.put(partition, partitionLabel);
            
            PartitionExecutor es = e.getValue();
            PartitionExecutor.Debug dbg = es.getDebugContext();
            Queue<?> es_queue = dbg.getWorkQueue();
            TransactionInitPriorityQueue dtxn_queue = queueManager.getInitQueue(partition);
            AbstractTransaction current_dtxn = dbg.getCurrentDtxn();
            
            // Queue Information
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            
            // Header
            m.put(String.format("%3d total / %3d queued / %3d blocked / %3d spec-exec\n",
                                    es_queue.size(),
                                    dbg.getWorkQueueSize(),
                                    dbg.getBlockedWorkCount(),
                                    dbg.getBlockedSpecExecCount()), null);
            
            // Execution Info
//            status = String.format("%-5s [limit=%d, release=%d] %s",
//                                   es_queue.size(), es_queue.getQueueMax(), es_queue.getQueueRelease(),
//                                   (es_queue.isThrottled() ? "*THROTTLED* " : ""));
            // m.put("Exec Queue", status);
            
            txn_id = dbg.getCurrentTxnId();
            m.put("Current Txn", String.format("%s / %s", (txn_id != null ? "#"+txn_id : "-"), dbg.getExecutionMode()));
            m.put("Current DTXN", (current_dtxn == null ? "-" : current_dtxn));
            
            txn_id = dbg.getLastExecutedTxnId();
            m.put("Last Executed Txn", (txn_id != null ? "#"+txn_id : "-"));
            
            txn_id = dbg.getLastCommittedTxnId();
            m.put("Last Committed Txn", (txn_id != null ? "#"+txn_id : "-"));
            
            // TransactionQueueManager Info
            status = String.format("%-5s", dtxn_queue.size());
            txn_id = queueManagerDebug.getCurrentTransaction(partition);
            if (txn_id != null) {
                AbstractTransaction ts = hstore_site.getTransaction(txn_id);
                if (ts != null) {
                    TransactionInitQueueCallback callback = ts.getTransactionInitQueueCallback();
                    status += ts;
                    String spacer = StringUtil.repeat(" ", 5);
                    if (callback != null) {
                        status += "\n" + spacer;
                        status += String.format("Partitions=%s / Remaining=%d",
                                                callback.getPartitions(), callback.getCounter());
                    }
                    // FULL TXN DEBUG
                    // status += "\n" + StringUtil.prefix(ts.debug(), spacer);
                }
            }
            else if (queueManagerDebug.isLocked(partition)) {
                status += "\nWARNING: Queue is locked but without a txn!";
            }
            // TransactionQueueManager - Blocked
            if (queueManagerDebug.getBlockedQueueSize() > 0) {
                status += "\nBlocked: " + queueManagerDebug.getBlockedQueueSize();
            }
            // TransactionQueueManager - Requeued Txns
            if (queueManagerDebug.getRestartQueueSize() > 0) {
                status += "\nRequeues: " + queueManagerDebug.getRestartQueueSize();
            }
            m.put("DTXN Queue", status);
            
            
//            if (is_throttled && queue_size < queue_release && hstore_site.isShuttingDown() == false) {
//                LOG.warn(String.format("Partition %d is throttled when it should not be! [inflight=%d, release=%d]",
//                                        partition, queue_size, queue_release));
//            }
            
            
            if (hstore_conf.site.exec_profiling) {
                PartitionExecutorProfiler profiler = dbg.getProfiler();
                
                // Execution Time
                pm = profiler.exec_time;
                last = lastExecTxnTimes.get(es);
                m.put("Txn Execution", this.formatProfileMeasurements(pm, last, true, true)); 
                this.lastExecTxnTimes.put(es, new ProfileMeasurement(pm));
                invokedTxns.put(partition, (int)profiler.exec_time.getInvocations());
                totalExecTxnTime.appendTime(pm);
                
                // Idle Time
                last = lastExecIdleTimes.get(es);
                pm = profiler.idle_queue_time;
                m.put("Idle Time", this.formatProfileMeasurements(pm, last, false, false)); 
                this.lastExecIdleTimes.put(es, new ProfileMeasurement(pm));
                totalExecIdleTime.appendTime(pm);
                
                // Network Time
                last = lastExecNetworkTimes.get(es);
                pm = profiler.network_time;
                m.put("Network Time", this.formatProfileMeasurements(pm, last, false, true)); 
                this.lastExecNetworkTimes.put(es, new ProfileMeasurement(pm));
                totalExecNetworkTime.appendTime(pm);
                
                // Utility Time
                last = lastExecUtilityTimes.get(es);
                pm = profiler.util_time;
                m.put("Utility Time", this.formatProfileMeasurements(pm, last, false, true)); 
                this.lastExecUtilityTimes.put(es, new ProfileMeasurement(pm));
            }
            
            String label = "    Partition[" + partitionLabel + "]";
            
            // Get additional partition info
            Thread t = dbg.getExecutionThread();
            if (t != null && thread_manager.isRegistered(t)) {
                for (Integer cpu : thread_manager.getCPUIds(t)) {
                    label += "\n       \u2192 CPU *" + cpu + "*";
                } // FOR
            }
            
            m_exec.put(label, StringUtil.formatMaps(m) + "\n");
        } // FOR
        
        if (hstore_conf.site.exec_profiling) {
            m_exec.put("Total Txn Execution", this.formatProfileMeasurements(totalExecTxnTime, null, true, true));
            m_exec.put("Total Idle Execution", this.formatProfileMeasurements(totalExecIdleTime, null, true, true));
            m_exec.put("Total Network Execution", this.formatProfileMeasurements(totalExecNetworkTime, null, true, true));
            m_exec.put(" ", null);
        }
        
        // Incoming Partition Distribution
        if (siteDebug.getProfiler() != null) {
            Histogram<Integer> incoming = siteDebug.getProfiler().network_incoming_partitions;
            if (incoming.isEmpty() == false) {
                incoming.setDebugLabels(partitionLabels);
                incoming.enablePercentages();
                m_exec.put("Incoming Txns\nBase Partitions", incoming.toString(50, 10) + "\n");
            }
        }
        if (invokedTxns.isEmpty() == false) {
            invokedTxns.setDebugLabels(partitionLabels);
            invokedTxns.enablePercentages();
            m_exec.put("Invoked Txns", invokedTxns.toString(50, 10) + "\n");
        }
        
        return (m_exec);
    }
    
    private String formatProfileMeasurements(ProfileMeasurement pm, ProfileMeasurement last,
                                              boolean showInvocations, boolean compareLastAvg) {
        String value = "";
        if (showInvocations) {
            value += String.format("%d txns / ", pm.getInvocations()); 
        }
        
        String avgTime = StringUtil.formatTime("%.2f", pm.getAverageThinkTime());
        value += String.format("%.2fms total / %s avg",
                               pm.getTotalThinkTimeMS(), avgTime);
        if (last != null) {
            double delta;
            String deltaPrefix;
            if (compareLastAvg) {
                delta = pm.getAverageThinkTime() - last.getAverageThinkTime();
                deltaPrefix = "AVG: ";
            } else {
                delta = pm.getTotalThinkTime() - last.getTotalThinkTime();
                deltaPrefix = "";
            }
            String deltaTime = StringUtil.formatTime("%.2f", delta);
            String deltaArrow = " ";
            if (delta > 0) {
                deltaArrow = StringUtil.UNICODE_UP_ARROW;
            } else if (delta < 0) {
                deltaArrow = StringUtil.UNICODE_DOWN_ARROW;
            }
            value += String.format("  [%s%s%s]", deltaPrefix, deltaArrow, deltaTime);
        }
        return (value);
    }
    
    // ----------------------------------------------------------------------------
    // TRANSACTION EXECUTION INFO
    // ----------------------------------------------------------------------------
    
    /**
     * 
     * @return
     */
    private Map<String, String> txnExecInfo() {
        Set<TransactionCounter> cnts_to_include = new TreeSet<TransactionCounter>();
        Collection<String> procs = TransactionCounter.getAllProcedures();
        if (procs.isEmpty()) return (null);
        for (TransactionCounter tc : TransactionCounter.values()) {
            if (TXNINFO_ALWAYS_SHOW.contains(tc) || (tc.get() > 0 && TXNINFO_EXCLUDES.contains(tc) == false)) cnts_to_include.add(tc);
        } // FOR
        
        boolean first = true;
        int num_cols = cnts_to_include.size() + 1;
        String header[] = new String[num_cols];
        Object rows[][] = new String[procs.size()+2][];
        String col_delimiters[] = new String[num_cols];
        String row_delimiters[] = new String[rows.length];
        int i = -1;
        int j = 0;
        for (String proc_name : procs) {
            j = 0;
            rows[++i] = new String[num_cols];
            rows[i][j++] = proc_name;
            if (first) header[0] = "";
            for (TransactionCounter tc : cnts_to_include) {
                if (first) header[j] = tc.toString().replace("partition", "P");
                Long cnt = tc.getHistogram().get(proc_name);
                rows[i][j++] = (cnt != null ? cnt.toString() : "-");
            } // FOR
            first = false;
        } // FOR
        
        j = 0;
        rows[++i] = new String[num_cols];
        rows[i+1] = new String[num_cols];
        rows[i][j++] = "TOTAL";
        row_delimiters[i] = "-"; // "\u2015";
        
        for (TransactionCounter tc : cnts_to_include) {
            if (TXNINFO_COL_DELIMITERS.contains(tc)) col_delimiters[j] = " | ";
            
            if (tc == TransactionCounter.COMPLETED || tc == TransactionCounter.RECEIVED) {
                rows[i][j] = Integer.toString(tc.get());
                rows[i+1][j] = "";
            } else {
                Double ratio = tc.ratio();
                rows[i][j] = (ratio == null ? "-" : Integer.toString(tc.get()));
                rows[i+1][j] = (ratio == null ? "-": String.format("%.3f", ratio));
            }
            j++;
        } // FOR
        
        if (debug.get()) {
            for (i = 0; i < rows.length; i++) {
                LOG.debug("ROW[" + i + "]: " + Arrays.toString(rows[i]));
            }
        }
        TableUtil.Format f = new TableUtil.Format("  ", col_delimiters, row_delimiters, true, false, true, false, false, false, true, true, null);
        return (TableUtil.tableMap(f, header, rows));
    }
    
    // ----------------------------------------------------------------------------
    // BATCH PLANNER INFO
    // ----------------------------------------------------------------------------
    
    /**
     * 
     * @return
     */
    private Map<String, String> batchPlannerInfo() {
        // First get all the BatchPlanners that we have
        Collection<BatchPlanner> bps = new HashSet<BatchPlanner>();
        for (PartitionExecutor es : this.executors.values()) {
            PartitionExecutor.Debug dbg = es.getDebugContext();
            bps.addAll(dbg.getBatchPlanners());
        } // FOR
        Map<Procedure, ProfileMeasurement[]> proc_totals = new HashMap<Procedure, ProfileMeasurement[]>();
        ProfileMeasurement final_totals[] = null;
        int num_cols = 0;
        for (BatchPlanner bp : bps) {
            ProfileMeasurement times[] = bp.getProfiler().getProfileMeasurements();
            
            Procedure catalog_proc = bp.getProcedure();
            ProfileMeasurement totals[] = proc_totals.get(catalog_proc);
            if (totals == null) {
                num_cols = times.length+2;
                totals = new ProfileMeasurement[num_cols-1];
                final_totals = new ProfileMeasurement[num_cols-1];
                proc_totals.put(catalog_proc, totals);
            }
            for (int i = 0; i < totals.length; i++) {
                if (i == 0) {
                    if (totals[i] == null) totals[i] = new ProfileMeasurement("total");
                } else {
                    if (totals[i] == null)
                        totals[i] = new ProfileMeasurement(times[i-1]);
                    else
                        totals[i].appendTime(times[i-1]);
                    totals[0].appendTime(times[i-1], false);
                }
                if (final_totals[i] == null) final_totals[i] = new ProfileMeasurement(totals[i].getType());
            } // FOR
        } // FOR
        if (proc_totals.isEmpty()) return (null);
        
        boolean first = true;
        String header[] = new String[num_cols];
        Object rows[][] = new String[proc_totals.size()+2][];
        String col_delimiters[] = new String[num_cols];
        String row_delimiters[] = new String[rows.length];
        int i = -1;
        int j = 0;
        for (Procedure proc : proc_totals.keySet()) {
            j = 0;
            rows[++i] = new String[num_cols];
            rows[i][j++] = proc.getName();
            if (first) header[0] = "";
            for (ProfileMeasurement pm : proc_totals.get(proc)) {
                if (first) header[j] = pm.getType();
                final_totals[j-1].appendTime(pm, false);
                rows[i][j] = Long.toString(Math.round(pm.getTotalThinkTimeMS()));
                j++;
            } // FOR
            first = false;
        } // FOR
        
        j = 0;
        rows[++i] = new String[num_cols];
        rows[i+1] = new String[num_cols];
        rows[i][j++] = "TOTAL";
        row_delimiters[i] = "-"; // "\u2015";

        for (int final_idx = 0; final_idx < final_totals.length; final_idx++) {
            if (final_idx == 0) col_delimiters[j] = " | ";
            
            ProfileMeasurement pm = final_totals[final_idx];
            rows[i][j] = Long.toString(Math.round(pm.getTotalThinkTimeMS()));
            rows[i+1][j] = (final_idx > 0 ? String.format("%.3f", pm.getTotalThinkTimeMS() / final_totals[0].getTotalThinkTimeMS()) : ""); 
            j++;
        } // FOR
        
//        if (debug.get()) {
//            for (i = 0; i < rows.length; i++) {
//                LOG.debug("ROW[" + i + "]: " + Arrays.toString(rows[i]));
//            }
//        }
        TableUtil.Format f = new TableUtil.Format("   ", col_delimiters, row_delimiters, true, false, true, false, false, false, true, true, null);
        return (TableUtil.tableMap(f, header, rows));
    }
    
    // ----------------------------------------------------------------------------
    // THREAD INFO
    // ----------------------------------------------------------------------------
    
    /**
     * 
     * @return
     */
    private Map<String, Object> threadInfo() {
        HStoreThreadManager manager = hstore_site.getThreadManager();
        assert(manager != null);
        
        final Map<String, Object> m_thread = new LinkedHashMap<String, Object>();
        final Map<Thread, StackTraceElement[]> threads = Thread.getAllStackTraces();
        sortedThreads.clear();
        sortedThreads.addAll(threads.keySet());
        
        m_thread.put("Number of Threads", threads.size());
        for (Thread t : sortedThreads) {
            StackTraceElement stack[] = threads.get(t);
            
            String name = StringUtil.abbrv(t.getName(), 24, true);
            if (manager.isRegistered(t) == false) {
                name += " *UNREGISTERED*";
            }
            
            String trace = null;
            if (stack.length == 0) {
                trace = "<NO STACK TRACE>";
//            } else if (t.getName().startsWith("Thread-")) {
//                trace = Arrays.toString(stack);
            } else {
                // Find the first line that is interesting to us
//                trace = StringUtil.join("\n", stack);
                for (int i = 0; i < stack.length; i++) {
                    // if (THREAD_REGEX.matcher(stack[i].getClassName()).matches()) {
                        trace += stack[i].toString();
//                        break;
//                    }
                } // FOR
                if (trace == null) stack[0].toString();
            }
            m_thread.put(name, trace);
        } // FOR
        return (m_thread);
    }
    
    // ----------------------------------------------------------------------------
    // TRANSACTION PROFILING
    // ----------------------------------------------------------------------------
    
    /**
     * 
     * @param catalog_db
     */
    private void initTxnProfileInfo(Database catalog_db) {
        TransactionProfiler profiler = new TransactionProfiler();
        ProfileMeasurement fields[] = profiler.getProfileMeasurements();
        
        // COLUMN DELIMITERS
        String last_prefix = null;
        String col_delimiters[] = new String[fields.length*2 + 2];
        int col_idx = 0;
        for (ProfileMeasurement pm : fields) {
            String prefix = pm.getType().split("_")[0];
            assert(prefix.isEmpty() == false);
            if (last_prefix != null && col_idx > 0 && prefix.equals(last_prefix) == false) {
                col_delimiters[col_idx+1] = " | ";        
            }
            col_idx += 2;
            last_prefix = prefix;
        } // FOR
        this.txn_profile_format = new TableUtil.Format("   ", col_delimiters, null,
                                                       true, false, true,
                                                       false, false, false,
                                                       true, true, "-");
        
        // TABLE HEADER
        int idx = 0;
        this.txn_profiler_header = new String[fields.length*2 + 2];
        this.txn_profiler_header[idx++] = "";
        this.txn_profiler_header[idx++] = "txns";
        for (ProfileMeasurement pm : fields) {
            String name = pm.getType();
            this.txn_profiler_header[idx++] = name;
            this.txn_profiler_header[idx++] = name+"_inv";
        } // FOR
        
        // PROCEDURE TOTALS
        for (Procedure catalog_proc : catalog_db.getProcedures()) {
            if (catalog_proc.getSystemproc()) continue;
            this.txn_profile_queues.put(catalog_proc, new LinkedBlockingDeque<long[]>());
            
            long totals[] = new long[fields.length*2 + 1];
            for (int i = 0; i < totals.length; i++) {
                totals[i] = 0;
            } // FOR
            this.txn_profile_totals.put(catalog_proc, totals);
        } // FOR
    }
    
    /**
     * 
     * @param tp
     */
    public void addTxnProfile(Procedure catalog_proc, TransactionProfiler tp) {
        assert(catalog_proc != null);
        assert(tp.isStopped());
        if (trace.get()) LOG.info("Calculating TransactionProfile information");

        long tuple[] = tp.getTuple();
        assert(tuple != null);
        if (trace.get()) LOG.trace(String.format("Appending TransactionProfile: %s", tp, Arrays.toString(tuple)));
        this.txn_profile_queues.get(catalog_proc).offer(tuple);
    }
    
    private void calculateTxnProfileTotals(Procedure catalog_proc) {
        long totals[] = this.txn_profile_totals.get(catalog_proc);
        
        long tuple[] = null;
        LinkedBlockingDeque<long[]> queue = this.txn_profile_queues.get(catalog_proc); 
        while ((tuple = queue.poll()) != null) {
            totals[0]++;
            for (int i = 0, cnt = tuple.length; i < cnt; i++) {
                totals[i+1] += tuple[i];
            } // FOR
        } // FOR
    }
    
    /**
     * 
     * TODO: This should be broken out in a separate component that stores the data
     *       down in the EE. That way we can extract it in a variety of ways
     * 
     * @return
     */
    private Object[][] generateTxnProfileSnapshot() {
        // TABLE ROWS
        List<Object[]> rows = new ArrayList<Object[]>(); 
        for (Entry<Procedure, long[]> e : this.txn_profile_totals.entrySet()) {
            this.calculateTxnProfileTotals(e.getKey());
            long totals[] = e.getValue();
            if (totals[0] == 0) continue;

            int col_idx = 0;
            Object row[] = new String[this.txn_profiler_header.length];
            row[col_idx++] = e.getKey().getName();
            
            for (int i = 0; i < totals.length; i++) {
                // # of Txns
                if (i == 0) {
                    row[col_idx++] = Long.toString(totals[i]);
                // Everything Else
                } else {
                    row[col_idx++] = (totals[i] > 0 ? String.format("%.02f", totals[i] / 1000000d) : null);
                }
            } // FOR
            if (debug.get()) LOG.debug("ROW[" + rows.size() + "] " + Arrays.toString(row));
            rows.add(row);
        } // FOR
        if (rows.isEmpty()) return (null);
        Object rows_arr[][] = rows.toArray(new String[rows.size()][this.txn_profiler_header.length]);
        assert(rows_arr.length == rows.size());
        return (rows_arr);
    }
    
    private Map<String, String> txnProfileInfo() {
        Object rows[][] = this.generateTxnProfileSnapshot();
        if (rows == null) return (null);
        return (TableUtil.tableMap(this.txn_profile_format, this.txn_profiler_header, rows));
    }
    
    @SuppressWarnings("unused")
    private String txnProfileCSV() {
        Object rows[][] = this.generateTxnProfileSnapshot();
        if (rows == null) return (null);
        
        if (debug.get()) {
            for (int i = 0; i < rows.length; i++) {
                if (i == 0) LOG.debug("HEADER: " + Arrays.toString(this.txn_profiler_header));
                LOG.debug("ROW[" + i + "] " + Arrays.toString(rows[i]));
            } // FOR
        }
        TableUtil.Format f = TableUtil.defaultCSVFormat().clone();
        f.replace_null_cells = 0;
        f.prune_null_rows = true;
        return (TableUtil.table(f, this.txn_profiler_header, rows));
    }
    
    // ----------------------------------------------------------------------------
    // OBJECT POOL PROFILING
    // ----------------------------------------------------------------------------
    private Map<String, Object> poolInfo() {
        TypedObjectPool<?> pool = null;
        TypedPoolableObjectFactory<?> factory = null;
        
        // HStoreObjectPools
        Map<String, TypedObjectPool<?>> pools = hstore_site.getObjectPools().getGlobalPools(); 
        
        // MarkovPathEstimators
        // pools.put("Estimators", (TypedObjectPool<?>)MarkovEstimator.POOL_ESTIMATORS); 

        // TransactionEstimator.States
        // pools.put("EstimationStates", (TypedObjectPool<?>)MarkovEstimator.POOL_STATES);
        
        final Map<String, Object> m_pool = new LinkedHashMap<String, Object>();
        for (String key : pools.keySet()) {
            pool = pools.get(key);
            if (pool == null) continue;
            factory = (TypedPoolableObjectFactory<?>)pool.getFactory();
            if (factory.getCreatedCount() > 0) m_pool.put(key, this.formatPoolCounts(pool, factory));
        } // FOR

        // Partition Specific
        String labels[] = new String[] {
            "STATES_TXN_LOCAL",
            "STATES_TXN_REMOTE",
            "STATES_TXN_MAPREDUCE",
            "STATES_DISTRIBUTED",
            "STATES_PREFETCH",
        };
        HStoreObjectPools objPool = hstore_site.getObjectPools();
        for (int i = 0, cnt = labels.length; i < cnt; i++) {
            int total_active = 0;
            int total_idle = 0;
            int total_created = 0;
            int total_passivated = 0;
            int total_destroyed = 0;
            
            boolean found = false;
            for (int p : hstore_site.getLocalPartitionIds()) {
                pool = null;
                switch (i) {
                    case 0:
                        pool = objPool.getLocalTransactionPool(p);
                        break;
                    case 1:
                        pool = objPool.getRemoteTransactionPool(p);
                        break;
                    case 2:
                        pool = objPool.getMapReduceTransactionPool(p);
                        break;
                    case 3:
                        pool = objPool.getDistributedStatePool(p);
                        break;
                    case 4:
                        if (hstore_conf.site.exec_prefetch_queries) {
                            pool = objPool.getPrefetchStatePool(p);
                        }
                        break;
                } // SWITCH
                if (pool == null) continue;
                found = true;
                factory = (TypedPoolableObjectFactory<?>)pool.getFactory();
            
                total_active += pool.getNumActive();
                total_idle += pool.getNumIdle(); 
                total_created += factory.getCreatedCount();
                total_passivated += factory.getPassivatedCount();
                total_destroyed += factory.getDestroyedCount();
            } // FOR (partitions)
            if (found == false) continue;
            m_pool.put(labels[i], String.format(POOL_FORMAT, total_active,
                                                             total_idle,
                                                             total_created,
                                                             total_destroyed,
                                                             total_passivated));
        } // FOR
        
        return (m_pool);
    }
    
    

    
    private String formatPoolCounts(TypedObjectPool<?> pool, TypedPoolableObjectFactory<?> factory) {
        return (String.format(POOL_FORMAT, pool.getNumActive(),
                                           pool.getNumIdle(),
                                           factory.getCreatedCount(),
                                           factory.getDestroyedCount(),
                                           factory.getPassivatedCount()));
    }
    
    // ----------------------------------------------------------------------------
    // SHUTDOWN METHODS
    // ----------------------------------------------------------------------------
    
    @Override
    public void prepareShutdown(boolean error) {
        // TODO Auto-generated method stub
        
    }
    
    @Override
    public void shutdown() {
        // Dump LOG buffers...
        LOG.debug("Looking for RingBufferAppender messages...");
        for (String msg : RingBufferAppender.getLoggingMessages(Logger.getRootLogger().getLoggerRepository())) {
            System.out.print(msg);
        } // FOR
        
//        hstore_conf.site.status_show_thread_info = true;
        //this.printStatus();
        
        // Quick Sanity Check!
//        for (int i = 0; i < 2; i++) {
//            Histogram<Long> histogram = new Histogram<Long>();
//            Collection<Integer> localPartitions = hstore_site.getLocalPartitionIds(); 
//            TransactionQueueManager manager = hstore_site.getTransactionQueueManager();
//            for (Integer p : localPartitions) {
//                Long txn_id = manager.getCurrentTransaction(p);
//                if (txn_id != null) histogram.put(txn_id);
//            } // FOR
//            if (histogram.isEmpty()) break;
//            for (Long txn_id : histogram.values()) {
//                if (histogram.get(txn_id) == localPartitions.size()) continue;
//                
//                Map<String, String> m = new LinkedHashMap<String, String>();
//                m.put("TxnId", "#" + txn_id);
//                for (Integer p : hstore_site.getLocalPartitionIds()) {
//                    Long cur_id = manager.getCurrentTransaction(p);
//                    String status = "MISSING";
//                    if (txn_id == cur_id) {
//                        status = "READY";
//                    } else if (manager.getQueue(p).contains(txn_id)) {
//                        status = "QUEUED";
//                        // status += " / " + manager.getQueue(p); 
//                    }
//                    status += " / " + cur_id;
//                    m.put(String.format("  [%02d]", p), status);
//                } // FOR
//                LOG.info(manager.getClass().getSimpleName() + " Status:\n" + StringUtil.formatMaps(m));
//            } // FOR
//            LOG.info("Checking queue again...");
//            manager.checkQueues();
//            break;
//        } // FOR
        
//        if (hstore_conf.site.txn_profiling) {
//            String csv = this.txnProfileCSV();
//            if (csv != null) System.out.println(csv);
//        }
        
//        for (ExecutionSite es : this.executors.values()) {
//            TransactionEstimator te = es.getTransactionEstimator();
//            ProfileMeasurement pm = te.CONSUME;
//            System.out.println(String.format("[%02d] CONSUME %.2fms total / %.2fms avg / %d calls",
//                                              es.getPartitionId(), pm.getTotalThinkTimeMS(), pm.getAverageThinkTimeMS(), pm.getInvocations()));
//            pm = te.CACHE;
//            System.out.println(String.format("     CACHE %.2fms total / %.2fms avg / %d calls",
//                                             pm.getTotalThinkTimeMS(), pm.getAverageThinkTimeMS(), pm.getInvocations()));
//            System.out.println(String.format("     ATTEMPTS %d / SUCCESS %d", te.batch_cache_attempts.get(), te.batch_cache_success.get())); 
//        }
        if (this.self != null) this.self.interrupt();
    }
    
    @Override
    public boolean isShuttingDown() {
        return this.hstore_site.isShuttingDown();
    }
} // END CLASS