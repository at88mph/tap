/*
************************************************************************
*******************  CANADIAN ASTRONOMY DATA CENTRE  *******************
**************  CENTRE CANADIEN DE DONNÉES ASTRONOMIQUES  **************
*
*  (c) 2018.                            (c) 2018.
*  Government of Canada                 Gouvernement du Canada
*  National Research Council            Conseil national de recherches
*  Ottawa, Canada, K1A 0R6              Ottawa, Canada, K1A 0R6
*  All rights reserved                  Tous droits réservés
*
*  NRC disclaims any warranties,        Le CNRC dénie toute garantie
*  expressed, implied, or               énoncée, implicite ou légale,
*  statutory, of any kind with          de quelque nature que ce
*  respect to the software,             soit, concernant le logiciel,
*  including without limitation         y compris sans restriction
*  any warranty of merchantability      toute garantie de valeur
*  or fitness for a particular          marchande ou de pertinence
*  purpose. NRC shall not be            pour un usage particulier.
*  liable in any event for any          Le CNRC ne pourra en aucun cas
*  damages, whether direct or           être tenu responsable de tout
*  indirect, special or general,        dommage, direct ou indirect,
*  consequential or incidental,         particulier ou général,
*  arising from the use of the          accessoire ou fortuit, résultant
*  software.  Neither the name          de l'utilisation du logiciel. Ni
*  of the National Research             le nom du Conseil National de
*  Council of Canada nor the            Recherches du Canada ni les noms
*  names of its contributors may        de ses  participants ne peuvent
*  be used to endorse or promote        être utilisés pour approuver ou
*  products derived from this           promouvoir les produits dérivés
*  software without specific prior      de ce logiciel sans autorisation
*  written permission.                  préalable et particulière
*                                       par écrit.
*
*  This file is part of the             Ce fichier fait partie du projet
*  OpenCADC project.                    OpenCADC.
*
*  OpenCADC is free software:           OpenCADC est un logiciel libre ;
*  you can redistribute it and/or       vous pouvez le redistribuer ou le
*  modify it under the terms of         modifier suivant les termes de
*  the GNU Affero General Public        la “GNU Affero General Public
*  License as published by the          License” telle que publiée
*  Free Software Foundation,            par la Free Software Foundation
*  either version 3 of the              : soit la version 3 de cette
*  License, or (at your option)         licence, soit (à votre gré)
*  any later version.                   toute version ultérieure.
*
*  OpenCADC is distributed in the       OpenCADC est distribué
*  hope that it will be useful,         dans l’espoir qu’il vous
*  but WITHOUT ANY WARRANTY;            sera utile, mais SANS AUCUNE
*  without even the implied             GARANTIE : sans même la garantie
*  warranty of MERCHANTABILITY          implicite de COMMERCIALISABILITÉ
*  or FITNESS FOR A PARTICULAR          ni d’ADÉQUATION À UN OBJECTIF
*  PURPOSE.  See the GNU Affero         PARTICULIER. Consultez la Licence
*  General Public License for           Générale Publique GNU Affero
*  more details.                        pour plus de détails.
*
*  You should have received             Vous devriez avoir reçu une
*  a copy of the GNU Affero             copie de la Licence Générale
*  General Public License along         Publique GNU Affero avec
*  with OpenCADC.  If not, see          OpenCADC ; si ce n’est
*  <http://www.gnu.org/licenses/>.      pas le cas, consultez :
*                                       <http://www.gnu.org/licenses/>.
*
************************************************************************
*/

package ca.nrc.cadc.tap.db;

import ca.nrc.cadc.dali.Circle;
import ca.nrc.cadc.dali.DoubleInterval;
import ca.nrc.cadc.dali.Interval;
import ca.nrc.cadc.dali.LongInterval;
import ca.nrc.cadc.dali.Point;
import ca.nrc.cadc.dali.Polygon;
import ca.nrc.cadc.date.DateUtil;
import ca.nrc.cadc.db.DatabaseTransactionManager;
import ca.nrc.cadc.profiler.Profiler;
import ca.nrc.cadc.tap.PluginFactory;
import ca.nrc.cadc.tap.schema.ColumnDesc;
import ca.nrc.cadc.tap.schema.TableDesc;
import ca.nrc.cadc.tap.schema.TapDataType;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import javax.sql.DataSource;
import org.apache.log4j.Logger;
import org.opencadc.tap.io.TableDataInputStream;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;

