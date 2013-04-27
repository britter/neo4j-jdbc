package org.neo4j.jdbc.embedded;

import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.neo4j.cypher.javacompat.ExecutionEngine;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Transaction;
import org.neo4j.helpers.collection.IteratorWrapper;
import org.neo4j.jdbc.ExecutionResult;
import org.neo4j.jdbc.QueryExecutor;
import org.neo4j.jdbc.Version;
import org.neo4j.kernel.GraphDatabaseAPI;

/**
 * @author mh
 * @since 15.06.12
 */
public class EmbeddedQueryExecutor implements QueryExecutor {


    private final ExecutionEngine executionEngine;
    private final GraphDatabaseService gds;

    ThreadLocal<Transaction> tx=new ThreadLocal<Transaction>();

    public EmbeddedQueryExecutor(GraphDatabaseService gds) {
        this.gds = gds;
        executionEngine = new ExecutionEngine(gds);
    }

    @Override
    public ExecutionResult executeQuery(final String query, Map<String, Object> parameters, boolean autoCommit) throws Exception {
        final Map<String, Object> params = parameters == null ? Collections.<String, Object>emptyMap() : parameters;
        begin();
        final org.neo4j.cypher.javacompat.ExecutionResult result = executionEngine.execute(query, params);
        if (autoCommit) commit();
        final List<String> columns = result.columns();
        final int cols = columns.size();
        final Object[] resultRow = new Object[cols];
        return new ExecutionResult(columns,new IteratorWrapper<Object[],Map<String,Object>>(result.iterator()) {
            @Override
            public boolean hasNext() {
                try {
                    return super.hasNext();
                } catch(Exception e)  {
                    handleException(e, query);
                    return false;
                }
            }

            protected Object[] underlyingObjectToObject(Map<String, Object> row) {
                for (int i = 0; i < cols; i++) {
                    resultRow[i]=row.get(columns.get(i));
                }
                return resultRow;
            }
        });
    }

    private void begin() {
        if (tx.get()==null) {
            tx.set(gds.beginTx());
        }
    }

    @Override
    public void commit() throws Exception {
        final Transaction transaction = tx.get();
        if (transaction ==null) return; // throw new SQLException("Not in transaction for commit");
        tx.set(null);
        transaction.success();
        transaction.finish();
    }

    @Override
    public void rollback() throws Exception {
        final Transaction transaction = tx.get();
        if (transaction == null) return;
        tx.set(null);
        transaction.failure();
        transaction.finish();
    }

    private void handleException(Exception cause, String query) {
        final SQLException sqlException = new SQLException("Error executing query: " + query, cause);
        AnyThrow.unchecked(sqlException);
    }

    public static class AnyThrow {
        public static RuntimeException unchecked(Throwable e) {
            AnyThrow.<RuntimeException>throwAny(e);
            return null;
        }

        @SuppressWarnings("unchecked")
        private static <E extends Throwable> void throwAny(Throwable e) throws E {
            throw (E)e;
        }
    }

    @Override
    public void stop() throws Exception {
        rollback();
        // don't own the db, will be stopped when driver's stopped
    }

    @Override
    public Version getVersion() {
        return new Version(((GraphDatabaseAPI)gds).getKernelData().version().getRevision());
    }
}
