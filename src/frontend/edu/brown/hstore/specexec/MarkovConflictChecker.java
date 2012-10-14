package edu.brown.hstore.specexec;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;

import org.apache.log4j.Logger;
import org.voltdb.CatalogContext;
import org.voltdb.ParameterSet;
import org.voltdb.catalog.Column;
import org.voltdb.catalog.ConflictPair;
import org.voltdb.catalog.Procedure;
import org.voltdb.catalog.Statement;
import org.voltdb.catalog.StmtParameter;
import org.voltdb.catalog.Table;

import edu.brown.catalog.CatalogUtil;
import edu.brown.catalog.conflicts.ConflictSetUtil;
import edu.brown.designer.ColumnSet;
import edu.brown.hstore.estimators.EstimatorState;
import edu.brown.hstore.estimators.QueryEstimate;
import edu.brown.hstore.estimators.TransactionEstimate;
import edu.brown.hstore.txns.AbstractTransaction;
import edu.brown.hstore.txns.LocalTransaction;
import edu.brown.logging.LoggerUtil;
import edu.brown.logging.LoggerUtil.LoggerBoolean;
import edu.brown.mappings.ParameterMapping;
import edu.brown.mappings.ParameterMappingsSet;
import edu.brown.mappings.ParametersUtil;
import edu.brown.markov.EstimationThresholds;
import edu.brown.utils.CollectionUtil;

/**
 * A more fine-grained ConflictChecker based on estimations of what 
 * rows the txns will read/write.
 * @author pavlo
 */
public class MarkovConflictChecker extends AbstractConflictChecker {
    private static final Logger LOG = Logger.getLogger(MarkovConflictChecker.class);
    private static final LoggerBoolean debug = new LoggerBoolean(LOG.isDebugEnabled());
    private static final LoggerBoolean trace = new LoggerBoolean(LOG.isTraceEnabled());
    static {
        LoggerUtil.attachObserver(LOG, debug, trace);
    }
    
    private final ParameterMappingsSet paramMappings;
    private final EstimationThresholds thresholds;
    private final boolean disabled;

    // ------------------------------------------------------------
    // PRECOMPUTED CACHE
    // ------------------------------------------------------------

    private static class StatementCache {
        /**
         * We maintain a mapping to another Statement that it conflicts
         * with and its corresponding ConflictPair that represents that conflict
         */
        final Map<Statement, ConflictPair> conflicts = new HashMap<Statement, ConflictPair>();  

        /**
         * We maintain a list of the StmtParameters that are used in predicates with 
         * the target table's primary key
         */
        final Map<Column, StmtParameter> colParams = new HashMap<Column, StmtParameter>();
    } // CLASS
    
    /**
     * We have a separate cache object for each Statement
     */
    private final Map<Statement, StatementCache> stmtCache = new HashMap<Statement, StatementCache>();
    
    
    /**
     * Table -> Primary Keys
     */
    private final Map<Table, Column[]> pkeysCache = new HashMap<Table, Column[]>();
    
    
    // ------------------------------------------------------------
    // INITIALIZATION
    // ------------------------------------------------------------
    
    public MarkovConflictChecker(CatalogContext catalogContext, EstimationThresholds thresholds) {
        super(catalogContext);
        this.paramMappings = catalogContext.paramMappings;
        this.thresholds = thresholds;
        this.disabled = (this.paramMappings == null);
        
        for (Table catalog_tbl : CatalogUtil.getDataTables(this.catalogContext.database)) {
            this.pkeysCache.put(catalog_tbl, CatalogUtil.getPrimaryKeyColumns(catalog_tbl).toArray(new Column[0]));
        } // FOR (table)
        
        for (Procedure proc : this.catalogContext.getRegularProcedures()) {
            Collection<ConflictPair> conflicts = ConflictSetUtil.getAllConflictPairs(proc);
            for (Statement stmt : proc.getStatements()) {
                StatementCache cache = new StatementCache();
                for (ConflictPair cp : conflicts) {
                    if (cp.getStatement0().equals(stmt)) {
                        cache.conflicts.put(cp.getStatement1(), cp);
                    }
                } // FOR
                
                ColumnSet cset = CatalogUtil.extractStatementColumnSet(stmt, false);
                Map<Table, StmtParameter[]> tableParams = new HashMap<Table, StmtParameter[]>();
                List<StmtParameter> stmtParamOffsets = new ArrayList<StmtParameter>();
                for (Table tbl : CatalogUtil.getReferencedTables(stmt)) {
                    for (Column col : this.pkeysCache.get(tbl)) {
                        Collection<StmtParameter> params = cset.findAllForOther(StmtParameter.class, col);
                        
                        // If there are more than one, then it should always conflict
                        if (params.size() > 1) {
                            // TODO
                        }
                        else {
                            // If there are no references, then there is nothing else that we 
                            // we need to do here. The StmtParameter that we get back will be null
                            StmtParameter stmtParam = CollectionUtil.first(params);
                            assert(stmtParam != null);
                            cache.colParams.put(col, stmtParam);
                        }
                    } // FOR
                    tableParams.put(tbl, stmtParamOffsets.toArray(new StmtParameter[0]));
                } // FOR
                this.stmtCache.put(stmt, cache);
            } // FOR (stmt)
        } // FOR (proc)
    }

    @Override
    public boolean ignoreProcedure(Procedure proc) {
        return (this.disabled);
    }

