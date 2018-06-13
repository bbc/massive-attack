package com.delprks.massiveattack.method

import java.util.concurrent.Executors

import scala.collection.mutable.ListBuffer
import scala.collection.parallel.ForkJoinTaskSupport
import scala.collection.parallel.mutable.ParArray
import com.twitter.util.{Future => TwitterFuture}

import scala.concurrent.{Future => ScalaFuture, ExecutionContext}

class MethodLoadTest(props: MethodTestProperties = MethodTestProperties()) extends LoadGenerator {

  private implicit val context: ExecutionContext = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(props.threads))

  def test(longRunningMethod: () => TwitterFuture[_]) = {
    if (props.warmUp) {
      println(s"Warming up the JVM...")

      warmUpTwitterMethod(longRunningMethod)
    }

    println(s"Invoking long running method ${props.invocations} times - or maximum ${props.duration} seconds")

    val testStartTime = System.currentTimeMillis()
    val testEndTime = testStartTime + props.duration * 1000
    val parallelInvocation: ParArray[Int] = (1 to props.invocations).toParArray
    parallelInvocation.tasksupport = new ForkJoinTaskSupport(new java.util.concurrent.ForkJoinPool(props.threads))
    val results: ListBuffer[TwitterFuture[MeasureResult]] = measureTwitterMethod(parallelInvocation, () => longRunningMethod(), testEndTime)
    val testDuration: Double = (System.currentTimeMillis() - testStartTime).toDouble
    val testResultsF: TwitterFuture[TestResult] = twitterTestResults(results)

    println(s"Test finished. Duration: ${truncateAt(testDuration / 1000, 2)}s")

    testResultsF.map(println)
  }

  def test(longRunningMethod: () => ScalaFuture[_]) = {
    if (props.warmUp) {
      println(s"Warming up the JVM...")

      warmUpScalaMethod(longRunningMethod)
    }

    println(s"Invoking long running method ${props.invocations} times - or maximum ${props.duration} seconds")

    val testStartTime = System.currentTimeMillis()
    val testEndTime = testStartTime + props.duration * 1000
    val parallelInvocation: ParArray[Int] = (1 to props.invocations).toParArray
    parallelInvocation.tasksupport = new ForkJoinTaskSupport(new java.util.concurrent.ForkJoinPool(props.threads))
    val results: ListBuffer[ScalaFuture[MeasureResult]] = measureScalaMethod(parallelInvocation, () => longRunningMethod(), testEndTime)
    val testDuration: Double = (System.currentTimeMillis() - testStartTime).toDouble
    val testResultsF: ScalaFuture[TestResult] = scalaTestResults(results)

    println(s"Test finished. Duration: ${truncateAt(testDuration / 1000, 2)}s")

    testResultsF.map(println)
  }

  private def scalaTestResults(results: ListBuffer[ScalaFuture[MeasureResult]]): ScalaFuture[TestResult] = ScalaFuture.sequence(results).map { response =>
    val average = truncateAt(avg(response.map(_.duration)), 2)
    val invocationSeconds = response.map(_.endTime / 1000)
    val requestTimesPerSecond = invocationSeconds.groupBy(identity).map(_._2.size)

    val rpsAverage = truncateAt(avg(requestTimesPerSecond.map(_.toLong).toSeq), 2)

    TestResult(response.map(_.duration).min, response.map(_.duration).max, average, requestTimesPerSecond.min, requestTimesPerSecond.max, rpsAverage, response.size)
  }

  private def twitterTestResults(results: ListBuffer[TwitterFuture[MeasureResult]]): TwitterFuture[TestResult] = TwitterFuture.collect(results).map { response =>
    val average = truncateAt(avg(response.map(_.duration)), 2)
    val invocationSeconds = response.map(_.endTime / 1000)
    val requestTimesPerSecond = invocationSeconds.groupBy(identity).map(_._2.size)

    val rpsAverage = truncateAt(avg(requestTimesPerSecond.map(_.toLong).toSeq), 2)

    TestResult(response.map(_.duration).min, response.map(_.duration).max, average, requestTimesPerSecond.min, requestTimesPerSecond.max, rpsAverage, response.size)
  }

  private def warmUpScalaMethod(longRunningMethod: () => ScalaFuture[_]) = (1 to props.warmUpInvocations).foreach(_ => longRunningMethod())

  private def warmUpTwitterMethod(longRunningMethod: () => TwitterFuture[_]) = (1 to props.warmUpInvocations).foreach(_ => longRunningMethod())

  def measureScalaMethod(parallelInvocation: ParArray[Int], longRunningMethod: () => ScalaFuture[Any], testEndTime: Long): ListBuffer[ScalaFuture[MeasureResult]] = {
    var testResult = new ListBuffer[ScalaFuture[MeasureResult]]

    parallelInvocation.par.foreach { _ =>
      testResult += measure(longRunningMethod())

      if (System.currentTimeMillis() >= testEndTime) {
        return testResult
      }
    }

    testResult
  }

  def measureTwitterMethod(parallelInvocation: ParArray[Int], longRunningMethod: () => TwitterFuture[Any], testEndTime: Long): ListBuffer[TwitterFuture[MeasureResult]] = {
    var testResult = new ListBuffer[TwitterFuture[MeasureResult]]

    parallelInvocation.par.foreach { _ =>
      testResult += measure(longRunningMethod())

      if (System.currentTimeMillis() >= testEndTime) {
        return testResult
      }
    }

    testResult
  }
}