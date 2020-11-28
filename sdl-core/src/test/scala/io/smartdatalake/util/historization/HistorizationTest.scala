/*
 * Smart Data Lake - Build your data lake the smart way.
 *
 * Copyright © 2019-2020 ELCA Informatique SA (<https://www.elca.ch>)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package io.smartdatalake.util.historization

import java.time.LocalDateTime

import io.smartdatalake.definitions.{HiveConventions, TechnicalTableColumn}
import io.smartdatalake.testutils.TestUtil
import io.smartdatalake.util.evolution.SchemaEvolution
import io.smartdatalake.util.misc.SmartDataLakeLogger
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.functions.{col, lit, to_timestamp, when}
import org.apache.spark.sql.types.{IntegerType, StringType, StructField, StructType, TimestampType}
import org.apache.spark.sql.{Column, DataFrame, Row, SparkSession}
import org.scalatest.{BeforeAndAfter, FunSuite}


/**
 * Unit tests for historization
 *
 */
class HistorizationTest extends FunSuite with BeforeAndAfter with SmartDataLakeLogger {

  private implicit val session: SparkSession = TestUtil.sessionHiveCatalog

  private object HistorizationPhase extends Enumeration {
    type HistorizationPhase = Value
    val Existing: HistorizationPhase = Value
    val UpdatedNew: HistorizationPhase = Value
    val UpdatedOld: HistorizationPhase = Value
    val NewlyAdded: HistorizationPhase = Value
    val TechnicallyDeleted: HistorizationPhase = Value
  }

  private val ts1: java.time.LocalDateTime => Column = t => lit(t.toString).cast(TimestampType)
  private val doomsday =
    HiveConventions.getHistorizationSurrogateTimestamp
  private val erfasstTimestampOldHist = LocalDateTime.now.minusDays(2)
  private val ersetztTimestampOldHist = doomsday
  private val erfasstTimestampOldDeletedHist = LocalDateTime.now.minusDays(30)
  private val ersetztTimestampOldDeletedHist = LocalDateTime.now.minusDays(23)
  private val colNames = Seq("id", "name", "age", "health_state")
  private val colNullability = Map("id" -> true, "name" -> true, "age" -> true,
    "health_state" -> true)
  private val histColNames = Seq("id", "name", "age", "health_state", TechnicalTableColumn.captured.toString,
    TechnicalTableColumn.delimited.toString)
  private val primaryKeyColumns = Array("id", "name")
  private val referenceTimestampNew = LocalDateTime.now
  private val offsetNs = 1000000L
  private val referenceTimestampOld = referenceTimestampNew.minusNanos(offsetNs)

  // Executed before each test
  before {
    init()
  }

  /*
  Initializing of Hadoop components is only done once, otherwise you get errors 
  regarding mulitple instances of Hive Metastore
   */
  private def init(): Unit = {
  }

  private def toHistorizedDf(session: SparkSession, records: List[Tuple4[Int, String, Int, String]],
                             phase: HistorizationPhase.HistorizationPhase): DataFrame = {
    import session.sqlContext.implicits._
    val rddHist = session.sparkContext.parallelize(records)
    phase match {
      case HistorizationPhase.Existing =>
        val dfHist = rddHist.toDF(colNames: _*)
        .withColumn(s"${TechnicalTableColumn.captured.toString}", ts1(erfasstTimestampOldHist))
        .withColumn(s"${TechnicalTableColumn.delimited.toString}", ts1(ersetztTimestampOldHist))
        setNullableStateOfColumn(dfHist, colNullability)
      case HistorizationPhase.UpdatedOld =>
        val dfHist = rddHist.toDF(colNames: _*)
          .withColumn(s"${TechnicalTableColumn.captured.toString}", ts1(erfasstTimestampOldHist))
          .withColumn(s"${TechnicalTableColumn.delimited.toString}", ts1(referenceTimestampOld))
        setNullableStateOfColumn(dfHist, colNullability)
      case HistorizationPhase.UpdatedNew =>
        val dfHist = rddHist.toDF(colNames: _*)
          .withColumn(s"${TechnicalTableColumn.captured.toString}", ts1(referenceTimestampNew))
          .withColumn(s"${TechnicalTableColumn.delimited.toString}", ts1(doomsday))
        setNullableStateOfColumn(dfHist, colNullability)
      case HistorizationPhase.NewlyAdded =>
        val dfHist = rddHist.toDF(colNames: _*)
          .withColumn(s"${TechnicalTableColumn.captured.toString}", ts1(referenceTimestampNew))
          .withColumn(s"${TechnicalTableColumn.delimited.toString}", ts1(doomsday))
        setNullableStateOfColumn(dfHist, colNullability)
      case HistorizationPhase.TechnicallyDeleted =>
        val dfHist = rddHist.toDF(colNames: _*)
          .withColumn(s"${TechnicalTableColumn.captured.toString}", ts1(erfasstTimestampOldDeletedHist))
          .withColumn(s"${TechnicalTableColumn.delimited.toString}", ts1(ersetztTimestampOldDeletedHist))
        setNullableStateOfColumn(dfHist, colNullability)
    }
  }

