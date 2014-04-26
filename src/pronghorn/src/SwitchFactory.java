package pronghorn;

import pronghorn.FTable;
import pronghorn.FTable._InternalFlowTableEntry;
import pronghorn.SwitchJava._InternalSwitch;
import pronghorn.SwitchDeltaJava._InternalSwitchDelta;
import pronghorn.SwitchDeltaJava.SwitchDelta;
import pronghorn.PortStatsJava._InternalPortStats;

import ralph.Variables.NonAtomicTextVariable;
import ralph.Variables.AtomicNumberVariable;
import ralph.Variables.AtomicListVariable;
import ralph.RalphGlobals;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.Future;
import java.util.List;
import java.io.IOException;
import java.util.concurrent.ExecutionException;

import org.openflow.protocol.statistics.OFStatistics;
import org.openflow.protocol.statistics.OFPortStatisticsReply;

public class SwitchFactory
{
    /**
       Each factory has a unique prefix that it can use to generate
       ids for each switch.  Format of prefix can be:
          a
          a:b
          a:b:c
          
       etc.  Importantly, should not contain any
       SWITCH_PREFIX_TO_ID_SEPARATORs, because will append
       SWITCH_PREFIX_TO_ID_SEPARATOR<int> to end of the id to generate
       unique switch ids.
     */
    private String factory_switch_prefix;
    private AtomicInteger atomic_switch_id = new AtomicInteger(0);
    private final static String SWITCH_PREFIX_TO_ID_SEPARATOR = ".";


    /**
       Use a separate thread for each switch to send message to switch
       requesting it to apply some change (and receive response).
       These threads are pulled in the executor service
       hardware_pushing_service.
     */
    private final static int PUSH_CHANGE_TO_HARDWARE_THREAD_POOL_SIZE = 150;
    private final static ExecutorService hardware_pushing_service = 
        Executors.newFixedThreadPool(
            PUSH_CHANGE_TO_HARDWARE_THREAD_POOL_SIZE,
            new ThreadFactory()
            {
                // each thread created is a daemon
                public Thread newThread(Runnable r)
                {
                    Thread t=new Thread(r);
                    t.setDaemon(true);
                    return t;
                }
            });

    private RalphGlobals ralph_globals = null;
    private final boolean speculate;
    /**
       @param {int} _collect_statistics_period_ms.  If period < 0, then
       never collect statistics.
     */
    private final int collect_statistics_period_ms;

    public SwitchFactory(
        RalphGlobals _ralph_globals,boolean _speculate,
        int _collect_statistics_period_ms)
    {
        ralph_globals = _ralph_globals;
        speculate = _speculate;
        collect_statistics_period_ms = _collect_statistics_period_ms;
    }

    /**
       @param _factory_switch_prefix --- @see doc for
       factory_switch_prefix.
     */
    public void init(String _factory_switch_prefix)
    {
        factory_switch_prefix = _factory_switch_prefix;
    }

    /**
       Do not actually push changes to flow table to hardware, just
       say that we did.
     */
    public PronghornInternalSwitch construct(
        double available_capacity,StatisticsUpdater stats_updater)
    {
        return construct(available_capacity,null,null,"",stats_updater);
    }

