/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iotdb.confignode.service.thrift.server;

import org.apache.iotdb.common.rpc.thrift.EndPoint;
import org.apache.iotdb.common.rpc.thrift.TRegionReplicaSet;
import org.apache.iotdb.common.rpc.thrift.TSStatus;
import org.apache.iotdb.common.rpc.thrift.TSeriesPartitionSlot;
import org.apache.iotdb.common.rpc.thrift.TTimePartitionSlot;
import org.apache.iotdb.confignode.conf.ConfigNodeDescriptor;
import org.apache.iotdb.confignode.persistence.DataNodeInfoPersistence;
import org.apache.iotdb.confignode.persistence.PartitionInfoPersistence;
import org.apache.iotdb.confignode.persistence.RegionInfoPersistence;
import org.apache.iotdb.confignode.rpc.thrift.TDataNodeMessage;
import org.apache.iotdb.confignode.rpc.thrift.TDataNodeMessageResp;
import org.apache.iotdb.confignode.rpc.thrift.TDataNodeRegisterReq;
import org.apache.iotdb.confignode.rpc.thrift.TDataNodeRegisterResp;
import org.apache.iotdb.confignode.rpc.thrift.TDataPartitionReq;
import org.apache.iotdb.confignode.rpc.thrift.TDataPartitionResp;
import org.apache.iotdb.confignode.rpc.thrift.TGlobalConfig;
import org.apache.iotdb.confignode.rpc.thrift.TSchemaPartitionReq;
import org.apache.iotdb.confignode.rpc.thrift.TSetStorageGroupReq;
import org.apache.iotdb.confignode.rpc.thrift.TStorageGroupMessage;
import org.apache.iotdb.confignode.rpc.thrift.TStorageGroupMessageResp;
import org.apache.iotdb.rpc.TSStatusCode;