  private def toNewFeedDf(session: SparkSession, records: List[Tuple4[Int, String, Int, String]]) = {
    import session.sqlContext.implicits._
    val rdd = session.sqlContext.sparkContext.parallelize(records)
    val df = rdd.toDF(colNames: _*)
    setNullableStateOfColumn(df, colNullability)
  }

  def setNullableStateOfColumn(df: DataFrame, nullValues: Map[String, Boolean]) : DataFrame = {
    val schema = df.schema
    val newSchema = StructType(schema.map {
      case StructField( c, t, _, m) if nullValues.contains(c) => StructField( c, t,
        nullable = nullValues(c), m)
      case y: StructField => y
    })
    df.sqlContext.createDataFrame( df.rdd, newSchema )
  }

  private def sortResults(df: DataFrame): DataFrame = {
    df.sort("id", TechnicalTableColumn.delimited.toString)
  }

  test("History unchanged with new columns but unchanged data..") {

    val baseColumnsOldHist = List((123, "Egon", 23, "healthy"), (124, "Erna", 27, "healthy"))
    val dfOldHist = toHistorizedDf(session, baseColumnsOldHist, HistorizationPhase.Existing)
    logger.debug(s"History at beginning: ${dfOldHist.collect.foreach(println)}")

    val baseColumnsNewFeed = List((123, "Egon", 23, "healthy"), (124, "Erna", 27, "healthy"))
    val dfNewFeed = toNewFeedDf(session, baseColumnsNewFeed)
    val colExprNewcol = when(col("id")===123,"Test")
    val dfNewFeedWithAdditionalCols = dfNewFeed.withColumn("new_col1", colExprNewcol otherwise lit(null).cast(StringType))
    logger.debug(s"New feed: ${dfNewFeedWithAdditionalCols.collect.foreach(println)}")

    val (oldEvolvedDf, newEvolvedDf) = SchemaEvolution.process(dfOldHist, dfNewFeedWithAdditionalCols,
      colsToIgnore = Seq(TechnicalTableColumn.captured.toString, TechnicalTableColumn.delimited.toString))

    val dfHistorized = Historization.getHistorized(oldEvolvedDf, newEvolvedDf, primaryKeyColumns,
      referenceTimestampNew, None, None)
    logger.debug(s"Historization result: ${dfHistorized.collect.foreach(println)}")

    //ID 123 has new value and so old record is closed with newcol1=null
    val dfResultExistingRowNonNull = dfOldHist.filter("id==123")
      .drop(TechnicalTableColumn.captured.toString,TechnicalTableColumn.captured.toString)
      .drop(TechnicalTableColumn.captured.toString,TechnicalTableColumn.delimited.toString)
      .withColumn("new_col1", lit(null).cast(StringType))
      .withColumn(s"${TechnicalTableColumn.captured.toString}", ts1(erfasstTimestampOldHist))
      .withColumn(s"${TechnicalTableColumn.delimited.toString}", ts1(referenceTimestampOld))

    //ID 123 has new value and so new record is created with newcol1="Test"
    val dfResultNewRowNonNull = dfNewFeedWithAdditionalCols.filter("id==123")
      .withColumn(s"${TechnicalTableColumn.captured.toString}", ts1(referenceTimestampNew))
      .withColumn(s"${TechnicalTableColumn.delimited.toString}", ts1(doomsday))

    //ID 124 has null new value and so old record is left unchanged but with additional column
    val dfResultExistingRowNull = dfOldHist
      .filter("id==124")
      .drop(TechnicalTableColumn.captured.toString,TechnicalTableColumn.captured.toString)
      .drop(TechnicalTableColumn.captured.toString,TechnicalTableColumn.delimited.toString)
      .withColumn("new_col1", lit(null).cast(StringType))
      .withColumn(s"${TechnicalTableColumn.captured.toString}", ts1(erfasstTimestampOldHist))
      .withColumn(s"${TechnicalTableColumn.delimited.toString}", ts1(ersetztTimestampOldHist))


    val dfExpected = dfResultExistingRowNonNull
      .union(dfResultNewRowNonNull)
        .union(dfResultExistingRowNull)

    dfHistorized.printSchema()
    dfHistorized.show

    logger.debug(s"Expected result: ${dfExpected.collect.foreach(println)}")

    //assert(dfHistorized.collect().length==3)

    assert(TestUtil.isDataFrameEqual(sortResults(dfExpected), sortResults(dfHistorized)))

  }

