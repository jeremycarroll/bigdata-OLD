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
package com.bigdata.rdf.sparql.ast.eval.update;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import com.bigdata.rdf.sail.tck.BigdataSPARQLUpdateTest;
import com.bigdata.rdf.sail.tck.BigdataSPARQLUpdateTest2;
import com.bigdata.rdf.sail.tck.BigdataSPARQLUpdateTxTest;
import com.bigdata.rdf.sail.tck.BigdataSPARQLUpdateTxTest2;
import com.bigdata.rdf.sparql.ast.QueryHints;

/**
 * Aggregates test suites into increasing dependency order.
 * 
 * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
 * @version $Id$
 */
public class TestAll extends TestCase {

    /**
     * 
     */
    public TestAll() {
    }

    /**
     * @param arg0
     */
    public TestAll(String arg0) {
        super(arg0);
    }

    /**
     * Returns a test that will run each of the implementation specific test
     * suites in turn.
     */
    public static Test suite()
    {

        final TestSuite suite = new TestSuite("SPARQL Update Evaluation");

        /*
         * Boot strapped test suite for core UPDATE functionality.
         */

        suite.addTestSuite(TestUpdateBootstrap.class);

        /*
         * The openrdf SPARQL UPDATE test suite.
         * 
         * Note: This test suite is for quads mode only. SPARQL UPDATE support
         * is also tested by the NSS test suite.
         */

        // Unisolated operations.
        suite.addTestSuite(BigdataSPARQLUpdateTest.class);

        // Fully isolated read/write operations.
        suite.addTestSuite(BigdataSPARQLUpdateTxTest.class);

        /*
         * TODO We should always run this test suite, not just when the solution
         * set cache is enabled.
         */
        if(QueryHints.DEFAULT_SOLUTION_SET_CACHE) {

            /*
             * The bigdata extensions to SPARQL UPDATE to support solution sets
             * as well as graphs.
             */
        
            // Unisolated operations.
            suite.addTestSuite(BigdataSPARQLUpdateTest2.class);

            // Fully isolated read/write operations.
            suite.addTestSuite(BigdataSPARQLUpdateTxTest2.class);
        
        }
        
        return suite;
        
    }
    
}