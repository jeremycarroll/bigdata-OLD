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
 * Created on Feb 7, 2007
 */

package com.bigdata.btree;

/**
 * <p>
 * Interface for non-batch operations on a B+-Tree mapping arbitrary non-null
 * keys to arbitrary values.
 * </p>
 * 
 * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
 * @version $Id$
 * 
 * @todo re-define this interface for byte[] keys
 * 
 * @see UnicodeKeyBuilder, which may be used to encode one or more primitive data type
 *      values or Unicode strings into a variable length unsigned byte[] key.
 * 
 * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
 * @version $Id$
 */
public interface ISimpleBTree extends IRangeQuery {

    /**
     * Insert or update a value under the key.
     * 
     * @param key
     *            The key. 
     * @param value
     *            The value (may be null).
     * 
     * @return The previous value under that key or <code>null</code> if the
     *         key was not found.
     */
    public Object insert(Object key, Object value);
    
    /**
     * Lookup a value for a key.
     * 
     * @return The value or <code>null</code> if there is no entry for that
     *         key.
     */
    public Object lookup(Object key);

    /**
     * Return true iff there is an entry for the key.
     * 
     * @param key
     *            The key.
     * 
     * @return True if the btree contains an entry for that key.
     */
    public boolean contains(byte[] key);
        
    /**
     * Remove the key and its associated value.
     * 
     * @param key
     *            The key.
     * 
     * @return The value stored under that key or <code>null</code> if the key
     *         was not found.
     */
    public Object remove(Object key);

}
