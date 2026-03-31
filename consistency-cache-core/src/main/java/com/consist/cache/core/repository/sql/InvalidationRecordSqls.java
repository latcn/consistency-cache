package com.consist.cache.core.repository.sql;

import com.consist.cache.core.util.StringUtil;

import java.util.Map;

import static java.util.Map.entry;

public class InvalidationRecordSqls {


    public static final String INVALIDATION_RECORD_REPLACE = " #recordTable# ";

    /**
     * used for oracle. eg: and ROWNUM <= 10
     */
    protected static final String ORACLE_QUERY_LIMIT = " and ROWNUM <= ? ";

    /**
     * used for mysql, pgsql and mariadb. eg: limit 10
     */
    protected static final String NONE_ORACLE_QUERY_LIMIT = " limit ? ";

    /**
     * The constant invalidation_record
     */

    protected static final Map<String,String> sqlMap = Map.ofEntries(
            entry("save",
                    "insert into " + INVALIDATION_RECORD_REPLACE
                    + " (uid, cache_key, operation_type, node_id, status, create_time, update_time) "
                    + " values (?, ?, ?, ?, ?, ?, ?) "),
            entry("findByUidAndCacheKey",
                    "SELECT * FROM "+INVALIDATION_RECORD_REPLACE+" WHERE uid = ? and cache_key= ? for update"),
            entry("findPendingRecordsOlderThan",
                    "SELECT * FROM "+INVALIDATION_RECORD_REPLACE+" WHERE " +
                    " status = 0 AND create_time < ? ORDER BY create_time ASC"),
            entry("markCompleted",
                    "UPDATE "+INVALIDATION_RECORD_REPLACE+" SET status = 1, update_time = ? WHERE uid = ? and cache_key = ?"),
            entry("markFailed",
                    "UPDATE "+INVALIDATION_RECORD_REPLACE+"  SET retry_count = retry_count + 1, " +
                    " error_message = ?, update_time = ? WHERE uid = ? and cache_key = ?"),
            entry("deleteOldCompletedRecords",
                    "DELETE FROM "+INVALIDATION_RECORD_REPLACE+" WHERE status = 1 AND create_time < ?"),
            entry("getPendingCount",
                    "SELECT COUNT(*) FROM "+INVALIDATION_RECORD_REPLACE+" WHERE status = 0"),
            entry("getFailedCount",
                    "SELECT COUNT(*) FROM "+INVALIDATION_RECORD_REPLACE+" WHERE status = 2")
    );


    public static String getSQL(String methodName, String recordTable) {
        String sql = sqlMap.get(methodName);
        if (StringUtil.isNullOrEmpty(sql)) {
            return sql;
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
        }else {
            sqlBuilder.append(NONE_ORACLE_QUERY_LIMIT);
        }
        return sqlBuilder.toString();
    }


}
