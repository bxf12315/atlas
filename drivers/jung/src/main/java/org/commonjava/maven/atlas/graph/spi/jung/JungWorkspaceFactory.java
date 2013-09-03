package org.commonjava.maven.atlas.graph.spi.jung;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.commonjava.maven.atlas.graph.spi.GraphDriverException;
import org.commonjava.maven.atlas.graph.spi.GraphWorkspaceFactory;
import org.commonjava.maven.atlas.graph.workspace.GraphWorkspace;
import org.commonjava.maven.atlas.graph.workspace.GraphWorkspaceConfiguration;

public class JungWorkspaceFactory
    implements GraphWorkspaceFactory
{

    private final Map<String, GraphWorkspace> workspaces = new HashMap<String, GraphWorkspace>();

    @Override
    public GraphWorkspace createWorkspace( final GraphWorkspaceConfiguration config )
        throws GraphDriverException
    {
        final GraphWorkspace ws = new GraphWorkspace( Long.toString( System.currentTimeMillis() ), config, new JungEGraphDriver() );
        workspaces.put( ws.getId(), ws );
        return ws;
    }

    @Override
    public boolean deleteWorkspace( final String id )
    {
        return workspaces.remove( id ) != null;
    }

    @Override
    public void storeWorkspace( final GraphWorkspace workspace )
        throws GraphDriverException
    {
        // passed by reference, so automatically updated.
    }

    @Override
    public GraphWorkspace loadWorkspace( final String id )
        throws GraphDriverException
    {
        return workspaces.get( id );
    }

    @Override
    public Set<GraphWorkspace> loadAllWorkspaces()
    {
        return new HashSet<GraphWorkspace>( workspaces.values() );
    }

}
