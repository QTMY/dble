package com.actiontech.dble.manager.dump.handler;

import com.actiontech.dble.config.model.TableConfig;
import com.actiontech.dble.manager.dump.DumpException;
import com.actiontech.dble.manager.dump.DumpFileContext;
import com.actiontech.dble.meta.protocol.StructureMeta;
import com.actiontech.dble.route.factory.RouteStrategyFactory;
import com.actiontech.dble.singleton.ProxyMeta;
import com.actiontech.dble.singleton.SequenceManager;
import com.actiontech.dble.util.CollectionUtil;
import com.actiontech.dble.util.StringUtil;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.expr.SQLIntegerExpr;
import com.alibaba.druid.sql.ast.statement.SQLInsertStatement;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlInsertStatement;

import java.sql.SQLNonTransientException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class InsertHandler extends DefaultHandler {

    private static final Pattern INSERT_STMT = Pattern.compile("insert\\s+into\\s+`?(.*)`\\s+values", Pattern.CASE_INSENSITIVE);
    private final ShardingValuesHandler shardingValuesHandler = new ShardingValuesHandler();
    private final DefaultValuesHandler defaultValuesHandler = new DefaultValuesHandler();
    private DefaultValuesHandler valuesHandler;
    private String currentTable;

    @Override
    public SQLStatement preHandle(DumpFileContext context, String stmt) throws DumpException, SQLNonTransientException {
        // get table name simply
        String table = null;
        Matcher matcher = InsertHandler.INSERT_STMT.matcher(stmt);
        if (matcher.find()) {
            table = matcher.group(1);
        }
        context.setTable(table);
        if (table != null && table.equalsIgnoreCase(currentTable)) {
            if (context.isSkipContext() || context.getTableType() == TableType.DEFAULT) {
                return null;
            }
            return RouteStrategyFactory.getRouteStrategy().parserSQL(stmt);
        } else {
            currentTable = table;
        }

        if (context.isSkipContext() || context.getTableType() == TableType.DEFAULT) {
            return null;
        }

        MySqlInsertStatement insert = (MySqlInsertStatement) RouteStrategyFactory.getRouteStrategy().parserSQL(stmt);
        // check columns from insert columns
        checkColumns(context, insert.getColumns());
        // add
        StringBuilder insertHeader = new StringBuilder("INSERT INTO ");
        insertHeader.append("`");
        insertHeader.append(context.getTable());
        insertHeader.append("`");
        if (!CollectionUtil.isEmpty(insert.getColumns())) {
            insertHeader.append(insert.getColumns().toString());
        }
        insertHeader.append(" VALUES");
        if (context.getTableType() == TableType.SHARDING) {
            shardingValuesHandler.reset();
            valuesHandler = shardingValuesHandler;
        } else {
            valuesHandler = defaultValuesHandler;
        }
        valuesHandler.setInsertHeader(insertHeader);
        return insert;
    }

    @Override
    public void handle(DumpFileContext context, SQLStatement sqlStatement) throws InterruptedException {
        MySqlInsertStatement insert = (MySqlInsertStatement) sqlStatement;
        SQLInsertStatement.ValuesClause valueClause;

        valuesHandler.preProcess(context);
        for (int i = 0; i < insert.getValuesList().size(); i++) {
            valueClause = insert.getValuesList().get(i);
            try {
                processIncrementColumn(context, valueClause.getValues());
                valuesHandler.process(context, valueClause.getValues(), i == 0);
            } catch (SQLNonTransientException e) {
                context.addError(e.getMessage());
            }
        }
        valuesHandler.postProcess(context);
    }

    private void processIncrementColumn(DumpFileContext context, List<SQLExpr> values) throws SQLNonTransientException {
        int incrementIndex = context.getIncrementColumnIndex();
        if (incrementIndex == -1) {
            return;
        }

        String tableKey = StringUtil.getFullName(context.getSchema(), context.getTable());
        long val = SequenceManager.getHandler().nextId(tableKey);
        SQLExpr value = values.get(incrementIndex);
        if (!StringUtil.isEmpty(SQLUtils.toMySqlString(value)) && !context.isNeedSkipError()) {
            context.addError("For table using global sequence, dble has set increment column values for you.");
            context.setNeedSkipError(true);
        }
        values.set(incrementIndex, new SQLIntegerExpr(val));
    }

    /**
     * if sharding column index or increment column index is -1,
     * find from dble meta data or columns in insert statement
     *
     * @param context
     * @param columns
     * @throws DumpException
     * @throws SQLNonTransientException
     */
    private void checkColumns(DumpFileContext context, List<SQLExpr> columns) throws DumpException, SQLNonTransientException {
        int partitionColumnIndex = context.getPartitionColumnIndex();
        int incrementColumnIndex = context.getIncrementColumnIndex();

        TableConfig tableConfig = context.getTableConfig();
        // partition column check
        if ((tableConfig.getPartitionColumn() != null && partitionColumnIndex != -1) ||
                (tableConfig.isAutoIncrement() && incrementColumnIndex != -1)) {
            return;
        }

        boolean isAutoIncrement = tableConfig.isAutoIncrement();
        if (isAutoIncrement || tableConfig.getPartitionColumn() != null) {
            if (!CollectionUtil.isEmpty(columns)) {
                for (int i = 0; i < columns.size(); i++) {
                    SQLExpr column = columns.get(i);
                    String columnName = StringUtil.removeBackQuote(column.toString());
                    if (isAutoIncrement && columnName.equalsIgnoreCase(tableConfig.getIncrementColumn())) {
                        incrementColumnIndex = i;
                    }
                    if (columnName.equalsIgnoreCase(tableConfig.getPartitionColumn())) {
                        partitionColumnIndex = i;
                    }
                }
            } else {
                StructureMeta.TableMeta tableMeta = ProxyMeta.getInstance().getTmManager().getSyncTableMeta(context.getSchema(), context.getTable());
                if (tableMeta == null) {
                    throw new DumpException("can't find meta of table and the table has no create statement.");
                }

                for (int i = 0; i < tableMeta.getColumnsList().size(); i++) {
                    StructureMeta.ColumnMeta column = tableMeta.getColumnsList().get(i);
                    String columnName = column.getName();
                    if (isAutoIncrement && columnName.equalsIgnoreCase(tableConfig.getIncrementColumn())) {
                        incrementColumnIndex = i;
                    }
                    if (columnName.equalsIgnoreCase(tableConfig.getPartitionColumn())) {
                        partitionColumnIndex = i;
                    }
                }
            }
        }

        // partition column check
        if (tableConfig.getPartitionColumn() != null && partitionColumnIndex == -1) {
            throw new DumpException("can't find partition column in insert.");
        }
        // increment column check
        if (isAutoIncrement && incrementColumnIndex == -1) {
            throw new DumpException("can't find increment column in insert.");
        }
        context.setIncrementColumnIndex(incrementColumnIndex);
        context.setPartitionColumnIndex(partitionColumnIndex);
    }
}
