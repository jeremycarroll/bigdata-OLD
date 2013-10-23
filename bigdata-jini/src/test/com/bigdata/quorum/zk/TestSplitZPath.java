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
package com.bigdata.quorum.zk;

import cern.colt.Arrays;
import junit.framework.TestCase2;

public class TestSplitZPath extends TestCase2 {

    public TestSplitZPath() {
        
    }
    
    public TestSplitZPath(final String name) {
        super(name);
    }

    public void test_splitZPath() {
        
        final String zpath = "/fedname/serviceType/logicalServiceId/";
        
        final String [] a = zpath.split("/");
        
        if (log.isInfoEnabled())
            log.info(Arrays.toString(a));

        assertEquals("", a[0]);
        assertEquals("fedname", a[1]);
        assertEquals("serviceType", a[2]);
        assertEquals("logicalServiceId", a[3]);
        assertEquals(4, a.length);

    }
    
}
