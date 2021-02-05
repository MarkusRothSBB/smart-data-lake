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

package io.smartdatalake.metrics

import java.time.format.DateTimeFormatter
import java.time.{Duration, Instant, ZoneId}

import io.smartdatalake.config.SdlConfigObject.{ActionId, DataObjectId}
import io.smartdatalake.util.misc.SmartDataLakeLogger
import io.smartdatalake.workflow.ActionMetrics
import org.apache.spark.scheduler.{AccumulableInfo, SparkListener, SparkListenerJobStart, SparkListenerStageCompleted}

import scala.collection.mutable

/**
 * A parameter object holding the spark metrics for a spark stage.
 *
 * @see [[org.apache.spark.scheduler.StageInfo]] for more details on these metrics.
 */
private[smartdatalake] case class SparkStageMetrics(jobInfo: JobInfo, stageId: Int, stageName: String, numTasks: Int,
                                                    submissionTimestamp: Long, completionTimeStamp: Long,
                                                    executorRuntimeInMillis: Long, executorCpuTimeInNanos: Long,
                                                    executorDeserializeTimeInMillis: Long, executorDeserializeCpuTimeInNanos: Long,
                                                    resultSerializationTimeInMillis: Long, resultSizeInBytes: Long,
                                                    jvmGarbageCollectionTimeInMillis: Long,
                                                    memoryBytesSpilled: Long, diskBytesSpilled: Long,
                                                    peakExecutionMemoryInBytes: Long,
                                                    bytesRead: Long, recordsRead: Long,
                                                    bytesWritten: Long, recordsWritten: Long,
                                                    shuffleFetchWaitTimeInMillis: Long,
                                                    shuffleRemoteBlocksFetched: Long, shuffleLocalBlocksFetched: Long, shuffleTotalBlocksFetched: Long,
                                                    shuffleRemoteBytesRead: Long, shuffleLocalBytesRead: Long, shuffleTotalBytesRead: Long,
                                                    shuffleRecordsRead: Long,
                                                    shuffleWriteTimeInNanos: Long, shuffleBytesWritten: Long,
                                                    shuffleRecordsWritten: Long,
                                                    accumulables: Seq[AccumulableInfo]
                                                   ) extends ActionMetrics {

  lazy val stageSubmissionTime: Instant = Instant.ofEpochMilli(submissionTimestamp)
  lazy val stageCompletionTime: Instant = Instant.ofEpochMilli(completionTimeStamp)
  lazy val stageRuntime: Duration = Duration.ofMillis(completionTimeStamp - submissionTimestamp)
  lazy val aggregatedExecutorRuntime: Duration = Duration.ofMillis(executorRuntimeInMillis)
  lazy val aggregatedExecutorCpuTime: Duration = Duration.ofMillis(executorCpuTimeInNanos / 1000000)
  lazy val aggregatedExecutorGarbageCollectionTime: Duration = Duration.ofMillis(jvmGarbageCollectionTimeInMillis)
  lazy val aggregatedExecutorDeserializeTime: Duration = Duration.ofMillis(executorDeserializeTimeInMillis)
  lazy val aggregatedExecutorDeserializeCpuTime: Duration = Duration.ofMillis(executorDeserializeCpuTimeInNanos / 1000000)
  lazy val resultSerializationTime: Duration = Duration.ofMillis(resultSerializationTimeInMillis)
  lazy val shuffleFetchWaitTime: Duration = Duration.ofMillis(shuffleFetchWaitTimeInMillis)
  lazy val shuffleWriteTime: Duration = Duration.ofMillis(shuffleWriteTimeInNanos / 1000000)

  // formatters
  private lazy val dateTimeFormat = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.systemDefault)
  private def durationString(valueSeparator: String)(name: String, duration: Duration): String = s"${keyValueString(valueSeparator)(name, duration.toString)}"
  private def keyValueString(valueSeparator: String)(key: String, value: String): String = s"$key$valueSeparator$value"

  /**
   * @return A printable string reporting all metrics.
   */
  def getAsText: String = {
    val valueSeparator: String = "="
    val durationStringWithSeparator = durationString(valueSeparator)(_, _)
    val keyValueStringWithSeparator = keyValueString(valueSeparator)(_, _)

    s"""job_id=${jobInfo.id} stage_id=$stageId:
       |    ${keyValueStringWithSeparator("job_group", jobInfo.group)}
       |    ${keyValueStringWithSeparator("job_description", jobInfo.description)}
       |    ${keyValueStringWithSeparator("stage_name", stageName)}
       |    ${keyValueStringWithSeparator("num_tasks", numTasks.toString)}
       |    ${keyValueStringWithSeparator("submitted", dateTimeFormat.format(stageSubmissionTime))}
       |    ${keyValueStringWithSeparator("completed", dateTimeFormat.format(stageCompletionTime))}
       |    ${durationStringWithSeparator("runtime", stageRuntime)}
       |    ${durationStringWithSeparator("executor_aggregated_runtime", aggregatedExecutorRuntime)}
       |    ${durationStringWithSeparator("executor_aggregated_cputime", aggregatedExecutorCpuTime)}
       |    ${durationStringWithSeparator("executor_aggregated_deserializetime", aggregatedExecutorDeserializeTime)}
       |    ${durationStringWithSeparator("executor_aggregated_deserializecputime", aggregatedExecutorDeserializeCpuTime)}
       |    ${durationStringWithSeparator("result_serializationtime", resultSerializationTime)}
       |    ${keyValueStringWithSeparator("result_size", resultSizeInBytes.toString)} B
       |    ${durationStringWithSeparator("jvm_aggregated_gc_time", aggregatedExecutorGarbageCollectionTime)}
       |    ${keyValueStringWithSeparator("memory_spilled", memoryBytesSpilled.toString)} B
       |    ${keyValueStringWithSeparator("disk_spilled", diskBytesSpilled.toString)} B
       |    ${keyValueStringWithSeparator("peak_execution_memory", peakExecutionMemoryInBytes.toString)} B
       |    ${keyValueStringWithSeparator("bytes_read", bytesRead.toString)} B
       |    ${keyValueStringWithSeparator("bytes_written", bytesWritten.toString)} B
       |    ${keyValueStringWithSeparator("records_read", recordsRead.toString)}
       |    ${keyValueStringWithSeparator("records_written", recordsWritten.toString)}
       |    ${durationStringWithSeparator("shuffle_fetch_waittime", shuffleFetchWaitTime)}
       |    ${keyValueStringWithSeparator("shuffle_remote_blocks_fetched", shuffleRemoteBlocksFetched.toString)}
       |    ${keyValueStringWithSeparator("shuffle_local_blocks_fetched", shuffleLocalBlocksFetched.toString)}
       |    ${keyValueStringWithSeparator("shuffle_total_blocks_fetched", shuffleTotalBlocksFetched.toString)}
       |    ${keyValueStringWithSeparator("shuffle_remote_bytes_read", shuffleRemoteBytesRead.toString)} B
       |    ${keyValueStringWithSeparator("shuffle_local_bytes_read", shuffleLocalBytesRead.toString)}  B
       |    ${keyValueStringWithSeparator("shuffle_total_bytes_read", shuffleTotalBytesRead.toString )} B
       |    ${keyValueStringWithSeparator("shuffle_records_read", shuffleRecordsRead.toString)}
       |    ${durationStringWithSeparator("shuffle_write_time", shuffleWriteTime)}
       |    ${keyValueStringWithSeparator("shuffle_bytes_written", shuffleBytesWritten.toString)} B
       |    ${keyValueStringWithSeparator("shuffle_records_written", shuffleRecordsWritten.toString)}""".stripMargin
  }

  def getId: String = jobInfo.toString
  def getOrder: Long = stageId
  def getMainInfos: Map[String, Any] = {
    Map("stageDuration" -> stageRuntime, "records_written" -> recordsWritten, "bytes_written" -> bytesWritten, "num_tasks" -> numTasks.toLong, "stage" -> stageName.split(' ').head )
  }
}
private[smartdatalake] case class JobInfo(id: Int, group: String, description: String)

