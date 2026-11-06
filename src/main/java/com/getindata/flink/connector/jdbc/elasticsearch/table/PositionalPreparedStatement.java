package com.getindata.flink.connector.jdbc.elasticsearch.table;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.apache.flink.connector.jdbc.statement.FieldNamedPreparedStatement;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;

@RequiredArgsConstructor
class PositionalPreparedStatement implements FieldNamedPreparedStatement {

    @NonNull
    private final PreparedStatement statement;

    private static int parameterIndex(int fieldIndex) {
        return fieldIndex + 1;
    }

    @Override
    public void clearParameters() throws SQLException {
        statement.clearParameters();
    }

    @Override
    public ResultSet executeQuery() throws SQLException {
        return statement.executeQuery();
    }

    @Override
    public void addBatch() throws SQLException {
        statement.addBatch();
    }

    @Override
    public int[] executeBatch() throws SQLException {
        return statement.executeBatch();
    }

    @Override
    public void setNull(int fieldIndex, int sqlType) throws SQLException {
        statement.setNull(parameterIndex(fieldIndex), sqlType);
    }

    @Override
    public void setBoolean(int fieldIndex, boolean x) throws SQLException {
        statement.setBoolean(parameterIndex(fieldIndex), x);
    }

    @Override
    public void setByte(int fieldIndex, byte x) throws SQLException {
        statement.setByte(parameterIndex(fieldIndex), x);
    }

    @Override
    public void setShort(int fieldIndex, short x) throws SQLException {
        statement.setShort(parameterIndex(fieldIndex), x);
    }

    @Override
    public void setInt(int fieldIndex, int x) throws SQLException {
        statement.setInt(parameterIndex(fieldIndex), x);
    }

    @Override
    public void setLong(int fieldIndex, long x) throws SQLException {
        statement.setLong(parameterIndex(fieldIndex), x);
    }

    @Override
    public void setFloat(int fieldIndex, float x) throws SQLException {
        statement.setFloat(parameterIndex(fieldIndex), x);
    }

    @Override
    public void setDouble(int fieldIndex, double x) throws SQLException {
        statement.setDouble(parameterIndex(fieldIndex), x);
    }

    @Override
    public void setBigDecimal(int fieldIndex, BigDecimal x) throws SQLException {
        statement.setBigDecimal(parameterIndex(fieldIndex), x);
    }

    @Override
    public void setString(int fieldIndex, String x) throws SQLException {
        statement.setString(parameterIndex(fieldIndex), x);
    }

    @Override
    public void setBytes(int fieldIndex, byte[] x) throws SQLException {
        statement.setBytes(parameterIndex(fieldIndex), x);
    }

    @Override
    public void setDate(int fieldIndex, Date x) throws SQLException {
        statement.setDate(parameterIndex(fieldIndex), x);
    }

    @Override
    public void setTime(int fieldIndex, Time x) throws SQLException {
        statement.setTime(parameterIndex(fieldIndex), x);
    }

    @Override
    public void setTimestamp(int fieldIndex, Timestamp x) throws SQLException {
        statement.setTimestamp(parameterIndex(fieldIndex), x);
    }

    @Override
    public void setObject(int fieldIndex, Object x) throws SQLException {
        statement.setObject(parameterIndex(fieldIndex), x);
    }

    @Override
    public void close() throws SQLException {
        statement.close();
    }
}
