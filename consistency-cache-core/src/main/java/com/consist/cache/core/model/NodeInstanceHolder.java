package com.consist.cache.core.model;

import cn.hutool.core.lang.generator.SnowflakeGenerator;

import java.util.UUID;

public class NodeInstanceHolder {

   /* private static volatile NodeInstance INSTANCE = null;

    private static NodeInstance getInstance() {
        if (INSTANCE==null) {
            synchronized (NodeInstanceHolder.class) {
                if (INSTANCE == null) {
                    INSTANCE = new NodeInstance();
                }
            }
        }
        return INSTANCE;
    }*/

    public static String getNodeId() {
        return NodeInstance.nodeId;
    }

    public static SnowflakeGenerator getSnowflakeGenerator() {
        return NodeInstance.snowflakeGenerator;
    }

    static class NodeInstance {
        private static final String nodeId;
        private static final SnowflakeGenerator snowflakeGenerator;

        static {
            String temp_node_id = UUID.randomUUID().toString();
            try {
                String host = java.net.InetAddress.getLocalHost().getHostName();
                temp_node_id =  host + "-" + temp_node_id;
            } catch (Exception e) {
                temp_node_id = "unknown-" + temp_node_id;
            }
            nodeId = temp_node_id;
            snowflakeGenerator = new SnowflakeGenerator(nodeId.hashCode()&31, 0);
        }

        /*public String getNodeId() {
            return nodeId;
        }

        public SnowflakeGenerator getSnowflakeGenerator() {
            return snowflakeGenerator;
        }*/
    }
}
