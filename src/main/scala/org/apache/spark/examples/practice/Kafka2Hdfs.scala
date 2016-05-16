/**
  * Copyright (C) 2015 Baifendian Corporation
  *
  * Licensed under the Apache License, Version 2.0 (the "License");
  * you may not use this file except in compliance with the License.
  * You may obtain a copy of the License at
  *
  * http://www.apache.org/licenses/LICENSE-2.0
  *
  * Unless required by applicable law or agreed to in writing, software
  * distributed under the License is distributed on an "AS IS" BASIS,
  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  * See the License for the specific language governing permissions and
  * limitations under the License.
  */
package org.apache.spark.examples.practice

import java.io.{File, FileInputStream}
import java.text.SimpleDateFormat
import java.util.{Date, Properties}

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FSDataOutputStream, FileSystem, Path}
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.storage.StorageLevel
import org.apache.spark.streaming.kafka.KafkaUtils
import org.apache.spark.streaming.{Seconds, StreamingContext}
import org.apache.spark.{SparkConf, SparkContext}

// 参数的 key 值
object Params {
  // kafka params
  val ZK = "zookeeper"
  val GROUP = "groupid"
  val TOPICS = "topics"
  val TOPIC_THREAD = "topic.thread"

  // hdfs params
  val HDFS_PATH = "hdfs.path"
}

// 配置文件的广播变量, 这里使用了单件模式, 为了避免 driver 挂掉
object BroadConfig {
  @volatile private var instance: Broadcast[Properties] = null

  def getInstance(sc: SparkContext, filename: String = "props"): Broadcast[Properties] = {
    if (instance == null) {
      synchronized {
        if (instance == null) {
          val props = new Properties()
          props.load(new FileInputStream(filename))

          instance = sc.broadcast(props)
        }
      }
    }

    instance
  }
}

// 获取 HDFS 连接
object HdfsConnection {
  // hdfs 的配置
  val conf: Configuration = new Configuration()
  // 文件系统句柄
  val fileSystem: FileSystem = FileSystem.get(conf)
  // format is "yyyy-mm-dd"
  var currentDay: String = null
  // the directory of hdfs path
  var currentPath: String = null

  // hdfs 的写入句柄, 注意多线程问题, 第一个参数是写入句柄, 第二个参数是当前的小时情况
  val writeHandler: ThreadLocal[(FSDataOutputStream, String)] = new ThreadLocal[(FSDataOutputStream, String)] {
    override def initialValue(): (FSDataOutputStream, String) =
      (null, null)
  }

  // 获取 hdfs 的连接
  def getHdfsConnection(props: Properties): FSDataOutputStream = {
    this.synchronized {
      // 如果第一次, 需要初始化
      if (currentPath == null) {
        currentPath = props.getProperty(Params.HDFS_PATH)
      }

      // 获取当前时间
      val now = new Date()

      // 如果当前时间不一致, 则会重新构建 Path
      val format1 = new SimpleDateFormat("yyyy-MM-dd")
      val format2 = new SimpleDateFormat("yyyy-MM-dd-HH")
      val nowDay = format1.format(now)
      val nowHour = format2.format(now)

      // 如果 "天" 已经过时, 那么会创建一个目录
      if (currentDay == null || currentDay != nowDay) {
        currentDay = nowDay

        // 创建新的目录
        val path = new Path(s"${currentPath}${File.separator}${currentDay}")
        println(s"create dir: $path")
        if (!fileSystem.exists(path)) {
          fileSystem.mkdirs(path)
        }
      }

      // 获取句柄, 以及当前存储的时间
      val handler = writeHandler.get()._1
      val hour = writeHandler.get()._2

      // 如果 "小时" 已经过时, 也创建一个文件
      if (hour == null || hour != nowHour) {
        if (handler != null) {
          handler.close()
        }

        val newPath = new Path(s"${currentPath}${File.separator}${currentDay}${File.separator}${java.util.UUID.randomUUID.toString}-${nowHour}")
        println(s"create file: $newPath")
        val fout: FSDataOutputStream = fileSystem.create(newPath)

        writeHandler.set((fout, nowHour))
      }

      // 返回最新的句柄
      val newHandler = writeHandler.get()._1

      newHandler
    }
  }
}