  ignore("History unchanged when deleting columns but unchanged data.") {
    val baseColumnsOldHist = List((123, "Egon", 23, "healthy"), (124, "Erna", 27, "healthy"))
    val dfOldHist = toHistorizedDf(session, baseColumnsOldHist, HistorizationPhase.Existing)
    logger.debug(s"History at beginning: ${dfOldHist.collect.foreach(println)}")

    val baseColumnsNewFeed = List((123, "Egon", 23, "healthy"), (124, "Erna", 27, "healthy"))
    val dfNewFeed = toNewFeedDf(session, baseColumnsNewFeed)

    val dfNewFeedWithDeletedCols = dfNewFeed.drop("health_state")
    logger.debug(s"New feed: ${dfNewFeedWithDeletedCols.collect.foreach(println)}")

    val dfHistorized = Historization.getHistorized(dfOldHist, dfNewFeedWithDeletedCols, primaryKeyColumns,
      referenceTimestampNew, None, None)
    logger.debug(s"Historization result: ${dfHistorized.collect.foreach(println)}")

    val baseColumnsUpdatedOld = List((123, "Egon", 23, "healthy"), (124, "Erna", 27, "healthy"))
    val dfExpected = toHistorizedDf(session, baseColumnsUpdatedOld, HistorizationPhase.Existing)

    logger.debug(s"Expected result: ${dfExpected.collect.foreach(println)}")
    assert(TestUtil.isDataFrameEqual(sortResults(dfExpected), sortResults(dfHistorized)))

  }

  test("The history should stay unchanged when using the current load again.") {
    val baseColumnsOldHist = List((123, "Egon", 23, "healthy"), (124, "Erna", 27, "healthy"))
    val dfOldHist = toHistorizedDf(session, baseColumnsOldHist, HistorizationPhase.Existing)
    if (logger.isDebugEnabled) {
      logger.debug("History at beginning:")
      dfOldHist.collect.foreach(println)
    }

    val baseColumnsNewFeed = List((123, "Egon", 23, "healthy"), (124, "Erna", 27, "healthy"))
    val dfNewFeed = toNewFeedDf(session, baseColumnsNewFeed)
    if (logger.isDebugEnabled) {
      logger.debug("New feed:")
      dfNewFeed.collect.foreach(println)
    }

    val dfHistorized = Historization.getHistorized(dfOldHist, dfNewFeed, primaryKeyColumns,
      referenceTimestampNew, None, None)
    if (logger.isDebugEnabled) {
      logger.debug("Historization result:")
      dfHistorized.collect.foreach(println)
    }

    val baseColumnsUnchanged = List((123, "Egon", 23, "healthy"), (124, "Erna", 27, "healthy"))
    val dfUnchanged = toHistorizedDf(session, baseColumnsUnchanged, HistorizationPhase.Existing)

    val dfExpected = dfUnchanged
    if (logger.isDebugEnabled) {
      logger.debug("Expected result:")
      dfExpected.collect.foreach(println)
    }

    dfOldHist.show(10, truncate = false)
    dfNewFeed.show(10, truncate = false)

    dfExpected.show(10, truncate = false)
    dfHistorized.show(10, truncate = false)

    assert(TestUtil.isDataFrameEqual(sortResults(dfExpected), sortResults(dfHistorized)))
  }


