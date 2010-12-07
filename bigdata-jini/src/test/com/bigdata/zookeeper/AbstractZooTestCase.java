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
 * Created on Jan 4, 2009
 */

package com.bigdata.zookeeper;

import java.io.File;
import java.io.IOException;
import java.net.BindException;
import java.net.ServerSocket;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import junit.framework.TestCase2;
import net.jini.config.Configuration;
import net.jini.config.ConfigurationProvider;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.KeeperException.NodeExistsException;
import org.apache.zookeeper.KeeperException.SessionExpiredException;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.server.quorum.QuorumPeerMain;

import com.bigdata.jini.start.MockListener;
import com.bigdata.jini.start.config.ZookeeperServerConfiguration;
import com.bigdata.jini.start.process.ProcessHelper;
import com.bigdata.jini.start.process.ZookeeperProcessHelper;
import com.bigdata.jini.util.ConfigMath;
import com.bigdata.resources.ResourceFileFilter;

//BTM - FOR_ZOOKEEPER_SMART_PROXY - BEGIN
import com.bigdata.service.QuorumPeerService;
import com.sun.jini.admin.DestroyAdmin;
import net.jini.admin.Administrable;
import net.jini.core.discovery.LookupLocator;
import net.jini.core.lookup.ServiceItem;
import net.jini.core.lookup.ServiceTemplate;
import net.jini.discovery.DiscoveryGroupManagement;
import net.jini.discovery.LookupDiscoveryManager;
import net.jini.lookup.ServiceDiscoveryManager;
//BTM - FOR_ZOOKEEPER_SMART_PROXY - END

/**
 * Abstract base class for zookeeper integration tests.
 * 
 * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
 * @version $Id$
 */
public abstract class AbstractZooTestCase extends TestCase2 {

    /**
     * 
     */
    public AbstractZooTestCase() {
        super();
    }

    public AbstractZooTestCase(String name) {
        super(name);
    }
    
    /**
     * Return an open port on current machine. Try the suggested port first. If
     * suggestedPort is zero, just select a random port
     */
    protected static int getPort(final int suggestedPort) throws IOException {
        
        ServerSocket openSocket;
        
        try {
        
            openSocket = new ServerSocket(suggestedPort);
            
        } catch (BindException ex) {
            
            // the port is busy, so look for a random open port
            openSocket = new ServerSocket(0);
        
        }

        final int port = openSocket.getLocalPort();
        
        openSocket.close();

        return port;
        
    }

    /**
     * A configuration file used by some of the unit tests in this package. It
     * contains a description of the zookeeper server instance in case we need
     * to start one.
     */
    protected final String configFile = "file:bigdata-jini/src/test/com/bigdata/zookeeper/testzoo.config";

    /**
     * Note: The sessionTimeout is computed as 2x the tickTime as read out of
     * the configuration file by {@link #setUp()}. This corresponds to the
     * actual sessionTimeout for the client rather than a requested value.
     */
    int sessionTimeout;

    /**
     * The initial {@link ZooKeeper} instance obtained from the
     * {@link #zookeeperAccessor} when the test was setup.
     * <p>
     * Note: Some unit tests use {@link #expireSession(ZooKeeper)} to expire the
     * session associated with this {@link ZooKeeper} instance.
     * 
     * @see ZooKeeperAccessor
     */
    protected ZooKeeper zookeeper;

    /**
     * Factory for {@link ZooKeeper} instances using the configured hosts and
     * session timeout.
     */
    protected ZooKeeperAccessor zookeeperAccessor;

    /**
     * ACL used by the unit tests.
     */
    protected final List<ACL> acl = Ids.OPEN_ACL_UNSAFE;
    
    protected final MockListener listener = new MockListener();

    private File dataDir = null;
    
    // the chosen client port.
    int clientPort = -1;
    
//BTM - FOR_ZOOKEEPER_SMART_PROXY - BEGIN
    String hostname;
//BTM - FOR_ZOOKEEPER_SMART_PROXY - END

