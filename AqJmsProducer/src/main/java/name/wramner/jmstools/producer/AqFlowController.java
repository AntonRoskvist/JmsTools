/*
 * Copyright 2016 Erik Wramner.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package name.wramner.jmstools.producer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This flow controller monitors an AQ queue and pauses clients when the queue depth passes the high water mark. It
 * resumes when the low water mark has been reached.
 * <p>
 * The flow controller starts itself automatically when constructed. It implements {@link AutoCloseable} and stops when
 * it is closed.
 */
public class AqFlowController implements AutoCloseable, FlowController {
    private static final long MAX_SLEEP_TIME_MS = 10000L;
    private final Logger _logger = LoggerFactory.getLogger(getClass());
    private final String _queueName;
    private final String _jdbcUrl;
    private final String _jdbcUser;
    private final String _jdbcPassword;
    private final int _pauseAtDepth;
    private final int _resumeAtDepth;
    private final long _pollingIntervalMillis;
    private final Object _flowControlMonitor = new Object();
    private final Object _lifeCycleMonitor = new Object();
    private final Thread _backgroundThread;
    private boolean _aboveLimit;
    private boolean _stop;

    public AqFlowController(String jdbcUrl, String jdbcUser, String jdbcPassword, int pauseAtDepth, int resumeAtDepth,
                    String queueName, int pollingIntervalSeconds) {
        if (pauseAtDepth < 1) {
            throw new IllegalArgumentException("Pause depth must be > 0: " + pauseAtDepth);
        }
        if (resumeAtDepth >= pauseAtDepth) {
            throw new IllegalArgumentException("Resume depth (" + resumeAtDepth + ") must be less than pause depth ("
                            + pauseAtDepth + ")");
        }
        if (pollingIntervalSeconds < 1) {
            throw new IllegalArgumentException("Polling interval must be > 0: " + pollingIntervalSeconds);
        }
        _queueName = queueName;
        _pollingIntervalMillis = TimeUnit.MILLISECONDS.convert(pollingIntervalSeconds, TimeUnit.SECONDS);
        _pauseAtDepth = pauseAtDepth;
        _resumeAtDepth = resumeAtDepth;
        _jdbcUrl = jdbcUrl;
        _jdbcUser = jdbcUser;
        _jdbcPassword = jdbcPassword;
        _backgroundThread = new Thread(new QueueDepthPoller(), "AqFlowControllerThread");
        _backgroundThread.setDaemon(true);
        _backgroundThread.start();
    }

    /**
     * Stop background thread and close connection.
     */
    @Override
    public void close() {
        _logger.debug("Stopping AQ flow controller...");
        synchronized (_lifeCycleMonitor) {
            _stop = true;
            _lifeCycleMonitor.notifyAll();
        }
        try {
            _backgroundThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void sleepIfAboveLimit() {
        synchronized (_flowControlMonitor) {
            if (_aboveLimit && !_stop) {
                try {
                    long endTime = System.currentTimeMillis() + MAX_SLEEP_TIME_MS;
                    for (long sleepMillis = MAX_SLEEP_TIME_MS; _aboveLimit && sleepMillis > 0
                                    && !_stop; sleepMillis = endTime - System.currentTimeMillis()) {
                        _flowControlMonitor.wait(sleepMillis);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private class QueueDepthPoller implements Runnable {
        private static final String QUEUE_DEPTH_SQL_FMT = "select count(*) from %s where q_name = ? and state = 0";
        private static final int MAX_CONSEQUTIVE_DB_ERRORS = 10;

        @Override
        public void run() {
            _logger.debug("AQ flow controller started for {}", _queueName);
            try {
                Connection conn = null;
                PreparedStatement stat = null;
                String queueTableName = null;
                boolean paused = false;
                int errors = 0;
                while (errors < MAX_CONSEQUTIVE_DB_ERRORS && waitForStopOrPollingDelay()) {
                    try {
                        conn = DriverManager.getConnection(_jdbcUrl, _jdbcUser, _jdbcPassword);

                        if (queueTableName == null) {
                            queueTableName = determineQueueTableName(conn, _queueName);
                        }

                        stat = conn.prepareStatement(String.format(QUEUE_DEPTH_SQL_FMT, queueTableName));
                        stat.setString(1, _queueName.toUpperCase());

                        while (waitForStopOrPollingDelay()) {
                            paused = checkQueueDepthAndPauseOrResume(stat, paused);
                            errors = 0;
                        }
                    } catch (SQLException e) {
                        _logger.warn("Flow control failed with database error", e);
                        ++errors;
                    } finally {
                        closeSafely(stat);
                        closeSafely(conn);
                    }
                }
            } catch (Throwable e) {
                _logger.error("Flow controller failed, aborting!", e);
            } finally {
                setAboveLimit(false);
                _logger.debug("AQ flow controller stopped for {}", _queueName);
            }
        }

        private boolean waitForStopOrPollingDelay() {
            synchronized (_lifeCycleMonitor) {
                try {
                    long endTime = System.currentTimeMillis() + _pollingIntervalMillis;
                    for (long sleepMillis = _pollingIntervalMillis; !_stop
                                    && sleepMillis > 0; sleepMillis = endTime - System.currentTimeMillis()) {
                        _flowControlMonitor.wait(sleepMillis);
                    }
                } catch (InterruptedException e) {
                    _logger.debug("Interrupted, stopping!", e);
                    _stop = true;
                }
                return !_stop;
            }
        }

        private String determineQueueTableName(Connection conn, String queueName) throws SQLException {
            PreparedStatement stat = null;
            ResultSet rs = null;
            try {
                stat = conn.prepareStatement("select queue_table from user_queues where name = ?");
                stat.setString(1, queueName.toUpperCase());
                rs = stat.executeQuery();
                if (rs.next()) {
                    return rs.getString(1);
                } else {
                    throw new SQLException("Queue table for " + queueName + " not found!");
                }
            } finally {
                closeSafely(rs);
                closeSafely(stat);
            }
        }

        private boolean checkQueueDepthAndPauseOrResume(PreparedStatement stat, boolean paused) throws SQLException {
            ResultSet rs = null;
            try {
                rs = stat.executeQuery();
                if (rs.next()) {
                    int waiting = rs.getInt(1);
                    if (paused && waiting <= _resumeAtDepth) {
                        _logger.info("Flow controller resuming load, current queue depth {}", waiting);
                        setAboveLimit(false);
                        paused = false;
                    } else if (!paused && waiting >= _pauseAtDepth) {
                        _logger.info("Flow controller pausing load, current queue depth {}", waiting);
                        setAboveLimit(true);
                        paused = true;
                    }
                }
            } finally {
                closeSafely(rs);
            }
            return paused;
        }

        private void closeSafely(PreparedStatement stat) {
            if (stat != null) {
                try {
                    stat.close();
                } catch (SQLException e) {
                }
            }
        }

        private void closeSafely(ResultSet rs) {
            if (rs != null) {
                try {
                    rs.close();
                } catch (SQLException e) {
                }
            }
        }

        private void closeSafely(Connection conn) {
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException e) {
                }
            }
        }

        private void setAboveLimit(boolean aboveLimit) {
            synchronized (_flowControlMonitor) {
                _aboveLimit = aboveLimit;
                if (!aboveLimit) {
                    _flowControlMonitor.notifyAll();
                }
            }
        }
    }

}
