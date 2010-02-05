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
/*
 * Created on Jun 19, 2008
 */
package com.bigdata.rdf.sail.tck;

import java.io.File;
import java.io.IOException;
import java.util.Properties;

import org.openrdf.sail.RDFStoreTest;
import org.openrdf.sail.Sail;
import org.openrdf.sail.SailConnection;
import org.openrdf.sail.SailException;

import com.bigdata.journal.IIndexManager;
import com.bigdata.rdf.axioms.NoAxioms;
import com.bigdata.rdf.sail.BigdataSail;
import com.bigdata.rdf.sail.BigdataSail.Options;
import com.bigdata.rdf.store.LocalTripleStore;
import com.bigdata.relation.AbstractResource;

public class BigdataStoreTest extends RDFStoreTest {

    /**
     * Return a test suite using the {@link LocalTripleStore} and nested
     * subquery joins.
     */
    public static class LTSWithNestedSubquery extends BigdataStoreTest {

        public LTSWithNestedSubquery(String name) {
            super(name);
        }
        
        @Override
        protected Properties getProperties() {
            
            final Properties p = new Properties(super.getProperties());
            
            p.setProperty(AbstractResource.Options.NESTED_SUBQUERY,"true");
            
            p.setProperty(BigdataSail.Options.STORE_BLANK_NODES,"true");
            
            return p;
            
        }

    }
    

    /**
     * Return a test suite using the {@link LocalTripleStore} and pipeline
     * joins.
     */
    public static class LTSWithPipelineJoins extends BigdataStoreTest {

        public LTSWithPipelineJoins(String name) {
            
            super(name);
            
        }
        
        @Override
        protected Properties getProperties() {
            
            final Properties p = new Properties(super.getProperties());
            
            p.setProperty(AbstractResource.Options.NESTED_SUBQUERY,"false");
       
            p.setProperty(BigdataSail.Options.STORE_BLANK_NODES,"true");
            
            return p;
            
        }

    }
    
    static File createTempFile() {
        
        try {
            return File.createTempFile("bigdata-tck", ".jnl");
            
        } catch (IOException e) {
            
            throw new AssertionError(e);
            
        }
        
    }

    /**
     * Overridden to destroy the backend database and its files on the disk.
     */
    @Override
    protected void tearDown()
        throws Exception
    {
        
        final IIndexManager backend = sail == null ? null
                : ((BigdataSail) sail).getDatabase().getIndexManager();

        super.tearDown();

        if (backend != null)
            backend.destroy();

    }
    
    public BigdataStoreTest(String name) {

        super(name);
        
    }

    /**
     * @todo The problem here is that the {@link BigdataSail} uses a semaphore
     *       to grant the unisolated write connection. If a thread requests two
     *       sail connections then it will deadlock. This could be fixed either
     *       by supporting full transactions in the sail or by allowing more
     *       than one connection but having them interleave their incremental
     *       writes.
     */
    @Override
    public void testDualConnections(){
        fail("Not supported yet.");
    }

    /**
     * This unit test has been disabled. Sesame 2.x assumes that two blank nodes
     * are the same if they have the same identifier. bigdata does not have
     * those semantics. Neither does RDF. Sesame 3.x has the standard behavior
     * and does not run this unit test either.
     */
    public void testStatementSerialization() {
        
    }
    
    protected Properties getProperties() {
        
        final Properties props = new Properties();
        
        final File journal = createTempFile();
        
        props.setProperty(BigdataSail.Options.FILE, journal.getAbsolutePath());
/*        
        props.setProperty(Options.STATEMENT_IDENTIFIERS, "false");
        
        props.setProperty(Options.QUADS, "true");
        
        props.setProperty(Options.AXIOMS_CLASS, NoAxioms.class.getName());
*/
        props.setProperty(Options.QUADS_MODE, "true");
        
        props.setProperty(Options.ALLOW_AUTO_COMMIT, "true");
        
        props.setProperty(Options.EXACT_SIZE, "true");
        
        return props;
        
    }
    
    @Override
    protected Sail createSail() throws SailException {
        
        final Sail sail = new BigdataSail(getProperties());
        
        sail.initialize();
        
        final SailConnection conn = sail.getConnection();
        
        try {
            
            conn.clear();
            
            conn.clearNamespaces();
            
            conn.commit();
            
        } finally {
            
            conn.close();
            
        }

        return sail;
        
    }

}
