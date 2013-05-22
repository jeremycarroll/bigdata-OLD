/**

Copyright (C) SYSTAP, LLC 2006-2010.  All rights reserved.

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
package com.bigdata.ha.msg;

import java.util.concurrent.TimeUnit;

/**
 * Message requesting a global write lock.
 * 
 * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
 * @see https://sourceforge.net/apps/trac/bigdata/ticket/566 ( Concurrent
 *      unisolated operations against multiple KBs on the same Journal)
 */
@Deprecated
public interface IHAGlobalWriteLockRequest extends IHAMessage {

    /**
     * The maximum amount of time to wait for the lock.
     */
    long getLockWaitTimeout();

    /**
     * The units for the timeout.
     */
    TimeUnit getLockWaitUnits();
    
    /**
     * The maximum amount of time to hold the lock.
     */
    long getLockHoldTimeout();

    /**
     * The units for the timeout.
     */
    TimeUnit getLockHoldUnits();
    
}
