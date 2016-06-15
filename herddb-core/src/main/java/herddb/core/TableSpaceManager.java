/*
 Licensed to Diennea S.r.l. under one
 or more contributor license agreements. See the NOTICE file
 distributed with this work for additional information
 regarding copyright ownership. Diennea S.r.l. licenses this file
 to you under the Apache License, Version 2.0 (the
 "License"); you may not use this file except in compliance
 with the License.  You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing,
 software distributed under the License is distributed on an
 "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 KIND, either express or implied.  See the License for the
 specific language governing permissions and limitations
 under the License.

 */
package herddb.core;

import herddb.client.ClientConfiguration;
import herddb.client.ClientSideMetadataProvider;
import herddb.client.ClientSideMetadataProviderException;
import herddb.client.HDBClient;
import herddb.client.HDBConnection;
import herddb.client.HDBException;
import herddb.client.TableSpaceDumpReceiver;
import herddb.core.system.SysclientsTableManager;
import herddb.core.system.SyscolumnsTableManager;
import herddb.core.system.SysconfigTableManager;
import herddb.core.system.SysnodesTableManager;
import herddb.core.system.SystablesTableManager;
import herddb.core.system.SystablespacesTableManager;
import herddb.log.CommitLog;
import herddb.log.FullRecoveryNeededException;
import herddb.log.LogEntry;
import herddb.log.LogEntryFactory;
import herddb.log.LogEntryType;
import herddb.log.LogNotAvailableException;
import herddb.log.LogSequenceNumber;
import herddb.metadata.MetadataStorageManager;
import herddb.metadata.MetadataStorageManagerException;
import herddb.model.DDLException;
import herddb.model.TransactionResult;
import herddb.model.DDLStatementExecutionResult;
import herddb.model.DataScanner;
import herddb.model.NodeMetadata;
import herddb.model.Record;
import herddb.model.Statement;
import herddb.model.StatementEvaluationContext;
import herddb.model.StatementExecutionException;
import herddb.model.StatementExecutionResult;
import herddb.model.Table;
import herddb.model.TableAlreadyExistsException;
import herddb.model.TableAwareStatement;
import herddb.model.TableDoesNotExistException;
import herddb.model.TableSpace;
import herddb.model.Transaction;
import herddb.model.TransactionContext;
import herddb.model.commands.AlterTableStatement;
import herddb.model.commands.BeginTransactionStatement;
import herddb.model.commands.CommitTransactionStatement;
import herddb.model.commands.CreateTableStatement;
import herddb.model.commands.DropTableStatement;
import herddb.model.commands.RollbackTransactionStatement;
import herddb.model.commands.ScanStatement;
import herddb.network.Channel;
import herddb.network.KeyValue;
import herddb.network.Message;
import herddb.network.SendResultCallback;
import herddb.network.ServerHostData;
import herddb.storage.DataStorageManager;
import herddb.storage.DataStorageManagerException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Manages a TableSet in memory
 *
 * @author enrico.olivelli
 */
public class TableSpaceManager {

    private static final Logger LOGGER = Logger.getLogger(TableSpaceManager.class.getName());

    private final MetadataStorageManager metadataStorageManager;
    private final DataStorageManager dataStorageManager;
    private final CommitLog log;
    private final String tableSpaceName;
    private final String nodeId;
    private final Map<String, AbstractTableManager> tables = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock generalLock = new ReentrantReadWriteLock();
    private final AtomicLong newTransactionId = new AtomicLong();
    private final DBManager manager;
    private volatile boolean leader;
    private volatile boolean closed;
    private volatile boolean failed;
    private LogSequenceNumber actualLogSequenceNumber;
    private boolean virtual;

    public TableSpaceManager(String nodeId, String tableSpaceName, MetadataStorageManager metadataStorageManager, DataStorageManager dataStorageManager, CommitLog log, DBManager manager, boolean virtual) {
        this.nodeId = nodeId;
        this.manager = manager;
        this.metadataStorageManager = metadataStorageManager;
        this.dataStorageManager = dataStorageManager;
        this.log = log;
        this.tableSpaceName = tableSpaceName;
        this.virtual = virtual;
    }

    private void bootSystemTables() {
        if (virtual) {
            registerSystemTableManager(new SysconfigTableManager(this));
            registerSystemTableManager(new SysclientsTableManager(this));
        } else {
            registerSystemTableManager(new SystablesTableManager(this));
            registerSystemTableManager(new SyscolumnsTableManager(this));
        }
        registerSystemTableManager(new SystablespacesTableManager(this));
        registerSystemTableManager(new SysnodesTableManager(this));
    }

