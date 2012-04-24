package shark.exec

import java.util.{List => JavaList}

import org.apache.hadoop.hive.metastore.api.FieldSchema
import org.apache.hadoop.hive.ql.exec.{TableScanOperator => HiveTableScanOperator, Utilities}
import org.apache.hadoop.hive.ql.{Context, DriverContext}
import org.apache.hadoop.hive.ql.metadata.{Partition, Table}
import org.apache.hadoop.hive.ql.optimizer.ppr.PartitionPruner
import org.apache.hadoop.hive.ql.parse._
import org.apache.hadoop.hive.ql.plan.CreateTableDesc
import org.apache.hadoop.hive.ql.plan.api.StageType

import scala.collection.JavaConversions._

import shark.LogHelper
import spark.RDD


class SparkWork(
  val pctx: ParseContext,
  val terminalOperator: TerminalAbstractOperator[_],
  val resultSchema: JavaList[FieldSchema])
extends java.io.Serializable


/**
 * SparkTask executes a query plan composed of RDD operators.
 */
class SparkTask extends org.apache.hadoop.hive.ql.exec.Task[SparkWork]
with java.io.Serializable with LogHelper {

  private var _tableRdd: TableRDD = null
  def tableRdd = _tableRdd

  override def execute(driverContext: DriverContext): Int = {
    logInfo("Executing " + this.getClass.getName)
    
    Operator.hconf = conf

    // Replace Hive physical plan with Shark plan.
    val terminalOp = work.terminalOperator
    val tableScanOps = terminalOp.returnTopOperators().asInstanceOf[Seq[TableScanOperator]]
    
    //ExplainTaskHelper.outputPlan(terminalOp, Console.out, true, 2)
    //ExplainTaskHelper.outputPlan(hiveTopOps.head, Console.out, true, 2)
    
    initializeTableScanTableDesc(tableScanOps)
    
    // Initialize the Hive query plan. This gives us all the object inspectors.
    initializeAllHiveTopOperators(terminalOp)
    
    terminalOp.initializeMasterOnAll()
    
    val sinkRdd = terminalOp.execute().asInstanceOf[RDD[Any]]

    _tableRdd = new TableRDD(sinkRdd, work.resultSchema, terminalOp.objectInspector)
    0
  }

  def initializeTableScanTableDesc(topOps: Seq[TableScanOperator]) {
    val topToTable = work.pctx.getTopToTable()

    // Add table metadata to TableScanOperators
    topOps.foreach { op => {
      val table = topToTable.get(op.hiveOp)
      op.tableDesc = Utilities.getTableDesc(table)
      op.table = table
      if (table.isPartitioned) {
        val ppl = PartitionPruner.prune(
          topToTable.get(op.hiveOp),
          work.pctx.getOpToPartPruner().get(op.hiveOp),
          work.pctx.getConf(), "",
          work.pctx.getPrunedPartitions())
        op.parts = ppl.getConfirmedPartns.toArray ++ ppl.getUnknownPartns.toArray
        val allParts = op.parts ++ ppl.getDeniedPartns.toArray
        op.firstConfPartDesc = Utilities.getPartitionDesc(
          allParts(0).asInstanceOf[Partition])
      }
    }}
  }

  def initializeAllHiveTopOperators(terminalOp: TerminalAbstractOperator[_]) {
    // Need to guarantee all parents are initialized before the child.
    val topOpList = new scala.collection.mutable.MutableList[HiveTopOperator]
    val terminalOpList = new scala.collection.mutable.MutableList[ReduceSinkOperator]
    val queue = new scala.collection.mutable.Queue[Operator[_]]
    queue.enqueue(terminalOp)

    while (!queue.isEmpty) {
      val current = queue.dequeue()
      current match {
        case op: HiveTopOperator => topOpList += op
        case op: ReduceSinkOperator => terminalOpList += op
        case _ => Unit
      }
      queue ++= current.parentOperators
    }
    
    // Break the Hive operator tree into multiple stages, separated by Hive
    // ReduceSink. This is necessary because the Hive operators after ReduceSink
    // cannot be initialized using ReduceSink's output object inspector. We
    // craft the struct object inspector (that has both KEY and VALUE) in Shark
    // ReduceSinkOperator.initializeDownStreamHiveOperators().
    terminalOpList.foreach { op =>
      val hiveOp = op.asInstanceOf[Operator[HiveOperator]].hiveOp
      if (hiveOp.getChildOperators() != null) {
        hiveOp.getChildOperators().foreach { child =>
          logInfo("Removing child %s from %s".format(child, hiveOp))
          hiveOp.removeChild(child)
        }
      }
    }

    // Run the initialization. This guarantees that upstream operators are
    // initialized before downstream ones.
    topOpList.reverse.foreach { topOp =>
      topOp.initializeHiveTopOperator() 
    }
  }

  override def getType = StageType.MAPRED

  override def getName = "MAPRED-SPARK"

  override def localizeMRTmpFilesImpl(ctx: Context) = Unit

}
