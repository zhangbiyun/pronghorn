package experiments;

import single_host.SingleHostFloodlightShim;
import single_host.SingleHostSwitchStatusHandler;
import single_host.JavaPronghornInstance.PronghornInstance;
import single_host.JavaPronghornConnection.PronghornConnection;
import RalphConnObj.SingleSideConnection;
import ralph.RalphGlobals;
import ralph.NonAtomicInternalList;
import pronghorn.FloodlightRoutingTableToHardware;
import java.lang.Thread;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import experiments.Util.HostPortPair;
import experiments.Util;
import experiments.Util.LatencyThread;

import ralph.RalphGlobals;
import ralph.EndpointConstructorObj;
import ralph.Endpoint;
import ralph.Ralph;


public class MultiControllerLatency
{
    public static final int CHILDREN_TO_CONTACT_HOST_PORT_CSV_ARG_INDEX = 0;
    public static final int PORT_TO_LISTEN_FOR_CONNECTIONS_ON_ARG_INDEX = 1;
    public static final int NUMBER_OPS_TO_RUN_ARG_INDEX = 2;
    public static final int OUTPUT_FILENAME_ARG_INDEX = 3;

    
    public static PronghornInstance prong = null;
    
    // wait this long for pronghorn to add all switches
    public static final int SETTLING_TIME_WAIT = 5000;

    public final static RalphGlobals ralph_globals = new RalphGlobals();
    
    public static void main (String[] args)
    {
        /* Grab arguments */
        if (args.length != 4)
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
        
        int num_ops_to_run =
            Integer.parseInt(args[NUMBER_OPS_TO_RUN_ARG_INDEX]);

        int num_threads = 1;


        String output_filename = args[OUTPUT_FILENAME_ARG_INDEX];

        
        /* Start up pronghorn */
        try {
            prong = new PronghornInstance(
                ralph_globals,
                "", new SingleSideConnection());
        } catch (Exception _ex) {
            System.out.println("\n\nERROR CONNECTING\n\n");
            return;
        }


        SingleHostFloodlightShim shim = new SingleHostFloodlightShim();
        SingleHostSwitchStatusHandler switch_status_handler =
            new SingleHostSwitchStatusHandler(
                shim,prong,
                FloodlightRoutingTableToHardware.FLOODLIGHT_ROUTING_TABLE_TO_HARDWARE_FACTORY);

        shim.subscribe_switch_status_handler(switch_status_handler);
        shim.start();


        // start listening for connections from parents
        Ralph.tcp_accept(
            new DummyConnectionConstructor(), "0.0.0.0", port_to_listen_on,ralph_globals);
        

        // now actually try to conect to parent
        for (HostPortPair hpp : children_to_contact_hpp)
        {
            PronghornConnection connection = null;
            try {
                System.out.println("\nConnecting to " + hpp.host + "  " + hpp.port);
                connection = (PronghornConnection)Ralph.tcp_connect(
                    new DummyConnectionConstructor(), hpp.host, hpp.port,ralph_globals);
                connection.set_service(prong);
                prong.add_child_connection(connection);
            } catch(Exception e) {
                e.printStackTrace();
                assert(false);
            }
        }

        
        // wait for first switch to connect
        Util.wait_on_switches(prong);
        // what's the first switch's id.
        String switch_id = Util.first_connected_switch_id(prong);
        
        if (num_ops_to_run != 0)
        {
            List<LatencyThread> all_threads = new ArrayList<LatencyThread>();
            for (int i = 0; i < num_threads; ++i)
                all_threads.add(new LatencyThread(prong,switch_id,num_ops_to_run));

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

        // OUTPUT_FILENAME_ARG_INDEX
        usage_string += "\n\t<String> : output filename\n";

        System.out.println(usage_string);
    }


    private static class DummyConnectionConstructor implements EndpointConstructorObj
    {
        @Override
        public Endpoint construct(
            RalphGlobals globals, String host_uuid,
            RalphConnObj.ConnectionObj conn_obj)
        {
            PronghornConnection to_return = null;
            System.out.println("\nBuilt a connection\n\n");
            try {
                to_return = new PronghornConnection(ralph_globals,host_uuid,conn_obj);
                to_return.set_service(prong);
                // prong.add_child_connection(to_return);
            } catch (Exception _ex) {
                _ex.printStackTrace();
                assert(false);
            }
            return to_return;
        }
    }
}