    private void registerSystemTableManager(AbstractTableManager tableManager) {
        tables.put(tableManager.getTable().name, tableManager);
    }

    void start() throws DataStorageManagerException, LogNotAvailableException, MetadataStorageManagerException, DDLException {

        TableSpace tableSpaceInfo = metadataStorageManager.describeTableSpace(tableSpaceName);

        bootSystemTables();
        if (virtual) {
            startAsLeader();
        } else {
            recover(tableSpaceInfo);

            LOGGER.log(Level.SEVERE, " after recovery of tableSpace " + tableSpaceName + ", actualLogSequenceNumber:" + actualLogSequenceNumber);

            tableSpaceInfo = metadataStorageManager.describeTableSpace(tableSpaceName);
            if (tableSpaceInfo.leaderId.equals(nodeId)) {
                startAsLeader();
            } else {
                startAsFollower();
            }
        }
    }

    void recover(TableSpace tableSpaceInfo) throws DataStorageManagerException, LogNotAvailableException, MetadataStorageManagerException {
        LogSequenceNumber logSequenceNumber = dataStorageManager.getLastcheckpointSequenceNumber(tableSpaceName);
        actualLogSequenceNumber = logSequenceNumber;
        LOGGER.log(Level.SEVERE, nodeId + " recover " + tableSpaceName + ", logSequenceNumber from DataStorage: " + logSequenceNumber);
        List<Table> tablesAtBoot = dataStorageManager.loadTables(logSequenceNumber, tableSpaceName);
        LOGGER.log(Level.SEVERE, nodeId + " tablesAtBoot", tablesAtBoot.stream().map(t -> {
            return t.name;
        }).collect(Collectors.joining()));
        for (Table table : tablesAtBoot) {
            bootTable(table, 0);
        }
        dataStorageManager.loadTransactions(logSequenceNumber, tableSpaceName, t -> {
            transactions.put(t.transactionId, t);
            try {
                for (Table table : t.newTables.values()) {
                    if (!tables.containsKey(table.name)) {
                        bootTable(table, t.transactionId);
                    }
                }
            } catch (Exception err) {
                LOGGER.log(Level.SEVERE, "error while booting tmp tables " + err, err);
                throw new RuntimeException(err);
            }
        });

        LOGGER.log(Level.SEVERE, nodeId + " recovering tablespace " + tableSpaceName + " log from sequence number " + logSequenceNumber);

        try {
            log.recovery(logSequenceNumber, new ApplyEntryOnRecovery(), false);
        } catch (FullRecoveryNeededException fullRecoveryNeeded) {
            LOGGER.log(Level.SEVERE, nodeId + " full recovery of data is needed for tableSpace " + tableSpaceName, fullRecoveryNeeded);
            downloadTableSpaceData();
            log.recovery(actualLogSequenceNumber, new ApplyEntryOnRecovery(), false);
        }
        checkpoint();

    }

    void recoverForLeadership() throws DataStorageManagerException, LogNotAvailableException {
        actualLogSequenceNumber = log.getLastSequenceNumber();
        LOGGER.log(Level.SEVERE, "recovering tablespace " + tableSpaceName + " log from sequence number " + actualLogSequenceNumber + ", with fencing");
        log.recovery(actualLogSequenceNumber, new ApplyEntryOnRecovery(), true);
    }

