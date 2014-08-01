package org.apache.helix.controller.stages;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.util.Map;

import org.apache.helix.HelixManager;
import org.apache.helix.controller.pipeline.AbstractBaseStage;
import org.apache.helix.controller.pipeline.StageException;
import org.apache.helix.controller.rebalancer.AutoRebalancer;
import org.apache.helix.controller.rebalancer.CustomRebalancer;
import org.apache.helix.controller.rebalancer.Rebalancer;
import org.apache.helix.controller.rebalancer.SemiAutoRebalancer;
import org.apache.helix.controller.rebalancer.internal.MappingCalculator;
import org.apache.helix.model.IdealState;
import org.apache.helix.model.Partition;
import org.apache.helix.model.Resource;
import org.apache.helix.model.ResourceAssignment;
import org.apache.helix.monitoring.mbeans.ClusterStatusMonitor;
import org.apache.helix.util.HelixUtil;
import org.apache.log4j.Logger;

/**
 * For partition compute best possible (instance,state) pair based on
 * IdealState,StateModel,LiveInstance
 */
public class BestPossibleStateCalcStage extends AbstractBaseStage {
  private static final Logger logger = Logger.getLogger(BestPossibleStateCalcStage.class.getName());

  @Override
  public void process(ClusterEvent event) throws Exception {
    long startTime = System.currentTimeMillis();
    logger.info("START BestPossibleStateCalcStage.process()");

    CurrentStateOutput currentStateOutput =
        event.getAttribute(AttributeName.CURRENT_STATE.toString());
    Map<String, Resource> resourceMap = event.getAttribute(AttributeName.RESOURCES.toString());
    ClusterDataCache cache = event.getAttribute("ClusterDataCache");

    if (currentStateOutput == null || resourceMap == null || cache == null) {
      throw new StageException("Missing attributes in event:" + event
          + ". Requires CURRENT_STATE|RESOURCES|DataCache");
    }

    BestPossibleStateOutput bestPossibleStateOutput =
        compute(event, resourceMap, currentStateOutput);
    event.addAttribute(AttributeName.BEST_POSSIBLE_STATE.name(), bestPossibleStateOutput);

    try {
      ClusterStatusMonitor clusterStatusMonitor =
          (ClusterStatusMonitor) event.getAttribute("clusterStatusMonitor");
      if (clusterStatusMonitor != null) {
        clusterStatusMonitor.setPerInstanceResourceStatus(bestPossibleStateOutput,
            cache.getInstanceConfigMap(), resourceMap, cache.getStateModelDefMap());
      }
    } catch (Exception e) {
      logger.error("Could not update cluster status metrics!", e);
    }

    long endTime = System.currentTimeMillis();
    logger.info("END BestPossibleStateCalcStage.process(). took: " + (endTime - startTime) + " ms");
  }

  private BestPossibleStateOutput compute(ClusterEvent event, Map<String, Resource> resourceMap,
      CurrentStateOutput currentStateOutput) {
    // for each ideal state
    // read the state model def
    // for each resource
    // get the preference list
    // for each instanceName check if its alive then assign a state
    ClusterDataCache cache = event.getAttribute("ClusterDataCache");

    BestPossibleStateOutput output = new BestPossibleStateOutput();

    for (String resourceName : resourceMap.keySet()) {
      logger.debug("Processing resource:" + resourceName);

      Resource resource = resourceMap.get(resourceName);
      // Ideal state may be gone. In that case we need to get the state model name
      // from the current state
      IdealState idealState = cache.getIdealState(resourceName);

      if (idealState == null) {
        // if ideal state is deleted, use an empty one
        logger.info("resource:" + resourceName + " does not exist anymore");
        idealState = new IdealState(resourceName);
        idealState.setStateModelDefRef(resource.getStateModelDefRef());
      }

      Rebalancer rebalancer = null;
      MappingCalculator mappingCalculator = null;
      switch (idealState.getRebalanceMode()) {
      case FULL_AUTO:
        AutoRebalancer autoRebalancer = new AutoRebalancer();
        rebalancer = autoRebalancer;
        mappingCalculator = autoRebalancer;
        break;
      case SEMI_AUTO:
        SemiAutoRebalancer semiAutoRebalancer = new SemiAutoRebalancer();
        rebalancer = semiAutoRebalancer;
        mappingCalculator = semiAutoRebalancer;
        break;
      case CUSTOMIZED:
        CustomRebalancer customRebalancer = new CustomRebalancer();
        rebalancer = customRebalancer;
        mappingCalculator = customRebalancer;
        break;
      case USER_DEFINED:
      case TASK:
        String rebalancerClassName = idealState.getRebalancerClassName();
        logger
            .info("resource " + resourceName + " use idealStateRebalancer " + rebalancerClassName);
        try {
          rebalancer =
              Rebalancer.class.cast(HelixUtil.loadClass(getClass(), rebalancerClassName)
                  .newInstance());
        } catch (Exception e) {
          logger.warn("Exception while invoking custom rebalancer class:" + rebalancerClassName, e);
        }
        if (rebalancer != null) {
          try {
            mappingCalculator = MappingCalculator.class.cast(rebalancer);
          } catch (ClassCastException e) {
            logger.info("Rebalancer does not have a mapping calculator, defaulting to SEMI_AUTO");
          }
        }
        if (mappingCalculator == null) {
          mappingCalculator = new SemiAutoRebalancer();
        }
        break;
      default:
        break;
      }
      if (rebalancer != null && mappingCalculator != null) {
        try {
          HelixManager manager = event.getAttribute("helixmanager");
          rebalancer.init(manager);
          idealState =
              rebalancer.computeNewIdealState(resourceName, idealState, currentStateOutput, cache);

          // Use the internal MappingCalculator interface to compute the final assignment
          // The next release will support rebalancers that compute the mapping from start to finish
          ResourceAssignment partitionStateAssignment =
              mappingCalculator.computeBestPossiblePartitionState(cache, idealState, resource,
                  currentStateOutput);
          for (Partition partition : resource.getPartitions()) {
            Map<String, String> newStateMap = partitionStateAssignment.getReplicaMap(partition);
            output.setState(resourceName, partition, newStateMap);
          }
        } catch (Exception e) {
          logger
              .error("Error computing assignment for resource " + resourceName + ". Skipping.", e);
        }
      }
    }
    return output;
  }
}