    public void setUp() throws Exception {

        try {
            
        if (log.isInfoEnabled())
            log.info(getName());

        // find ports that are not in use.
        clientPort = getPort(2181/* suggestedPort */);
        final int peerPort = getPort(2888/* suggestedPort */);
        final int leaderPort = getPort(3888/* suggestedPort */);
//BTM - PRE_ZOOKEEPER_SMART_PROXY - BEGIN
//BTM - PRE_ZOOKEEPER_SMART_PROXY        final String servers = "1=localhost:" + peerPort + ":" + leaderPort;
        hostname = com.bigdata.util.config.NicUtil.getIpAddress("default.nic", "default", true);
        final String servers = "1="+hostname+":" + peerPort + ":" + leaderPort;
//BTM - PRE_ZOOKEEPER_SMART_PROXY - END

        // create a temporary file for zookeeper's state.
        dataDir = File.createTempFile("test", ".zoo");
        // delete the file so that it can be re-created as a directory.
        dataDir.delete();
        // recreate the file as a directory.
        dataDir.mkdirs();

        final String[] args = new String[] {
        // The configuration file (overrides follow).
                configFile,
                // overrides the clientPort to be unique.
                QuorumPeerMain.class.getName() + "."
                        + ZookeeperServerConfiguration.Options.CLIENT_PORT + "="
                        + clientPort,
                // overrides servers declaration.
                QuorumPeerMain.class.getName() + "."
                        + ZookeeperServerConfiguration.Options.SERVERS + "=\""
                        + servers + "\"",
                // overrides the dataDir
                QuorumPeerMain.class.getName() + "."
                        + ZookeeperServerConfiguration.Options.DATA_DIR
                        + "=new java.io.File("
                        + ConfigMath.q(dataDir.toString()) + ")"//
        };
        
        System.err.println("args=" + Arrays.toString(args));

        final Configuration config = ConfigurationProvider.getInstance(args);
        
        final int tickTime = (Integer) config.getEntry(QuorumPeerMain.class
                .getName(), ZookeeperServerConfiguration.Options.TICK_TIME,
                Integer.TYPE);

        /*
         * Note: This is the actual session timeout that the zookeeper service
         * will impose on the client.
         */
        this.sessionTimeout = tickTime * 2;

        // if necessary, start zookeeper (a server instance).
//BTM - PRE_ZOOKEEPER_SMART_PROXY - BEGIN
//BTM - PRE_ZOOKEEPER_SMART_PROXY        ZookeeperProcessHelper.startZookeeper(config, listener);
//BTM - PRE_ZOOKEEPER_SMART_PROXY
//BTM - PRE_ZOOKEEPER_SMART_PROXY        zookeeperAccessor = new ZooKeeperAccessor("localhost:" + clientPort, sessionTimeout);

        zookeeperAccessor = new ZooKeeperAccessor(hostname+":" + clientPort, sessionTimeout);
        ZookeeperProcessHelper.startZookeeper(com.bigdata.quorum.ServiceImpl.class, config, listener);
//BTM - PRE_ZOOKEEPER_SMART_PROXY - END
        
        zookeeper = zookeeperAccessor.getZookeeper();
        
        try {
            
            /*
             * Since all unit tests use children of this node we must make sure
             * that it exists.
             */
            zookeeper
                    .create("/test", new byte[] {}, acl, CreateMode.PERSISTENT);

        } catch (NodeExistsException ex) {

            if (log.isInfoEnabled())
                log.info("/test already exits.");

        }

        } catch (Throwable t) {

            // don't leave around the dataDir if the setup fails.
            recursiveDelete(dataDir);
            
            throw new Exception(t);
            
        }

    }

