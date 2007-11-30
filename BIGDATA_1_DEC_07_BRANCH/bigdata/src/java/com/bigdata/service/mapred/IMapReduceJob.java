/*

Copyright (C) SYSTAP, LLC 2006-2007.  All rights reserved.

Contact:
     SYSTAP, LLC
     4501 Tower Road
     Greensboro, NC 27410
     licenses@bigdata.com

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; version 2 of the License.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA

*/
package com.bigdata.service.mapred;

import java.io.File;
import java.util.UUID;

/**
 * Interface for a map/reduce job.
 * 
 * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
 * @version $Id$
 */
public interface IMapReduceJob {

    /**
     * The UUID for this job.
     */
    public UUID getUUID();

    /**
     * The object that knows how to supply the master with the input sources
     * to be distributed among the map tasks.
     */
    public IMapSource getMapSource();

    /**
     * The maximum parallism (M) for the map operation. The master will attempt
     * to run no more than this many concurrent map tasks. The actual
     * parallelism is limited by the load and capacity of the available
     * {@link MapService}s.
     */
    public int getMapParallelism();

    /**
     * The #of reduce partitions (N) (the fan-in for the reduce operation).
     * <p>
     * Note: This is also the maximum parallelism for the reduce operation, but
     * that could be decoupled from the reduce fan-in. The master will attempt
     * to run no more than this many concurrent reduce tasks. The actual
     * parallelism is limited by the load and capacity of the available
     * {@link ReduceService}s.
     */
    public int getReducePartitionCount();

    /**
     * The maximum #of map tasks to execute (0L for no limit) - this property
     * may be used for debugging by limiting the #of inputs to the map
     * operation.
     */
    public long getMaxMapTasks();

    /**
     * When non-zero, the timeout for a map task (milliseconds).
     */
    public long getMapTaskTimeout();
    
    /**
     * When non-zero, the timeout for a reduce task (milliseconds).
     */
    public long getReduceTaskTimeout();
    
    /**
     * The maximum #of times a map task may be retried (zero disables retry).
     */
    public int getMaxMapTaskRetry();
    
    /**
     * The maximum #of times a reduce task may be retried (zero disables retry).
     */
    public int getMaxReduceTaskRetry();
    
    /**
     * The hash function used to assign key-value pairs generated by a map
     * task to a reduce tasks.
     */
    public IHashFunction getHashFunction();

    /**
     * The map task to be executed.
     * 
     * @param source
     *            The source for the map task. This is commonly a {@link File}
     *            in a networked file system but other kinds of sources may be
     *            supported.
     */
    public IMapTask getMapTask(Object source);

    /**
     * Used to re-execute a map task. This produces a new map task instance with
     * the same task UUID.
     * 
     * @param uuid
     *            The task identifier.
     * @param source
     *            The source for the map task. This is commonly a {@link File}
     *            in a networked file system but other kinds of sources may be
     *            supported.
     */
    public IMapTask getMapTask(UUID uuid, Object source);

    /**
     * Used to (re-)execute a reduce task. This produces a new reduce task
     * instance with the same task UUID.
     * 
     * @param uuid
     *            The task identifier. This is assigned when the map/reduce
     *            job starts.
     * @param dataService
     *            The data service from which the reduce task will read its
     *            data. This is also assigned when the map/reduce task
     *            starts.
     */
    public IReduceTask getReduceTask(UUID uuid, UUID dataService);

}
