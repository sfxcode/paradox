/*
 * Copyright © 2015 - 2016 Lightbend, Inc. <http://www.lightbend.com>
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

package com.lightbend.paradox

import com.lightbend.paradox.markdown.{ Breadcrumbs, Page, Path, Reader, TableOfContents, Writer, Frontin, PropertyUrl }
import com.lightbend.paradox.template.{ CachedTemplates, PageTemplate }
import com.lightbend.paradox.tree.Tree.{ Forest, Location }
import java.io.File
import java.nio.file.{ Path => jPath }
import org.pegdown.ast.{ ActiveLinkNode, ExpLinkNode, RootNode }
import org.stringtemplate.v4.STErrorListener
import scala.annotation.tailrec

/**
 * Markdown site processor.
 */
class ParadoxProcessor(reader: Reader = new Reader, writer: Writer = new Writer) {

  /**
   * Process all mappings to build the site.
   */
  def process(mappings: Seq[(File, String)],
              leadingBreadcrumbs: List[(String, String)],
              outputDirectory: File,
              sourceSuffix: String,
              targetSuffix: String,
              properties: Map[String, String],
              navigationDepth: Int,
              themeDir: File,
              errorListener: STErrorListener): Seq[(File, String)] = {
    val pages = parsePages(mappings, Path.replaceSuffix(sourceSuffix, targetSuffix))
    val paths = Page.allPaths(pages).toSet
    // TODO: We assume there that all source files are contained in the same folder, maybe check if it's true
    val rootPath = Path.relativeRootPath(mappings.head._1, mappings.head._2)
    val globalPageMappings = rootPageMappings(rootPath, pages)

    @tailrec
    def render(location: Option[Location[Page]], rendered: Seq[(File, String)] = Seq.empty): Seq[(File, String)] = location match {
      case Some(loc) =>
        val page = loc.tree.label
        val pageProperties = properties ++ page.properties.get
        val currentMapping = Path.generateTargetFile(Path.relativeLocalPath(rootPath, page.file.getPath), globalPageMappings)_
        val writerContext = Writer.Context(loc, paths, currentMapping, sourceSuffix, targetSuffix, pageProperties)
        val pageToc = new TableOfContents(pages = true, headers = false, ordered = false, maxDepth = navigationDepth)
        val headerToc = new TableOfContents(pages = false, headers = true, ordered = false, maxDepth = navigationDepth)
        val pageContext = PageContents(leadingBreadcrumbs, loc, writer, writerContext, pageToc, headerToc)
        val outputFile = new File(outputDirectory, page.path)
        val template = CachedTemplates(themeDir, page.properties(Page.Properties.DefaultLayoutMdIndicator, PageTemplate.DefaultName))
        outputFile.getParentFile.mkdirs
        template.write(pageContext, outputFile, errorListener)
        render(loc.next, rendered :+ (outputFile, page.path))
      case None => rendered
    }
    pages flatMap { root => render(Some(root.location)) }
  }

  /**
   * Default template contents for a markdown page at a particular location.
   */
  case class PageContents(leadingBreadcrumbs: List[(String, String)], loc: Location[Page], writer: Writer, context: Writer.Context, pageToc: TableOfContents, headerToc: TableOfContents) extends PageTemplate.Contents {
    import scala.collection.JavaConverters._

    private val page = loc.tree.label

    val getTitle = page.title
    val getContent = writer.writeContent(page.markdown, context)

    lazy val getBase = page.base
    lazy val getHome = link(Some(loc.root))
    lazy val getPrev = link(loc.prev)
    lazy val getNext = link(loc.next)
    lazy val getBreadcrumbs = writer.writeBreadcrumbs(Breadcrumbs.markdown(leadingBreadcrumbs, loc.path), context)
    lazy val getNavigation = writer.writeNavigation(pageToc.root(loc), context)
    lazy val hasSubheaders = page.headers.nonEmpty
    lazy val getToc = writer.writeToc(headerToc.headers(loc), context)
    lazy val getSource = githubLink(Some(loc)).getHref

    lazy val getProperties = context.properties.asJava

    private def link(location: Option[Location[Page]]): PageTemplate.Link = PageLink(location, page, writer, context)
    private def githubLink(location: Option[Location[Page]]): PageTemplate.Link = GithubLink(location, page, writer, context)
  }