    void apply(LogSequenceNumber position, LogEntry entry) throws DataStorageManagerException, DDLException {
        this.actualLogSequenceNumber = position;
        if (LOGGER.isLoggable(Level.FINEST)) {
            LOGGER.log(Level.FINEST, "apply entry {0} {1}", new Object[]{position, entry});
        }
        switch (entry.type) {
            case LogEntryType.BEGINTRANSACTION: {
                long id = entry.transactionId;
                Transaction transaction = new Transaction(id, tableSpaceName);
                transactions.put(id, transaction);
            }
            break;
            case LogEntryType.ROLLBACKTRANSACTION: {
                long id = entry.transactionId;
                Transaction transaction = transactions.get(id);
                if (transaction == null) {
                    throw new DataStorageManagerException("invalid transaction id " + id);
                }
                List<AbstractTableManager> managers;
                try {
                    generalLock.writeLock().lock();
                    managers = new ArrayList<>(tables.values());
                } finally {
                    generalLock.writeLock().unlock();
                }
                for (AbstractTableManager manager : managers) {
                    if (transaction.getNewTables().containsKey(manager.getTable().name)) {
                        LOGGER.log(Level.SEVERE, "rollback CREATE TABLE " + manager.getTable().name);
                        manager.dropTableData();
                        manager.close();
                        tables.remove(manager.getTable().name);
                    } else {
                        manager.onTransactionRollback(transaction);
                    }
                }
                transactions.remove(transaction.transactionId);
            }
            break;
            case LogEntryType.COMMITTRANSACTION: {
                long id = entry.transactionId;
                Transaction transaction = transactions.get(id);
                if (transaction == null) {
                    throw new DataStorageManagerException("invalid transaction id " + id);
                }
                List<AbstractTableManager> managers;
                try {
                    generalLock.writeLock().lock();
                    managers = new ArrayList<>(tables.values());
                } finally {
                    generalLock.writeLock().unlock();
                }
                for (AbstractTableManager manager : managers) {
                    manager.onTransactionCommit(transaction);

                }
                for (String dropped : transaction.droppedTables) {
                    for (AbstractTableManager manager : managers) {
                        if (manager.getTable().name.equals(dropped)) {
                            manager.dropTableData();
                            manager.close();
                            tables.remove(manager.getTable().name);
                        }
                    }
                }
                if (!transaction.getNewTables().isEmpty() || !transaction.droppedTables.isEmpty()) {
                    writeTablesOnDataStorageManager();
                }
                transactions.remove(transaction.transactionId);
            }
            break;
            case LogEntryType.CREATE_TABLE: {
                Table table = Table.deserialize(entry.value);
                if (entry.transactionId > 0) {
                    long id = entry.transactionId;
                    Transaction transaction = transactions.get(id);
                    transaction.registerNewTable(table);
                }

                bootTable(table, entry.transactionId);
                if (entry.transactionId <= 0) {
                    writeTablesOnDataStorageManager();
                }
            }
            break;
            case LogEntryType.DROP_TABLE: {
                String tableName = entry.tableName;
                if (entry.transactionId > 0) {
                    long id = entry.transactionId;
                    Transaction transaction = transactions.get(id);
                    transaction.registerDropTable(tableName);
                } else {
                    AbstractTableManager manager;
                    try {
                        generalLock.writeLock().lock();
                        manager = tables.get(tableName);
                    } finally {
                        generalLock.writeLock().unlock();
                    }
                    if (manager != null) {
                        manager.dropTableData();
                        manager.close();
                        tables.remove(manager.getTable().name);
                    }
                }

                if (entry.transactionId <= 0) {
                    writeTablesOnDataStorageManager();
                }
            }
            break;
            case LogEntryType.ALTER_TABLE: {
                Table table = Table.deserialize(entry.value);
                alterTable(table, null);
                writeTablesOnDataStorageManager();
            }
            break;
        }

        if (entry.tableName != null
                && entry.type != LogEntryType.CREATE_TABLE
                && entry.type != LogEntryType.ALTER_TABLE
                && entry.type != LogEntryType.DROP_TABLE) {
            AbstractTableManager tableManager = tables.get(entry.tableName);
            tableManager.apply(position, entry);
        }

    }

    private void writeTablesOnDataStorageManager() throws DataStorageManagerException {
        LogSequenceNumber logSequenceNumber = log.getLastSequenceNumber();
        List<Table> tablelist = new ArrayList<>();
        for (AbstractTableManager tableManager : tables.values()) {
            if (!tableManager.isSystemTable()) {
                tablelist.add(tableManager.getTable());
            }
        }
        dataStorageManager.writeTables(tableSpaceName, logSequenceNumber, tablelist);
    }

    DataScanner scan(ScanStatement statement, StatementEvaluationContext context, TransactionContext transactionContext) throws StatementExecutionException {
        Transaction transaction = transactions.get(transactionContext.transactionId);
        if (transaction != null && !transaction.tableSpace.equals(tableSpaceName)) {
            throw new StatementExecutionException("transaction " + transaction.transactionId + " is for tablespace " + transaction.tableSpace + ", not for " + tableSpaceName);
        }
        String table = statement.getTable();
        AbstractTableManager manager;
        generalLock.readLock().lock();
        try {
            manager = tables.get(table);
        } finally {
            generalLock.readLock().unlock();
        }
        if (manager == null) {
            throw new TableDoesNotExistException("no table " + table + " in tablespace " + tableSpaceName);
        }
        if (manager.getCreatedInTransaction() > 0) {
            if (transaction == null || transaction.transactionId != manager.getCreatedInTransaction()) {
                throw new TableDoesNotExistException("no table " + table + " in tablespace " + tableSpaceName + ". created temporary in transaction " + manager.getCreatedInTransaction());
            }
        }
        return manager.scan(statement, context, transaction);
    }

