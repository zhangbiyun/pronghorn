package experiments;

import java.lang.Thread;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

import ralph.RalphGlobals;
import ralph.NonAtomicInternalList;
import ralph.RalphObject;
import ralph.Endpoint;
import ralph.Ralph;
import ralph.InternalServiceFactory;
import RalphDurability.IDurabilityContext;
import RalphDurability.DurabilityReplayContext;

import pronghorn.FloodlightShim;
import pronghorn.SwitchStatusHandler;
import pronghorn.InstanceJava.Instance;
import pronghorn.ft_ops.FloodlightFlowTableToHardware;

import experiments.Util.HostPortPair;
import experiments.Util;
import experiments.Util.LatencyThread;
import experiments.GetNumberSwitchesJava.GetNumberSwitches;
import experiments.MultiControllerTunnelsJava.MultiControllerTunnelsApp;


public class MultiControllerTunnels
{
    public static final int CHILDREN_TO_CONTACT_HOST_PORT_CSV_ARG_INDEX = 0;
    public static final int PORT_TO_LISTEN_FOR_CONNECTIONS_ON_ARG_INDEX = 1;
    public static final int NUMBER_OPS_TO_RUN_ARG_INDEX = 2;
    public static final int COLLECT_STATISTICS_ARG_INDEX = 3;
    public static final int OUTPUT_FILENAME_ARG_INDEX = 4;

    public static Instance prong = null;
    public static MultiControllerTunnelsApp mc_tunnels_app = null;
    public static GetNumberSwitches num_switches_app = null;

    // wait this long for pronghorn to add all switches
    public static final int SETTLING_TIME_WAIT = 5000;

    public static RalphGlobals ralph_globals;

    public static void main (String[] args)
    {
        /* Grab arguments */
        if (args.length != 5)
        {
            print_usage();
            return;
        }

        Set<HostPortPair> children_to_contact_hpp = null;
        if (! args[CHILDREN_TO_CONTACT_HOST_PORT_CSV_ARG_INDEX].equals("-1"))
            children_to_contact_hpp = Util.parse_csv_host_port_pairs(
                args[CHILDREN_TO_CONTACT_HOST_PORT_CSV_ARG_INDEX]);
        else
            children_to_contact_hpp = new HashSet<HostPortPair>();

        int port_to_listen_on =
            Integer.parseInt(args[PORT_TO_LISTEN_FOR_CONNECTIONS_ON_ARG_INDEX]);

        RalphGlobals.Parameters params = new RalphGlobals.Parameters();
        params.tcp_port_to_listen_for_connections_on = port_to_listen_on;
        ralph_globals = new RalphGlobals(params);

        int num_ops_to_run =
            Integer.parseInt(args[NUMBER_OPS_TO_RUN_ARG_INDEX]);

        int num_threads = 1;

        int collect_statistics_period_ms =
            Integer.parseInt(args[COLLECT_STATISTICS_ARG_INDEX]);

        String output_filename = args[OUTPUT_FILENAME_ARG_INDEX];

        /* Start up pronghorn */
        try
        {
            prong = Instance.create_single_sided(ralph_globals);
            prong.start();
            mc_tunnels_app =
                MultiControllerTunnelsApp.create_single_sided(ralph_globals);
            num_switches_app =
                GetNumberSwitches.create_single_sided(ralph_globals);

            prong.add_application(mc_tunnels_app);
            prong.add_application(num_switches_app);
        }
        catch (Exception _ex)
        {
            System.out.println("\n\nERROR CONNECTING\n\n");
            return;
        }


        FloodlightShim shim = new FloodlightShim();
        SwitchStatusHandler switch_status_handler =
            new SwitchStatusHandler(
                shim,prong,
                FloodlightFlowTableToHardware.FLOODLIGHT_FLOW_TABLE_TO_HARDWARE_FACTORY,
                true,collect_statistics_period_ms);

        shim.subscribe_switch_status_handler(switch_status_handler);
        shim.start();

        // now actually try to conect to parent
        for (HostPortPair hpp : children_to_contact_hpp)
        {
            try {
                System.out.println("\nConnecting to " + hpp.host + "  " + hpp.port);

                InternalServiceFactory factory =
                        new InternalServiceFactory(MultiControllerTunnelsApp.factory,
                                                   ralph_globals);
                mc_tunnels_app.install_remotes(factory);
            } catch(Exception e) {
                e.printStackTrace();
                assert(false);
            }
        }

        // wait for first switch to connect
        Util.wait_on_switches(num_switches_app);
        List<String> switch_id_list =
            Util.get_switch_id_list (num_switches_app);

        if (num_ops_to_run != 0)
        {
            List<LatencyThread> all_threads = new ArrayList<LatencyThread>();
            for (int i = 0; i < num_threads; ++i)
            {
                all_threads.add(
                    new LatencyThread(
                        mc_tunnels_app,num_ops_to_run,switch_id_list));
            }

            for (LatencyThread lt : all_threads)
                lt.start();

            // wait for all threads to finish and collect their results
            for (LatencyThread lt : all_threads)
            {
                try {
                    lt.join();
                } catch (Exception _ex) {
                    _ex.printStackTrace();
                    assert(false);
                    return;
                }
            }

            StringBuffer string_buffer = new StringBuffer();
            for (LatencyThread lt : all_threads)
                lt.write_times(string_buffer);
            Util.write_results_to_file(
                output_filename,string_buffer.toString());
        }

        // run indefinitely
        while (true)
        {
            try{
                Thread.sleep(1000);
            } catch (InterruptedException _ex) {
                _ex.printStackTrace();
                break;
            }
        }

        // actually tell shims to stop.
        shim.stop();
        Util.force_shutdown();
    }

    private static void print_usage()
    {
        String usage_string = "";

        // CHILDREN_TO_CONTACT_HOST_PORT_CSV_ARG_INDEX
        usage_string +=
            "\n\t<csv>: Children to contact host port csv.  Pronghorn ";
        usage_string += "controllers to connect to.  ";
        usage_string += "Format host:port,host:port\n";

        // PORT_TO_LISTEN_FOR_CONNECTIONS_ON_ARG_INDEX
        usage_string +=
            "\n\t<int>: Port to listen for connections on.\n";

        // NUMBER_OPS_TO_RUN_PER_EXPERIMENT_ARG_INDEX
        usage_string +=
            "\n\t<int>: Number ops to run per experiment\n";

        // COLLECT_STATISTICS_ARG_INDEX
        usage_string +=
            "\n\t<int> : period for collecting individual switch stastics " +
            "in ms.  < 0 if should not collect any statistics\n";

        // OUTPUT_FILENAME_ARG_INDEX
        usage_string += "\n\t<String> : output filename\n";

        System.out.println(usage_string);
    }
}
