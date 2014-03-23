/*******************************************************************************
 * Copyright (C) 2014 John Casey.
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 ******************************************************************************/
package org.commonjava.maven.atlas.graph.spi.neo4j.traverse;

import static org.commonjava.maven.atlas.graph.spi.neo4j.io.Conversions.toProjectRelationship;
import static org.commonjava.maven.atlas.graph.spi.neo4j.traverse.TraversalUtils.accepted;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.commonjava.maven.atlas.graph.model.GraphPathInfo;
import org.commonjava.maven.atlas.graph.model.GraphView;
import org.commonjava.maven.atlas.graph.rel.ProjectRelationship;
import org.commonjava.maven.atlas.graph.spi.neo4j.AbstractNeo4JEGraphDriver;
import org.commonjava.maven.atlas.graph.spi.neo4j.CyclePath;
import org.commonjava.maven.atlas.graph.spi.neo4j.io.ConversionCache;
import org.commonjava.maven.atlas.graph.spi.neo4j.io.Conversions;
import org.commonjava.maven.atlas.graph.spi.neo4j.model.Neo4jGraphPath;
import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Path;
import org.neo4j.graphdb.PathExpander;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.traversal.BranchState;
import org.neo4j.graphdb.traversal.Evaluation;
import org.neo4j.graphdb.traversal.Evaluator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AtlasCollector<STATE>
    implements Evaluator, PathExpander<STATE>
{

    private final Logger logger = LoggerFactory.getLogger( getClass() );

    private Direction direction = Direction.OUTGOING;

    private final Set<Node> startNodes;

    private GraphView view;

    private Map<Neo4jGraphPath, GraphPathInfo> pathInfos = new HashMap<Neo4jGraphPath, GraphPathInfo>();

    private boolean avoidCycles = true;

    private ConversionCache cache = new ConversionCache();

    private TraverseVisitor visitor;

    public AtlasCollector( final TraverseVisitor visitor, final Node start, final GraphView view )
    {
        this( visitor, Collections.singleton( start ), view );
    }

    public AtlasCollector( final TraverseVisitor visitor, final Set<Node> startNodes, final GraphView view )
    {
        this.visitor = visitor;
        visitor.configure( this );

        this.startNodes = startNodes;

        this.view = view;
    }

    public AtlasCollector( final TraverseVisitor visitor, final Set<Node> startNodes, final GraphView view, final Direction direction )
    {
        this( visitor, startNodes, view );
        this.direction = direction;
    }

    public final void setPathInfoMap( final Map<Neo4jGraphPath, GraphPathInfo> pathInfos )
    {
        if ( pathInfos != null )
        {
            this.pathInfos = pathInfos;
        }
    }

    public void setConversionCache( final ConversionCache cache )
    {
        this.cache = cache;
    }

    public Map<Neo4jGraphPath, GraphPathInfo> getPathInfoMap()
    {
        return pathInfos;
    }

    @Override
    @SuppressWarnings( "rawtypes" )
    public final Iterable<Relationship> expand( final Path path, final BranchState state )
    {
        if ( !visitor.isEnabledFor( path ) )
        {
            logger.debug( "Disabled, NOT expanding: {}", path );
            return Collections.emptySet();
        }

        if ( !startNodes.isEmpty() )
        {
            final Node startNode = path.startNode();
            if ( !startNodes.contains( startNode ) )
            {
                logger.debug( "Rejecting path; it does not start with one of our roots:\n\t{}", path );
                return Collections.emptySet();
            }

            if ( visitor.shouldAvoidRedundantPaths() )
            {

            }
            for ( final Node node : path.nodes() )
            {
                if ( !node.equals( startNode ) && startNodes.contains( node ) )
                {
                    // TODO: is this safe to discard? I think so, except possibly in rare cases...
                    logger.debug( "Redundant path detected; another start node is contained in path intermediary nodes." );
                }
            }
        }

        Neo4jGraphPath graphPath = new Neo4jGraphPath( path );
        graphPath = visitor.spliceGraphPathFor( graphPath, path );

        GraphPathInfo pathInfo = pathInfos.remove( graphPath );
        logger.debug( "Retrieved pathInfo for {} of: {}", graphPath, pathInfo );

        if ( pathInfo == null )
        {
            if ( path.lastRelationship() == null )
            {
                pathInfo = visitor.initializeGraphPathInfoFor( path, graphPath, view );
                if ( pathInfo == null )
                {
                    logger.debug( "Failed to initialize path info for: {}", path );
                    return Collections.emptySet();
                }
                else
                {
                    logger.debug( "Initialized pathInfo to: {} for path: {}", pathInfo, path );
                }
            }
            else
            {
                logger.debug( "path has at least one relationship but no associated pathInfo: {}", path );
                return Collections.emptySet();
            }
        }

        pathInfo = visitor.spliceGraphPathInfoFor( pathInfo, graphPath, path );

        logger.info( "Checking hasSeen for graphPath: {} with pathInfo: {} (actual path: {})", graphPath, pathInfo, path );
        if ( visitor.hasSeen( graphPath, pathInfo ) )
        {
            logger.debug( "Already seen: {} (path: {})", graphPath, path );
            return Collections.emptySet();
        }

        if ( !avoidCycles )
        {
            final List<Long> rids = new ArrayList<Long>();
            final List<Long> starts = new ArrayList<Long>();
            for ( final Relationship pathR : path.relationships() )
            {
                rids.add( pathR.getId() );

                final long sid = pathR.getStartNode()
                                      .getId();

                final long eid = pathR.getEndNode()
                                      .getId();

                final int idx = starts.indexOf( eid );
                if ( idx > -1 )
                {
                    final CyclePath cp = new CyclePath( rids.subList( idx, rids.size() - 1 ) );
                    logger.debug( "Detected cycle in progress for path: {} at relationship: {}\n  Cycle path is: {}", path, pathR, cp );

                    visitor.cycleDetected( cp, pathR );
                    return Collections.emptySet();
                }

                starts.add( sid );
            }
        }

        if ( returnChildren( path, graphPath, pathInfo ) )
        {

            //            final ProjectRelationshipFilter nextFilter = pathInfo.getFilter();
            //            log( "Implementation says return the children of: {}\n  lastRel={}\n  nextFilter={}\n\n",
            //                 path.endNode()
            //                     .hasProperty( GAV ) ? path.endNode()
            //                                               .getProperty( GAV ) : "Unknown", path.lastRelationship(), nextFilter );

            final Set<Relationship> nextRelationships = new HashSet<Relationship>();

            logger.debug( "Getting relationships from node: {} ({}) in direction: {} (path: {})", path.endNode(),
                          path.endNode()
                              .getProperty( Conversions.GAV ), direction, path );
            final Iterable<Relationship> relationships = path.endNode()
                                                             .getRelationships( direction );

            //            logger.info( "{} Determining which of {} child relationships to expand traversal into for: {}\n{}", getClass().getName(), path.length(),
            //                         path.endNode()
            //                             .hasProperty( GAV ) ? path.endNode()
            //                                                       .getProperty( GAV ) : "Unknown", new JoinString( "\n  ", Thread.currentThread()
            //                                                                                                                      .getStackTrace() ) );

            for ( Relationship r : relationships )
            {
                if ( avoidCycles && Conversions.getBooleanProperty( Conversions.CYCLES_INJECTED, r, false ) )
                {
                    logger.debug( "Detected marked cycle from path: {} in child relationship: {}", path, r );
                    continue;
                }

                final AbstractNeo4JEGraphDriver db = (AbstractNeo4JEGraphDriver) view.getDatabase();
                logger.info( "Using database: {} to check selection of: {} in path: {}", db, wrap( r ), path );

                final Relationship selected = db == null ? null : db.select( r, view, pathInfo, graphPath );
                if ( selected == null )
                {
                    logger.debug( "selection failed for: {} at {}. Likely, this is filter rejection from: {}", r, graphPath, pathInfo );
                    continue;
                }

                // if no selection happened and r is a selection-only relationship, skip it.
                if ( selected == r && Conversions.getBooleanProperty( Conversions.SELECTION, r, false ) )
                {
                    logger.debug( "{} is NOT the result of selection, yet it is marked as a selection relationship. Path: {}", r, path );
                    continue;
                }

                if ( !accepted( selected, view, cache ) )
                {
                    logger.debug( "{} NOT accepted, likely due to incompatible POM location or source URI. Path: {}", r, path );
                    continue;
                }

                if ( selected != null )
                {
                    r = selected;
                }

                logger.debug( "+= {}", wrap( r ) );
                nextRelationships.add( r );

                final ProjectRelationship<?> rel = toProjectRelationship( r, cache );

                final Neo4jGraphPath nextPath = new Neo4jGraphPath( graphPath, r.getId() );
                GraphPathInfo nextPathInfo = pathInfo.getChildPathInfo( rel );

                // allow for cases where we're bootstrapping the pathInfos map before the traverse starts.
                if ( path.lastRelationship() == null && pathInfos.containsKey( nextPath ) )
                {
                    nextPathInfo = pathInfos.get( nextPath );
                }

                pathInfos.put( nextPath, nextPathInfo );

                visitor.includingChild( r, nextPath, nextPathInfo, path );
            }

            return nextRelationships;
        }

        logger.debug( "children not being returned for: {}", path );
        return Collections.emptySet();
    }

    public boolean returnChildren( final Path path, final Neo4jGraphPath graphPath, final GraphPathInfo pathInfo )
    {
        // if there's a GraphPathInfo mapped for this path, then it was accepted during expansion.
        return visitor.includeChildren( path, graphPath, pathInfo );
    }

    private Object wrap( final Relationship r )
    {
        return new Object()
        {
            @Override
            public String toString()
            {
                return r + " " + String.valueOf( toProjectRelationship( r, cache ) );
            }
        };
    }

    @Override
    public final Evaluation evaluate( final Path path )
    {
        return Evaluation.INCLUDE_AND_CONTINUE;
    }

    public boolean isAvoidCycles()
    {
        return avoidCycles;
    }

    public void setAvoidCycles( final boolean avoidCycles )
    {
        this.avoidCycles = avoidCycles;
    }

    @Override
    public PathExpander<STATE> reverse()
    {
        final AtlasCollector<STATE> collector = new AtlasCollector<STATE>( visitor, startNodes, view, direction.reverse() );
        collector.setPathInfoMap( pathInfos );
        collector.setAvoidCycles( avoidCycles );
        collector.setConversionCache( cache );

        return collector;
    }

}
