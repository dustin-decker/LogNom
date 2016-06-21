package org.squishyspace.lognom

/**
  * Created by Dustin Decker on 6/19/16.
  */

import org.apache.log4j.{Level, Logger}
import org.apache.spark.{SparkContext, SparkConf}
import org.apache.spark.streaming.{Seconds, StreamingContext}
import org.apache.spark.storage.StorageLevel
import _root_.com.redislabs.provider.redis._
import org.elasticsearch.spark._
import org.json4s.native.JsonMethods._

object LogNom {
  def main(args: Array[String]): Unit = {
    Logger.getLogger("org.apache").setLevel(Level.WARN)
    Logger.getLogger("akka").setLevel(Level.WARN)
    Logger.getLogger("com.redislabs").setLevel(Level.WARN)

    val sc = new SparkContext(new SparkConf()
      .setAppName("LogNom")

      // must be at least [2] cores
      .setMaster("local[2]")

      // initial redis host - can be any node in cluster mode
      .set("redis.host", "172.17.0.2")

      // initial redis port
      .set("redis.port", "6379")

      // optional redis AUTH password
      //.set("redis.auth", "")

      .set("es.index.auto.create", "true")
      .set("es.nodes", "elasticsearch:9200")
    )

    // creates a sliding window of n seconds for the stream
    val slidingWindowTime = 10
    val ssc = new StreamingContext(sc, Seconds(slidingWindowTime))

    // redis lists
    val stage1_name = "stage1"
    val stage2_name = "stage2"

    // this will create a wicked fast stream BLPOPing off the redis lists provided
    val logStream = ssc.createRedisStreamWithoutListname(Array(stage1_name),
      storageLevel = StorageLevel.MEMORY_AND_DISK) // only uses disk if necessary

    //////// Processing begins here /////////

    // access elasticsearch index as an RDD
    val es_data = sc.esRDD("logstash-%s".
      format(new java.text.SimpleDateFormat("YYYY-MM-dd").toString))

    es_data.take(5).foreach(println)

    // stage 1 example filtering and conversion
    val stage1 = logStream
      .filter(!_.contains("dumb event"))
      .filter(!_.contains("dumb scanner"))
      .filter(!_.contains("dumb ip"))
      .filter(!_.contains("another dumb event"))
      // converts JSON to AST object
      .map(msg => parse(msg))

    // stage 2 converting AST back to JSON string
    val stage2 = stage1.map(json => compact(render(json)))

    // send each window to next redis stage
    stage2.foreachRDD((rdd, time) => {
      val rdd_count = rdd.count()
      if (rdd_count > 0) {
        rdd.take(5).foreach(println)
        println("%d events per second".format(rdd_count / slidingWindowTime))
        sc.toRedisFixedLIST(rdd, stage2_name, 50000)
      }
    })

    //////// Processing ends here ////////

    ssc.start()
    ssc.awaitTermination()
  }
}