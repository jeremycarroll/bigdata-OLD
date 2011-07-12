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
/*
 * Created on Jul 26, 2010
 */

package com.bigdata.rdf.model;

import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;

import junit.framework.TestCase2;

import org.openrdf.model.URI;
import org.openrdf.model.Value;
import org.openrdf.model.ValueFactory;

import com.bigdata.rdf.internal.DummyIV;
import com.bigdata.rdf.internal.IV;
import com.bigdata.rdf.internal.XSD;

/**
 * Unit tests for {@link BigdataValueFactoryImpl}.
 * 
 * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
 * @version $Id$
 */
public class TestFactory extends TestCase2 {

    /**
     * 
     */
    public TestFactory() {
    }

    /**
     * @param name
     */
    public TestFactory(String name) {
        super(name);
    }

    final BigdataValueFactory vf = BigdataValueFactoryImpl.getInstance(getName());

    public void test_create_literal_xsdInt() {
        
        final BigdataLiteral l1 = vf.createLiteral("12", XSD.INT);
    
        assertEquals(XSD.INT, l1.getDatatype());
        
        assertEquals(12, l1.intValue());
        
    }

    /**
     * Unit test verifies that the created URIs are canonical for well-known
     * XSD URIs.
     */
    public void test_create_xsdInt_canonical() {
        
		final BigdataURI v1 = vf.createURI(XSD.INT.stringValue());

		final BigdataURI v2 = vf.createURI(XSD.INT.stringValue());

		// verify the URI.
		assertEquals(v1.stringValue(),XSD.INT.stringValue());
		
		// verify the same reference (canonical).
		assertTrue(v1 == v2);
    
    }

    /**
     * Unit test for {@link ValueFactory#createLiteral(String, URI)} when the
     * datatype URI is <code>null</code>.
     * 
     * @see https://sourceforge.net/apps/trac/bigdata/ticket/226
     */
    public void test_create_literal_datatypeIsNull() {
        
        final BigdataLiteral l1 = vf.createLiteral("12", (URI) null);
        
        assertEquals(null, l1.getDatatype());

        assertEquals(12, l1.intValue());
        
    }

    /**
     * Unit test for {@link ValueFactory#createLiteral(XMLGregorianCalendar)}.
     * 
     * @see https://sourceforge.net/apps/trac/bigdata/ticket/117
     */
    public void test_gregorian() throws DatatypeConfigurationException {

        final XMLGregorianCalendar cal = DatatypeFactory.newInstance().newXMLGregorianCalendarDate(
                2010,// year
                1, // month,
                13, // day,
                0// timezone
                );
        
		assertEquals(//
				"http://www.w3.org/2001/XMLSchema#date",//
				vf.createLiteral(cal).getDatatype().stringValue()//
				);

    }

	/**
	 * Unit test verifies that a new {@link BigdataValue} instance is returned
	 * when {@link BigdataValueFactory#asValue(org.openrdf.model.Value)} is
	 * invoked with a {@link BigdataValue} whose {@link IV} is a "dummmy" IV
	 * (aka a "mock" IV). A "dummy" or "mock" {@link IV} is an {@link IV} which
	 * stands in for a "null" and is used to hold the place for an RDF
	 * {@link Value} which is not known to the database.
	 * 
	 * @see https://sourceforge.net/apps/trac/bigdata/ticket/348
	 */
    public void test_asValue_mockIV() {
    	
    	final BigdataValue v1 = vf.createURI("http://www.bigdata.com");
    	
    	final BigdataValue v2 = vf.asValue(v1);
    	
    	v1.setIV(DummyIV.INSTANCE);

    	final BigdataValue v3 = vf.asValue(v1);
    	
    	// same BigdataValue
    	assertTrue(v2 == v1);
    	
    	// distinct BigdataValue
    	assertTrue(v3 != v1);
    	
    }
    
}
