CREATE TABLE `hcc_cache_message` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `cache_key` varchar(255) NOT NULL COMMENT '需要失效的缓存Key',
  `status` tinyint(4) NOT NULL DEFAULT '0' COMMENT '0:待处理, 1:已处理',
  `create_time` datetime NOT NULL,
  PRIMARY KEY (`id`)
);

