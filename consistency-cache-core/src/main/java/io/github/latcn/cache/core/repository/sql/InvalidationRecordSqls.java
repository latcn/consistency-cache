package io.github.latcn.cache.core.repository.sql;

import static java.util.Map.entry;

import io.github.latcn.cache.core.exception.CacheError;
import io.github.latcn.cache.core.exception.CacheException;
import io.github.latcn.cache.core.util.StringUtil;
import java.util.Map;

public class InvalidationRecordSqls {

	private static final String INVALIDATION_RECORD_REPLACE = " #recordTable# ";

	/**
	 * used for oracle. eg: and ROWNUM <= 10
	 */
	private static final String ORACLE_QUERY_LIMIT = " and ROWNUM <= ? ";

	/**
	 * used for mysql, pgsql and mariadb. eg: limit 10
	 */
	private static final String NONE_ORACLE_QUERY_LIMIT = " limit ? ";

	public static class SqlNames {

		public final static String INSERT = "insert";

		public final static String FIND_BY_UID_AND_CACHE_KEY = "findByUidAndCacheKey";

		public final static String FIND_PENDING_RECORDS_OLDER_THAN = "findPendingRecordsOlderThan";

		public final static String MARK_COMPLETED = "markCompleted";

		public final static String MARK_FAILED = "markFailed";

		public final static String DELETE_OLD_COMPLETED_RECORDS = "deleteOldCompletedRecords";

		public final static String GET_PENDING_COUNT = "getPendingCount";

		public final static String GET_FAILED_COUNT = "getFailedCount";

	}

	/**
	 * The constant invalidation_record
	 */

	private static final Map<String, String> sqlMap = Map.ofEntries(entry(SqlNames.INSERT, "insert into "
			+ INVALIDATION_RECORD_REPLACE
			+ " (uid, cache_key, cache_level, consistency_level, operation_type, node_id, create_time, update_time) "
			+ " values (?, ?, ?, ?, ?, ?, ?, ?) "),
			entry(SqlNames.FIND_BY_UID_AND_CACHE_KEY,
					"SELECT * FROM " + INVALIDATION_RECORD_REPLACE + " WHERE uid = ? and cache_key= ? for update"),
			entry(SqlNames.FIND_PENDING_RECORDS_OLDER_THAN,
					"SELECT * FROM " + INVALIDATION_RECORD_REPLACE + " WHERE "
							+ " status = 0 AND create_time < ? ORDER BY create_time ASC"),
			entry(SqlNames.MARK_COMPLETED,
					"UPDATE " + INVALIDATION_RECORD_REPLACE
							+ " SET status = 1, update_time = ? WHERE uid = ? and cache_key = ?"),
			entry(SqlNames.MARK_FAILED,
					"UPDATE " + INVALIDATION_RECORD_REPLACE + "  SET retry_count = retry_count + 1, "
							+ " error_message = ?, update_time = ? WHERE uid = ? and cache_key = ?"),
			entry(SqlNames.DELETE_OLD_COMPLETED_RECORDS,
					"DELETE FROM " + INVALIDATION_RECORD_REPLACE + " WHERE status = 1 AND create_time < ?"),
			entry(SqlNames.GET_PENDING_COUNT,
					"SELECT COUNT(*) FROM " + INVALIDATION_RECORD_REPLACE + " WHERE status = 0"),
			entry(SqlNames.GET_FAILED_COUNT,
					"SELECT COUNT(*) FROM " + INVALIDATION_RECORD_REPLACE + " WHERE status = 2"));

	public static String getSQL(String methodName, String recordTable) {
		String sql = sqlMap.get(methodName);
		if (StringUtil.isNullOrEmpty(sql)) {
			throw new CacheException(CacheError.DB_QUERY_FAILED, "can't find " + methodName + " sql");
		}
		return sql.replace(INVALIDATION_RECORD_REPLACE, recordTable);
	}

	public static String getLimitQuerySQL(String methodName, String recordTable, boolean isOracle) {
		String sql = getSQL(methodName, recordTable);
		if (StringUtil.isNullOrEmpty(sql)) {
			return sql;
		}
		StringBuilder sqlBuilder = new StringBuilder(sql);
		if (isOracle) {
			sqlBuilder.append(ORACLE_QUERY_LIMIT);
		}
		else {
			sqlBuilder.append(NONE_ORACLE_QUERY_LIMIT);
		}
		return sqlBuilder.toString();
	}

}