    public void tearDown() throws Exception {

        try {

            if (log.isInfoEnabled())
                log.info(getName());

            if (zookeeperAccessor != null) {

                zookeeperAccessor.close();

            }

//BTM - FOR_ZOOKEEPER_SMART_PROXY - BEGIN
            // Gracefully shutdown QuorumPeerService
            // For graceful shutdown of QuorumPeerService
            LookupLocator[] locs =
                new LookupLocator[]
                        { new LookupLocator("jini://"+hostname) };
            LookupDiscoveryManager ldm =
                new LookupDiscoveryManager
                        (DiscoveryGroupManagement.NO_GROUPS,
                         locs, null);
            //wait no more than N secs for lookup to be discovered
            int nWait = 3;
            for (int i=0; i<nWait; i++) {
                if ( (ldm.getRegistrars()).length > 0 ) break;
                com.bigdata.util.Util.delayMS(1L*1000L);
            }
            ServiceDiscoveryManager sdm =
                new ServiceDiscoveryManager(ldm, null);
            Class[] quorumServiceType =
                new Class[] {QuorumPeerService.class};
            ServiceTemplate quorumServiceTmpl = 
                new ServiceTemplate(null, quorumServiceType, null);
            ServiceItem[] items =
                sdm.lookup(quorumServiceTmpl, Integer.MAX_VALUE, null);
            for (int i=0; i<items.length; i++) {
                QuorumPeerService zk = (QuorumPeerService)(items[i].service);
                try {
                    Object admin = ((Administrable)zk).getAdmin();
                    ((DestroyAdmin)admin).destroy();
                } catch(Exception e) {
                    log.warn("failure on zookeeper destroy ["+zk+"]", e);
                }
            }
            sdm.terminate();
//BTM - FOR_ZOOKEEPER_SMART_PROXY - END

            for (ProcessHelper h : listener.running) {

                // destroy zookeeper service iff we started it.
                h.kill(true/* immediateShutdown */);

            }

            if (dataDir != null) {

                // clean out the zookeeper data dir.
                recursiveDelete(dataDir);

            }

        } catch (Throwable t) {

            log.error(t, t);

        }
        
    }
    
    /**
     * Return a new {@link Zookeeper} instance that is connected to the same
     * zookeeper ensemble but which has a distinct session.
     * 
     * @return
     * @throws IOException
     * @throws InterruptedException
     */
    protected ZooKeeper getDistinctZooKeeperWithDistinctSession()
            throws IOException, InterruptedException {

        final ZooKeeper zookeeper2 = new ZooKeeper(zookeeperAccessor.hosts,
                zookeeperAccessor.sessionTimeout, new Watcher() {
                    public void process(WatchedEvent e) {

                    }
                });
        
        /*
         * Wait until this instance is connected.
         */
        final long timeout = TimeUnit.MILLISECONDS.toNanos(1000/* ms */);

        final long begin = System.nanoTime();

        while (zookeeper2.getState() != ZooKeeper.States.CONNECTED
                && zookeeper2.getState().isAlive()) {

            final long elapsed = System.nanoTime() - begin;

            if (elapsed > timeout) {

                fail("ZooKeeper session did not connect? elapsed="
                        + TimeUnit.NANOSECONDS.toMillis(elapsed));

            }

            if (log.isInfoEnabled()) {

                log.info("Awaiting connected.");

            }

            Thread.sleep(100/* ms */);

        }

        if (!zookeeper2.getState().isAlive()) {

            fail("Zookeeper died?");

        }

        if(log.isInfoEnabled())
            log.info("Zookeeper connected.");
        
        return zookeeper2;

    }
    
    /**
     * Return a new {@link ZooKeeper} instance that is connected to the same
     * zookeeper ensemble as the given instance and is using the same session
     * but is nevertheless a distinct instance.
     * <p>
     * Note: This is used by some unit tests to force the given
     * {@link ZooKeeper} to report a {@link SessionExpiredException} by closing
     * the returned instance.
     * 
     * @param zookeeper
     *            A zookeeper instance.
     * 
     * @return A distinct instance associated with the same session.
     * 
     * @throws IOException
     * @throws InterruptedException
     */
    protected ZooKeeper getDistinctZooKeeperForSameSession(ZooKeeper zookeeper1)
            throws IOException, InterruptedException {

        final ZooKeeper zookeeper2 = new ZooKeeper(zookeeperAccessor.hosts,
                zookeeperAccessor.sessionTimeout, new Watcher() {
                    public void process(WatchedEvent e) {

                    }
                }, zookeeper1.getSessionId(), zookeeper1.getSessionPasswd());
        
        /*
         * Wait until this instance is connected.
         */
        final long timeout = TimeUnit.MILLISECONDS.toNanos(1000/* ms */);

        final long begin = System.nanoTime();

        while (zookeeper2.getState() != ZooKeeper.States.CONNECTED
                && zookeeper2.getState().isAlive()) {

            final long elapsed = System.nanoTime() - begin;

            if (elapsed > timeout) {

                fail("ZooKeeper session did not connect? elapsed="
                        + TimeUnit.NANOSECONDS.toMillis(elapsed));

            }

            if (log.isInfoEnabled()) {

                log.info("Awaiting connected.");

            }

            Thread.sleep(100/* ms */);

        }

        if (!zookeeper2.getState().isAlive()) {

            fail("Zookeeper died?");

        }

        if(log.isInfoEnabled())
            log.info("Zookeeper connected.");
        
        return zookeeper2;

    }

