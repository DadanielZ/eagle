/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.eagle.metric.kafka;

import backtype.storm.generated.StormTopology;
import backtype.storm.spout.SchemeAsMultiScheme;
import backtype.storm.topology.BoltDeclarer;
import backtype.storm.topology.TopologyBuilder;
import backtype.storm.topology.base.BaseRichSpout;
import backtype.storm.tuple.Fields;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.apache.eagle.app.StormApplication;
import org.apache.eagle.app.environment.impl.StormEnvironment;
import org.apache.eagle.dataproc.impl.storm.kafka.KafkaSourcedSpoutProvider;
import org.apache.eagle.dataproc.impl.storm.kafka.KafkaSourcedSpoutScheme;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import storm.kafka.BrokerHosts;
import storm.kafka.KafkaSpout;
import storm.kafka.SpoutConfig;
import storm.kafka.ZkHosts;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Since 8/12/16.
 */
public class EagleMetricCollectorApplication extends StormApplication{
    private static final Logger LOG = LoggerFactory.getLogger(EagleMetricCollectorApplication.class);

    public final static String SPOUT_TASK_NUM = "topology.numOfSpoutTasks";
    public final static String DISTRIBUTION_TASK_NUM = "topology.numOfDistributionTasks";

    @Override
    public StormTopology execute(Config config, StormEnvironment environment) {
        String deserClsName = config.getString("dataSourceConfig.deserializerClass");
        final KafkaSourcedSpoutScheme scheme = new KafkaSourcedSpoutScheme(deserClsName, config) {
            @Override
            public List<Object> deserialize(byte[] ser) {
                Object tmp = deserializer.deserialize(ser);
                Map<String, Object> map = (Map<String, Object>)tmp;
                if(tmp == null) return null;
                return Arrays.asList(map.get("user"), map.get("timestamp"));
            }
        };

        // TODO: Refactored the anonymous in to independen class file, avoiding too complex logic in main method
        KafkaSourcedSpoutProvider kafkaMessageSpoutProvider = new KafkaSourcedSpoutProvider() {
            @Override
            public BaseRichSpout getSpout(Config context) {
                // Kafka topic
                String topic = context.getString("dataSourceConfig.topic");
                // Kafka consumer group id
                String groupId = context.getString("dataSourceConfig.metricCollectionConsumerId");
                // Kafka fetch size
                int fetchSize = context.getInt("dataSourceConfig.fetchSize");
                // Kafka deserializer class
                String deserClsName = context.getString("dataSourceConfig.deserializerClass");

                // Kafka broker zk connection
                String zkConnString = context.getString("dataSourceConfig.zkQuorum");

                // transaction zkRoot
                String zkRoot = context.getString("dataSourceConfig.transactionZKRoot");

                LOG.info(String.format("Use topic id: %s",topic));

                String brokerZkPath = null;
                if(context.hasPath("dataSourceConfig.brokerZkPath")) {
                    brokerZkPath = context.getString("dataSourceConfig.brokerZkPath");
                }

                BrokerHosts hosts;
                if(brokerZkPath == null) {
                    hosts = new ZkHosts(zkConnString);
                } else {
                    hosts = new ZkHosts(zkConnString, brokerZkPath);
                }

                SpoutConfig spoutConfig = new SpoutConfig(hosts,
                        topic,
                        zkRoot + "/" + topic,
                        groupId);

                // transaction zkServers
                String[] zkConnections = zkConnString.split(",");
                List<String> zkHosts = new ArrayList<>();
                for (String zkConnection : zkConnections) {
                    zkHosts.add(zkConnection.split(":")[0]);
                }
                Integer zkPort = Integer.valueOf(zkConnections[0].split(":")[1]);

                spoutConfig.zkServers = zkHosts;
                // transaction zkPort
                spoutConfig.zkPort = zkPort;
                // transaction update interval
                spoutConfig.stateUpdateIntervalMs = context.getLong("dataSourceConfig.transactionStateUpdateMS");
                // Kafka fetch size
                spoutConfig.fetchSizeBytes = fetchSize;

                spoutConfig.scheme = new SchemeAsMultiScheme(scheme);
                KafkaSpout kafkaSpout = new KafkaSpout(spoutConfig);
                return kafkaSpout;
            }
        };

        TopologyBuilder builder = new TopologyBuilder();
        BaseRichSpout spout1 = new KafkaOffsetSourceSpoutProvider().getSpout(config);
        BaseRichSpout spout2 = kafkaMessageSpoutProvider.getSpout(config);

        int numOfSpoutTasks = config.getInt(SPOUT_TASK_NUM);
        int numOfDistributionTasks = config.getInt(DISTRIBUTION_TASK_NUM);

        builder.setSpout("kafkaLogLagChecker", spout1, numOfSpoutTasks);
        builder.setSpout("kafkaMessageFetcher", spout2, numOfSpoutTasks);

        KafkaMessageDistributionBolt bolt = new KafkaMessageDistributionBolt(config);
        BoltDeclarer bolteclarer = builder.setBolt("distributionBolt", bolt, numOfDistributionTasks);
        bolteclarer.fieldsGrouping("kafkaLogLagChecker", new Fields("f1"));
        bolteclarer.fieldsGrouping("kafkaLogLagChecker", new Fields("f1"));
        return builder.createTopology();
    }


    public static void main(String[] args){
        Config config = ConfigFactory.load();
        EagleMetricCollectorApplication app = new EagleMetricCollectorApplication();
        app.run(config);
    }
}