  test("History should stay unchanged when using current load but with different column sorting.") {
    val baseColumnsOldHist = List((123, "Egon", 23, "healthy"), (124, "Erna", 27, "healthy"))
    val dfOldHist = toHistorizedDf(session, baseColumnsOldHist, HistorizationPhase.Existing)
    if (logger.isDebugEnabled) {
      logger.debug("History at beginning:")
      dfOldHist.collect.foreach(println)
    }

    import session.implicits._

    val baseColumnsNewFeed = List((123, "Egon", 23, "healthy"), (124, "Erna", 27, "healthy"))
    val dfNewFeed = toNewFeedDf(session, baseColumnsNewFeed).select($"age", $"health_state", $"id", $"name")
    if (logger.isDebugEnabled) {
      logger.debug("New feed:")
      dfNewFeed.collect.foreach(println)
    }

    val dfHistorized = Historization.getHistorized(dfOldHist, dfNewFeed, primaryKeyColumns,
      referenceTimestampNew, None, None)
    if (logger.isDebugEnabled) {
      logger.debug("Historization result:")
      dfHistorized.collect.foreach(println)
    }

    val baseColumnsUnchanged = List((123, "Egon", 23, "healthy"), (124, "Erna", 27, "healthy"))
    val dfUnchanged = toHistorizedDf(session, baseColumnsUnchanged, HistorizationPhase.Existing)

    val dfExpected = dfUnchanged
    if (logger.isDebugEnabled) {
      logger.debug("Expected result:")
      dfExpected.collect.foreach(println)
    }

    dfOldHist.show(10, truncate = false)
    dfNewFeed.show(10, truncate = false)

    dfExpected.show(10, truncate = false)
    dfHistorized.show(10, truncate = false)

    assert(TestUtil.isDataFrameEqual(sortResults(dfExpected), sortResults(dfHistorized)))
  }


  test("When updating 1 record, the history should contain the old and the new version of the values.") {
    val baseColumnsOldHist = List((123, "Egon", 23, "healthy"), (124, "Erna", 27, "healthy"))
    val dfOldHist = toHistorizedDf(session, baseColumnsOldHist, HistorizationPhase.Existing)
    if (logger.isDebugEnabled) {
      logger.debug("History at beginning:")
      dfOldHist.collect.foreach(println)
    }

    val baseColumnsNewFeed = List((123, "Egon", 23, "sick"), (124, "Erna", 27, "healthy"))
    val dfNewFeed = toNewFeedDf(session, baseColumnsNewFeed)
    if (logger.isDebugEnabled) {
      logger.debug("New feed:")
      dfNewFeed.collect.foreach(println)
    }

    val dfHistorized = Historization.getHistorized(dfOldHist, dfNewFeed, primaryKeyColumns,
      referenceTimestampNew, None, None)
    if (logger.isDebugEnabled) {
      logger.debug("Historization result:")
      dfHistorized.collect.foreach(println)
    }

    val baseColumnsUpdatedOld = List((123, "Egon", 23, "healthy"))
    val dfUpdatedOld = toHistorizedDf(session, baseColumnsUpdatedOld, HistorizationPhase.UpdatedOld)

    val baseColumnsUpdatedNew = List((123, "Egon", 23, "sick"))
    val dfUpdatedNew = toHistorizedDf(session, baseColumnsUpdatedNew, HistorizationPhase.UpdatedNew)

    val baseColumnsUnchanged = List((124, "Erna", 27, "healthy"))
    val dfUnchanged = toHistorizedDf(session, baseColumnsUnchanged, HistorizationPhase.Existing)

    val dfExpected = dfUpdatedNew.union(dfUpdatedOld).union(dfUnchanged)
    if (logger.isDebugEnabled) {
      logger.debug("Expected result:")
      dfExpected.collect.foreach(println)
    }

    assert(TestUtil.isDataFrameEqual(sortResults(dfExpected), sortResults(dfHistorized)))
  }