    @Override
    public boolean canExecute(AbstractTransaction dtxn, LocalTransaction ts, int partitionId) {
        // Get the queries for both of the txns
        EstimatorState dtxnState = dtxn.getEstimatorState();
        EstimatorState tsState = ts.getEstimatorState();
        if (dtxnState == null || tsState == null) {
            if (debug.get())
                LOG.debug(String.format("No EstimatorState available for %s<->%s", dtxn, ts));
            return (false);
        }
        
        // Get the current TransactionEstimate for the DTXN and the 
        // initial TransactionEstimate for the single-partition txn
        // We need to make sure that both estimates have the list of 
        // queries that the transaction is going to execute
        TransactionEstimate dtxnEst = dtxnState.getLastEstimate();
        assert(dtxnEst != null);
        if (dtxnEst.hasQueryEstimate() == false) {
            if (debug.get())
                LOG.debug(String.format("No query list estimate is available for dtxn %s", dtxn));
            return (false);
        }
        TransactionEstimate tsEst = tsState.getInitialEstimate();
        assert(tsEst != null);
        if (tsEst.hasQueryEstimate() == false) {
            if (debug.get())
                LOG.debug(String.format("No query list estimate is available for candidate %s", ts));
            return (false);
        }
        
        // If both txns are read-only, then we can let our homeboy go
        boolean readonly0 = dtxnEst.isReadOnlyPartition(this.thresholds, partitionId);
        boolean readonly1 = tsEst.isReadOnlyPartition(this.thresholds, partitionId);
        if (readonly0 && readonly1) {
            if (debug.get())
                LOG.debug(String.format("%s<->%s are both are read-only. No conflict!", dtxn, ts));
            return (false);
        }
        
        QueryEstimate queries0 = dtxnEst.getEstimatedQueries(partitionId);
        QueryEstimate queries1 = tsEst.getEstimatedQueries(partitionId);
        
        return this.canExecute(dtxn, queries0, ts, queries1);
    }
    
    protected boolean canExecute(AbstractTransaction ts0, QueryEstimate queries0,
                                 AbstractTransaction ts1, QueryEstimate queries1) {
        ParameterSet params0 = ts0.getProcedureParameters();
        ParameterSet params1 = ts1.getProcedureParameters();
        Statement stmt0, stmt1;
        int stmtCtr0, stmtCtr1;
        StatementCache cache0, cache1;
        Map<StmtParameter, SortedSet<ParameterMapping>> mappings0, mappings1;
        
        // TODO: Rather than checking the values referenced in each ConflictPair
        // individually, we should go through all of them first and just get the 
        // ProcParameter + Offsets that actually matter. Then we can just check the
        // values one after each other.
        
        // Now with ConflictPairs, we need to get the minimum set of ProcParameters that are
        // used as input to the conflicting Statements and then check whether they have the same
        // value. If they do, then we cannot run the candidate txn.
        
        for (int i0 = 0, cnt0 = queries0.size(); i0 < cnt0; i0++) {
            stmt0 = queries0.getStatement(i0);
            stmtCtr0 = queries0.getStatementCounter(i0);
            cache0 = this.stmtCache.get(stmt0);
            mappings0 = this.catalogContext.paramMappings.get(stmt0, stmtCtr0);
            
            for (int i1 = 0, cnt1 = queries1.size(); i1 < cnt1; i1++) {
                stmt1 = queries1.getStatement(i1);
                stmtCtr1 = queries0.getStatementCounter(i1);
                ConflictPair cp = cache0.conflicts.get(stmt1);

                // If there isn't a ConflictPair, then there isn't a conflict
                if (cp == null) {
                    continue;
                }
                // If the ConflictPair is marked as always conflicting, then
                // we can stop right here
                else if (cp.getAlwaysconflicting()) {
                    return (true);
                }
                
                // Otherwise, at this point we know that we have two queries that both 
                // reference the same table(s). Therefore, we need to evaluate the values 
                // of the primary keys referenced in the queries to see whether they conflict
                cache1 = this.stmtCache.get(stmt1);
                mappings1 = this.catalogContext.paramMappings.get(stmt1, stmtCtr1);
                
                for (Column col : cache0.colParams.keySet()) {
                    StmtParameter param0 = cache0.colParams.get(col);
                    StmtParameter param1 = cache1.colParams.get(col);
                    assert(param1 != null);
                    
                    ParameterMapping pm0 = CollectionUtil.first(mappings0.get(param0));
                    assert(pm0 != null);
                    ParameterMapping pm1 = CollectionUtil.first(mappings1.get(param1));
                    assert(pm1 != null);
                    
                    if (this.equalParameters(params0, pm0, params1, pm1) == false) {
                        return (true);
                    }
                } // FOR
            } // FOR
        } // FOR
        return (false);
    }
    
    protected boolean equalParameters(ParameterSet params0, ParameterMapping pm0, ParameterSet params1, ParameterMapping pm1) {
        Object val0, val1;
        
        if (pm0.procedure_parameter.getIsarray()) {
            assert(pm0.procedure_parameter_index != ParametersUtil.NULL_PROC_PARAMETER_OFFSET);
            val0 = ((Object[])params0.toArray()[pm0.procedure_parameter.getIndex()])[pm0.procedure_parameter_index];
        } else {
            val0 = params0.toArray()[pm0.procedure_parameter.getIndex()];
        }
        if (pm1.procedure_parameter.getIsarray()) {
            assert(pm1.procedure_parameter_index != ParametersUtil.NULL_PROC_PARAMETER_OFFSET);
            val1 = ((Object[])params1.toArray()[pm1.procedure_parameter.getIndex()])[pm1.procedure_parameter_index];
        } else {
            val1 = params1.toArray()[pm1.procedure_parameter.getIndex()];
        }
        
        if (val0 == null) {
            return (val1 != null);
        } else if (val1 == null) {
            return (false);
        }
        return (val0.equals(val1));
    }

}
