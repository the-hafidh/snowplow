/*
 * Copyright (c) 2013-2014 Snowplow Analytics Ltd. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package com.snowplowanalytics.snowplow.collectors
package scalastream
package sinks

// Java
import java.nio.ByteBuffer

// Amazon
import com.amazonaws.services.kinesis.model.ResourceNotFoundException
import com.amazonaws.AmazonServiceException
import com.amazonaws.auth.{
  BasicAWSCredentials,
  ClasspathPropertiesFileCredentialsProvider
}
import com.amazonaws.services.kinesis.AmazonKinesisClient

// Scalazon (for Kinesis interaction)
import io.github.cloudify.scala.aws.kinesis.Client
import io.github.cloudify.scala.aws.kinesis.Client.ImplicitExecution._
import io.github.cloudify.scala.aws.kinesis.Definitions.{
  Stream,
  PutResult,
  Record
}
import io.github.cloudify.scala.aws.kinesis.KinesisDsl._
import io.github.cloudify.scala.aws.auth.CredentialsProvider.InstanceProfile

// Config
import com.typesafe.config.Config

// Concurrent libraries
import scala.concurrent.{Future,Await,TimeoutException}
import scala.concurrent.duration._

// Logging
import org.slf4j.LoggerFactory

// Mutable data structures
import scala.collection.mutable.StringBuilder
import scala.collection.mutable.MutableList
import scala.util.{Success, Failure}

// Snowplow
import scalastream._
import thrift.SnowplowRawEvent

/**
 * Kinesis Sink for the Scala collector.
 */
class KinesisSink(config: CollectorConfig) extends AbstractSink {
  private lazy val log = LoggerFactory.getLogger(getClass())
  import log.{error, debug, info, trace}

  implicit lazy val ec = {
    info("Creating thread pool of size " + config.threadpoolSize)
    val executorService = java.util.concurrent.Executors.newFixedThreadPool(config.threadpoolSize)
    concurrent.ExecutionContext.fromExecutorService(executorService)
  }

  // Create a Kinesis client for stream interactions.
  private implicit val kinesis = createKinesisClient

  // The output stream for enriched events.
  private val enrichedStream = createAndLoadStream()

  // Checks if a stream exists.
  def streamExists(name: String, timeout: Int = 60): Boolean = {

    val exists: Boolean = try {
      val streamDescribeFuture = for {
        s <- Kinesis.stream(name).describe
      } yield s

      val description = Await.result(streamDescribeFuture, Duration(timeout, SECONDS))
      description.isActive

    } catch {
      case rnfe: ResourceNotFoundException => false
    }

    if (exists) {
      info(s"Stream $name exists and is active")
    } else {
      info(s"Stream $name doesn't exist or is not active")
    }

    exists
  }

  // Creates a new stream if one doesn't exist.
  def createAndLoadStream(timeout: Int = 60): Stream = {
    val name = config.streamName
    val size = config.streamSize

    if (streamExists(name)) {
      Kinesis.stream(name)
    } else {
      info(s"Creating stream $name of size $size")
      val createStream = for {
        s <- Kinesis.streams.create(name)
      } yield s

      try {
        val stream = Await.result(createStream, Duration(timeout, SECONDS))

        info(s"Successfully created stream $name. Waiting until it's active")
        Await.result(stream.waitActive.retrying(timeout),
          Duration(timeout, SECONDS))

        info(s"Stream $name active")

        stream
      } catch {
        case _: TimeoutException =>
          throw new RuntimeException("Error: Timed out")
      }
    }
  }

  /**
   * Creates a new Kinesis client from provided AWS access key and secret
   * key. If both are set to "cpf", then authenticate using the classpath
   * properties file.
   *
   * @return the initialized AmazonKinesisClient
   */
  private def createKinesisClient: Client = {
    val accessKey = config.awsAccessKey
    val secretKey = config.awsSecretKey
    val client = if (isCpf(accessKey) && isCpf(secretKey)) {
      new AmazonKinesisClient(new ClasspathPropertiesFileCredentialsProvider())
    } else if (isCpf(accessKey) || isCpf(secretKey)) {
      throw new RuntimeException("access-key and secret-key must both be set to 'cpf', or neither of them")
    } else if (isIam(accessKey) && isIam(secretKey)) {
      new AmazonKinesisClient(InstanceProfile)
    } else if (isIam(accessKey) || isIam(secretKey)) {
      throw new RuntimeException("access-key and secret-key must both be set to 'iam', or neither of them")
    } else if (isEnv(accessKey) && isEnv(secretKey)) {
      new AmazonKinesisClient()
    } else if (isEnv(accessKey) || isEnv(secretKey)) {
      throw new RuntimeException("access-key and secret-key must both be set to 'env', or neither of them")
    } else {
      new AmazonKinesisClient(new BasicAWSCredentials(accessKey, secretKey))
    }

    client.setEndpoint(config.streamEndpoint)
    Client.fromClient(client)
  }

  def storeRawEvent(event: SnowplowRawEvent, key: String) = {
    info(s"Writing Thrift record to Kinesis: ${event.toString}")
    val putData = for {
      p <- enrichedStream.put(
        ByteBuffer.wrap(serializeEvent(event)),
        key
      )
    } yield p

    putData onComplete {
      case Success(result) => {
        info(s"Writing successful.")
        info(s"  + ShardId: ${result.shardId}")
        info(s"  + SequenceNumber: ${result.sequenceNumber}")
      }
      case Failure(f) => {
        error(s"Writing failed.")
        error(s"  + " + f.getMessage)
      }
    }

    null
  }

  /**
   * Is the access/secret key set to the special value "cpf" i.e. use
   * the classpath properties file for credentials.
   *
   * @param key The key to check
   * @return true if key is cpf, false otherwise
   */
  private def isCpf(key: String): Boolean = (key == "cpf")

  /**
   * Is the access/secret key set to the special value "iam" i.e. use
   * the IAM role to get credentials.
   *
   * @param key The key to check
   * @return true if key is iam, false otherwise
   */
  private def isIam(key: String): Boolean = (key == "iam")

  /**
   * Is the access/secret key set to the special value "env" i.e. get
   * the credentials from environment variables
   *
   * @param key The key to check
   * @return true if key is iam, false otherwise
   */
  private def isEnv(key: String): Boolean = (key == "env")
}
