package io.github.latcn.cache.core.model;

import java.util.UUID;

public class NodeInstanceHolder {

	public static String getNodeId() {
		return NodeInstance.nodeId;
	}

	static class NodeInstance {

		private static final String nodeId;

		static {
			String temp_node_id = UUID.randomUUID().toString();
			try {
				String host = java.net.InetAddress.getLocalHost().getHostName();
				temp_node_id = host + "-" + temp_node_id;
			}
			catch (Exception e) {
				temp_node_id = "unknown-" + temp_node_id;
			}
			nodeId = temp_node_id;
		}

	}

}