  /**
   * Default template links, rendered to just a relative uri and HTML for the link.
   */
  case class PageLink(location: Option[Location[Page]], current: Page, writer: Writer, context: Writer.Context) extends PageTemplate.Link {
    lazy val getHref: String = location.map(href).orNull
    lazy val getHtml: String = location.map(link).orNull
    lazy val getTitle: String = location.map(title).orNull
    lazy val isActive: Boolean = location.exists(active)

    private def link(location: Location[Page]): String = {
      val node = if (active(location))
        new ActiveLinkNode(href(location), location.tree.label.label)
      else
        new ExpLinkNode("", href(location), location.tree.label.label)
      writer.writeFragment(node, context)
    }

    private def active(location: Location[Page]): Boolean = {
      location.tree.label.path == current.path
    }

    private def href(location: Location[Page]): String = current.base + location.tree.label.path

    private def title(location: Location[Page]): String = location.tree.label.title
  }

  /**
   * Github links, rendered to just a HTML for the link.
   */
  case class GithubLink(location: Option[Location[Page]], page: Page, writer: Writer, context: Writer.Context) extends PageTemplate.Link {
    lazy val getHref: String = buildPath(PropertyUrl("github.base_url", context.properties.get).base, "/tree/master", page.relativePath)
    lazy val getHtml: String = getHref // TODO: temporary, should provide a link directly
    lazy val getTitle: String = "source"
    lazy val isActive: Boolean = false

    private def buildPath(paths: String*): String = {
      def removeSidesSeparators(path: String): String = {
        (path.startsWith("/"), path.endsWith("/")) match {
          case (true, true)  => path.drop(1).dropRight(1)
          case (true, false) => path.drop(1)
          case (false, true) => path.dropRight(1)
          case _             => path
        }
      }

      paths.toList.foldLeft("") {
        case (p1, p2) if (p1 != "") => p1 + "/" + removeSidesSeparators(p2)
        case (p1, p2)               => removeSidesSeparators(p2)
      }
    }
  }

  /**
   * Parse markdown files (with paths) into a forest of linked pages.
   */
  def parsePages(mappings: Seq[(File, String)], convertPath: String => String): Forest[Page] = {
    Page.forest(parseMarkdown(mappings), convertPath)
  }

  /**
   * Parse markdown files into pegdown AST.
   */
  def parseMarkdown(mappings: Seq[(File, String)]): Seq[(File, String, RootNode, Map[String, String])] = {
    mappings map {
      case (file, path) =>
        val frontin = Frontin(file)
        (file, normalizePath(path), reader.read(frontin.body), frontin.header)
    }
  }

  /**
   * Normalize path to '/' separator.
   */
  def normalizePath(path: String, separator: Char = java.io.File.separatorChar): String = {
    if (separator == '/') path else path.replace(separator, '/')
  }

  /**
   * Create Mappings from page path to target file name
   */
  def rootPageMappings(root: String, pages: Forest[Page]): Map[String, String] = {
    @tailrec
    def mapping(location: Option[Location[Page]], fileMappings: List[(String, String)] = Nil): List[(String, String)] = location match {
      case Some(loc) =>
        val page = loc.tree.label
        val fullSrcPath = page.file.getPath
        val curTargetPath = page.path
        val curSrcPath = Path.relativeLocalPath(root, fullSrcPath)
        val curMappings = (curSrcPath, curTargetPath)
        mapping(loc.next, curMappings :: fileMappings)
      case None => fileMappings
    }
    pages.flatMap { root => mapping(Some(root.location)) }.toMap
  }

}
