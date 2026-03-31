package com.consist.cache.core.repository;

import com.consist.cache.core.exception.CacheException;
import com.consist.cache.core.model.InvalidationRecord;
import com.consist.cache.core.repository.sql.InvalidationRecordSqls;
import com.consist.cache.core.util.IOUtil;
import lombok.extern.slf4j.Slf4j;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public class InvalidationRecordDAO implements InvalidationRecordRepository {

    private static String DEFAULT_LOG_TABLE_NAME = "invalidation_record";
    private static volatile InvalidationRecordDAO instance;

    private InvalidationRecordDAO() {
    }

    public static InvalidationRecordDAO getInstance() {
        if (instance==null) {
            synchronized (InvalidationRecordDAO.class) {
                if (instance == null) {
                    instance = new InvalidationRecordDAO();
                }
            }
        }
        return instance;
    }

    @Override
    public boolean insert(Connection conn, InvalidationRecord record) {
        PreparedStatement ps = null;
        try {
            Timestamp now = new Timestamp(System.currentTimeMillis());
            String sql = InvalidationRecordSqls.getSQL("insert", DEFAULT_LOG_TABLE_NAME);
            ps = conn.prepareStatement(sql);
            ps.setString(1, record.getUid());
            ps.setString(2, record.getCacheKey());
            ps.setString(3, record.getOperationType());
            ps.setString(4, record.getNodeId());
            ps.setInt(5, record.getStatus());
            ps.setTimestamp(6, now);
            ps.setTimestamp(7, now);
            return ps.executeUpdate() > 0;
        } catch (SQLIntegrityConstraintViolationException e) {
            throw new CacheException(
                    String.format(
                            "Insert invalidation record duplicate key exception. cacheKey= %s, nodeId= %s",
                            record.getCacheKey(), record.getNodeId()));
        } catch (SQLException e) {
            log.error("insert error", e);
            throw new CacheException(e.getErrorCode(), "insert error:"+e.getMessage());
        } finally {
            IOUtil.close(ps);
        }
    }

    @Override
    public List<InvalidationRecord> findPendingRecordsOlderThan(Connection conn, int thresholdSeconds, int limit) {
        PreparedStatement ps = null;
        ResultSet rs = null;
        List<InvalidationRecord> list = new ArrayList<>();
        try {
            Timestamp createTimeParam = new Timestamp(System.currentTimeMillis()-thresholdSeconds*1000);
            String sql = InvalidationRecordSqls.getLimitQuerySQL("findPendingRecordsOlderThan",
                    DEFAULT_LOG_TABLE_NAME, isOracle(conn));
            ps = conn.prepareStatement(sql);
            ps.setTimestamp(1, createTimeParam);
            ps.setInt(2, limit);
            rs = ps.executeQuery();
            while (rs.next()) {
                InvalidationRecord invalidationRecord = convert(rs);
                list.add(invalidationRecord);
            }
        } catch (SQLException e) {
            log.error("findPendingRecordsOlderThan error", e);
            throw new CacheException(e.getErrorCode(), "findPendingRecordsOlderThan error:"+e.getMessage());
        } finally {
            IOUtil.close(ps);
        }
        return list;
    }

    /**
     * findByUidAndCacheKey
     * @param conn
     * @param uid
     * @param cacheKey
     * @return
     */
    @Override
    public List<InvalidationRecord> findByUidAndCacheKey(Connection conn, String uid, String cacheKey) {
        PreparedStatement ps = null;
        ResultSet rs = null;
        List<InvalidationRecord> list = new ArrayList<>();
        try {
            String sql = InvalidationRecordSqls.getLimitQuerySQL("findByUidAndCacheKey",
                    DEFAULT_LOG_TABLE_NAME, isOracle(conn));
            ps = conn.prepareStatement(sql);
            ps.setString(1, uid);
            ps.setString(2, cacheKey);
            rs = ps.executeQuery();
            while (rs.next()) {
                InvalidationRecord invalidationRecord = convert(rs);
                list.add(invalidationRecord);
            }
        } catch (SQLException e) {
            log.error("findByUidAndCacheKey error", e);
            throw new CacheException(e.getErrorCode(), "findByUidAndCacheKey error:"+e.getMessage());
        } finally {
            IOUtil.close(ps);
        }
        return list;
    }

    @Override
    public boolean markCompleted(Connection conn, String uid, String cacheKey) {
        PreparedStatement ps = null;
        try {
            Timestamp now = new Timestamp(System.currentTimeMillis());
            String sql = InvalidationRecordSqls.getSQL("markCompleted", DEFAULT_LOG_TABLE_NAME);
            ps = conn.prepareStatement(sql);
            ps.setTimestamp(1, now);
            ps.setString(2, uid);
            ps.setString(3, cacheKey);
            return ps.executeUpdate()>0;
        } catch (SQLException e) {
            log.error("findPendingRecordsOlderThan error", e);
            throw new CacheException(e.getErrorCode(), "findPendingRecordsOlderThan error:"+e.getMessage());
        } finally {
            IOUtil.close(ps);
        }
    }

    @Override
    public boolean markFailed(Connection conn, String uid, String cacheKey, String errorMessage) {
        PreparedStatement ps = null;
        try {
            Timestamp now = new Timestamp(System.currentTimeMillis());
            String sql = InvalidationRecordSqls.getSQL("markFailed", DEFAULT_LOG_TABLE_NAME);
            ps = conn.prepareStatement(sql);
            ps.setString(1, errorMessage);
            ps.setTimestamp(2, now);
            ps.setString(2, uid);
            ps.setString(3, cacheKey);
            return ps.executeUpdate()>0;
        } catch (SQLException e) {
            log.error("findPendingRecordsOlderThan error", e);
            throw new CacheException(e.getErrorCode(), "findPendingRecordsOlderThan error:"+e.getMessage());
        } finally {
            IOUtil.close(ps);
        }
    }

    @Override
    public int deleteOldCompletedRecords(Connection conn, long thresholdSeconds) {
        PreparedStatement ps = null;
        try {
            Timestamp createTimeParam = new Timestamp(System.currentTimeMillis()-thresholdSeconds*1000);
            String sql = InvalidationRecordSqls.getSQL("deleteOldCompletedRecords", DEFAULT_LOG_TABLE_NAME);
            ps = conn.prepareStatement(sql);
            ps.setTimestamp(1, createTimeParam);
            return ps.executeUpdate();
        } catch (SQLException e) {
            log.error("deleteOldCompletedRecords error", e);
            throw new CacheException(e.getErrorCode(), "deleteOldCompletedRecords error:"+e.getMessage());
        } finally {
            IOUtil.close(ps);
        }
    }

    @Override
    public long getPendingCount(Connection conn) {
        return commonNoParamQuery("getPendingCount", conn);
    }

    @Override
    public long getFailedCount(Connection conn) {
        return commonNoParamQuery("getFailedCount", conn);
    }

    private long commonNoParamQuery(String methodName, Connection conn) {
        PreparedStatement ps = null;
        try {
            String sql = InvalidationRecordSqls.getSQL(methodName, DEFAULT_LOG_TABLE_NAME);
            ps = conn.prepareStatement(sql);
            return ps.executeUpdate();
        } catch (SQLException e) {
            log.error("{} error", methodName, e);
            throw new CacheException(e.getErrorCode(), methodName+" error:"+e.getMessage());
        } finally {
            IOUtil.close(ps);
        }
    }

    private static boolean isOracle(Connection connection) {
        try {
            String url = connection.getMetaData().getURL();
            return url.toLowerCase().contains(":oracle:");
        } catch (SQLException e) {
            log.error("get db type fail", e);
        }
        return false;
    }

    private static InvalidationRecord convert(ResultSet rs) throws SQLException{
        try {
            InvalidationRecord invalidationRecord = new InvalidationRecord();
            invalidationRecord.setId(rs.getLong("id"));
            invalidationRecord.setUid(rs.getString("uid"));
            invalidationRecord.setCacheKey(rs.getString("cache_key"));
            invalidationRecord.setOperationType(rs.getString("operation_type"));
            invalidationRecord.setStatus(rs.getInt("status"));
            invalidationRecord.setRetryCount(rs.getInt("retry_count"));
            invalidationRecord.setErrorMessage(rs.getString("error_message"));
            invalidationRecord.setNodeId(rs.getString("node_id"));
            invalidationRecord.setCreateTime(rs.getTimestamp("create_time"));
            invalidationRecord.setUpdateTime(rs.getTimestamp("update_time"));
            return invalidationRecord;
        } catch (SQLException e) {
            throw e;
        }
    }
}