/**
 * Utility to bulk load content into a table.
 * 
 * @author pdowler, majorb
 */
public class TableLoader {
    private static final Logger log = Logger.getLogger(TableLoader.class);

    private final DatabaseDataType ddType;
    private final DataSource dataSource;
    private final int batchSize;
    private long totalInserts = 0;
    
    /**
     * Constructor.
     * 
     * @param dataSource destination database connection pool
     * @param batchSize number of rows per commit transaction
     */
    public TableLoader(DataSource dataSource, int batchSize) { 
        this.dataSource = dataSource;
        this.batchSize = batchSize;
        PluginFactory pf = new PluginFactory();
        this.ddType = pf.getDatabaseDataType();
        log.debug("loaded: " + ddType.getClass().getName());
    }
    
    /**
     * Load the table data.
     * 
     * @param destTable The table description
     * @param data The table data.
     */
    public void load(TableDesc destTable, TableDataInputStream data) { 
        TableDesc reorgTable = data.acceptTargetTableDesc(destTable);
        
        Profiler prof = new Profiler(TableLoader.class);
        DatabaseTransactionManager tm = new DatabaseTransactionManager(dataSource);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        
        // Loop over rows, start/commit txn every batchSize rows
        String sql = generateInsertSQL(reorgTable); 
        boolean done = false;
        Iterator<List<Object>> dataIterator = data.iterator();
        List<Object> nextRow = null;

        int count = 0;
        try {
            while (!done) {
                count = 0;
                tm.startTransaction();
                prof.checkpoint("start-transaction");
                
                while (count < batchSize && dataIterator.hasNext()) {
                    nextRow = dataIterator.next();
                    convertValueObjects(nextRow);
                    jdbc.update(sql, nextRow.toArray());
                    count++;
                }
                log.debug("Inserting " + count + " rows in this batch.");
                prof.checkpoint("batch-of-inserts");
                
                tm.commitTransaction();
                prof.checkpoint("commit-transaction");
                totalInserts += count;
                done = !dataIterator.hasNext();
            }
        } catch (IllegalArgumentException | IndexOutOfBoundsException ex) {
            try {
                data.close();
                prof.checkpoint("close-input");
            } catch (Exception oops) {
                log.error("unexpected exception trying to close input stream", oops);
            }
            try {
                if (tm.isOpen()) {
                    tm.rollbackTransaction();
                    prof.checkpoint("rollback-transaction");
                }
            } catch (Exception oops) {
                log.error("Unexpected: could not rollback transaction", oops);
            }
            throw new IllegalArgumentException("Inserted " + totalInserts + " rows. " +
                    "Current batch failed with: " + ex.getMessage() + " on line " + (totalInserts + count));
        } catch (Throwable t) {
            try {
                data.close();
                prof.checkpoint("close-input");
            } catch (Exception oops) {
                log.error("unexpected exception trying to close input stream", oops);
            }
            try {
                if (tm.isOpen()) {
                    tm.rollbackTransaction();
                    prof.checkpoint("rollback-transaction");
                }
            } catch (Throwable oops) {
                log.error("Unexpected: could not rollback transaction", oops);
            }

            log.debug("Batch insert failure", t);
            throw new RuntimeException("Inserted " + totalInserts + " rows. "
                + "Current batch of " + batchSize + " failed with: " + t.getMessage(), t);
            
        } finally {
            if (tm.isOpen()) {
                log.error("BUG: Transaction manager unexpectedly open, rolling back.");
                try {
                    tm.rollbackTransaction();
                    prof.checkpoint("rollback-transaction");
                } catch (Throwable t) {
                    log.error("Unexpected: could not rollback transaction", t);
                }
            }
            try {
                data.close();
            } catch (Exception ex) {
                log.debug("exception trying to close input stream in finally: ignoring it", ex);
            }
        }
        log.debug("Inserted a total of " + totalInserts + " rows.");
    }
    