  test("When deleting 1 record (technical deletion) the dl_ts_delimited column should be updated.") {
    val baseColumnsOldHist = List((123, "Egon", 23, "healthy"), (124, "Erna", 27, "healthy"))
    val dfOldHist = toHistorizedDf(session, baseColumnsOldHist, HistorizationPhase.Existing)
    if (logger.isDebugEnabled) {
      logger.debug("History at beginning:")
      dfOldHist.collect.foreach(println)
    }

    val baseColumnsNewFeed = List((124, "Erna", 27, "healthy"))
    val dfNewFeed = toNewFeedDf(session, baseColumnsNewFeed)
    if (logger.isDebugEnabled) {
      logger.debug("New feed:")
      dfNewFeed.collect.foreach(println)
    }

    val dfHistorized = Historization.getHistorized(dfOldHist, dfNewFeed, primaryKeyColumns,
      referenceTimestampNew, None, None)
    if (logger.isDebugEnabled) {
      logger.debug("Historization result:")
      dfHistorized.collect.foreach(println)
    }

    val baseColumnsUpdatedOld = List((123, "Egon", 23, "healthy"))
    val dfUpdatedOld = toHistorizedDf(session, baseColumnsUpdatedOld, HistorizationPhase.UpdatedOld)

    val baseColumnsUnchanged = List((124, "Erna", 27, "healthy"))
    val dfUnchanged = toHistorizedDf(session, baseColumnsUnchanged, HistorizationPhase.Existing)

    val dfExpected = dfUpdatedOld.union(dfUnchanged)
    if (logger.isDebugEnabled) {
      logger.debug("Expected result:")
      dfExpected.collect.foreach(println)
    }

    assert(TestUtil.isDataFrameEqual(sortResults(dfExpected), sortResults(dfHistorized)))
  }

  test("When adding 1 record, the history should contain the new record.") {
    val baseColumnsOldHist = List((123, "Egon", 23, "healthy"), (124, "Erna", 27, "healthy"))
    val dfOldHist = toHistorizedDf(session, baseColumnsOldHist, HistorizationPhase.Existing)
    if (logger.isDebugEnabled) {
      logger.debug("History at beginning:")
      dfOldHist.collect.foreach(println)
    }

    val baseColumnsNewFeed = List((123, "Egon", 23, "healthy"), (124, "Erna", 27, "healthy"),
      (125, "Edeltraut", 54, "healthy"))
    val dfNewFeed = toNewFeedDf(session, baseColumnsNewFeed)
    if (logger.isDebugEnabled) {
      logger.debug("New feed:")
      dfNewFeed.collect.foreach(println)
    }

    val dfHistorized = Historization.getHistorized(dfOldHist, dfNewFeed, primaryKeyColumns,
      referenceTimestampNew, None, None)
    if (logger.isDebugEnabled) {
      logger.debug("Historization result:")
      dfHistorized.collect.foreach(println)
    }

    val baseColumnsUnchanged = List((123, "Egon", 23, "healthy"), (124, "Erna", 27, "healthy"))
    val dfUnchanged = toHistorizedDf(session, baseColumnsUnchanged, HistorizationPhase.Existing)

    val baseColumnsAdded = List((125, "Edeltraut", 54, "healthy"))
    val dfAdded = toHistorizedDf(session, baseColumnsAdded, HistorizationPhase.NewlyAdded)

    val dfExpected = dfAdded.union(dfUnchanged)
    if (logger.isDebugEnabled) {
      logger.debug("Expected result:")
      dfExpected.collect.foreach(println)
    }

    dfHistorized.show()

    assert(TestUtil.isDataFrameEqual(sortResults(dfExpected), sortResults(dfHistorized)))
  }

