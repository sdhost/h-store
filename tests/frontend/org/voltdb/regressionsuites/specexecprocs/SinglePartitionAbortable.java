package org.voltdb.regressionsuites.specexecprocs;

import org.apache.log4j.Logger;
import org.voltdb.ProcInfo;
import org.voltdb.SQLStmt;
import org.voltdb.VoltProcedure;
import org.voltdb.VoltTable;

import edu.brown.benchmark.tm1.TM1Constants;

/**
 * Special single-partition transaction that will always abort
 * @author pavlo
 */
@ProcInfo(
    partitionParam = 0,
    singlePartition = true
)
public class SinglePartitionAbortable extends VoltProcedure {
    private static final Logger LOG = Logger.getLogger(SinglePartitionAbortable.class);
    
    public final SQLStmt updateSubscriber = new SQLStmt(
        "UPDATE " + TM1Constants.TABLENAME_SUBSCRIBER +
        "  SET VLR_LOCATION = ? " +
        " WHERE S_ID = ? "
    );
    
    public VoltTable[] run(long s_id, int marker, long abort) {
        // Let 'er rip!
        voltQueueSQL(updateSubscriber, marker, s_id);
        final VoltTable results[] = voltExecuteSQL();
        assert(results.length == 1);
        LOG.info("RESULTS:\n" + results[0]);

        if (abort == 1) {
            String msg = String.format("Aborting [S_ID=%d / MARKER=%d]", s_id, marker);
            LOG.warn(msg);
            throw new VoltAbortException(msg);
        }
        LOG.warn(String.format("Updated %s [S_ID=%d / MARKER=%d]",
                 TM1Constants.TABLENAME_SUBSCRIBER, s_id, marker));
        return (results);
    }
}