    private void downloadTableSpaceData() throws MetadataStorageManagerException, DataStorageManagerException, LogNotAvailableException {
        TableSpace tableSpaceData = metadataStorageManager.describeTableSpace(tableSpaceName);
        String leaderId = tableSpaceData.leaderId;
        if (this.nodeId.equals(leaderId)) {
            throw new DataStorageManagerException("cannot download data of tableSpace " + tableSpaceName + " from myself");
        }
        Optional<NodeMetadata> leaderAddress = metadataStorageManager.listNodes().stream().filter(n -> n.nodeId.equals(leaderId)).findAny();
        if (!leaderAddress.isPresent()) {
            throw new DataStorageManagerException("cannot download data of tableSpace " + tableSpaceName + " from leader " + leaderId + ", no metadata found");
        }
        NodeMetadata nodeData = leaderAddress.get();
        ClientConfiguration clientConfiguration = new ClientConfiguration(manager.getTmpDirectory());
        clientConfiguration.set(ClientConfiguration.PROPERTY_CLIENT_USERNAME, manager.getServerToServerUsername());
        clientConfiguration.set(ClientConfiguration.PROPERTY_CLIENT_PASSWORD, manager.getServerToServerPassword());
        try (HDBClient client = new HDBClient(clientConfiguration);) {
            client.setClientSideMetadataProvider(new ClientSideMetadataProvider() {
                @Override
                public String getTableSpaceLeader(String tableSpace) throws ClientSideMetadataProviderException {
                    return leaderId;
                }

                @Override
                public ServerHostData getServerHostData(String nodeId) throws ClientSideMetadataProviderException {
                    return new ServerHostData(nodeData.host, nodeData.port, "?", nodeData.ssl, Collections.emptyMap());
                }
            });
            try (HDBConnection con = client.openConnection()) {
                DumpReceiver receiver = new DumpReceiver();
                int fetchSize = 10000;
                con.dumpTableSpace(tableSpaceName, receiver, fetchSize);
                long _start = System.currentTimeMillis();
                boolean ok = receiver.join(1000 * 60 * 60);
                if (!ok) {
                    throw new DataStorageManagerException("Cannot receive dump within " + (System.currentTimeMillis() - _start) + " ms");
                }
                if (receiver.getError() != null) {
                    throw new DataStorageManagerException("Error while receiving dump: " + receiver.getError(), receiver.getError());
                }
                this.actualLogSequenceNumber = receiver.logSequenceNumber;
                LOGGER.log(Level.SEVERE, "After download local actualLogSequenceNumber is " + actualLogSequenceNumber);

            } catch (ClientSideMetadataProviderException | HDBException | InterruptedException networkError) {
                throw new DataStorageManagerException(networkError);
            }

        }

    }

    public MetadataStorageManager getMetadataStorageManager() {
        return metadataStorageManager;
    }

    public List<Table> getAllCommittedTables() {
        generalLock.readLock().lock();
        try {
            return tables.values().stream().filter(s -> s.getCreatedInTransaction() == 0).map(AbstractTableManager::getTable).collect(Collectors.toList());
        } finally {
            generalLock.readLock().unlock();
        }
    }

    private StatementExecutionResult alterTable(AlterTableStatement alterTableStatement, TransactionContext transactionContext) throws TableDoesNotExistException, StatementExecutionException {

        if (transactionContext.transactionId > 0) {
            throw new StatementExecutionException("ALTER TABLE cannot be executed inside a transaction (txid=" + transactionContext.transactionId + ")");
        }
        AbstractTableManager manager;
        generalLock.writeLock().lock();
        try {
            manager = tables.get(alterTableStatement.getTable());
            if (manager == null) {
                throw new TableDoesNotExistException("no table " + alterTableStatement.getTable() + " in tablespace " + tableSpaceName);
            }
            manager = tables.get(alterTableStatement.getTable());
        } finally {
            generalLock.writeLock().unlock();
        }
        Table newTable;
        try {
            newTable = manager.getTable().applyAlterTable(alterTableStatement);
        } catch (IllegalArgumentException error) {
            throw new StatementExecutionException(error);
        }
        LogEntry entry = LogEntryFactory.alterTable(newTable, null);
        try {
            LogSequenceNumber pos = log.log(entry);
            apply(pos, entry);
        } catch (Exception err) {
            throw new StatementExecutionException(err);
        }
        return new DDLStatementExecutionResult();

    }

