package experiments;

import ralph.RalphGlobals;

import pronghorn.ft_ops.TCPVersionManager;
import pronghorn.switch_factory.NoLogVersionFactory;
import pronghorn.switch_factory.IVersionListenerFactory;

public enum VersionListenerFactoryArgs
{
    NO_LISTENER_FACTORY ("no-listener-factory"),
    SWITCH_LISTENER_FACTORY ("switch-listener-factory");

    final static String DEFAULT_TCP_VERSION_SERVER_IP_ADDRESS = "0.0.0.0";
    final static int DEFAULT_TCP_VERSION_SERVER_PORT = 59025;
    
    
    public final String name;
    private VersionListenerFactoryArgs(String _name)
    {
        name = _name;
    }

    public static IVersionListenerFactory produce_factory(
        String factory_name,RalphGlobals ralph_globals)
    {
        if (factory_name.equals(NO_LISTENER_FACTORY.name))
        {
            return new NoLogVersionFactory();
        }
        else if (factory_name.equals(SWITCH_LISTENER_FACTORY.name))
        {
            return new TCPVersionManager(
                DEFAULT_TCP_VERSION_SERVER_IP_ADDRESS,
                DEFAULT_TCP_VERSION_SERVER_PORT,ralph_globals);
        }
        
        return null;
    }
}