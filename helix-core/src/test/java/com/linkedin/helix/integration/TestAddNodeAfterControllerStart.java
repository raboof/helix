/**
 * Copyright (C) 2012 LinkedIn Inc <opensource@linkedin.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.linkedin.helix.integration;

import java.util.Date;
import java.util.List;

import org.apache.log4j.Logger;
import org.testng.Assert;
import org.testng.annotations.Test;

import com.linkedin.helix.InstanceType;
import com.linkedin.helix.PropertyPathConfig;
import com.linkedin.helix.PropertyType;
import com.linkedin.helix.TestHelper;
import com.linkedin.helix.controller.HelixControllerMain;
import com.linkedin.helix.manager.zk.CallbackHandler;
import com.linkedin.helix.manager.zk.ZKHelixManager;
import com.linkedin.helix.mock.storage.MockParticipant;
import com.linkedin.helix.tools.ClusterStateVerifier;

public class TestAddNodeAfterControllerStart extends ZkIntegrationTestBase
{
  private static Logger LOG       =
                                      Logger.getLogger(TestAddNodeAfterControllerStart.class);
  final String          className = getShortClassName();

  class ZkClusterManagerWithGetHandlers extends ZKHelixManager
  {
    public ZkClusterManagerWithGetHandlers(String clusterName,
                                           String instanceName,
                                           InstanceType instanceType,
                                           String zkConnectString) throws Exception
    {
      super(clusterName, instanceName, instanceType, zkConnectString);
    }

    @Override
    public List<CallbackHandler> getHandlers()
    {
      return super.getHandlers();
    }

  }

  @Test
  public void testStandalone() throws Exception
  {
    String clusterName = className + "_standalone";
    System.out.println("START " + clusterName + " at "
        + new Date(System.currentTimeMillis()));

    final int nodeNr = 5;
    
    TestHelper.setupCluster(clusterName,
                            ZK_ADDR,
                            12918,
                            "localhost",
                            "TestDB",
                            1,
                            20,
                            nodeNr - 1,
                            3,
                            "MasterSlave",
                            true);

    MockParticipant[] participants = new MockParticipant[nodeNr];
    for (int i = 0; i < nodeNr - 1; i++)
    {
      String instanceName = "localhost_" + (12918 + i);
      participants[i] = new MockParticipant(clusterName, instanceName, ZK_ADDR);
      new Thread(participants[i]).start();
    }

    ZkClusterManagerWithGetHandlers controller =
        new ZkClusterManagerWithGetHandlers(clusterName,
                                            "controller_0",
                                            InstanceType.CONTROLLER,
                                            ZK_ADDR);
    controller.connect();
    boolean result;
    result =
        ClusterStateVerifier.verifyByPolling(new ClusterStateVerifier.BestPossAndExtViewZkVerifier(ZK_ADDR,
                                                                                          clusterName));
    Assert.assertTrue(result);
    String msgPath = PropertyPathConfig.getPath(PropertyType.MESSAGES, clusterName, "localhost_12918");
    result = checkHandlers(controller.getHandlers(), msgPath);
    Assert.assertTrue(result);

    _gSetupTool.addInstanceToCluster(clusterName, "localhost:12922");
    _gSetupTool.rebalanceStorageCluster(clusterName, "TestDB0", 3);

    participants[nodeNr - 1] =
        new MockParticipant(clusterName, "localhost_12922", ZK_ADDR);
    new Thread(participants[nodeNr - 1]).start();
    result =
        ClusterStateVerifier.verifyByPolling(new ClusterStateVerifier.BestPossAndExtViewZkVerifier(ZK_ADDR,
                                                                                          clusterName));
    Assert.assertTrue(result);
    msgPath = PropertyPathConfig.getPath(PropertyType.MESSAGES, clusterName, "localhost_12922");
    result = checkHandlers(controller.getHandlers(), msgPath);
    Assert.assertTrue(result);

    // clean up
    controller.disconnect();
    for (int i = 0; i < nodeNr; i++)
    {
      participants[i].syncStop();
    }

    System.out.println("END " + clusterName + " at "
        + new Date(System.currentTimeMillis()));
  }

  @Test
  public void testDistributed() throws Exception
  {
    String clusterName = className + "_distributed";
    System.out.println("START " + clusterName + " at "
        + new Date(System.currentTimeMillis()));

    // setup grand cluster
    TestHelper.setupCluster("GRAND_" + clusterName,
                            ZK_ADDR,
                            0,
                            "controller",
                            null,
                            0,
                            0,
                            1,
                            0,
                            null,
                            true);
   
    TestHelper.startController("GRAND_" + clusterName,
                               "controller_0",
                               ZK_ADDR,
                               HelixControllerMain.DISTRIBUTED);
    
    // setup cluster
    _gSetupTool.addCluster(clusterName, true);
    _gSetupTool.addCluster(clusterName, "GRAND_" + clusterName);    // addCluster2

    boolean result;
    result =
        ClusterStateVerifier.verifyByPolling(new ClusterStateVerifier.BestPossAndExtViewZkVerifier(ZK_ADDR,
                                                                                          "GRAND_" + clusterName));
    Assert.assertTrue(result);
    
    // add node/resource, and do rebalance
    final int nodeNr = 2;
    for (int i = 0; i < nodeNr - 1; i++)
    {
      int port = 12918 + i;
      _gSetupTool.addInstanceToCluster(clusterName, "localhost:" + port);
    }

    _gSetupTool.addResourceToCluster(clusterName, "TestDB0", 1, "LeaderStandby");
    _gSetupTool.rebalanceStorageCluster(clusterName, "TestDB0", 1);
    
    MockParticipant[] participants = new MockParticipant[nodeNr];
    for (int i = 0; i < nodeNr - 1; i++)
    {
      String instanceName = "localhost_" + (12918 + i);
      participants[i] = new MockParticipant(clusterName, instanceName, ZK_ADDR);
      participants[i].syncStart();
//      new Thread(participants[i]).start();
    }

    result =
        ClusterStateVerifier.verifyByPolling(new ClusterStateVerifier.BestPossAndExtViewZkVerifier(ZK_ADDR,
                                                                                          clusterName));
    Assert.assertTrue(result);
    
    // check if controller_0 has message listener for localhost_12918
    String msgPath = PropertyPathConfig.getPath(PropertyType.MESSAGES, clusterName, "localhost_12918");
    int numberOfListeners = TestHelper.numberOfListeners(ZK_ADDR, msgPath);
    // System.out.println("numberOfListeners(" + msgPath + "): " + numberOfListeners);
    Assert.assertEquals(numberOfListeners, 2);  // 1 of participant, and 1 of controller

    _gSetupTool.addInstanceToCluster(clusterName, "localhost:12919");
    _gSetupTool.rebalanceStorageCluster(clusterName, "TestDB0", 2);

    participants[nodeNr - 1] =
        new MockParticipant(clusterName, "localhost_12919", ZK_ADDR);
    participants[nodeNr - 1].syncStart();
//    new Thread(participants[nodeNr - 1]).start();
    
    result =
        ClusterStateVerifier.verifyByPolling(new ClusterStateVerifier.BestPossAndExtViewZkVerifier(ZK_ADDR,
                                                                                        clusterName));
    Assert.assertTrue(result);
    // check if controller_0 has message listener for localhost_12919
    msgPath = PropertyPathConfig.getPath(PropertyType.MESSAGES, clusterName, "localhost_12919");
    numberOfListeners = TestHelper.numberOfListeners(ZK_ADDR, msgPath);
    // System.out.println("numberOfListeners(" + msgPath + "): " + numberOfListeners);
    Assert.assertEquals(numberOfListeners, 2);  // 1 of participant, and 1 of controller

    // clean up
    for (int i = 0; i < nodeNr; i++)
    {
      participants[i].syncStop();
    }

    System.out.println("END " + clusterName + " at "
        + new Date(System.currentTimeMillis()));
  }

  boolean checkHandlers(List<CallbackHandler> handlers, String path)
  {
//    System.out.println(handlers.size() + " handlers: ");
    for (CallbackHandler handler : handlers)
    {
//      System.out.println(handler.getPath());
      if (handler.getPath().equals(path))
      {
        return true;
      }
    }
    return false;
  }
  
  // TODO: need to add a test case for ParticipantCodeRunner
}