    private class DumpReceiver extends TableSpaceDumpReceiver {

        private TableManager currentTable;
        private final CountDownLatch latch;
        private Throwable error;
        private LogSequenceNumber logSequenceNumber;

        public DumpReceiver() {
            this.latch = new CountDownLatch(1);
        }

        @Override
        public void start(LogSequenceNumber logSequenceNumber) throws DataStorageManagerException {
            this.logSequenceNumber = logSequenceNumber;
        }

        public LogSequenceNumber getLogSequenceNumber() {
            return logSequenceNumber;
        }

        public boolean join(int timeout) throws InterruptedException {
            return latch.await(timeout, TimeUnit.MILLISECONDS);
        }

        public Throwable getError() {
            return error;
        }

        @Override
        public void onError(Throwable error) throws DataStorageManagerException {
            LOGGER.log(Level.SEVERE, "dumpReceiver " + tableSpaceName + ", onError ", error);
            this.error = error;
            latch.countDown();

        }

        @Override
        public void finish() throws DataStorageManagerException {
            LOGGER.log(Level.SEVERE, "dumpReceiver " + tableSpaceName + ", finish");
            latch.countDown();
        }

        @Override
        public void endTable() throws DataStorageManagerException {
            LOGGER.log(Level.SEVERE, "dumpReceiver " + tableSpaceName + ", endTable " + currentTable.getTable().name);
            currentTable = null;
        }

        @Override
        public void receiveTableDataChunk(List<Record> record) throws DataStorageManagerException {
            currentTable.writeFromDump(record);
        }

        @Override
        public void beginTable(Table table) throws DataStorageManagerException {
            LOGGER.log(Level.SEVERE, "dumpReceiver " + tableSpaceName + ", beginTable " + table.name);
            dataStorageManager.dropTable(tableSpaceName, table.name);
            currentTable = bootTable(table, 0);
        }

    }

    void dumpTableSpace(String dumpId, Channel _channel, int fetchSize) throws DataStorageManagerException, LogNotAvailableException {

        checkpoint();
        LOGGER.log(Level.SEVERE, "dumpTableSpace dumpId:" + dumpId + " channel " + _channel + " fetchSize:" + fetchSize);
        generalLock.readLock().lock();
        try {

            final int timeout = 60000;

            Map<String, Object> startData = new HashMap<>();
            startData.put("command", "start");
            LogSequenceNumber logSequenceNumber = log.getLastSequenceNumber();
            startData.put("ledgerid", logSequenceNumber.ledgerId);
            startData.put("offset", logSequenceNumber.offset);
            Message response_to_start = _channel.sendMessageWithReply(Message.TABLESPACE_DUMP_DATA(null, tableSpaceName, dumpId, startData), timeout);
            if (response_to_start.type != Message.TYPE_ACK) {
                LOGGER.log(Level.SEVERE, "error response at start command: " + response_to_start.parameters);
                return;
            }

            for (AbstractTableManager tableManager : tables.values()) {
                if (tableManager.isSystemTable()) {
                    continue;
                }
                Table table = tableManager.getTable();
                byte[] serialized = table.serialize();
                Map<String, Object> beginTableData = new HashMap<>();
                beginTableData.put("command", "beginTable");
                beginTableData.put("table", serialized);
                _channel.sendMessageWithReply(Message.TABLESPACE_DUMP_DATA(null, tableSpaceName, dumpId, beginTableData), timeout);

                List<KeyValue> batch = new ArrayList<>();
                Consumer<Record> sink = new Consumer<Record>() {
                    @Override
                    public void accept(Record t) {
                        try {
                            batch.add(new KeyValue(t.key.data, t.value.data));
                            if (batch.size() == fetchSize) {
                                Map<String, Object> data = new HashMap<>();
                                data.put("command", "data");
                                data.put("records", batch);
                                _channel.sendMessageWithReply(Message.TABLESPACE_DUMP_DATA(null, tableSpaceName, dumpId, data), timeout);
                                batch.clear();
                            }
                        } catch (Exception error) {
                            throw new RuntimeException(error);
                        }
                    }
                };
                try {
                    tableManager.dump(sink);

                    if (!batch.isEmpty()) {
                        Map<String, Object> data = new HashMap<>();
                        data.put("command", "data");
                        data.put("records", batch);
                        _channel.sendMessageWithReply(Message.TABLESPACE_DUMP_DATA(null, tableSpaceName, dumpId, data), timeout);
                        batch.clear();
                    }

                } catch (DataStorageManagerException err) {
                    Map<String, Object> errorOnData = new HashMap<>();
                    errorOnData.put("command", "error");
                    _channel.sendMessageWithReply(Message.TABLESPACE_DUMP_DATA(null, tableSpaceName, dumpId, errorOnData), timeout);
                    LOGGER.log(Level.SEVERE, "error sending dump id " + dumpId, err);
                    return;
                }

                Map<String, Object> endTableData = new HashMap<>();
                endTableData.put("command", "endTable");
                _channel.sendMessageWithReply(Message.TABLESPACE_DUMP_DATA(null, tableSpaceName, dumpId, endTableData), timeout);
            }

            Map<String, Object> finishData = new HashMap<>();
            finishData.put("command", "finish");
            _channel.sendOneWayMessage(Message.TABLESPACE_DUMP_DATA(null, tableSpaceName, dumpId, finishData), new SendResultCallback() {
                @Override
                public void messageSent(Message originalMessage, Throwable error) {
                }
            });
        } catch (InterruptedException | TimeoutException error) {
            LOGGER.log(Level.SEVERE, "error sending dump id " + dumpId);
        } finally {
            generalLock.readLock().unlock();

        }

    }

