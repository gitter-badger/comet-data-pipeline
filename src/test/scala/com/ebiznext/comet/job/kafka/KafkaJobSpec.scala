package com.ebiznext.comet.job.kafka

import com.dimafeng.testcontainers.{ForAllTestContainer, KafkaContainer}
import com.ebiznext.comet.TestHelper
import com.ebiznext.comet.job.index.kafkaload.{KafkaJob, KafkaJobConfig}
import com.ebiznext.comet.utils.kafka.KafkaTopicUtils
import com.typesafe.config.ConfigFactory
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.spark.sql.SaveMode

import java.io.{File, PrintWriter}
import java.util.{Properties, UUID}
import scala.collection.mutable.ListBuffer

class KafkaJobSpec extends TestHelper with ForAllTestContainer {
  override val container = KafkaContainer()
  // We need to start iit manually because we need to access the HTTP mapped poorrt
  // in the configuration below before any test get executed.
  container.start()

  val kafkaConfig = ConfigFactory
    .parseString(s"""
        |kafka {
        |  server-options = {
        |      "bootstrap.servers": "${container.bootstrapServers}"
        |  }
        |  topics {
        |    "test_offload": {
        |      max-read = 0
        |      fields = ["key as STRING", "value as STRING"]
        |      write-format = "parquet"
        |      access-options = {
        |        "kafka.bootstrap.servers": "${container.bootstrapServers}"
        |        "bootstrap.servers": "${container.bootstrapServers}"
        |        "key.deserializer": "org.apache.kafka.common.serialization.StringDeserializer"
        |        "value.deserializer": "org.apache.kafka.common.serialization.StringDeserializer"
        |        "key.serializer": "org.apache.kafka.common.serialization.StringSerializer"
        |        "value.serializer": "org.apache.kafka.common.serialization.StringSerializer"
        |        "subscribe": "test_offload"
        |      }
        |    }
        |  }
        |}
        |""".stripMargin)
    .withFallback(super.testConfiguration)

  new WithSettings(kafkaConfig) {
    "runs with embedded kafka" should "work" in {
      val properties = new Properties()
      properties.put("bootstrap.servers", container.bootstrapServers)
      properties.put("group.id", "consumer-test")
      properties.put("key.deserializer", classOf[StringDeserializer])
      properties.put("value.deserializer", classOf[StringDeserializer])

      val kafkaConsumer = new KafkaConsumer[String, String](properties)
      val topics = kafkaConsumer.listTopics()
    }
    "Create topic comet_offets topic" should "succeed" in {
      val client = new KafkaTopicUtils(Map("bootstrap.servers" -> container.bootstrapServers))
      val topicProperties = Map("cleanup.policy" -> "compact")
      client.createTopicIfNotPresent(new NewTopic("comet_offsets", 1, 1.toShort), topicProperties)
    }

    "Get comet_offets partitions " should "return 1" in {
      val client = new KafkaTopicUtils(Map("bootstrap.servers" -> container.bootstrapServers))
      val topicProperties = Map("cleanup.policy" -> "compact")
      client.createTopicIfNotPresent(new NewTopic("comet_offsets", 1, 1.toShort), topicProperties)
      val partitions = client.topicPartitions(
        "comet_offsets",
        settings.comet.kafka.topics("comet_offsets").accessOptions
      )
      partitions should have size 1
    }

    "Access comet_offets end offsets" should "work" in {
      val client = new KafkaTopicUtils(Map("bootstrap.servers" -> container.bootstrapServers))
      val topicProperties = Map("cleanup.policy" -> "compact")
      client.createTopicIfNotPresent(new NewTopic("comet_offsets", 1, 1.toShort), topicProperties)
      val properties = Map(
        "bootstrap.servers"  -> container.bootstrapServers,
        "key.deserializer"   -> "org.apache.kafka.common.serialization.StringDeserializer",
        "value.deserializer" -> "org.apache.kafka.common.serialization.StringDeserializer"
      )
      val offsets = client.topicEndOffsets("comet_offsets", properties)
      offsets should contain theSameElementsAs List((0, 0))
    }

    "Offload messages from Kafka" should "work" in {
      val client = new KafkaTopicUtils(Map("bootstrap.servers" -> container.bootstrapServers))
      client.createTopicIfNotPresent(new NewTopic("test_offload", 1, 1.toShort), Map.empty)
      val elems = ListBuffer[String]()
      for (i <- 1 to 5000) {
        elems += s"""{"key": "key-$i","value": "${UUID.randomUUID().toString}-$i"}"""
      }
      val writer = new PrintWriter(new File("/tmp/json.json"))
      writer.write(elems.mkString("\n"))
      writer.close()
      val kafkaJob =
        new KafkaJob(
          KafkaJobConfig(
            "test_offload",
            "json",
            SaveMode.Overwrite,
            Some(Left("/tmp/json.json")),
            offload = false
          )
        )
      kafkaJob.load()
      val kafkaJob2 =
        new KafkaJob(
          KafkaJobConfig("test_offload", "json", SaveMode.Overwrite, None)
        )
      kafkaJob2.offload().get.dataframe.get.count() shouldBe 5000
    }
  }
}