object Kafka2Hdfs {
  def functionToCreateContext(): StreamingContext = {
    // 加载配置文件, 配置文件示例为: conf.properties
    val sparkConf = new SparkConf().setAppName("Kafka2Hdfs").
      set("spark.streaming.receiver.writeAheadLog.enable", "true").
      set("spark.streaming.receiver.maxRate", "20000").
      set("spark.streaming.stopGracefullyOnShutdown", "true").
      set("spark.streaming.kafka.maxRetries", "3")

    // 创建 spark context 和 streaming context, 注意这里也设置了 checkpoint, 目的用于 stream 的状态恢复
    val ctx = new SparkContext(sparkConf)
    val ssc = new StreamingContext(ctx, Seconds(10))

    ssc.checkpoint("checkpoint/Kafka2Hdfs")

    ssc
  }

  def main(args: Array[String]) {
    // 注意我们这里有个 checkpoint 的恢复机制, 应对 driver 的重启(从 metadata 恢复), 另外也可以应对有状态的操作(不过本示例没有)
    val ssc = StreamingContext.getOrCreate("checkpoint/Kafka2Hdfs", functionToCreateContext _)
    val ctx = ssc.sparkContext

    // 创建 kafka stream
    val topics = BroadConfig.getInstance(ctx).value.getProperty(Params.TOPICS)
    val zk = BroadConfig.getInstance(ctx).value.getProperty(Params.ZK)
    val group = BroadConfig.getInstance(ctx).value.getProperty(Params.GROUP)
    val topicThread = BroadConfig.getInstance(ctx).value.getProperty(Params.TOPIC_THREAD) toInt

    println(s"topics: $topics, zookeeper: $zk, group id: $group, topic thread: $topicThread")

    // 注意这里也没有设置 Parallelism, 这是因为 Direct Stream 方式有简单的并行性, 即 "many RDD partitions as there are Kafka partitions".
    // 不过千万要注意, Direct Stream 还处于试验阶段, 慎用啊
    //    val topicsSet = topics.split(",").toSet
    //    val kafkaParams = Map[String, String]("metadata.broker.list" -> brokers, "auto.offset.reset" -> "smallest")
    //    val messages = KafkaUtils.createDirectStream[String, String, StringDecoder, StringDecoder](
    //      ssc, kafkaParams, topicsSet)

    // 注意这里是 receiver 方式
    val topicMap = topics.split(",").map((_, topicThread)).toMap
    val messages = KafkaUtils.createStream(ssc, zk, group, topicMap, StorageLevel.MEMORY_AND_DISK_SER)

    val hdfsPath = BroadConfig.getInstance(ctx).value.getProperty(Params.HDFS_PATH)

    println(s"hdfs path: $hdfsPath")

    // 对我们获取的数据, 进行处理, 保存到 hdfs 中
    messages.map(x => x._2).foreachRDD { rdd =>
      // only can be execution on driver
      val config = BroadConfig.getInstance(rdd.sparkContext).value
      // executed at the worker
      rdd.foreachPartition {
        partitionOfRecords =>
          val connection = HdfsConnection.getHdfsConnection(config)
          partitionOfRecords.foreach(
            record => {
              connection.writeUTF(record)
              connection.writeBytes("\n")
            }
          )
          // 每次完了之后进行 flush
          try {
            connection.hflush()
          } catch {
            case e: Exception => println(s"hflush exception: ${e.getMessage}")
          }
      }
    }

    // Start the computation
    ssc.start()
    ssc.awaitTermination()
  }
}