    private class FollowerThread implements Runnable {

        @Override
        public String toString() {
            return "FollowerThread{" + tableSpaceName + '}';
        }

        @Override
        public void run() {
            try {
                while (!isLeader() && !closed) {
                    log.followTheLeader(actualLogSequenceNumber, new BiConsumer< LogSequenceNumber, LogEntry>() {
                        @Override
                        public void accept(LogSequenceNumber num, LogEntry u
                        ) {
                            LOGGER.log(Level.SEVERE, "follow " + num + ", " + u.toString());
                            try {
                                apply(num, u);
                            } catch (Throwable t) {
                                throw new RuntimeException(t);
                            }
                        }
                    });
                }
            } catch (Throwable t) {
                LOGGER.log(Level.SEVERE, "follower error " + tableSpaceName, t);
                setFailed();
            }
        }

    }

    void setFailed() {
        LOGGER.log(Level.SEVERE, "failed!", new Exception().fillInStackTrace());
        failed = true;
    }

    boolean isFailed() {
        return failed;
    }

    void startAsFollower() throws DataStorageManagerException, DDLException, LogNotAvailableException {
        manager.submit(new FollowerThread());
    }

    void startAsLeader() throws DataStorageManagerException, DDLException, LogNotAvailableException {
        if (virtual) {

        } else {

            LOGGER.log(Level.SEVERE, "startAsLeader {0} tablespace {1}", new Object[]{nodeId, tableSpaceName});
            recoverForLeadership();

            // every pending transaction MUST be rollback back
            List<Long> pending_transactions = new ArrayList<>(this.transactions.keySet());
            log.startWriting();
            LOGGER.log(Level.SEVERE, "startAsLeader {0} tablespace {1} log, there were {2} pending transactions to be rolledback", new Object[]{nodeId, tableSpaceName, transactions.size()});
            for (long tx : pending_transactions) {
                LOGGER.log(Level.SEVERE, "rolling back transaction {0}", tx);
                LogEntry rollback = LogEntryFactory.rollbackTransaction(tableSpaceName, tx);
                // let followers see the rollback on the log
                LogSequenceNumber pos = log.log(rollback);
                apply(pos, rollback);
            }
        }
        leader = true;
    }

    private final ConcurrentHashMap<Long, Transaction> transactions = new ConcurrentHashMap<>();