    /**
     * Expires the session associated with the {@link Zookeeper} client
     * instance.
     * 
     * @param zookeeper
     * 
     * @throws IOException
     * @throws InterruptedException
     */
    protected void expireSession(ZooKeeper zookeeper) throws IOException,
            InterruptedException {

        /*
         * Obtain a distinct ZooKeeper instance associated with the _same_
         * session.
         */
        final ZooKeeper zookeeper2 = getDistinctZooKeeperForSameSession(zookeeper);

        /*
         * Close this instance, forcing the original instance to report a
         * SessionExpiredException. Note that this is not synchronous so we need
         * to wait until the original ZooKeeper instance notices that its
         * session is expired.
         */
        zookeeper2.close();
        
        /*
         * Wait up to the session timeout and then wait some more so that the
         * events triggered by that timeout have time to propagate.
         */
        final long timeout = TimeUnit.MILLISECONDS.toNanos(sessionTimeout * 2);

        final long begin = System.nanoTime();

        while (zookeeper.getState().isAlive()) {

            final long elapsed = System.nanoTime() - begin;

            if (elapsed > timeout) {

                fail("ZooKeeper session did not expire? elapsed="
                        + TimeUnit.NANOSECONDS.toMillis(elapsed)
                        + ", sessionTimeout=" + sessionTimeout);

            }

            if(log.isInfoEnabled()) {
                
                log.info("Awaiting session expired.");
                
            }
            
            Thread.sleep(500/* ms */);

        }

        if (log.isInfoEnabled()) {

            final long elapsed = System.nanoTime() - begin;

            log.info("Session was expired: elapsed="
                    + TimeUnit.NANOSECONDS.toMillis(elapsed)
                    + ", sessionTimeout=" + sessionTimeout);

        }
        
    }
    
    /**
     * Class used to test concurrency primitives.
     * 
     * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
     * @version $Id$
     */
    abstract protected class ClientThread extends Thread {

        private final Thread main;
        
        protected final ReentrantLock lock;

        /**
         * 
         * @param main
         *            The thread in which the test is running.
         * @param lock
         *            A lock.
         */
        public ClientThread(final Thread main, final ReentrantLock lock) {

            if (main == null)
                throw new IllegalArgumentException();

            if (lock == null)
                throw new IllegalArgumentException();

            this.main = main;

            this.lock = lock;

            setDaemon(true);

        }

        public void run() {

            try { 

                run2();

            } catch (Throwable t) {

                // log error since won't be seen otherwise.
                log.error(t.getLocalizedMessage(), t);

                // interrupt the main thread.
                main.interrupt();

            }

        }

        abstract void run2() throws Exception;

    }

    /**
     * Recursively removes any files and subdirectories and then removes the
     * file (or directory) itself.
     * <p>
     * Note: Files that are not recognized will be logged by the
     * {@link ResourceFileFilter}.
     * 
     * @param f
     *            A file or directory.
     */
    private void recursiveDelete(final File f) {

        if (f.isDirectory()) {

            final File[] children = f.listFiles();

            if (children == null) {

                // The directory does not exist.
                return;
                
            }
            
            for (int i = 0; i < children.length; i++) {

                recursiveDelete(children[i]);

            }

        }

        if(log.isInfoEnabled())
            log.info("Removing: " + f);

        if (f.exists() && !f.delete()) {

            log.warn("Could not remove: " + f);

        }

    }

    /**
     * Recursive delete of znodes.
     * 
     * @param zpath
     * 
     * @throws KeeperException
     * @throws InterruptedException
     */
    protected void destroyZNodes(final ZooKeeper zookeeper, final String zpath)
            throws KeeperException, InterruptedException {

        // System.err.println("enter : " + zpath);

        final List<String> children = zookeeper.getChildren(zpath, false);

        for (String child : children) {

            destroyZNodes(zookeeper, zpath + "/" + child);

        }

        if(log.isInfoEnabled())
            log.info("delete: " + zpath);

        zookeeper.delete(zpath, -1/* version */);

    }

}
