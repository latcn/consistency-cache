CREATE TABLE `hcc_cache_message` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `uid` varchar(64) NOT NULL COMMENT '唯一标识符 (Snowflake ID)',
  `cache_key` varchar(255) NOT NULL COMMENT '需要失效的缓存 Key，格式：cacheName:key',
  `cache_level` varchar(32) DEFAULT NULL COMMENT '缓存级别 (L1/L2/ALL)',
  `consistency_level` varchar(32) DEFAULT NULL COMMENT '一致性级别 (HIGH/AVAILABLE)',
  `operation_type` varchar(32) NOT NULL COMMENT '操作类型 (DELETE/UPDATE)',
  `status` tinyint(4) NOT NULL DEFAULT '0' COMMENT '状态：0=PENDING, 1=COMPLETED, 2=FAILED',
  `retry_count` int(11) DEFAULT '0' COMMENT '重试次数',
  `error_message` text COMMENT '失败时的错误信息',
  `node_id` varchar(128) DEFAULT NULL COMMENT '创建记录的节点 ID (hostname-pid)',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_uid` (`uid`),
  KEY `idx_status_create_time` (`status`, `create_time`),
  KEY `idx_node_id` (`node_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='缓存失效消息表（事务发件箱模式）';

