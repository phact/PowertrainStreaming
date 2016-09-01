/**
  * Created by sebastianestevez on 6/1/16.
  */

import java.sql.Timestamp

import com.datastax.driver.dse.DseSession
import com.datastax.driver.dse.graph.SimpleGraphStatement
import kafka.serializer.StringDecoder
import org.apache.spark.sql.SQLContext
import org.apache.spark.streaming.dstream.DStream
import org.apache.spark.streaming.kafka.KafkaUtils
import org.apache.spark.streaming.{Seconds, StreamingContext}
import org.apache.spark.{SparkConf, SparkContext}

import org.apache.log4j.{Level, Logger}


object StreamVehicleData {

  def check(x: Int) = if (x == 1) "Peyton" else "Ryan"

  def main(args: Array[String]) {

    val logger: Logger = Logger.getLogger("StreamVehicleData")

    val sparkConf = new SparkConf()
    val debug = sparkConf.get("spark.debugging", "false").toBoolean
    val graph_name = sparkConf.get("spark.graph_name")
    val dse_host = sparkConf.get("spark.dse_host")

    if (debug) {
      logger.info("WARNING!!! Running in local debug mode!  Initializing graph schema")

     /*
        with dse spark submit these are all set for you.  Do they need to be set for local development?
        sparkConf
        .setMaster("local[1]")
        .setAppName(graph_name)
        .set("spark.cassandra.connection.host", "dse_host")
       */

      // Creates the graph if it does not exist
      initialize_graph(dse_host, graph_name)
      // Drops the schema and recreates it
      val session = get_dse_session(dse_host, graph_name)
      initialize_schema(session, "schema")
      session.close()
    }


    val contextDebugStr: String = sparkConf.toDebugString
    logger.info("contextDebugStr = " + contextDebugStr)

    def createStreamingContext(): StreamingContext = {
      @transient val newSsc = new StreamingContext(sparkConf, Seconds(1))
      logger.info(s"Creating new StreamingContext $newSsc")
      newSsc
    }

    val sparkStreamingContext = StreamingContext.getActiveOrCreate(createStreamingContext)

    val sc = SparkContext.getOrCreate(sparkConf)
    val sqlContext = SQLContext.getOrCreate(sc)

    //not checkpointing
    //ssc.checkpoint("/ratingsCP")

    val topicsArg = "vehicle_events"
    val brokers = sparkConf.get("spark.kafka_brokers", "localhost:9092")
    val debugOutput = true


    val topics: Set[String] = topicsArg.split(",").map(_.trim).toSet
    val kafkaParams = Map[String, String]("metadata.broker.list" -> brokers)

    logger.info(s"connecting to brokers: $brokers")
    logger.info(s"sparkStreamingContext: $sparkStreamingContext")
    logger.info(s"kafkaParams: $kafkaParams")
    logger.info(s"topics: $topics")


    import com.datastax.spark.connector.streaming._


    val rawVehicleStream = KafkaUtils.createDirectStream[String, String, StringDecoder, StringDecoder](sparkStreamingContext, kafkaParams, topics)

    val splitArray = rawVehicleStream.map { case (key, rawVehicleStr) =>
      val strings = rawVehicleStr.split(",")

      logger.info(s"update type: ${strings(0)}")
      strings
    }

    splitArray.filter(data => data(0) == "location")
      .map { data =>
        logger.info(s"vehicle location: ${data(1)}")
        VehicleLocation(data(1), data(2), data(3), data(4).toDouble, data(5).toDouble, new Timestamp(data(6).toLong), new Timestamp(data(7).toLong), data(8), data(9).toInt)
      }
      .saveToCassandra("vehicle_tracking_app", "vehicle_stats")



    val vehicleEventsStream: DStream[VehicleEvent] = splitArray.filter(data => data(0) == "event").map { data =>
      VehicleEvent(vehicle_id = data(1), event_name = data(2), event_value = data(3), time_period =  new Timestamp(data(4).toLong), collect_time = new Timestamp(data(5).toLong), elapsed_time = data(6).toInt)
    }

    vehicleEventsStream
      .saveToCassandra("vehicle_tracking_app", "vehicle_events")

    vehicleEventsStream.foreachRDD(event_partitions => {
      event_partitions.foreachPartition(events => {
        if (events.nonEmpty) {
          val session = get_dse_session(dse_host, graph_name)
          val create_event = new SimpleGraphStatement(
            """
            graph.addVertex(label, 'powertrain_events',
                            'vehicle_id', vehicle_id,
                            'time_period', time_period,
                            'collect_time', collect_time,
                            'event_name', event_name,
                            'event_value', event_value,
                            'elapsed_time', elapsed_time)
            """)
          val create_event_edge = new SimpleGraphStatement(
            "def event = g.V(event_id).next()\n" +
              "def user = g.V().hasLabel('github_user').has('account', account).next()\n" +
              "user.addEdge('has_events', event)"
          )

          events.foreach(vehicleEvent => {
            if (vehicleEvent.event_name == "lap" || vehicleEvent.event_name == "finish") {
              create_event
                .set("vehicle_id", vehicleEvent.vehicle_id)
                .set("time_period", vehicleEvent.time_period)
                .set("collect_time", vehicleEvent.collect_time)
                .set("event_name", vehicleEvent.event_name)
                .set("event_value", vehicleEvent.event_value)
                .set("elapsed_time", vehicleEvent.elapsed_time)

              logger.info(s"create_event query: ${create_event.getQueryString}")
              val lap_event = session.executeGraph(create_event)
              if (lap_event.getAvailableWithoutFetching > 0) {
                val vertexId = lap_event.one().asVertex().getId
                logger.info(s"vertexId: $vertexId")

                create_event_edge
                  .set("event_id", vertexId)
                  .set("account", vehicleEvent.vehicle_id)

                logger.info(s"create_event_edge: ${create_event_edge.getQueryString}")
                session.executeGraph(create_event_edge)
              }
              else {
                logger.info("Error creating event edge")
              }
            }
          })
        }

      })
    })
    //Kick off
    sparkStreamingContext.start()
    sparkStreamingContext.awaitTermination()
  }

  def get_dse_session(dse_host: String, graph_name: String): DseSession = {
    val dseCluster = new DseJavaDriverWrapper().CreateNewCluster(dse_host, graph_name)
    dseCluster.connect()
  }

  def initialize_schema(dseSession: DseSession, schema_file: String): Boolean = {
    dseSession.executeGraph("schema.clear()")
    val schema = scala.io.Source.fromFile(getClass().getResource(schema_file).getFile()).getLines() foreach (line => {
      dseSession.executeGraph(line)
    })
    true
  }

  def initialize_graph(dse_host: String, graph_name: String): Boolean = {
    val dseCluster = new DseJavaDriverWrapper().CreateNewCluster(dse_host, "")
    val dseSession = dseCluster.connect()
    dseSession.executeGraph(new SimpleGraphStatement(
      "system.graph(graph_name).ifNotExists().create()"
    )
      .set("graph_name", graph_name))

    true
  }
}
