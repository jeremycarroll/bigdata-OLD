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
package com.bigdata.rdf.rio;

import org.openrdf.model.Resource;
import org.openrdf.model.Statement;
import org.openrdf.model.URI;
import org.openrdf.model.Value;
import org.openrdf.rio.StatementHandler;

/**
 * Statement handler for the RIO RDF Parser that writes on a
 * {@link StatementBuffer}.
 * 
 * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
 * @version $Id$
 */
public class PresortRioLoader extends BasicRioLoader implements IRioLoader, StatementHandler
{

    /**
     * Used to buffer RDF {@link Value}s and {@link Statement}s emitted by
     * the RDF parser.
     */
    final IStatementBuffer buffer;

    /**
     * Sets up parser to load RDF.
     * 
     * @param buffer
     *            The buffer used to collect, sort, and write statements onto
     *            the database.
     */
    public PresortRioLoader(IStatementBuffer buffer) {

        assert buffer != null;
                
        this.buffer = buffer;
        
    }
        
    /**
     * bulk insert the buffered data into the store.
     */
    protected void success() {

        if(buffer != null) {
            
            buffer.flush();
            
        }

    }

    // Let the caller clear the buffer!!!
//    /**
//     * Clear the buffer.
//     */
//    protected void cleanUp() {
//
//        buffer.clear();
//
//    }

    public StatementHandler newStatementHandler() {
        
        return this;
        
    }

    public void handleStatement( Resource s, URI p, Value o ) {

        // buffer the write (handles overflow).
        buffer.add(s, p, o);

        stmtsAdded++;
        
        if ( stmtsAdded % 100000 == 0 ) {
            
            notifyListeners();
            
        }
        
    }
    
}
