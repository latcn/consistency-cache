package io.github.latcn.cache.core.repository;

import io.github.latcn.cache.core.exception.CacheError;
import io.github.latcn.cache.core.exception.CacheException;
import io.github.latcn.cache.core.model.InvalidationRecord;
import io.github.latcn.cache.core.repository.sql.InvalidationRecordSqls;
import io.github.latcn.cache.core.util.IOUtil;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class InvalidationRecordDAO implements InvalidationRecordRepository {

	private static String DEFAULT_LOG_TABLE_NAME = "invalidation_record";

	private static volatile InvalidationRecordDAO instance;

	private InvalidationRecordDAO() {
	}

	public static InvalidationRecordDAO getInstance() {
		if (instance == null) {
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
			String sql = InvalidationRecordSqls.getSQL(InvalidationRecordSqls.SqlNames.INSERT, DEFAULT_LOG_TABLE_NAME);
			ps = conn.prepareStatement(sql);
			ps.setString(1, record.getUid());
			ps.setString(2, record.getCacheKey());
			ps.setString(3, record.getCacheLevel());
			ps.setString(4, record.getConsistencyLevel());
			ps.setString(5, record.getOperationType());
			ps.setString(6, record.getNodeId());
			ps.setTimestamp(7, now);
			ps.setTimestamp(8, now);
			return ps.executeUpdate() > 0;
		}
		catch (SQLIntegrityConstraintViolationException e) {
			throw new CacheException(CacheError.DB_DUPLICATE_KEY,
					String.format("Insert invalidation record duplicate key exception. cacheKey= %s, nodeId= %s",
							record.getCacheKey(), record.getNodeId()));
		}
		catch (SQLException e) {
			log.error("insert error", e);
			throw CacheException.wrap(e, CacheError.DB_INSERT_FAILED);
		}
		finally {
			IOUtil.close(ps);
		}
	}

	@Override
	public List<InvalidationRecord> findPendingRecordsOlderThan(Connection conn, int thresholdSeconds, int limit) {
		PreparedStatement ps = null;
		ResultSet rs = null;
		List<InvalidationRecord> list = new ArrayList<>();
		try {
			Timestamp createTimeParam = new Timestamp(System.currentTimeMillis() - thresholdSeconds * 1000);
			String sql = InvalidationRecordSqls.getLimitQuerySQL("findPendingRecordsOlderThan", DEFAULT_LOG_TABLE_NAME,
					isOracle(conn));
			ps = conn.prepareStatement(sql);
			ps.setTimestamp(1, createTimeParam);
			ps.setInt(2, limit);
			rs = ps.executeQuery();
			while (rs.next()) {
				InvalidationRecord invalidationRecord = convert(rs);
				list.add(invalidationRecord);
			}
		}
		catch (SQLException e) {
			log.error("findPendingRecordsOlderThan error", e);
			throw CacheException.wrap(e, CacheError.DB_QUERY_FAILED);
		}
		finally {
			IOUtil.close(rs, ps);
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
			String sql = InvalidationRecordSqls.getLimitQuerySQL(
					InvalidationRecordSqls.SqlNames.FIND_BY_UID_AND_CACHE_KEY, DEFAULT_LOG_TABLE_NAME, isOracle(conn));
			ps = conn.prepareStatement(sql);
			ps.setString(1, uid);
			ps.setString(2, cacheKey);
			rs = ps.executeQuery();
			while (rs.next()) {
				InvalidationRecord invalidationRecord = convert(rs);
				list.add(invalidationRecord);
			}
		}
		catch (SQLException e) {
			log.error("findByUidAndCacheKey error", e);
			throw CacheException.wrap(e, CacheError.DB_QUERY_FAILED);
		}
		finally {
			IOUtil.close(rs, ps);
		}
		return list;
	}

	@Override
	public boolean markCompleted(Connection conn, String uid, String cacheKey) {
		PreparedStatement ps = null;
		try {
			Timestamp now = new Timestamp(System.currentTimeMillis());
			String sql = InvalidationRecordSqls.getSQL(InvalidationRecordSqls.SqlNames.MARK_COMPLETED,
					DEFAULT_LOG_TABLE_NAME);
			ps = conn.prepareStatement(sql);
			ps.setTimestamp(1, now);
			ps.setString(2, uid);
			ps.setString(3, cacheKey);
			return ps.executeUpdate() > 0;
		}
		catch (SQLException e) {
			log.error("markCompleted error", e);
			throw CacheException.wrap(e, CacheError.DB_UPDATE_FAILED);
		}
		finally {
			IOUtil.close(ps);
		}
	}

	@Override
	public boolean markFailed(Connection conn, String uid, String cacheKey, String errorMessage) {
		PreparedStatement ps = null;
		try {
			Timestamp now = new Timestamp(System.currentTimeMillis());
			String sql = InvalidationRecordSqls.getSQL(InvalidationRecordSqls.SqlNames.MARK_FAILED,
					DEFAULT_LOG_TABLE_NAME);
			ps = conn.prepareStatement(sql);
			ps.setString(1, errorMessage);
			ps.setTimestamp(2, now);
			ps.setString(3, uid);
			ps.setString(4, cacheKey);
			return ps.executeUpdate() > 0;
		}
		catch (SQLException e) {
			log.error("markFailed error", e);
			throw CacheException.wrap(e, CacheError.DB_UPDATE_FAILED);
		}
		finally {
			IOUtil.close(ps);
		}
	}

	@Override
	public int deleteOldCompletedRecords(Connection conn, long thresholdSeconds) {
		PreparedStatement ps = null;
		try {
			Timestamp createTimeParam = new Timestamp(System.currentTimeMillis() - thresholdSeconds * 1000);
			String sql = InvalidationRecordSqls.getSQL(InvalidationRecordSqls.SqlNames.DELETE_OLD_COMPLETED_RECORDS,
					DEFAULT_LOG_TABLE_NAME);
			ps = conn.prepareStatement(sql);
			ps.setTimestamp(1, createTimeParam);
			return ps.executeUpdate();
		}
		catch (SQLException e) {
			log.error("deleteOldCompletedRecords error", e);
			throw CacheException.wrap(e, CacheError.DB_DELETE_FAILED);
		}
		finally {
			IOUtil.close(ps);
		}
	}

	@Override
	public long getPendingCount(Connection conn) {
		return commonNoParamQuery(InvalidationRecordSqls.SqlNames.GET_PENDING_COUNT, conn);
	}

	@Override
	public long getFailedCount(Connection conn) {
		return commonNoParamQuery(InvalidationRecordSqls.SqlNames.GET_FAILED_COUNT, conn);
	}

	private long commonNoParamQuery(String methodName, Connection conn) {
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			String sql = InvalidationRecordSqls.getSQL(methodName, DEFAULT_LOG_TABLE_NAME);
			ps = conn.prepareStatement(sql);
			rs = ps.executeQuery();
			long count = 0;
			while (rs.next()) {
				count = rs.getLong(1);
			}
			return count;
		}
		catch (SQLException e) {
			log.error("{} error", methodName, e);
			throw CacheException.wrap(e, CacheError.DB_QUERY_FAILED);
		}
		finally {
			IOUtil.close(rs, ps);
		}
	}

	private static boolean isOracle(Connection connection) {
		try {
			String url = connection.getMetaData().getURL();
			return url.toLowerCase().contains(":oracle:");
		}
		catch (SQLException e) {
			log.error("get db type fail", e);
		}
		return false;
	}

	private static InvalidationRecord convert(ResultSet rs) throws SQLException {
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
		}
		catch (SQLException e) {
			throw e;
		}
	}

}
