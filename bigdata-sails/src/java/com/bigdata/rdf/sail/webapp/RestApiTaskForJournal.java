/*

 Copyright (C) SYSTAP, LLC 2006-2008.  All rights reserved.

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
package com.bigdata.rdf.sail.webapp;

import com.bigdata.journal.AbstractTask;
import com.bigdata.journal.IConcurrencyManager;
import com.bigdata.journal.Journal;

/**
 * Wrapper for a task to be executed on the {@link IConcurrencyManager} of a
 * {@link Journal}.
 * 
 * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
 * @param <T>
 * @see <a href="http://sourceforge.net/apps/trac/bigdata/ticket/753" > HA
 *      doLocalAbort() should interrupt NSS requests and AbstractTasks </a>
 * @see <a href="- http://sourceforge.net/apps/trac/bigdata/ticket/566" >
 *      Concurrent unisolated operations against multiple KBs </a>
 */
public class RestApiTaskForJournal<T> extends AbstractTask<T> {

    private final RestApiTask<T> delegate;

    public RestApiTaskForJournal(final IConcurrencyManager concurrencyManager,
            final long timestamp, final String[] resource,
            final RestApiTask<T> delegate) {

        super(concurrencyManager, timestamp, resource);

        this.delegate = delegate;

    }

    @Override
    protected T doTask() throws Exception {

        delegate.setIndexManager(getJournal());

        try {

            return delegate.call();

        } finally {

            delegate.clearIndexManager();

        }

    }

}