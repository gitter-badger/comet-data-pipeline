package com.ebiznext.comet.utils.kafka

import com.ebiznext.comet.config.Settings.{KafkaConfig, KafkaTopicConfig}
import com.typesafe.scalalogging.StrictLogging
import org.apache.kafka.clients.admin.{AdminClient, NewTopic}
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord}
import org.apache.kafka.common.{TopicPartition, TopicPartitionInfo}
import org.apache.spark.sql.{DataFrame, SparkSession}

import java.time.Duration
import java.util.Properties
import scala.collection.JavaConverters._
import scala.collection.mutable

class KafkaClient(kafkaConfig: KafkaConfig) extends StrictLogging with AutoCloseable {

  val serverOptions: Map[String, String] = kafkaConfig.serverOptions
  val cometOffsetsConfig: KafkaTopicConfig = kafkaConfig.topics("comet_offsets")

  val props = new Properties()
  serverOptions.foreach { case (k, v) =>
    props.put(k, v)
  }

  val client: AdminClient = AdminClient.create(props)

  createTopicIfNotPresent(
    new NewTopic(
      "comet_offsets",
      cometOffsetsConfig.partitions,
      cometOffsetsConfig.replicationFactor
    ),
    Map("cleanup.policy" -> "compact")
  )

  def close(): Unit = client.close()

  def deleteTopic(topicName: String): Unit = {
    val found = client.listTopics().names().get().contains(topicName)
    client.listTopics().names().get().asScala.toSet.foreach(println)
    if (found) {
      client.deleteTopics(List(topicName).asJavaCollection)
    }
  }

  def createTopicIfNotPresent(topic: NewTopic, conf: Map[String, String]): Any = {
    val found = client.listTopics().names().get().contains(topic.name)
    if (!found) {
      topic.configs(conf.asJava)
      client.createTopics(java.util.Collections.singleton(topic)).all().get()
    }
  }

  def topicPartitions(topicName: String): List[TopicPartitionInfo] = {
    client
      .describeTopics(java.util.Collections.singleton(topicName))
      .all()
      .get()
      .get(topicName)
      .partitions()
      .asScala
      .toList
  }

  def topicEndOffsets(topicName: String, accessOptions: Map[String, String]): List[(Int, Long)] = {
    val props: Properties = buildProps(accessOptions)
    val consumer = new KafkaConsumer[String, String](props)
    val partitions = consumer
      .partitionsFor(topicName)
      .asScala
      .map(info => new TopicPartition(topicName, info.partition()))
      .toList
    consumer.assign(partitions.asJava)
    consumer.seekToEnd(partitions.asJava)
    partitions.map(p => (p.partition(), consumer.position(p)))
  }

  private def buildProps(accessOptions: Map[String, String]) = {
    val props = new Properties()
    accessOptions.foreach { option =>
      props.put(option._1, option._2)
    }
    props
  }

  def topicSaveOffsets(
    topicConfigName: String,
    accessOptions: Map[String, String],
    offsets: List[(Int, Long)]
  ): Unit = {
    val props: Properties = buildProps(accessOptions)
    val producer = new KafkaProducer[String, String](props)
    offsets.foreach { case (partition, offset) =>
      producer.send(
        new ProducerRecord[String, String](
          "comet_offsets",
          s"$topicConfigName/$partition",
          s"$offset"
        )
      )
    }
    producer.close()
  }

  def topicCurrentOffsets(topicConfigName: String): Option[List[(Int, Long)]] = {
    val props = new Properties()
    cometOffsetsConfig.accessOptions.foreach { option =>
      props.put(option._1, option._2)
    }
    val consumer = new KafkaConsumer[String, String](props)
    val partitions =
      topicPartitions("comet_offsets").map(info =>
        new TopicPartition("comet_offsets", info.partition())
      )
    consumer.assign(partitions.asJava)
    consumer.seekToBeginning(partitions.asJava)
    val offsets = mutable.Map.empty[String, String]
    var records = consumer.poll(Duration.ofMillis(100))
    while (records != null && !records.isEmpty) {
      records.records("comet_offsets").asScala.foreach(r => offsets += r.key() -> r.value())
      records = consumer.poll(Duration.ofMillis(100))
    }

    // (topic/partition, offset)
    val res = offsets.keys.map { k =>
      val tab = k.split('/')
      (tab(0), tab(1), offsets(k))
    } groupBy { case (topic, _, _) => topic } mapValues (_.map { case (topic, partition, offset) =>
      (partition.toInt, offset.toLong)
    }.toList) get topicConfigName
    res
  }

  def offsetsAsJson(topicName: String, offsets: List[(Int, Long)]): Option[String] = {
    if (offsets.isEmpty)
      None
    else {
      val offsetsAsString = offsets.map { case (partition, partitionOffset) =>
        s""""$partition": $partitionOffset"""
      } mkString ","
      Some(s"""{"$topicName":{$offsetsAsString}}""")
    }

  }

  def consumeTopicBatch(
    topicConfigName: String,
    session: SparkSession,
    config: KafkaTopicConfig
  ): (DataFrame, List[(Int, Long)]) = {
    val EARLIEST_OFFSET = -2L
    val startOffsets =
      topicCurrentOffsets(topicConfigName)
        .getOrElse {
          topicPartitions(config.topicName)
            .map(p => (p.partition(), EARLIEST_OFFSET))
        }
    val endOffsets =
      topicEndOffsets(config.topicName, config.accessOptions)

    // We do not use the topic Name but the config name to allow us to
    // consume differently the same topic
    val withOffsetsTopicOptions = config.accessOptions ++ Seq(
      "startingOffsets" -> offsetsAsJson(config.topicName, startOffsets).getOrElse("earliest"),
      "endingOffsets"   -> offsetsAsJson(config.topicName, endOffsets).getOrElse("latest")
    )
    // TODO Loop based on maxRead need to be implemented here

    val reader = session.read.format("kafka")
    val df =
      withOffsetsTopicOptions
        .foldLeft(reader) { case (reader, (k, v)) => reader.option(k, v) }
        .load()
        .selectExpr(config.fields.map(x => s"CAST($x)"): _*)
    df.printSchema()
    (df, endOffsets)
  }

  def consumeTopicStreaming(
    session: SparkSession,
    config: KafkaTopicConfig
  ): DataFrame = {
    val reader = session.readStream.format("kafka")
    val df =
      config.accessOptions
        .foldLeft(reader) { case (reader, (k, v)) => reader.option(k, v) }
        .load()
        .selectExpr(config.fields.map(x => s"CAST($x)"): _*)
    df
  }

  def sinkToTopic(
    config: KafkaTopicConfig,
    df: DataFrame
  ): Unit = {
    val writer = df.selectExpr(config.fields.map(x => s"CAST($x)"): _*).write.format("kafka")

    config.accessOptions
      .foldLeft(writer) { case (writer, (k, v)) => writer.option(k, v) }
      .option("topic", config.topicName)
      .save()
  }
}
