package com.getindata.flink.connector.jdbc.elasticsearch.table;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class PositionalPreparedStatementTest {

    private PreparedStatement delegate;
    private PositionalPreparedStatement statement;

    @BeforeEach
    void setUp() {
        delegate = mock(PreparedStatement.class);
        statement = new PositionalPreparedStatement(delegate);
    }

    @Test
    void shouldShiftFieldIndexByOneForEverySetter() throws Exception {
        BigDecimal decimal = new BigDecimal("12.34");
        byte[] bytes = {1, 2};
        Date date = Date.valueOf("2020-01-01");
        Time time = Time.valueOf("10:00:00");
        Timestamp timestamp = Timestamp.valueOf("2020-01-01 10:00:00");
        Object object = new Object();

        statement.setNull(0, Types.VARCHAR);
        statement.setBoolean(1, true);
        statement.setByte(2, (byte) 3);
        statement.setShort(3, (short) 4);
        statement.setInt(4, 5);
        statement.setLong(5, 6L);
        statement.setFloat(6, 7.5f);
        statement.setDouble(7, 8.5d);
        statement.setBigDecimal(8, decimal);
        statement.setString(9, "text");
        statement.setBytes(10, bytes);
        statement.setDate(11, date);
        statement.setTime(12, time);
        statement.setTimestamp(13, timestamp);
        statement.setObject(14, object);

        verify(delegate).setNull(1, Types.VARCHAR);
        verify(delegate).setBoolean(2, true);
        verify(delegate).setByte(3, (byte) 3);
        verify(delegate).setShort(4, (short) 4);
        verify(delegate).setInt(5, 5);
        verify(delegate).setLong(6, 6L);
        verify(delegate).setFloat(7, 7.5f);
        verify(delegate).setDouble(8, 8.5d);
        verify(delegate).setBigDecimal(9, decimal);
        verify(delegate).setString(10, "text");
        verify(delegate).setBytes(11, bytes);
        verify(delegate).setDate(12, date);
        verify(delegate).setTime(13, time);
        verify(delegate).setTimestamp(14, timestamp);
        verify(delegate).setObject(15, object);
        verifyNoMoreInteractions(delegate);
    }

    @Test
    void shouldPassNullValuesUnchanged() throws Exception {
        statement.setString(0, null);
        statement.setObject(1, null);
        statement.setTimestamp(2, null);

        verify(delegate).setString(1, null);
        verify(delegate).setObject(2, null);
        verify(delegate).setTimestamp(3, null);
    }

    @Test
    void shouldDelegateStatementOperations() throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        when(delegate.executeQuery()).thenReturn(resultSet);
        when(delegate.executeBatch()).thenReturn(new int[] {1, 2});

        statement.clearParameters();
        assertThat(statement.executeQuery()).isSameAs(resultSet);
        statement.addBatch();
        assertThat(statement.executeBatch()).containsExactly(1, 2);
        statement.close();

        verify(delegate).clearParameters();
        verify(delegate).executeQuery();
        verify(delegate).addBatch();
        verify(delegate).executeBatch();
        verify(delegate).close();
        verifyNoMoreInteractions(delegate);
    }

    @Test
    void shouldPropagateDriverExceptions() throws Exception {
        SQLException failure = new SQLException("boom");
        doThrow(failure).when(delegate).setString(1, "x");
        when(delegate.executeQuery()).thenThrow(failure);

        assertThatThrownBy(() -> statement.setString(0, "x")).isSameAs(failure);
        assertThatThrownBy(() -> statement.executeQuery()).isSameAs(failure);
    }

    @Test
    void shouldRejectNullDelegate() {
        assertThatThrownBy(() -> new PositionalPreparedStatement(null))
                .isInstanceOf(NullPointerException.class);
    }
}
