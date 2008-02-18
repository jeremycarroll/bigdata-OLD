/**

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
/*
 * Created on Apr 23, 2007
 */

package com.bigdata.mdi;

import java.util.UUID;

import com.bigdata.btree.IndexSegment;
import com.bigdata.journal.Journal;

/**
 * A description of the metadata state for a partition of a scale-out index.
 * 
 * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
 * @version $Id$
 */
public interface IPartitionMetadata {

    /**
     * The unique partition identifier.
     */
    public int getPartitionId();

    /**
     * The ordered list of data services on which data for this partition will
     * be written and from which data for this partition may be read.
     */
    public UUID[] getDataServices();
    
    /**
     * Zero or more files containing {@link Journal}s or {@link IndexSegment}s
     * holding live data for this partition. The entries in the array reflect
     * the creation time of the resources. The earliest resource is listed
     * first. The most recently created resource is listed last.
     * <p>
     * Note: Only the {@link ResourceState#Live} resources must be read in order
     * to provide a consistent view of the data for the index partition.
     * {@link ResourceState#Dead} resources will eventually be scheduled for
     * restart-safe deletion.
     * 
     * @see ResourceState
     */
    public IResourceMetadata[] getResources();

}