    StatementExecutionResult executeStatement(Statement statement, StatementEvaluationContext context, TransactionContext transactionContext) throws StatementExecutionException {
        if (virtual) {
            throw new StatementExecutionException("executeStatement not available on virtual tablespaces");
        }
        Transaction transaction = transactions.get(transactionContext.transactionId);
        if (transaction != null && !transaction.tableSpace.equals(tableSpaceName)) {
            throw new StatementExecutionException("transaction " + transaction.transactionId + " is for tablespace " + transaction.tableSpace + ", not for " + tableSpaceName);
        }
        if (statement instanceof CreateTableStatement) {
            return createTable((CreateTableStatement) statement, transaction);
        }
        if (statement instanceof DropTableStatement) {
            return dropTable((DropTableStatement) statement, transaction);
        }
        if (statement instanceof BeginTransactionStatement) {
            if (transaction != null) {
                throw new IllegalArgumentException("transaction already started");
            }
            return beginTransaction();
        }
        if (statement instanceof RollbackTransactionStatement) {
            return rollbackTransaction((RollbackTransactionStatement) statement);
        }
        if (statement instanceof CommitTransactionStatement) {
            return commitTransaction((CommitTransactionStatement) statement);
        }
        if (statement instanceof AlterTableStatement) {
            return alterTable((AlterTableStatement) statement, transactionContext);
        }
        if (statement instanceof TableAwareStatement) {
            TableAwareStatement st = (TableAwareStatement) statement;
            String table = st.getTable();
            AbstractTableManager manager;
            generalLock.readLock().lock();
            try {
                manager = tables.get(table);
            } finally {
                generalLock.readLock().unlock();
            }
            if (manager == null) {
                throw new TableDoesNotExistException("no table " + table + " in tablespace " + tableSpaceName);
            }
            if (manager.getCreatedInTransaction() > 0) {
                if (transaction == null || transaction.transactionId != manager.getCreatedInTransaction()) {
                    throw new TableDoesNotExistException("no table " + table + " in tablespace " + tableSpaceName + ". created temporary in transaction " + manager.getCreatedInTransaction());
                }
            }
            return manager.executeStatement(statement, transaction, context);
        }

        throw new StatementExecutionException("unsupported statement " + statement);
    }

    private StatementExecutionResult createTable(CreateTableStatement statement, Transaction transaction) throws StatementExecutionException {
        try {
            generalLock.writeLock().lock();
            if (tables.containsKey(statement.getTableDefinition().name)) {
                throw new TableAlreadyExistsException(statement.getTableDefinition().name);
            }
            LogEntry entry = LogEntryFactory.createTable(statement.getTableDefinition(), transaction);
            LogSequenceNumber pos;
            try {
                pos = log.log(entry);
            } catch (LogNotAvailableException ex) {
                throw new StatementExecutionException(ex);
            }

            apply(pos, entry);

            return new DDLStatementExecutionResult();
        } catch (DataStorageManagerException err) {
            throw new StatementExecutionException(err);
        } finally {
            generalLock.writeLock().unlock();
        }
    }

    private StatementExecutionResult dropTable(DropTableStatement statement, Transaction transaction) throws StatementExecutionException {
        try {
            generalLock.writeLock().lock();
            if (!tables.containsKey(statement.getTable())) {
                throw new TableDoesNotExistException("table does not exist " + statement.getTable() + " on tableSpace " + statement.getTableSpace());
            }
            if (transaction != null && transaction.isTableDropped(statement.getTable())) {
                throw new TableDoesNotExistException("table does not exist " + statement.getTable() + " on tableSpace " + statement.getTableSpace());
            }
            LogEntry entry = LogEntryFactory.dropTable(statement.getTableSpace(), statement.getTable(), transaction);
            LogSequenceNumber pos;
            try {
                pos = log.log(entry);
            } catch (LogNotAvailableException ex) {
                throw new StatementExecutionException(ex);
            }

            apply(pos, entry);

            return new DDLStatementExecutionResult();
        } catch (DataStorageManagerException err) {
            throw new StatementExecutionException(err);
        } finally {
            generalLock.writeLock().unlock();
        }
    }

    private TableManager bootTable(Table table, long transaction) throws DataStorageManagerException {
        LOGGER.log(Level.SEVERE, "bootTable {0} {1}.{2}", new Object[]{nodeId, tableSpaceName, table.name});
        TableManager tableManager = new TableManager(table, log, dataStorageManager, this, transaction);
        if (tables.containsKey(table.name)) {
            throw new DataStorageManagerException("Table " + table.name + " already present in tableSpace " + tableSpaceName);
        }
        tables.put(table.name, tableManager);
        tableManager.start();
        return tableManager;
    }

    private AbstractTableManager alterTable(Table table, Transaction transaction) throws DDLException {
        LOGGER.log(Level.SEVERE, "alterTable {0} {1}.{2}", new Object[]{nodeId, tableSpaceName, table.name});
        AbstractTableManager tableManager = tables.get(table.name);
        tableManager.tableAltered(table, transaction);
        return tableManager;
    }