    /**
       @param to_handle_pushing_changes --- Can be null, in which
       case, will simulate pushing changes to hardware.  (Ie., will
       always return message that says it did push changes to hardware
       without doing any work.)  
     */
    public PronghornInternalSwitch construct(
        double available_capacity,
        FlowTableToHardware to_handle_pushing_changes,
        ShimInterface shim, String floodlight_switch_id,
        StatisticsUpdater stats_updater)
    {
        // create a new switch id
        Integer factory_local_unique_id = atomic_switch_id.addAndGet(1);
        String new_switch_id =
            factory_switch_prefix + SWITCH_PREFIX_TO_ID_SEPARATOR +
            factory_local_unique_id.toString();

        // Create the switch delta struct
        _InternalSwitchDelta switch_delta =
            new _InternalSwitchDelta(ralph_globals);

        // Create the new switch and return it
        PronghornInternalSwitch internal_switch =
            new PronghornInternalSwitch(
                ralph_globals,new_switch_id,available_capacity,
                switch_delta,collect_statistics_period_ms,shim,
                floodlight_switch_id,stats_updater);

        SwitchSpeculateListener switch_speculate_listener =
            new SwitchSpeculateListener();
        
        // override switch_lock variable: switch_lock variable serves
        // as a guard for both port_deltas and ft_deltas.
        InternalPronghornSwitchGuard switch_guard_num_var =
            new InternalPronghornSwitchGuard(
                ralph_globals,new_switch_id,speculate,
                to_handle_pushing_changes,
                new DeltaListStateSupplier(switch_delta),
                switch_speculate_listener);

        switch_delta.switch_lock = switch_guard_num_var;
        switch_speculate_listener.init(
            switch_delta,internal_switch,switch_guard_num_var);
        
        return internal_switch;
    }

    
    public class PronghornInternalSwitch
        extends _InternalSwitch implements Runnable
    {
        public String ralph_internal_switch_id;
        private final ShimInterface shim;

        private final String floodlight_switch_id;
        
        /**
           @param {int} collect_statistics_period_ms.  If period < 0, then
           never collect statistics.
         */
        private final int collect_statistics_period_ms;
        /**
           Used to actually update switch statistics.
         */
        private final StatisticsUpdater stats_updater;
        
        public PronghornInternalSwitch(
            RalphGlobals ralph_globals,String _ralph_internal_switch_id,
            double _available_capacity,
            _InternalSwitchDelta internal_switch_delta,
            int _collect_statistics_period_ms, ShimInterface _shim,
            String _floodlight_switch_id,StatisticsUpdater _stats_updater)
        {
            super(ralph_globals);
            ralph_internal_switch_id = _ralph_internal_switch_id;
            delta = new SwitchDelta (
                false,internal_switch_delta,ralph_globals);
            switch_id = new NonAtomicTextVariable(
                false,_ralph_internal_switch_id,ralph_globals);
            available_capacity =
                new AtomicNumberVariable(
                    false,_available_capacity,ralph_globals);

            shim = _shim;
            floodlight_switch_id = _floodlight_switch_id;
            collect_statistics_period_ms = _collect_statistics_period_ms;
            stats_updater = _stats_updater;
            
            // shim == null if simulating hardware
            if ((collect_statistics_period_ms > 0) && (shim != null)) 
            {
                // update thread to periodically poll for switch
                // statistics.
                Thread t = new Thread(this);
                t.setDaemon(true);
                t.start();
            }
        }

        /**
           Periodically check the switch for statistics.
         */
        public void run()
        {
            while(true)
            {
                try
                {
                    // FIXME: decide whether to collect any additional
                    // statistics, eg., flow stats.
                    
                    // handle port stats
                    Future<List<OFStatistics>> future_stats =
                        shim.get_port_stats(floodlight_switch_id);
                    List<OFStatistics> port_stats_list = future_stats.get();
                    for (OFStatistics of_stat : port_stats_list)
                    {
                        OFPortStatisticsReply of_port_stat =
                            (OFPortStatisticsReply) of_stat;
                        _InternalPortStats port_stats =
                            OFStatsMessageParser.of_port_stats_to_ralph(
                                of_port_stat,ralph_globals);

                        Double port_num =
                            new Double(of_port_stat.getPortNumber());
                        stats_updater.update_port_stats(
                            ralph_internal_switch_id,port_num,port_stats);
                    }
                }
                catch (IOException ex)
                {
                    // switch failed.  stop this thread.  no longer
                    // need to collect stats for switch.
                    break;
                }
                catch (InterruptedException ex)
                {
                    // retry on the flooldight controller
                    continue;
                }
                catch (ExecutionException ex)
                {
                    // retry on the flooldight controller
                    continue;
                }

                // wait some time before polling again for more updates.
                try
                {
                    Thread.sleep(collect_statistics_period_ms);
                }
                catch (InterruptedException ex)
                {
                    ex.printStackTrace();
                    System.out.println(
                        "\nShould never receive interrupted exception when " +
                        "polling for stats.\n");
                    assert(false);
                }
            }
        }
    }
}