    // this assumes that columns in destTable and data are in the same order
    // generate a parameterized insert statement for use with one of the API choices
    private String generateInsertSQL(TableDesc td) {
        
        StringBuilder sb = new StringBuilder("insert into ");
        sb.append(td.getTableName());
        sb.append(" (");
        for (ColumnDesc cd : td.getColumnDescs()) {
            sb.append(cd.getColumnName());
            sb.append(", ");
        }
        sb.setLength(sb.length() - 2);
        sb.append(") values (");
        for (ColumnDesc cd : td.getColumnDescs()) {
            sb.append("?, ");
        }
        sb.setLength(sb.length() - 2);
        sb.append(")");

        return sb.toString();
    }
    
    private class BulkInsertStatement implements PreparedStatementCreator {
        private final Calendar utc = Calendar.getInstance(DateUtil.UTC);
        private TableDesc tableDesc;
        Object[] row;
        
        @Override
        public PreparedStatement createPreparedStatement(Connection con) throws SQLException {
            String sql = generateInsertSQL(tableDesc);
            PreparedStatement ret = con.prepareStatement(sql);
            
            for (int i = 0; i < tableDesc.getColumnDescs().size(); i++) {
                ColumnDesc cd = tableDesc.getColumnDescs().get(i);
                Object val = row[i];
                if (val != null && val instanceof Date && TapDataType.TIMESTAMP.equals(cd.getDatatype())) {
                    Date d = (Date) val;
                    ret.setTimestamp(i + 1, new Timestamp(d.getTime()), utc);
                } else {
                    ret.setObject(i + 1, val);
                }
            }
            return ret;
        }
    }
    
    /**
     * @return The total number of rows inserted.
     */
    public long getTotalInserts() {
        return totalInserts;
    }
    
    // convert values that the JDBC driver won't accept
    private void convertValueObjects(List<Object> values) {
        for (int i=0; i < values.size(); i++) {
            Object v = values.get(i);
            if (v != null) {
                if (v instanceof URI) {
                    String nv = ((URI) v).toASCIIString();
                    values.set(i, nv);
                } else if (v instanceof DoubleInterval) {
                    Object nv = ddType.getIntervalObject((DoubleInterval) v);
                    values.set(i, nv);
                } else if (v instanceof LongInterval) {
                    Interval inter = (Interval) v;
                    DoubleInterval di = new DoubleInterval(inter.getLower().doubleValue(), inter.getUpper().doubleValue());
                    Object nv = ddType.getIntervalObject(di);
                    values.set(i, nv);
                } else if (v instanceof Point) {
                    Object nv = ddType.getPointObject((Point) v);
                    values.set(i, nv);
                } else if (v instanceof Circle) {
                    Object nv = ddType.getCircleObject((Circle) v);
                    values.set(i, nv);
                } else if (v instanceof Polygon) {
                    Object nv = ddType.getPolygonObject((Polygon) v);
                    values.set(i, nv);
                } else if (v instanceof ca.nrc.cadc.stc.Position) {
                    Object nv = ddType.getPointObject((ca.nrc.cadc.stc.Position) v);
                    values.set(i, nv);
                } else if (v instanceof ca.nrc.cadc.stc.Region) {
                    Object nv = ddType.getRegionObject((ca.nrc.cadc.stc.Region) v);
                    values.set(i, nv);
                } else if (v instanceof short[]) {
                    Object nv = ddType.getArrayObject((short[]) v);
                    values.set(i, nv);
                } else if (v instanceof int[]) {
                    Object nv = ddType.getArrayObject((int[]) v);
                    values.set(i, nv);
                } else if (v instanceof long[]) {
                    Object nv = ddType.getArrayObject((long[]) v);
                    values.set(i, nv);
                } else if (v instanceof float[]) {
                    Object nv = ddType.getArrayObject((float[]) v);
                    values.set(i, nv);
                } else if (v instanceof double[]) {
                    Object nv = ddType.getArrayObject((double[]) v);
                    values.set(i, nv);
                }
            }
        }
    }
}
