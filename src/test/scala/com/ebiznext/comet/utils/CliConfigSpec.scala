package com.ebiznext.comet.utils

import com.ebiznext.comet.TestHelper
import com.ebiznext.comet.extractor.ExtractScriptGenConfig
import com.ebiznext.comet.job.convert.Parquet2CSVConfig
import com.ebiznext.comet.job.index.bqload.BigQueryLoadConfig
import com.ebiznext.comet.job.index.connectionload.ConnectionLoadConfig
import com.ebiznext.comet.job.index.esload.ESLoadConfig
import com.ebiznext.comet.job.index.kafkaload.KafkaJobConfig
import com.ebiznext.comet.job.infer.InferSchemaConfig
import com.ebiznext.comet.job.ingest.LoadConfig
import com.ebiznext.comet.job.metrics.MetricsConfig
import com.ebiznext.comet.schema.generator.{DDL2YmlConfig, Xls2YmlConfig, Yml2XlsConfig}
import com.ebiznext.comet.workflow.{ImportConfig, TransformConfig, WatchConfig}

class CliConfigSpec extends TestHelper {
  new WithSettings() {
    "Generate Documentation" should "succeed" in {
      val rstMap = Map(
        "import"       -> ImportConfig.sphinx(),
        "bqload"       -> BigQueryLoadConfig.sphinx(),
        "esload"       -> ESLoadConfig.sphinx(),
        "infer-schema" -> InferSchemaConfig.sphinx(),
        "load"         -> LoadConfig.sphinx(),
        "metrics"      -> MetricsConfig.sphinx(),
        "parquet2csv"  -> Parquet2CSVConfig.sphinx(),
        "cnxload"      -> ConnectionLoadConfig.sphinx(),
        "xls2yml"      -> Xls2YmlConfig.sphinx(),
        "ddl2yml"      -> DDL2YmlConfig.sphinx(),
        "extract"      -> ExtractScriptGenConfig.sphinx(),
        "transform"    -> TransformConfig.sphinx(),
        "watch"        -> WatchConfig.sphinx(),
        "kafkaload"    -> KafkaJobConfig.sphinx(),
        "yml2xls"      -> Yml2XlsConfig.sphinx()
      )
      val rstPath = getClass.getResource("/").getPath + "../../../docs/user/cli"
      rstMap.foreach { case (k, v) =>
        reflect.io.File(s"$rstPath/$k.rst").writeAll(v)
      }
    }
  }
}