    public void close() throws LogNotAvailableException {
        closed = true;
        if (!virtual) {
            try {
                generalLock.writeLock().lock();
                for (AbstractTableManager table : tables.values()) {
                    table.close();
                }
                log.close();
            } finally {
                generalLock.writeLock().unlock();
            }
        }
    }

    void checkpoint() throws DataStorageManagerException, LogNotAvailableException {
        if (virtual) {
            return;
        }
        generalLock.writeLock().lock();
        try {
            LogSequenceNumber logSequenceNumber = log.getLastSequenceNumber();

            if (logSequenceNumber.isStartOfTime()) {
                LOGGER.log(Level.SEVERE, nodeId + " checkpoint " + tableSpaceName + " at " + logSequenceNumber + ". skipped (no write ever issued to log)");
                return;
            }
            LOGGER.log(Level.SEVERE, nodeId + " checkpoint " + tableSpaceName + " at " + logSequenceNumber);
            if (actualLogSequenceNumber == null) {
                throw new DataStorageManagerException("actualLogSequenceNumber cannot be null");
            }
            // we checkpoint all data to disk and save the actual log sequence number            
            for (AbstractTableManager tableManager : tables.values()) {
                if (!tableManager.isSystemTable()) {
                    tableManager.checkpoint();
                }
            }
            writeTablesOnDataStorageManager();

            dataStorageManager.writeTransactionsAtCheckpoint(tableSpaceName, logSequenceNumber, transactions.values());

            dataStorageManager.writeCheckpointSequenceNumber(tableSpaceName, logSequenceNumber);
            log.dropOldLedgers(logSequenceNumber);

        } finally {
            generalLock.writeLock().unlock();
        }

    }

    private StatementExecutionResult beginTransaction() throws StatementExecutionException {
        long id = newTransactionId.incrementAndGet();

        LogEntry entry = LogEntryFactory.beginTransaction(tableSpaceName, id);
        LogSequenceNumber pos;
        try {
            pos = log.log(entry);
            apply(pos, entry);
        } catch (Exception err) {
            throw new StatementExecutionException(err);
        }

        return new TransactionResult(id, TransactionResult.OutcomeType.BEGIN);
    }

    private StatementExecutionResult rollbackTransaction(RollbackTransactionStatement rollbackTransactionStatement) throws StatementExecutionException {
        Transaction tx = transactions.get(rollbackTransactionStatement.getTransactionId());
        if (tx == null) {
            throw new StatementExecutionException("no such transaction " + rollbackTransactionStatement.getTransactionId());
        }
        LogEntry entry = LogEntryFactory.rollbackTransaction(tableSpaceName, tx.transactionId);
        LogSequenceNumber pos;
        try {
            pos = log.log(entry);
            apply(pos, entry);
        } catch (Exception err) {
            throw new StatementExecutionException(err);
        }

        return new TransactionResult(tx.transactionId, TransactionResult.OutcomeType.ROLLBACK);
    }

    private StatementExecutionResult commitTransaction(CommitTransactionStatement commitTransactionStatement) throws StatementExecutionException {
        Transaction tx = transactions.get(commitTransactionStatement.getTransactionId());
        if (tx == null) {
            throw new StatementExecutionException("no such transaction " + commitTransactionStatement.getTransactionId());
        }
        LogEntry entry = LogEntryFactory.commitTransaction(tableSpaceName, tx.transactionId);

        try {
            LogSequenceNumber pos = log.log(entry);
            apply(pos, entry);
        } catch (Exception err) {
            throw new StatementExecutionException(err);
        }

        return new TransactionResult(tx.transactionId, TransactionResult.OutcomeType.COMMIT);
    }

    public boolean isLeader() {
        return leader;
    }

    Transaction getTransaction(long transactionId) {
        return transactions.get(transactionId);
    }

    public AbstractTableManager getTableManager(String tableName) {
        return tables.get(tableName);
    }

    public Collection<Long> getOpenTransactions() {
        return new HashSet<>(this.transactions.keySet());

    }

    private class ApplyEntryOnRecovery implements BiConsumer<LogSequenceNumber, LogEntry> {

        public ApplyEntryOnRecovery() {
        }

        @Override
        public void accept(LogSequenceNumber t, LogEntry u) {
            try {
                apply(t, u);
            } catch (Exception err) {
                throw new RuntimeException(err);
            }
        }
    }

    public DBManager getManager() {
        return manager;
    }

}