/**
 * Collects spark metrics for spark stages.
 */
private[smartdatalake] class SparkStageMetricsListener(notifyStageMetricsFunc: (ActionId, Option[DataObjectId], ActionMetrics) => Unit) extends SparkListener with SmartDataLakeLogger {

  /**
   * Stores jobID and jobDescription indexed by stage ids.
   */
  val jobInfoLookupTable: mutable.Map[Int, JobInfo] = mutable.Map.empty

  /**
   * On job start, register the job ids and stage ids.
   */
  override def onJobStart(jobStart: SparkListenerJobStart): Unit = {
    jobStart.stageIds.foreach { stageId =>
      jobInfoLookupTable(stageId) = JobInfo(jobStart.jobId, jobStart.properties.getProperty("spark.jobGroup.id"), jobStart.properties.getProperty("spark.job.description"))
    }
  }

  /**
   * On stage complete notify spark metrics
   */
  override def onStageCompleted(stageCompleted: SparkListenerStageCompleted): Unit = {
    // extract useful informations/metrics
    val stageId = stageCompleted.stageInfo.stageId
    val taskMetrics = stageCompleted.stageInfo.taskMetrics
    val shuffleReadMetrics = taskMetrics.shuffleReadMetrics
    val jobInfo = jobInfoLookupTable(stageId)
    val sparkStageMetrics = SparkStageMetrics(jobInfo, stageId, stageCompleted.stageInfo.name, stageCompleted.stageInfo.numTasks,
      stageCompleted.stageInfo.submissionTime.getOrElse(-1L), stageCompleted.stageInfo.completionTime.getOrElse(-1L),
      taskMetrics.executorRunTime, taskMetrics.executorCpuTime, taskMetrics.executorDeserializeTime, taskMetrics.executorDeserializeCpuTime,
      taskMetrics.resultSerializationTime, taskMetrics.resultSize,
      taskMetrics.jvmGCTime,
      taskMetrics.memoryBytesSpilled, taskMetrics.diskBytesSpilled,
      taskMetrics.peakExecutionMemory,
      taskMetrics.inputMetrics.bytesRead, taskMetrics.inputMetrics.recordsRead,
      taskMetrics.outputMetrics.bytesWritten, taskMetrics.outputMetrics.recordsWritten,
      shuffleReadMetrics.fetchWaitTime,
      shuffleReadMetrics.remoteBlocksFetched, shuffleReadMetrics.localBlocksFetched, shuffleReadMetrics.totalBlocksFetched,
      shuffleReadMetrics.remoteBytesRead, shuffleReadMetrics.localBytesRead, shuffleReadMetrics.totalBytesRead,
      shuffleReadMetrics.recordsRead,
      taskMetrics.shuffleWriteMetrics.writeTime, taskMetrics.shuffleWriteMetrics.bytesWritten, taskMetrics.shuffleWriteMetrics.recordsWritten,
      stageCompleted.stageInfo.accumulables.values.toSeq
    )
    // extract concerned Action and DataObject
    val actionIdRegex = "Action~([a-zA-Z0-9_-]+)".r.unanchored
    val actionId = sparkStageMetrics.jobInfo.group match {
      case actionIdRegex(id) => Some(ActionId(id))
      case _ => sparkStageMetrics.jobInfo.description match { // for spark streaming jobs we cant set the jobGroup, but only the description
        case actionIdRegex(id) => Some(ActionId(id))
        case _ =>
          logger.warn(s"Couldn't extract ActionId from sparkJobGroupId (${sparkStageMetrics.jobInfo.group})")
          None
      }
    }
    if (actionId.isDefined) {
      val dataObjectIdRegex = "DataObject~([a-zA-Z0-9_-]+)".r.unanchored
      val dataObjectId = sparkStageMetrics.jobInfo.description match {
        case dataObjectIdRegex(id) => Some(DataObjectId(id))
        case _ => sparkStageMetrics.jobInfo.description match { // for spark streaming jobs we cant set the jobGroup, but only the description
          case dataObjectIdRegex(id) => Some(DataObjectId(id))
          case _ => None // there are some stages which are created by Spark DataFrame operations which dont belong to manipulation Actions target DataObject's, e.g. pivot operator
        }
      }
      notifyStageMetricsFunc(actionId.get, dataObjectId, sparkStageMetrics)
    }
  }
}