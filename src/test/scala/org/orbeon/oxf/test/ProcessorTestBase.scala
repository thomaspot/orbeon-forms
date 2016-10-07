/**
  * Copyright (C) 2010 Orbeon, Inc.
  *
  * This program is free software; you can redistribute it and/or modify it under the terms of the
  * GNU Lesser General Public License as published by the Free Software Foundation; either version
  * 2.1 of the License, or (at your option) any later version.
  *
  * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
  * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
  * See the GNU Lesser General Public License for more details.
  *
  * The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
  */
package org.orbeon.oxf.test

import java.{util ⇒ ju}

import org.orbeon.dom.Document
import org.orbeon.dom.saxon.DocumentWrapper
import org.orbeon.errorified.Exceptions
import org.orbeon.oxf.common.Version
import org.orbeon.oxf.pipeline.api.PipelineContext
import org.orbeon.oxf.processor.generator.URLGenerator
import org.orbeon.oxf.processor.{DOMSerializer, Processor, ProcessorUtils}
import org.orbeon.oxf.util.CollectionUtils._
import org.orbeon.oxf.util.CoreUtils._
import org.orbeon.oxf.util.{PipelineUtils, XPath, XPathCache}
import org.orbeon.oxf.xml.Dom4j
import org.orbeon.oxf.xml.dom4j.Dom4jUtils
import org.orbeon.saxon.om.{NodeInfo, ValueRepresentation}
import org.orbeon.scaxon.XML._
import org.scalatest.{BeforeAndAfter, FunSpecLike}
import org.scalatest.FunSpecLike

import scala.collection.JavaConverters._
import scala.util.control.NonFatal

abstract class ProcessorTestBase(testsDocUrl: String)
  extends ResourceManagerTestBase
     with FunSpecLike
     with BeforeAndAfter
     with XMLSupport {

  case class TestDescriptor(
    descriptionOpt     : Option[String],
    groupOpt           : Option[String],
    processor          : Processor,
    requestUrlOpt      : Option[String],
    docsAndSerializers : List[(Document, DOMSerializer)]
  )

  sealed trait TestResult
  case object SuccessTestResult                                      extends TestResult
  case class  FailedTestResult(expected: Document, actual: Document) extends TestResult
  case class  ErrorTestResult(t: Throwable)                          extends TestResult

  // Setup and tear-down
  locally {

    ResourceManagerTestBase.staticSetup() // don't like this much, find another solution.

    var pipelineContext: Option[PipelineContext] = None

    before {
      pipelineContext = Some(createPipelineContextWithExternalContext)
    }

    after {
      pipelineContext foreach (_.destroy(true))
    }
  }

  // Run tests
  findTestsToRun groupByKeepOrder (_.groupOpt) foreach { case (groupOpt, descriptors) ⇒
    describe(groupOpt getOrElse "[No group description provided]") {
      descriptors foreach { descriptor ⇒
        it (s"must pass ${descriptor.descriptionOpt getOrElse "[No test description provided]"}") {
          runTest(descriptor) match {
            case SuccessTestResult ⇒
            case FailedTestResult(expected, actual) ⇒
              assert(Dom4jUtils.domToPrettyString(expected) === Dom4jUtils.domToPrettyString(actual))
              assert(false)
            case ErrorTestResult(t) ⇒
              throw Exceptions.getRootThrowable(t)
          }
        }
      }
    }

  }

  private def runTest(d: TestDescriptor): TestResult = {
    try {
      // Create pipeline context
      val pipelineContext =
        d.requestUrlOpt match {
          case Some(requestURL) ⇒ createPipelineContextWithExternalContext(requestURL)
          case None             ⇒ createPipelineContextWithExternalContext
        }

      d.processor.reset(pipelineContext)

      if (d.docsAndSerializers.isEmpty) {
        // Processor with no output: just run it
        d.processor.start(pipelineContext)
        SuccessTestResult
      } else {

        val resultsIt =
          for {
            (doc, serializer) ← d.docsAndSerializers.iterator
          } yield {

            val actualDoc = serializer.runGetDocument(pipelineContext)

            // NOTE: We could make the comparison more configurable, for example to not collapse white space
            if (Dom4j.compareDocumentsIgnoreNamespacesInScopeCollapse(doc, actualDoc))
              SuccessTestResult
            else
              FailedTestResult(doc, actualDoc)
          }

        resultsIt collectFirst { case f: FailedTestResult ⇒ f } match {
          case Some(firstFailure) ⇒ firstFailure
          case None               ⇒ SuccessTestResult
        }

      }
    } catch {
      case NonFatal(t)⇒ ErrorTestResult(t)
    }
  }

  private def findTestsToRun: List[TestDescriptor] = {

    val testsDoc = {
      val urlGenerator  = new URLGenerator(testsDocUrl, true)
      val domSerializer = new DOMSerializer

      PipelineUtils.connect(urlGenerator, "data", domSerializer, "data")
      domSerializer.runGetDocument(new PipelineContext)
    }

    val expr =
      """
          let $only := (/tests/test | /tests/group/test)[
              ancestor-or-self::*/@only = 'true' and not(ancestor-or-self::*/@exclude = 'true')
          ] return
            if (exists($only)) then
                $only
            else
                (/tests/test | /tests/group/test)[
                    not(ancestor-or-self::*/@exclude = 'true')
                ]
      """

    val items =
      XPathCache.evaluateKeepItems(
        contextItems        = ju.Collections.singletonList(new DocumentWrapper(testsDoc, null, XPath.GlobalConfiguration)),
        contextPosition     = 1,
        xpathString         = expr,
        namespaceMapping    = null,
        variableToValueMap  = ju.Collections.emptyMap[String, ValueRepresentation](),
        functionLibrary     = null,
        functionContext     = null,
        baseURI             = null,
        locationData        = null,
        reporter            = null
      ).asInstanceOf[ju.List[NodeInfo]].asScala

    val testDescriptors =
      for {
        testElem       ← items
        groupElem      ← testElem.parentOption

        editionOpt     = testElem.attValueNonBlankOpt("edition") map (_.toLowerCase)
        if ! (Version.isPE   && editionOpt.contains("ce"))
        if ! (! Version.isPE && editionOpt.contains("pe"))

        descriptionOpt = testElem.attValueNonBlankOpt("description")
        groupOpt       = if (groupElem.localname == "group") groupElem.attValueNonBlankOpt("description") else None
        requestUrlOpt  = testElem.attValueNonBlankOpt("request")
      } yield {

        // Create processor and connect its inputs
        val processor =
          ProcessorUtils.createProcessorWithInputs(unsafeUnwrapElement(testElem)) |!>
            (_.setId("Main Test Processor"))

        // Connect outputs
        val docsAndSerializers =
          for {
            outputElem ← testElem child "output"
            name       = outputElem.attValueNonBlankOpt("name") getOrElse (throw new IllegalArgumentException("Output `name` is mandatory"))
            doc        = ProcessorUtils.createDocumentFromEmbeddedOrHref(unsafeUnwrapElement(outputElem), outputElem.attValueNonBlankOpt("href").orNull)
            serializer = new DOMSerializer
          } yield {
            PipelineUtils.connect(processor, name, serializer, "data")
            (doc, serializer)
          }

        TestDescriptor(
          descriptionOpt,
          groupOpt,
          processor,
          requestUrlOpt,
          docsAndSerializers.to[List]
        )
      }

    testDescriptors.to[List]
  }

}
