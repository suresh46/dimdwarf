/*
 * This file is part of Dimdwarf Application Server <http://dimdwarf.sourceforge.net/>
 *
 * Copyright (c) 2008, Esko Luontola. All Rights Reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 *     * Redistributions of source code must retain the above copyright notice,
 *       this list of conditions and the following disclaimer.
 *
 *     * Redistributions in binary form must reproduce the above copyright notice,
 *       this list of conditions and the following disclaimer in the documentation
 *       and/or other materials provided with the distribution.
 *
 *     * Neither the name of the copyright holder nor the names of its contributors
 *       may be used to endorse or promote products derived from this software
 *       without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package net.orfjackal.dimdwarf.db.inmemory;

import com.google.inject.Singleton;
import net.orfjackal.dimdwarf.db.Blob;
import net.orfjackal.dimdwarf.db.Database;
import net.orfjackal.dimdwarf.db.DatabaseManager;
import net.orfjackal.dimdwarf.tx.Transaction;
import org.jetbrains.annotations.TestOnly;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * An in-memory database which uses multiversion concurrency control.
 * The isolation mode is snapshot isolation - it allows write skew, but prevents phantom reads.
 * <p/>
 * See:
 * <a href="http://en.wikipedia.org/wiki/Multiversion_concurrency_control">multiversion concurrency control</a>,
 * <a href="http://en.wikipedia.org/wiki/Snapshot_isolation">snapshot isolation</a>
 * <p/>
 * This class is thread-safe.
 *
 * @author Esko Luontola
 * @since 18.8.2008
 */
@Singleton
public class InMemoryDatabase implements DatabaseManager, PersistedDatabase {

    // TODO: this class smells too big/messy
    // Responsibilities:
    // - keep track of uncommitted database connections
    // - keep track of available database tables
    // - keep track of committed database revision
    // - prepare and commit modifications (includes locking)
    // - keep track of data and revision seen inside a transaction
    // - keep track of uncommitted modified data inside a transaction

    private final ConcurrentMap<Transaction, VolatileDatabase> openConnections = new ConcurrentHashMap<Transaction, VolatileDatabase>();
    private final Object commitLock = new Object();

    private final ConcurrentMap<String, InMemoryDatabaseTable> tables = new ConcurrentHashMap<String, InMemoryDatabaseTable>();
    private final RevisionCounter revisionCounter;
    private volatile long committedRevision;

    public InMemoryDatabase() {
        revisionCounter = new RevisionCounter();
        committedRevision = revisionCounter.getCurrentRevision();
    }

    // Tables

    public Set<String> getTableNames() {
        return tables.keySet();
    }

    public PersistedDatabaseTable openOrCreateTable(String name) {
        PersistedDatabaseTable table = getExistingTable(name);
        if (table == null) {
            table = createNewTable(name);
        }
        return table;
    }

    private PersistedDatabaseTable getExistingTable(String name) {
        return tables.get(name);
    }

    private PersistedDatabaseTable createNewTable(String name) {
        tables.putIfAbsent(name, new InMemoryDatabaseTable(revisionCounter));
        return getExistingTable(name);
    }

    // Connections

    public Database<Blob, Blob> openConnection(Transaction tx) {
        VolatileDatabase db = getExistingConnection(tx);
        if (db == null) {
            db = createNewConnection(tx);
        }
        return db;
    }

    private VolatileDatabase getExistingConnection(Transaction tx) {
        return openConnections.get(tx);
    }

    private VolatileDatabase createNewConnection(Transaction tx) {
        openConnections.putIfAbsent(tx, new VolatileDatabase(this, committedRevision, tx));
        return getExistingConnection(tx);
    }

    private void closeConnection(Transaction tx) {
        VolatileDatabase removed = openConnections.remove(tx);
        assert removed != null : "Tried to close connection twise: " + tx;
        purgeOldUnusedRevisions();
    }

    private void purgeOldUnusedRevisions() {
        long oldestUncommitted = getOldestUncommittedRevision();
        for (InMemoryDatabaseTable table : tables.values()) {
            table.purgeRevisionsOlderThan(oldestUncommitted);
        }
    }

    protected long getOldestUncommittedRevision() {
        long oldest = committedRevision;
        for (VolatileDatabase db : openConnections.values()) {
            oldest = Math.min(oldest, db.visibleRevision);
        }
        return oldest;
    }

    @TestOnly
    protected int getOpenConnections() {
        return openConnections.size();
    }

    @TestOnly
    protected long getCurrentRevision() {
        return committedRevision;
    }

    @TestOnly
    protected long getOldestStoredRevision() {
        long oldest = revisionCounter.getCurrentRevision();
        for (InMemoryDatabaseTable table : tables.values()) {
            oldest = Math.min(oldest, table.getOldestRevision());
        }
        return oldest;
    }

    // Transactions

    // TODO: move these to VolatileDatabase

    public void prepareUpdates(Collection<VolatileDatabaseTable> updates) {
        synchronized (commitLock) {
            for (VolatileDatabaseTable update : updates) {
                update.prepare();
            }
        }
    }

    public void commitUpdates(Collection<VolatileDatabaseTable> updates, Transaction tx) {
        synchronized (commitLock) {
            try {
                updateRevisionAndCommit(updates);
            } finally {
                closeConnection(tx);
            }
        }
    }

    private void updateRevisionAndCommit(Collection<VolatileDatabaseTable> updates) {
        try {
            revisionCounter.incrementRevision();
            for (VolatileDatabaseTable update : updates) {
                update.commit();
            }
        } finally {
            committedRevision = revisionCounter.getCurrentRevision();
        }
    }

    public void rollbackUpdates(Collection<VolatileDatabaseTable> updates, Transaction tx) {
        synchronized (commitLock) {
            try {
                for (VolatileDatabaseTable update : updates) {
                    update.rollback();
                }
            } finally {
                closeConnection(tx);
            }
        }
    }
}
