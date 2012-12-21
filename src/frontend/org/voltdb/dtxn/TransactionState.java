/* This file is part of VoltDB.
 * Copyright (C) 2008-2010 VoltDB L.L.C.
 *
 * VoltDB is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * VoltDB is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.voltdb.dtxn;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.voltdb.VoltTable;
import org.voltdb.debugstate.ExecutorContext.ExecutorTxnState;
import org.voltdb.messaging.FragmentResponseMessage;
import org.voltdb.messaging.FragmentTaskMessage;
import org.voltdb.messaging.Mailbox;
import org.voltdb.messaging.TransactionInfoBaseMessage;

import edu.brown.hstore.PartitionExecutor;

/**
 * Controls the state of a transaction. Encapsulates from the SimpleDTXNConnection
 * all the logic about what needs to happen next in a transaction. The DTXNConn just
 * pumps events into the TransactionState and it takes the appropriate actions,
 * ultimately it will return true from finished().
 *
 */
@Deprecated
public abstract class TransactionState implements Comparable<TransactionState> {

    public final long txnId;
    public final int initiatorSiteId;
    public final int coordinatorSiteId;
    public final boolean isReadOnly;
    protected int m_nextDepId = 1;
    protected final Mailbox m_mbox;
    protected final SiteTransactionConnection m_site;
    protected boolean m_done = false;

    /**
     * Set up the final member variables from the parameters. This will
     * be called exclusively by subclasses.
     *
     * @param mbox The mailbox for the site.
     * @param notice The information about the new transaction.
     */
    protected TransactionState(Mailbox mbox,
                               PartitionExecutor site,
                               TransactionInfoBaseMessage notice)
    {
        m_mbox = mbox;
        m_site = null; // site;
        txnId = notice.getTxnId();
        initiatorSiteId = notice.getSourcePartitionId();
        coordinatorSiteId = notice.getDestinationPartitionId();
        isReadOnly = notice.isReadOnly();
    }

    /**
     * Package private accessors for test cases.
     * @return true of transaction state is complete
     */
    final public boolean isDone() {
        return m_done;
    }

    public boolean isInProgress() {
        return false;
    }

    public abstract boolean doWork();

    public boolean shouldResumeProcedure() {
        return false;
    }

    public void createFragmentWork(int[] partitions, FragmentTaskMessage task) {
        String msg = "The current transaction context of type " + this.getClass().getName();
        msg += " doesn't support creating fragment tasks.";
        throw new UnsupportedOperationException(msg);
    }

    public void createAllParticipatingFragmentWork(FragmentTaskMessage task) {
        String msg = "The current transaction context of type " + this.getClass().getName();
        msg += " doesn't support creating fragment tasks.";
        throw new UnsupportedOperationException(msg);
    }

    public void createLocalFragmentWork(FragmentTaskMessage task, boolean nonTransactional) {
        String msg = "The current transaction context of type " + this.getClass().getName();
        msg += " doesn't support accepting fragment tasks.";
        throw new UnsupportedOperationException(msg);
    }

    public void setupProcedureResume(boolean isFinal, int[] dependencies) {
        String msg = "The current transaction context of type " + this.getClass().getName();
        msg += " doesn't support receiving dependencies.";
        throw new UnsupportedOperationException(msg);
    }

    public void processRemoteWorkResponse(FragmentResponseMessage response) {
        String msg = "The current transaction context of type ";
        msg += this.getClass().getName();
        msg += " doesn't support receiving fragment responses.";
        throw new UnsupportedOperationException(msg);
    }

    public Map<Integer, List<VoltTable>> getPreviousStackFrameDropDependendencies() {
        String msg = "The current transaction context of type ";
        msg += this.getClass().getName();
        msg += " doesn't support collecting stack frame drop dependencies.";
        throw new UnsupportedOperationException(msg);
    }

    public int getNextDependencyId() {
        return m_nextDepId++;
    }

    public abstract void getDumpContents(StringBuilder sb);

    public abstract ExecutorTxnState getDumpContents();

    @Override
    public int compareTo(TransactionState o) {
        long x = o.txnId - txnId;
        if (x < 0) return 1;
        if (x > 0) return -1;
        return 0;
    }

    /**
     * Process the failure of failedSites.
     * @param globalCommitPoint greatest committed transaction id in the cluster
     * @param failedSites list of execution and initiator sites that have failed
     */
    public abstract void handleSiteFaults(ArrayList<Integer> failedSites);

}