import org.apache.ratis.util.FileUtils;
import org.apache.thrift.TException;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class ConfigNodeRPCServerProcessorTest {

  ConfigNodeRPCServerProcessor processor;

  @Before
  public void before() throws IOException, InterruptedException {
    processor = new ConfigNodeRPCServerProcessor();
    // Sleep 1s to make sure the Consensus group has done leader election
    TimeUnit.SECONDS.sleep(1);
  }

  @After
  public void after() throws IOException {
    DataNodeInfoPersistence.getInstance().clear();
    PartitionInfoPersistence.getInstance().clear();
    RegionInfoPersistence.getInstance().clear();
    processor.close();
    FileUtils.deleteFully(new File(ConfigNodeDescriptor.getInstance().getConf().getConsensusDir()));
  }

  private void checkGlobalConfig(TGlobalConfig globalConfig) {
    Assert.assertEquals(
        ConfigNodeDescriptor.getInstance().getConf().getDataNodeConsensusProtocolClass(),
        globalConfig.getDataNodeConsensusProtocolClass());
    Assert.assertEquals(
        ConfigNodeDescriptor.getInstance().getConf().getSeriesPartitionSlotNum(),
        globalConfig.getSeriesPartitionSlotNum());
    Assert.assertEquals(
        ConfigNodeDescriptor.getInstance().getConf().getSeriesPartitionExecutorClass(),
        globalConfig.getSeriesPartitionExecutorClass());
  }

  @Test
  public void registerAndQueryDataNodeTest() throws TException {
    TDataNodeRegisterResp resp;
    TDataNodeRegisterReq registerReq0 = new TDataNodeRegisterReq(new EndPoint("0.0.0.0", 6667));
    TDataNodeRegisterReq registerReq1 = new TDataNodeRegisterReq(new EndPoint("0.0.0.0", 6668));
    TDataNodeRegisterReq registerReq2 = new TDataNodeRegisterReq(new EndPoint("0.0.0.0", 6669));

    // test success register
    resp = processor.registerDataNode(registerReq0);
    Assert.assertEquals(TSStatusCode.SUCCESS_STATUS.getStatusCode(), resp.getStatus().getCode());
    Assert.assertEquals(0, resp.getDataNodeID());
    checkGlobalConfig(resp.getGlobalConfig());

    resp = processor.registerDataNode(registerReq1);
    Assert.assertEquals(TSStatusCode.SUCCESS_STATUS.getStatusCode(), resp.getStatus().getCode());
    Assert.assertEquals(1, resp.getDataNodeID());
    checkGlobalConfig(resp.getGlobalConfig());

    resp = processor.registerDataNode(registerReq2);
    Assert.assertEquals(TSStatusCode.SUCCESS_STATUS.getStatusCode(), resp.getStatus().getCode());
    Assert.assertEquals(2, resp.getDataNodeID());
    checkGlobalConfig(resp.getGlobalConfig());

    // test success re-register
    resp = processor.registerDataNode(registerReq1);
    Assert.assertEquals(
        TSStatusCode.DATANODE_ALREADY_REGISTERED.getStatusCode(), resp.getStatus().getCode());
    Assert.assertEquals(1, resp.getDataNodeID());
    checkGlobalConfig(resp.getGlobalConfig());

    // test query DataNodeInfo
    TDataNodeMessageResp msgResp = processor.getDataNodesMessage(-1);
    Assert.assertEquals(TSStatusCode.SUCCESS_STATUS.getStatusCode(), msgResp.getStatus().getCode());
    Map<Integer, TDataNodeMessage> msgMap = msgResp.getDataNodeMessageMap();
    Assert.assertEquals(3, msgMap.size());
    List<Map.Entry<Integer, TDataNodeMessage>> messageList = new ArrayList<>(msgMap.entrySet());
    messageList.sort(Comparator.comparingInt(Map.Entry::getKey));
    for (int i = 0; i < 3; i++) {
      Assert.assertEquals(i, messageList.get(i).getValue().getDataNodeId());
      Assert.assertEquals("0.0.0.0", messageList.get(i).getValue().getEndPoint().getIp());
      Assert.assertEquals(6667 + i, messageList.get(i).getValue().getEndPoint().getPort());
    }

    msgResp = processor.getDataNodesMessage(1);
    Assert.assertEquals(TSStatusCode.SUCCESS_STATUS.getStatusCode(), msgResp.getStatus().getCode());
    msgMap = msgResp.getDataNodeMessageMap();
    Assert.assertEquals(1, msgMap.size());
    Assert.assertNotNull(msgMap.get(1));
    Assert.assertEquals("0.0.0.0", msgMap.get(1).getEndPoint().getIp());
    Assert.assertEquals(6668, msgMap.get(1).getEndPoint().getPort());
  }

  @Test
  public void setAndQueryStorageGroupTest() throws TException {
    TSStatus status;
    final String sg = "root.sg0";

    // failed because there are not enough DataNodes
    TSetStorageGroupReq setReq = new TSetStorageGroupReq(sg);
    status = processor.setStorageGroup(setReq);
    Assert.assertEquals(TSStatusCode.NOT_ENOUGH_DATA_NODE.getStatusCode(), status.getCode());
    Assert.assertEquals("DataNode is not enough, please register more.", status.getMessage());

    // register DataNodes
    TDataNodeRegisterReq registerReq0 = new TDataNodeRegisterReq(new EndPoint("0.0.0.0", 6667));
    TDataNodeRegisterReq registerReq1 = new TDataNodeRegisterReq(new EndPoint("0.0.0.0", 6668));
    TDataNodeRegisterReq registerReq2 = new TDataNodeRegisterReq(new EndPoint("0.0.0.0", 6669));
    status = processor.registerDataNode(registerReq0).getStatus();
    Assert.assertEquals(TSStatusCode.SUCCESS_STATUS.getStatusCode(), status.getCode());
    status = processor.registerDataNode(registerReq1).getStatus();
    Assert.assertEquals(TSStatusCode.SUCCESS_STATUS.getStatusCode(), status.getCode());
    status = processor.registerDataNode(registerReq2).getStatus();
    Assert.assertEquals(TSStatusCode.SUCCESS_STATUS.getStatusCode(), status.getCode());

    // set StorageGroup
    status = processor.setStorageGroup(setReq);
    Assert.assertEquals(TSStatusCode.SUCCESS_STATUS.getStatusCode(), status.getCode());

    // query StorageGroupSchema
    TStorageGroupMessageResp resp = processor.getStorageGroupsMessage();
    Assert.assertEquals(TSStatusCode.SUCCESS_STATUS.getStatusCode(), resp.getStatus().getCode());
    Map<String, TStorageGroupMessage> msgMap = resp.getStorageGroupMessageMap();
    Assert.assertEquals(1, msgMap.size());
    Assert.assertNotNull(msgMap.get(sg));
    Assert.assertEquals(sg, msgMap.get(sg).getStorageGroup());

    // test fail by re-register
    status = processor.setStorageGroup(setReq);
    Assert.assertEquals(
        TSStatusCode.STORAGE_GROUP_ALREADY_EXISTS.getStatusCode(), status.getCode());
  }

  // TODO: Reuse this test after PatterTree is moved to node-commons
  public void applySchemaPartitionTest() throws TException, IOException {
    TSStatus status;
    final String sg = "root.sg0";

    // failed because there are not enough DataNodes
    TSetStorageGroupReq setReq = new TSetStorageGroupReq(sg);
    status = processor.setStorageGroup(setReq);
    Assert.assertEquals(TSStatusCode.INTERNAL_SERVER_ERROR.getStatusCode(), status.getCode());
    Assert.assertEquals("DataNode is not enough, please register more.", status.getMessage());

    // register DataNodes
    TDataNodeRegisterReq registerReq0 = new TDataNodeRegisterReq(new EndPoint("0.0.0.0", 6667));
    TDataNodeRegisterReq registerReq1 = new TDataNodeRegisterReq(new EndPoint("0.0.0.0", 6668));
    TDataNodeRegisterReq registerReq2 = new TDataNodeRegisterReq(new EndPoint("0.0.0.0", 6669));
    status = processor.registerDataNode(registerReq0).getStatus();
    Assert.assertEquals(TSStatusCode.SUCCESS_STATUS.getStatusCode(), status.getCode());
    status = processor.registerDataNode(registerReq1).getStatus();
    Assert.assertEquals(TSStatusCode.SUCCESS_STATUS.getStatusCode(), status.getCode());
    status = processor.registerDataNode(registerReq2).getStatus();
    Assert.assertEquals(TSStatusCode.SUCCESS_STATUS.getStatusCode(), status.getCode());

    // set StorageGroup
    status = processor.setStorageGroup(setReq);
    Assert.assertEquals(TSStatusCode.SUCCESS_STATUS.getStatusCode(), status.getCode());

    // applySchemaPartition
    TSchemaPartitionReq getSchemaPartitionReq = new TSchemaPartitionReq();
    List<Integer> deviceGroupIds = new ArrayList<>();
    Integer deviceGroupId = 1000;
    deviceGroupIds.add(deviceGroupId);
    //    getSchemaPartitionReq.setStorageGroup(sg).setDeviceGroupIDs(deviceGroupIds);
    //    SchemaPartitionInfo schemaPartitionInfo =
    // processor.applySchemaPartition(getSchemaPartitionReq);
    //    Assert.assertNotNull(schemaPartitionInfo);
    //    Assert.assertNotNull(schemaPartitionInfo.getSchemaRegionDataNodesMap().get(sg));
    //    schemaPartitionInfo
    //        .getSchemaRegionDataNodesMap()
    //        .get(sg)
    //        .forEach((key, value) -> Assert.assertEquals(deviceGroupId, key));
  }

  // TODO: Reuse this test after PatterTree is moved to node-commons
  public void getSchemaPartitionTest() throws TException, IOException {
    TSStatus status;
    final String sg = "root.sg0";

    // failed because there are not enough DataNodes
    TSetStorageGroupReq setReq = new TSetStorageGroupReq(sg);
    status = processor.setStorageGroup(setReq);
    Assert.assertEquals(TSStatusCode.INTERNAL_SERVER_ERROR.getStatusCode(), status.getCode());
    Assert.assertEquals("DataNode is not enough, please register more.", status.getMessage());

    // register DataNodes
    TDataNodeRegisterReq registerReq0 = new TDataNodeRegisterReq(new EndPoint("0.0.0.0", 6667));
    TDataNodeRegisterReq registerReq1 = new TDataNodeRegisterReq(new EndPoint("0.0.0.0", 6668));
    TDataNodeRegisterReq registerReq2 = new TDataNodeRegisterReq(new EndPoint("0.0.0.0", 6669));
    status = processor.registerDataNode(registerReq0).getStatus();
    Assert.assertEquals(TSStatusCode.SUCCESS_STATUS.getStatusCode(), status.getCode());
    status = processor.registerDataNode(registerReq1).getStatus();
    Assert.assertEquals(TSStatusCode.SUCCESS_STATUS.getStatusCode(), status.getCode());
    status = processor.registerDataNode(registerReq2).getStatus();
    Assert.assertEquals(TSStatusCode.SUCCESS_STATUS.getStatusCode(), status.getCode());

    // set StorageGroup
    status = processor.setStorageGroup(setReq);
    Assert.assertEquals(TSStatusCode.SUCCESS_STATUS.getStatusCode(), status.getCode());

    // getSchemaPartition
    TSchemaPartitionReq getSchemaPartitionReq = new TSchemaPartitionReq();
    List<Integer> deviceGroupIds = new ArrayList<>();
    Integer deviceGroupId = 1000;
    deviceGroupIds.add(deviceGroupId);
    //    getSchemaPartitionReq.setStorageGroup(sg).setDeviceGroupIDs(deviceGroupIds);
    //    SchemaPartitionInfo schemaPartitionInfo =
    // processor.getSchemaPartition(getSchemaPartitionReq);
    //    Assert.assertNotNull(schemaPartitionInfo);
    //    Assert.assertNotNull(schemaPartitionInfo.getSchemaRegionDataNodesMap().get(sg));
    //
    //    // because does not apply schema partition, so schema partition is null
    //
    // Assert.assertNull(schemaPartitionInfo.getSchemaRegionDataNodesMap().get(sg).get(deviceGroupId));
    //
    //    // applySchemaPartition
    //    deviceGroupIds.add(deviceGroupId);
    //    getSchemaPartitionReq.setStorageGroup(sg).setDeviceGroupIDs(deviceGroupIds);
    //    schemaPartitionInfo = processor.applySchemaPartition(getSchemaPartitionReq);
    //    Assert.assertNotNull(schemaPartitionInfo);
    //    Assert.assertNotNull(schemaPartitionInfo.getSchemaRegionDataNodesMap().get(sg));
    //    schemaPartitionInfo
    //        .getSchemaRegionDataNodesMap()
    //        .get(sg)
    //        .forEach((key, value) -> Assert.assertEquals(deviceGroupId, key));
    //
    //    // getSchemaPartition twice
    //    getSchemaPartitionReq = new GetSchemaPartitionReq();
    //    deviceGroupIds = new ArrayList<>();
    //    deviceGroupIds.add(deviceGroupId);
    //    getSchemaPartitionReq.setStorageGroup(sg).setDeviceGroupIDs(deviceGroupIds);
    //    schemaPartitionInfo = processor.getSchemaPartition(getSchemaPartitionReq);
    //    Assert.assertNotNull(schemaPartitionInfo);
    //    Assert.assertNotNull(schemaPartitionInfo.getSchemaRegionDataNodesMap().get(sg));
    //
    //    // because apply schema partition, so schema partition is not null
    //    Assert.assertNotNull(
    //        schemaPartitionInfo.getSchemaRegionDataNodesMap().get(sg).get(deviceGroupId));
  }

  private Map<String, Map<TSeriesPartitionSlot, List<TTimePartitionSlot>>>
      constructPartitionSlotsMap(int sgNum, int seriesPartitionSlotNum, long timePartitionSlotNum) {
    final String sg = "root.sg";
    Map<String, Map<TSeriesPartitionSlot, List<TTimePartitionSlot>>> result = new HashMap<>();

    for (int i = 0; i < sgNum; i++) {
      String storageGroup = sg + i;
      result.put(storageGroup, new HashMap<>());
      for (int j = 0; j < seriesPartitionSlotNum; j++) {
        TSeriesPartitionSlot seriesPartitionSlot = new TSeriesPartitionSlot(j);
        result.get(storageGroup).put(seriesPartitionSlot, new ArrayList<>());
        for (long k = 0; k < timePartitionSlotNum; k++) {
          TTimePartitionSlot timePartitionSlot = new TTimePartitionSlot(k);
          result.get(storageGroup).get(seriesPartitionSlot).add(timePartitionSlot);
        }
      }
    }

    return result;
  }

  private void checkDataPartitionMap(
      int sgNum,
      int seriesPartitionSlotNum,
      long timePartitionSlotNum,
      Map<String, Map<TSeriesPartitionSlot, Map<TTimePartitionSlot, List<TRegionReplicaSet>>>>
          dataPartitionMap) {
    final String sg = "root.sg";
    Assert.assertEquals(sgNum, dataPartitionMap.size());
    for (int i = 0; i < sgNum; i++) {
      String storageGroup = sg + i;
      Assert.assertTrue(dataPartitionMap.containsKey(storageGroup));
      Assert.assertEquals(seriesPartitionSlotNum, dataPartitionMap.get(storageGroup).size());
      for (int j = 0; j < seriesPartitionSlotNum; j++) {
        TSeriesPartitionSlot seriesPartitionSlot = new TSeriesPartitionSlot(j);
        Assert.assertTrue(dataPartitionMap.get(storageGroup).containsKey(seriesPartitionSlot));
        Assert.assertEquals(
            timePartitionSlotNum,
            dataPartitionMap.get(storageGroup).get(seriesPartitionSlot).size());
        for (long k = 0; k < timePartitionSlotNum; k++) {
          TTimePartitionSlot timePartitionSlot = new TTimePartitionSlot(k);
          Assert.assertTrue(
              dataPartitionMap
                  .get(storageGroup)
                  .get(seriesPartitionSlot)
                  .containsKey(timePartitionSlot));
          // One RegionReplicaSet
          Assert.assertEquals(
              1,
              dataPartitionMap
                  .get(storageGroup)
                  .get(seriesPartitionSlot)
                  .get(timePartitionSlot)
                  .size());
          // Including three RegionReplica
          Assert.assertEquals(
              3,
              dataPartitionMap
                  .get(storageGroup)
                  .get(seriesPartitionSlot)
                  .get(timePartitionSlot)
                  .get(0)
                  .getEndpointSize());
        }
      }
    }
  }

  @Test
  public void getAndCreateDataPartitionTest() throws TException {
    TSStatus status;

    // register DataNodes
    TDataNodeRegisterReq registerReq0 = new TDataNodeRegisterReq(new EndPoint("0.0.0.0", 6667));
    TDataNodeRegisterReq registerReq1 = new TDataNodeRegisterReq(new EndPoint("0.0.0.0", 6668));
    TDataNodeRegisterReq registerReq2 = new TDataNodeRegisterReq(new EndPoint("0.0.0.0", 6669));
    status = processor.registerDataNode(registerReq0).getStatus();
    Assert.assertEquals(TSStatusCode.SUCCESS_STATUS.getStatusCode(), status.getCode());
    status = processor.registerDataNode(registerReq1).getStatus();
    Assert.assertEquals(TSStatusCode.SUCCESS_STATUS.getStatusCode(), status.getCode());
    status = processor.registerDataNode(registerReq2).getStatus();
    Assert.assertEquals(TSStatusCode.SUCCESS_STATUS.getStatusCode(), status.getCode());

    final String sg = "root.sg";
    final int sgNum = 2;
    final int seriesPartitionSlotNum = 4;
    final long timePartitionSlotNum = 6;

    // Prepare partitionSlotsMap
    Map<String, Map<TSeriesPartitionSlot, List<TTimePartitionSlot>>> partitionSlotsMap0 =
        constructPartitionSlotsMap(sgNum, seriesPartitionSlotNum, timePartitionSlotNum);
    Map<String, Map<TSeriesPartitionSlot, List<TTimePartitionSlot>>> partitionSlotsMap1 =
        constructPartitionSlotsMap(sgNum * 2, seriesPartitionSlotNum * 2, timePartitionSlotNum * 2);

    // set StorageGroups
    for (int i = 0; i < sgNum; i++) {
      TSetStorageGroupReq setReq = new TSetStorageGroupReq(sg + i);
      status = processor.setStorageGroup(setReq);
      Assert.assertEquals(TSStatusCode.SUCCESS_STATUS.getStatusCode(), status.getCode());
    }

    // Test getDataPartition, the result should be empty
    TDataPartitionReq dataPartitionReq = new TDataPartitionReq();
    dataPartitionReq.setPartitionSlotsMap(partitionSlotsMap0);
    TDataPartitionResp dataPartitionResp = processor.getDataPartition(dataPartitionReq);
    Assert.assertEquals(
        TSStatusCode.SUCCESS_STATUS.getStatusCode(), dataPartitionResp.getStatus().getCode());
    Assert.assertNotNull(dataPartitionResp.getDataPartitionMap());
    Assert.assertEquals(0, dataPartitionResp.getDataPartitionMapSize());

    // Test getOrCreateDataPartition, ConfigNode should create DataPartition for PartitionSlots
    dataPartitionResp = processor.getOrCreateDataPartition(dataPartitionReq);
    Assert.assertEquals(
        TSStatusCode.SUCCESS_STATUS.getStatusCode(), dataPartitionResp.getStatus().getCode());
    Assert.assertNotNull(dataPartitionResp.getDataPartitionMap());
    checkDataPartitionMap(
        sgNum,
        seriesPartitionSlotNum,
        timePartitionSlotNum,
        dataPartitionResp.getDataPartitionMap());

    // Test getDataPartition, the result should only contain DataPartition created before
    dataPartitionReq.setPartitionSlotsMap(partitionSlotsMap1);
    dataPartitionResp = processor.getDataPartition(dataPartitionReq);
    Assert.assertEquals(
        TSStatusCode.SUCCESS_STATUS.getStatusCode(), dataPartitionResp.getStatus().getCode());
    Assert.assertNotNull(dataPartitionResp.getDataPartitionMap());
    checkDataPartitionMap(
        sgNum,
        seriesPartitionSlotNum,
        timePartitionSlotNum,
        dataPartitionResp.getDataPartitionMap());
  }
}