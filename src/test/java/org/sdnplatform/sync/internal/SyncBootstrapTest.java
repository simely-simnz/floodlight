package org.sdnplatform.sync.internal;

import java.util.ArrayList;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.debugcounter.IDebugCounterService;
import net.floodlightcontroller.debugcounter.NullDebugCounter;
import net.floodlightcontroller.threadpool.IThreadPoolService;
import net.floodlightcontroller.threadpool.ThreadPool;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.sdnplatform.sync.IStoreClient;
import org.sdnplatform.sync.ISyncService.Scope;
import org.sdnplatform.sync.internal.config.Node;
import org.sdnplatform.sync.internal.config.SyncStoreCCProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.net.HostAndPort;

import static org.junit.Assert.*;
import static org.sdnplatform.sync.internal.SyncManagerTest.waitForValue;
import static org.sdnplatform.sync.internal.SyncManagerTest.waitForFullMesh;

public class SyncBootstrapTest {
    protected static Logger logger =
            LoggerFactory.getLogger(SyncBootstrapTest.class);
    
    @Rule
    public TemporaryFolder dbFolder1 = new TemporaryFolder();
    @Rule
    public TemporaryFolder dbFolder2 = new TemporaryFolder();
    @Rule
    public TemporaryFolder dbFolder3 = new TemporaryFolder();
    @Rule
    public TemporaryFolder dbFolder4 = new TemporaryFolder();

    public TemporaryFolder[] dbFolders = 
        {dbFolder1, dbFolder2, dbFolder3, dbFolder4};
    
    @Test
    public void testBootstrap() throws Exception {
        ArrayList<SyncManager> syncManagers = new ArrayList<SyncManager>();
        ArrayList<IStoreClient<Short,Node>> nodeStores = 
                new ArrayList<IStoreClient<Short,Node>>();
        ArrayList<IStoreClient<String,String>> unsyncStores = 
                new ArrayList<IStoreClient<String,String>>();
        ArrayList<Short> nodeIds = new ArrayList<Short>();
        ArrayList<Node> nodes = new ArrayList<Node>();
        
        FloodlightModuleContext fmc = new FloodlightModuleContext();
        ThreadPool tp = new ThreadPool();

        int curPort = 6699;
        
        // autobootstrap a cluster of 4 nodes
        for (int i = 0; i < 4; i++) {
            SyncManager syncManager = new SyncManager();
            syncManagers.add(syncManager);

            fmc.addService(IThreadPoolService.class, tp);
            fmc.addService(IDebugCounterService.class, new NullDebugCounter());
            fmc.addConfigParam(syncManager, "dbPath", 
                               dbFolders[i].getRoot().getAbsolutePath());

            tp.init(fmc);
            syncManager.init(fmc);
            tp.startUp(fmc);
            syncManager.startUp(fmc);
            syncManager.registerStore("localTestStore", Scope.LOCAL);
            syncManager.registerStore("globalTestStore", Scope.GLOBAL);
            
            IStoreClient<String, String> unsyncStore = 
                    syncManager.getStoreClient(SyncStoreCCProvider.
                                               SYSTEM_UNSYNC_STORE, 
                                               String.class, String.class);
            IStoreClient<Short, Node> nodeStore = 
                    syncManager.getStoreClient(SyncStoreCCProvider.
                                               SYSTEM_NODE_STORE, 
                                                Short.class, Node.class);
            unsyncStores.add(unsyncStore);
            nodeStores.add(nodeStore);
            
            // Note that it will end up going through a transitional state
            // where it will listen on 6642 because it will use the fallback
            // config
            unsyncStore.put("localNodePort", String.valueOf(curPort));

            String curSeed = "";
            if (i > 0) {
                curSeed = HostAndPort.fromParts(nodes.get(i-1).getHostname(), 
                                                nodes.get(i-1).getPort()).
                                                toString();
            }
            // The only thing really needed for bootstrapping is to put
            // a value for "seeds" into the unsynchronized store.
            unsyncStore.put("seeds", curSeed);

            waitForValue(unsyncStore, "localNodeId", null, 
                         3000, "unsyncStore" + i);
            short nodeId = 
                    Short.parseShort(unsyncStore.getValue("localNodeId"));
            Node node = nodeStore.getValue(nodeId);
            nodeIds.add(nodeId);
            nodes.add(node);

            while (syncManager.getClusterConfig().
                    getNode().getNodeId() != nodeId) {
                Thread.sleep(100);
            }
            while (syncManager.getClusterConfig().
                    getNode().getPort() != curPort) {
                Thread.sleep(100);
            }
            for (int j = 0; j <= i; j++) {
                for (int k = 0; k <= i; k++) {
                    waitForValue(nodeStores.get(j), nodeIds.get(k), 
                                 nodes.get(k), 3000, "nodeStore" + j);
                }
            }
            curPort -= 1;
        }
        for (SyncManager syncManager : syncManagers) {
            assertEquals(syncManagers.size(), 
                         syncManager.getClusterConfig().getNodes().size());
        }        
        
        SyncManager[] syncManagerArr = 
                syncManagers.toArray(new SyncManager[syncManagers.size()]);
        waitForFullMesh(syncManagerArr, 5000);
    }
}