  test("When adding 1 record that was technically deleted in the past already, the history should contain the new version.") {
    val baseColumnsOldExistingHist = List((123, "Egon", 23, "healthy"))
    val dfOldExistingHist = toHistorizedDf(session, baseColumnsOldExistingHist, HistorizationPhase.Existing)

    val baseColumnsOldDeletedHist = List((124, "Erna", 27, "healthy"))
    val dfOldDeletedHist = toHistorizedDf(session, baseColumnsOldDeletedHist, HistorizationPhase.TechnicallyDeleted)

    val dfOldHist = dfOldExistingHist.union(dfOldDeletedHist)

    if (logger.isDebugEnabled) {
      logger.debug("History at beginning:")
      dfOldHist.collect.foreach(println)
    }

    val baseColumnsNewFeed = List((123, "Egon", 23, "healthy"), (124, "Erna", 28, "healthy"))
    val dfNewFeed = toNewFeedDf(session, baseColumnsNewFeed)
    if (logger.isDebugEnabled) {
      logger.debug("New feed:")
      dfNewFeed.collect.foreach(println)
    }

    val dfHistorized = Historization.getHistorized(dfOldHist, dfNewFeed, primaryKeyColumns,
      referenceTimestampNew, None, None)
    if (logger.isDebugEnabled) {
      logger.debug("Historization result:")
      dfHistorized.collect.foreach(println)
    }

    val baseColumnsUnchangedExistingHist = List((123, "Egon", 23, "healthy"))
    val dfUnchangedExistingHist = toHistorizedDf(session, baseColumnsUnchangedExistingHist, HistorizationPhase.Existing)

    val baseColumnsUnchangedDeletedHist = List((124, "Erna", 27, "healthy"))
    val dfUnchangedDeletedHist = toHistorizedDf(session, baseColumnsUnchangedDeletedHist, HistorizationPhase.TechnicallyDeleted)

    val dfUnchanged = dfUnchangedExistingHist.union(dfUnchangedDeletedHist)

    val baseColumnsAdded = List((124, "Erna", 28, "healthy"))
    val dfAdded = toHistorizedDf(session, baseColumnsAdded, HistorizationPhase.NewlyAdded)

    val dfExpected = dfAdded.union(dfUnchanged)
    if (logger.isDebugEnabled) {
      logger.debug("Expected result:")
      dfExpected.collect.foreach(println)
    }

    assert(TestUtil.isDataFrameEqual(sortResults(dfExpected), sortResults(dfHistorized)))
  }

  test("Exchanging non-null value and null value between columns should create a new history entry") {

    // Arrange
    val schemaValues = List(StructField("id", IntegerType, nullable = false),
      StructField("col_A", StringType, nullable = true),
      StructField("col_B", StringType, nullable = true))

    val schemaHistory = schemaValues ++ List(
      StructField("captured", StringType, nullable = false),
      StructField("delimited", StringType, nullable = true))

    val existingData = Seq(Row.fromSeq(Seq(1, null, "value", erfasstTimestampOldHist.toString, doomsday.toString)))
    val newData = Seq(Row.fromSeq(Seq(1, "value", null)))
    val expectedResult = Seq(Row.fromSeq(Seq(1, null, "value", erfasstTimestampOldHist.toString, referenceTimestampOld.toString)),
      Row.fromSeq(Seq(1, "value", null, referenceTimestampNew.toString, doomsday.toString)))

    val parseTimestampColumns : DataFrame => DataFrame = df =>
      df.withColumn(TechnicalTableColumn.captured.toString, to_timestamp(col("captured")))
        .withColumn(TechnicalTableColumn.delimited.toString, to_timestamp(col("delimited")))
        .drop("captured")
        .drop("delimited")

    val dfHistory = parseTimestampColumns(session.createDataFrame(session.sparkContext.parallelize(existingData), StructType(schemaHistory)))
    val dfExpected = parseTimestampColumns(session.createDataFrame(session.sparkContext.parallelize(expectedResult), StructType(schemaHistory)))
    val dfNew = session.createDataFrame(session.sparkContext.parallelize(newData), StructType(schemaValues))

    // Act
    val dfResult = Historization.getHistorized(dfHistory, dfNew, Seq("id"), referenceTimestampNew, None, None)

    // Assert
    println(s"Existing history: ${dfHistory.collect.map(_.toString).mkString(",")}")
    println(s"New Value: ${dfNew.collect.map(_.toString).mkString(",")}")
    println(s"Expected value: ${sortResults(dfExpected).collect.map(_.toString).mkString(",")}")
    println(s"Historization result: ${sortResults(dfResult).collect.map(_.toString).mkString(",")}")

    assert(TestUtil.isDataFrameEqual(sortResults(dfExpected), sortResults(dfResult)))
  }

}
